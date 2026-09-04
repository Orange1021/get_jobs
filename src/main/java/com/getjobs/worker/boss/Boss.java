package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.application.service.KeywordDeliveryQuotaService;
import com.getjobs.worker.utils.DeliveryPacing;
import com.getjobs.worker.utils.HumanDelay;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobScoreService;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.utils.RiskGuard;
import com.getjobs.worker.utils.SelectorRepository;
import com.getjobs.worker.utils.SessionBudget;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final KeywordDeliveryQuotaService keywordDeliveryQuotaService;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private static final int MAX_DAILY_DELIVERIES_PER_KEYWORD = 10;
    private static final String PLATFORM_NAME = "boss";

    // ===== 会话防封预算：单次投递会话的总量约束，任一维度耗尽即收工 =====
    private static final int SESSION_MAX_DELIVERIES = 60;
    private static final Duration SESSION_MAX_DURATION = Duration.ofHours(2);

    private final HumanDelay humanDelay = new HumanDelay();
    private final DeliveryPacing pacing = new DeliveryPacing(humanDelay);
    private final RiskGuard riskGuard = new RiskGuard();
    private final JobScoreService jobScoreService = new JobScoreService();
    private final SelectorRepository selectors = SelectorRepository.getInstance();
    private SessionBudget sessionBudget;
    /** 风控熔断信号：一旦触发，整个投递会话（所有城市/关键词）全部终止。 */
    private volatile boolean riskBreakTriggered = false;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        // 每次会话重置预算：防止单次会话过量投递触发风控
        sessionBudget = SessionBudget.builder()
                .maxDeliveries(SESSION_MAX_DELIVERIES)
                .maxDuration(SESSION_MAX_DURATION)
                .build();
        riskBreakTriggered = false;
        log.info("会话预算已初始化: 最大投递 {} 个岗位, 最长运行 {} 分钟",
                SESSION_MAX_DELIVERIES, SESSION_MAX_DURATION.toMinutes());
        for (String cityCode : config.getCityCode()) {
            if (riskBreakTriggered) {
                log.warn("风控熔断信号已触发，终止所有城市的投递");
                break;
            }
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        return resultList.size();
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }


    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            String normalizedKeyword = keyword == null ? "" : keyword.trim();
            if (normalizedKeyword.isEmpty()) {
                continue;
            }
            // 检查是否需要停止（与其他调用点保持一致的判空写法）
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            // 检查会话预算，耗尽则整体收工
            if (sessionBudget.isExhausted()) {
                log.info("会话预算耗尽，提前收工 | 已投递 {} 个岗位, 剩余关键词[{}]跳过",
                        sessionBudget.deliveredCount(), normalizedKeyword);
                progressCallback.accept("会话预算耗尽，停止投递（已投 " + sessionBudget.deliveredCount() + " 个）", 0, 0);
                return;
            }

            int todayCount = keywordDeliveryQuotaService.getTodayCount(PLATFORM_NAME, normalizedKeyword);
            if (todayCount >= MAX_DAILY_DELIVERIES_PER_KEYWORD) {
                progressCallback.accept("Keyword daily limit reached, skip: " + normalizedKeyword, 0, MAX_DAILY_DELIVERIES_PER_KEYWORD);
                continue;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(normalizedKeyword, StandardCharsets.UTF_8);

            // 解析本关键词会话使用的页面选择器（支持 selectors.yml 外部覆盖）
            final String selListContainer = selectors.get("boss", "JOB_LIST_CONTAINER",
                    "//ul[contains(@class, 'rec-job-list')]");
            final String selJobCards = selectors.get("boss", "JOB_CARDS",
                    "//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            final String selJobName = selectors.get("boss", "JOB_NAME_IN_CARD", "a.job-name, span.job-name");
            final String selDetailHeader = selectors.get("boss", "DETAIL_HEADER", "div.job-detail-header");
            final String selBossInfo = selectors.get("boss", "BOSS_INFO_ATTR", "div.boss-info-attr");
            final String selHrActive = selectors.get("boss", "HR_ACTIVE_TIME", "span.boss-active-time");
            final String selChatBtn = selectors.get("boss", "CHAT_BUTTON", "a.op-btn-chat");
            final String selSalary = selectors.get("boss", "JOB_SALARY", "span.job-salary, span.salary");

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15_000));
            // 等待列表容器出现，确保页面完成首屏渲染
            page.waitForSelector(selListContainer, new Page.WaitForSelectorOptions().setTimeout(60_000));

            log.info("【{}】开始边投递边滚动", normalizedKeyword);

            // 边投递边滚动逻辑
            int processedIndex = 0; // 已处理的岗位索引
            int noNewCardsCount = 0; // 连续无新岗位的次数

            while (todayCount < MAX_DAILY_DELIVERIES_PER_KEYWORD && !sessionBudget.isExhausted()) {
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", processedIndex, -1);
                    return;
                }

                // 预算中途耗尽（数量或时长），记录进度后收工
                if (sessionBudget.isExhausted()) {
                    log.info("会话预算在投递中途耗尽 | 已投递 {} 个岗位", sessionBudget.deliveredCount());
                    progressCallback.accept("会话预算耗尽，停止投递（已投 " + sessionBudget.deliveredCount() + " 个）", processedIndex, -1);
                    return;
                }

                // 风控熔断：检测到滑块验证/验证码/风控文本/登录失效，立即停止投递
                RiskGuard.Result risk = riskGuard.check(this::probeRiskSignals);
                if (risk.confirmed()) {
                    riskBreakTriggered = true;
                    log.warn("风控熔断：{} | 已投递 {} 个岗位，本轮会话终止", risk.reason(), sessionBudget.deliveredCount());
                    progressCallback.accept("⚠️ 风控熔断：" + risk.reason() + "（已投 " + sessionBudget.deliveredCount() + " 个，请人工检查账号状态）", processedIndex, -1);
                    return;
                }

                // 获取当前可见的岗位
                Locator cards = page.locator(selJobCards);
                int currentCount = cards.count();

                // 如果已处理完当前所有岗位，尝试滚动加载更多
                if (processedIndex >= currentCount) {
                    // 检查是否到达页面底部
                    Locator footer = page.locator("div#footer, #footer");
                    if (footer.count() > 0 && footer.first().isVisible()) {
                        log.info("【{}】已到达页面底部，停止加载", normalizedKeyword);
                        break;
                    }

                    // 滚动加载更多
                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");
                    PlaywrightUtil.sleep(1);

                    // 检查是否有新岗位加载
                    int newCount = cards.count();
                    if (newCount == currentCount) {
                        noNewCardsCount++;
                        if (noNewCardsCount >= 3) {
                            // 尝试强制触底
                            page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                            PlaywrightUtil.sleep(1);
                            noNewCardsCount = 0;
                        }
                    } else {
                        noNewCardsCount = 0;
                    }
                    continue;
                }

                // 处理下一个岗位
                Locator currentCard = cards.nth(processedIndex);

                // 获取卡片上的岗位名称（用于日志）
                String jobName = "";
                try {
                    Locator nameLocator = currentCard.locator(selJobName);
                    if (nameLocator.count() > 0) {
                        jobName = nameLocator.first().textContent();
                    }
                } catch (Throwable ignore) {}

                // 调试模式：仅遍历不投递
                if (Boolean.TRUE.equals(config.getDebugger())) {
                    log.info("调试模式：仅遍历岗位，不投递 | 卡片索引：{} | 岗位：{}", processedIndex, jobName);
                    processedIndex++;
                    continue;
                }

                // 输出进度
                progressCallback.accept("正在投递：" + jobName, processedIndex + 1, -1);

                try {
                    // 1. 点击卡片，触发右侧详情面板加载
                    currentCard.click();
                    PlaywrightUtil.sleep(1);

                    // 2. 等待右侧详情面板加载完成
                    Locator detailHeader = page.locator(selDetailHeader);
                    try {
                        detailHeader.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                    } catch (Throwable e) {
                        log.warn("等待右侧详情面板超时，跳过 | 岗位：{}", jobName);
                        processedIndex++;
                        continue;
                    }

                    // 2.5 从右侧详情面板获取公司和HR信息，进行黑名单过滤
                    String companyName = "";
                    String hrTitle = "";
                    String hrActiveStatus = "";

                    try {
                        // 获取公司名称和HR职位：格式如 "理想汽车 · HR"
                        Locator bossInfoAttr = page.locator(selBossInfo);
                        if (bossInfoAttr.count() > 0) {
                            String infoText = bossInfoAttr.first().textContent();
                            if (infoText != null && infoText.contains("·")) {
                                String[] parts = infoText.split("·");
                                companyName = parts[0].trim();
                                hrTitle = parts.length > 1 ? parts[1].trim() : "";
                            }
                        }

                        // 获取HR活跃状态
                        Locator activeTime = page.locator(selHrActive);
                        if (activeTime.count() > 0) {
                            hrActiveStatus = activeTime.first().textContent();
                        }
                    } catch (Throwable e) {
                        log.debug("获取详情信息失败: {}", e.getMessage());
                    }

                    // 黑名单过滤：岗位
                    boolean shouldSkip = false;
                    if (jobName != null && blackJobs != null) {
                        for (String blackJob : blackJobs) {
                            if (jobName.contains(blackJob)) {
                                log.info("被过滤：岗位黑名单命中 | 岗位：{} | 关键词：{}", jobName, blackJob);
                                shouldSkip = true;
                                break;
                            }
                        }
                    }

                    // 黑名单过滤：公司
                    if (!shouldSkip && companyName != null && !companyName.isEmpty() && blackCompanies != null) {
                        for (String blackCompany : blackCompanies) {
                            if (companyName.contains(blackCompany)) {
                                log.info("被过滤：公司黑名单命中 | 公司：{} | 关键词：{}", companyName, blackCompany);
                                shouldSkip = true;
                                break;
                            }
                        }
                    }

                    // 黑名单过滤：招聘者职位
                    if (!shouldSkip && hrTitle != null && !hrTitle.isEmpty() && blackRecruiters != null) {
                        for (String blackRecruiter : blackRecruiters) {
                            if (hrTitle.contains(blackRecruiter)) {
                                log.info("被过滤：招聘者黑名单命中 | 招聘者职位：{} | 关键词：{}", hrTitle, blackRecruiter);
                                shouldSkip = true;
                                break;
                            }
                        }
                    }

                    // HR活跃状态过滤：包含"年"表示很久不活跃
                    if (!shouldSkip && Boolean.TRUE.equals(config.getFilterDeadHR()) && hrActiveStatus != null && hrActiveStatus.contains("年")) {
                        log.info("被过滤：HR不活跃 | 活跃状态：{} | 公司：{}", hrActiveStatus, companyName);
                        shouldSkip = true;
                    }

                    // 如果命中过滤规则，跳过该岗位
                    if (shouldSkip) {
                        processedIndex++;
                        continue;
                    }

                    // 2.8 岗位质量评分门控：低分岗位跳过，把配额留给高分岗位
                    String salaryText = "";
                    String welfareText = "";
                    try {
                        Locator salaryLocator = currentCard.locator(selSalary);
                        if (salaryLocator.count() > 0) {
                            salaryText = salaryLocator.first().textContent();
                        }
                        Locator tagLocator = currentCard.locator(TAG_LIST);
                        if (tagLocator.count() > 0) {
                            welfareText = String.join(",", tagLocator.allTextContents());
                        }
                    } catch (Throwable ignore) {}

                    JobScoreService.JobFacts facts = new JobScoreService.JobFacts(
                            jobName, companyName, salaryText, hrActiveStatus, welfareText, normalizedKeyword);
                    int jobScore = jobScoreService.score(facts, config.getExpectedSalary());
                    if (!jobScoreService.shouldDeliver(jobScore, config.getQualityScoreThreshold())) {
                        log.info("被过滤：岗位评分不足 | 岗位：{} | 评分：{} | 阈值：{}",
                                jobName, jobScore, config.getQualityScoreThreshold());
                        processedIndex++;
                        continue;
                    }

                    // 3. 在右侧详情面板中查找"立即沟通"按钮
                    Locator chatBtn = detailHeader.locator(selChatBtn);

                    if (chatBtn.count() == 0) {
                        log.warn("右侧详情面板未找到立即沟通按钮，跳过 | 岗位：{}", jobName);
                        processedIndex++;
                        continue;
                    }

                    // 检查按钮文本是否为"立即沟通"（不是"继续沟通"等）
                    String btnText = "";
                    try {
                        btnText = chatBtn.first().textContent();
                    } catch (Throwable ignore) {}

                    if (btnText == null || !btnText.contains("立即沟通")) {
                        log.info("按钮文本为 [{}]，可能已沟通过，跳过 | 岗位：{}", btnText, jobName);
                        processedIndex++;
                        continue;
                    }

                    // 4. 点击"立即沟通"
                    chatBtn.first().click();
                    PlaywrightUtil.sleep(1);

                    // 5. 关闭弹窗
                    closePopupIfPresent(page);

                    // 6. 记录投递成功
                    postCount++;
                    todayCount = keywordDeliveryQuotaService.recordDelivery(PLATFORM_NAME, normalizedKeyword);
                    sessionBudget.recordDelivery();
                    log.info("投递成功 | 关键词：{} | 第 {} 个岗位 | 岗位：{} | 会话已投：{} | 岗位评分：{}",
                            normalizedKeyword, processedIndex + 1, jobName, sessionBudget.deliveredCount(), jobScore);

                    // 创建Job对象并添加到结果列表（用于统计）
                    Job job = new Job();
                    job.setJobName(jobName);
                    resultList.add(job);

                } catch (Exception e) {
                    log.warn("投递失败 | 第 {} 个岗位 | 岗位：{} | 错误：{}", processedIndex + 1, jobName, e.getMessage());
                }

                // 投递间隔：高斯随机延迟，避免固定间隔暴露自动化特征
                pacing.betweenDeliveries();

                // 处理下一个岗位
                processedIndex++;

                // 每处理5个岗位后适度下滑，确保新岗位加载
                try {
                    if (processedIndex % 5 == 0) {
                        page.evaluate("window.scrollBy(0, 200);");
                        PlaywrightUtil.sleepMillis(500);
                    }
                } catch (Throwable ignore) {}
            }
            log.info("【{}】岗位已投递完毕！已投递岗位数量:{}", normalizedKeyword, postCount);
        }
    }


    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }


    /**
     * 采集当前页面风控信号，供 {@link RiskGuard} 判定。
     * 任何探测异常都按"无信号"处理，绝不因探测失败中断投递主流程。
     */
    private RiskGuard.Signals probeRiskSignals() {
        try {
            String url = page.url() == null ? "" : page.url();
            boolean slider = url.contains("safe/verify");

            boolean captcha = false;
            for (String selector : new String[]{
                    "iframe[src*='captcha']", ".geetest_panel", ".geetest_ghost",
                    ".nc-container", "#nc_1_wrapper", "div.index-sms"}) {
                try {
                    if (page.locator(selector).count() > 0) {
                        captcha = true;
                        break;
                    }
                } catch (Throwable ignore) {}
            }

            boolean riskText = false;
            try {
                Locator body = page.locator("body");
                if (body.count() > 0) {
                    riskText = RiskGuard.looksLikeRiskText(body.first().textContent());
                }
            } catch (Throwable ignore) {}

            boolean loginRequired = false;
            try {
                Locator loginBtn = page.locator(LOGIN_BTNS);
                loginRequired = loginBtn.count() > 0 && loginBtn.first().textContent().contains("登录");
            } catch (Throwable ignore) {}

            return new RiskGuard.Signals(url, slider, captcha, riskText, loginRequired);
        } catch (Throwable t) {
            log.debug("风控信号采集失败（按无信号处理）: {}", t.getMessage());
            return RiskGuard.Signals.empty();
        }
    }


    /**
     * 检测并关闭可能出现的弹窗
     * 处理点击"立即沟通"后可能出现的各种弹窗，如：
     * - 提示弹窗
     * - 确认弹窗
     * - 达到上限提示
     * - 要求完善简历等
     *
     * @param page 页面对象
     * @return true 表示有关闭弹窗，false 表示没有弹窗
     */
    private boolean closePopupIfPresent(Page page) {
        boolean closedAny = false;
        try {
            // 多种可能的弹窗关闭按钮选择器（Boss直聘优先）
            String[] closeSelectors = {
                ".greet-boss-container .close",// Boss打招呼弹窗关闭按钮
                ".greet-boss-container span.close",
                "span.close .icon-close",
                "a.cancel-btn",                  // Boss弹窗"留在此页"按钮
                "i.icon-close",
                ".icon-close",
                "[class*='icon-close']",
                ".dialog-close",
                "[class*='close-btn']",
                "button.close",
                "div.close-btn",
                "span.close"
            };

            // 尝试检测并关闭弹窗，最多尝试3次（处理多层弹窗）
            for (int attempt = 0; attempt < 3; attempt++) {
                boolean foundPopup = false;

                for (String selector : closeSelectors) {
                    try {
                        Locator closeBtn = page.locator(selector);
                        if (closeBtn.count() > 0 && closeBtn.first().isVisible()) {
                            // 检测弹窗内容，记录日志
                            try {
                                Locator dialogContent = page.locator(".dialog-con, .dialog-content, .dialog-title, [class*='dialog-title']");
                                if (dialogContent.count() > 0 && dialogContent.first().isVisible()) {
                                    String content = dialogContent.first().textContent();
                                    log.info("检测到弹窗内容: {}", content != null ? content.trim() : "");

                                    // 检测是否是达到上限的提示
                                    if (content != null && (content.contains("上限") || content.contains("已达") || content.contains("今日"))) {
                                        log.warn("检测到投递上限提示弹窗: {}", content.trim());
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("获取弹窗内容失败: {}", e.getMessage());
                            }

                            // 点击关闭按钮
                            closeBtn.first().click();
                            PlaywrightUtil.sleepMillis(500);
                            log.info("已关闭弹窗 (selector: {})", selector);
                            foundPopup = true;
                            closedAny = true;
                            break;
                        }
                    } catch (Exception e) {
                        // 忽略单个选择器的异常，继续尝试其他选择器
                    }
                }

                // 如果本轮没有发现弹窗，退出循环
                if (!foundPopup) {
                    break;
                }
            }

            // 额外检测：按ESC键关闭可能的模态框
            if (!closedAny) {
                try {
                    page.keyboard().press("Escape");
                    PlaywrightUtil.sleepMillis(300);
                } catch (Exception ignore) {
                }
            }

        } catch (Exception e) {
            log.debug("弹窗检测/关闭过程异常: {}", e.getMessage());
        }

        return closedAny;
    }

}
