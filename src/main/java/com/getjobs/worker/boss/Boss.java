package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.KeywordDeliveryQuotaService;
import com.getjobs.worker.utils.DeliveryPacing;
import com.getjobs.worker.utils.HumanDelay;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.utils.SessionBudget;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final AiService aiService;
    private final KeywordDeliveryQuotaService keywordDeliveryQuotaService;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
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
    private SessionBudget sessionBudget;

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
        log.info("会话预算已初始化: 最大投递 {} 个岗位, 最长运行 {} 分钟",
                SESSION_MAX_DELIVERIES, SESSION_MAX_DURATION.toMinutes());
        for (String cityCode : config.getCityCode()) {
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

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            String normalizedKeyword = keyword == null ? "" : keyword.trim();
            if (normalizedKeyword.isEmpty()) {
                continue;
            }
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
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

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15_000));
            // 等待列表容器出现，确保页面完成首屏渲染
            page.waitForSelector("//ul[contains(@class, 'rec-job-list')]", new Page.WaitForSelectorOptions().setTimeout(60_000));

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

                // 获取当前可见的岗位
                Locator cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
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
                    Locator nameLocator = currentCard.locator("a.job-name, span.job-name");
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
                    Locator detailHeader = page.locator("div.job-detail-header");
                    try {
                        detailHeader.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                    } catch (Throwable e) {
                        log.warn("等待右侧详情面板超时，跳过 | 岗位：{}", jobName);
                        continue;
                    }

                    // 2.5 从右侧详情面板获取公司和HR信息，进行黑名单过滤
                    String companyName = "";
                    String hrTitle = "";
                    String hrActiveStatus = "";

                    try {
                        // 获取公司名称和HR职位：格式如 "理想汽车 · HR"
                        Locator bossInfoAttr = page.locator("div.boss-info-attr");
                        if (bossInfoAttr.count() > 0) {
                            String infoText = bossInfoAttr.first().textContent();
                            if (infoText != null && infoText.contains("·")) {
                                String[] parts = infoText.split("·");
                                companyName = parts[0].trim();
                                hrTitle = parts.length > 1 ? parts[1].trim() : "";
                            }
                        }

                        // 获取HR活跃状态
                        Locator activeTime = page.locator("span.boss-active-time");
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

                    // 3. 在右侧详情面板中查找"立即沟通"按钮
                    Locator chatBtn = detailHeader.locator("a.op-btn-chat");

                    if (chatBtn.count() == 0) {
                        log.warn("右侧详情面板未找到立即沟通按钮，跳过 | 岗位：{}", jobName);
                        continue;
                    }

                    // 检查按钮文本是否为"立即沟通"（不是"继续沟通"等）
                    String btnText = "";
                    try {
                        btnText = chatBtn.first().textContent();
                    } catch (Throwable ignore) {}

                    if (btnText == null || !btnText.contains("立即沟通")) {
                        log.info("按钮文本为 [{}]，可能已沟通过，跳过 | 岗位：{}", btnText, jobName);
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
                    log.info("投递成功 | 关键词：{} | 第 {} 个岗位 | 岗位：{} | 会话已投：{}",
                            normalizedKeyword, processedIndex + 1, jobName, sessionBudget.deliveredCount());

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

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
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
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    private void resumeSubmission(String keyword, Job job) {
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return;
        }

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            log.warn("未找到\"查看更多信息\"按钮，跳过...");
            return;
        }
        // 强制用js新开tab
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            log.warn("未获取到岗位详情链接，跳过...");
            return;
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // 2. 在新窗口打开详情页
        Page detailPage = page.context().newPage();
        detailPage.navigate(detailUrl);
        PlaywrightUtil.sleep(1);

        // 3. 查找"立即沟通"按钮
        Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
        boolean foundChatBtn = false;
        for (int i = 0; i < 5; i++) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            if (chatBtn.count() > 0 && (chatBtn.first().textContent().contains("立即沟通"))) {
                foundChatBtn = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!foundChatBtn) {
            log.warn("未找到立即沟通按钮，跳过岗位: {}", job.getJobName());
            // 关闭详情页
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            return;
        }
        chatBtn.first().click();
        PlaywrightUtil.sleep(1);

        // 3.5 检测并关闭可能出现的弹窗（如提示弹窗、确认弹窗等）
        closePopupIfPresent(detailPage);

        // 4. 等待聊天输入框
        Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        boolean inputReady = false;
        for (int i = 0; i < 10; i++) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束等待聊天输入框 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return;
            }
            if (inputLocator.count() > 0 && inputLocator.first().isVisible()) {
                inputReady = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!inputReady) {
            log.warn("聊天输入框未出现，跳过: {}", job.getJobName());
            // 关闭详情页
            try {
                detailPage.close();
            } catch (Exception ignore) {
            }
            return;
        }

        // 5. AI智能生成打招呼语
        String aiMessage = null;
        if (config.getEnableAI()) {
            String jd = job.getJobInfo();
            if (jd != null && !jd.isEmpty()) {
                aiMessage = generateAiMessage(keyword, job.getJobName(), jd);
            }
        }
        String message = isValidString(aiMessage) ? aiMessage : config.getSayHi();

        // 6. 输入打招呼语
        Locator input = inputLocator.first();
        input.click();
        Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
        if (tagObj instanceof String && ((String) tagObj).equals("textarea")) {
            input.fill(message);
        } else {
            // 对 contenteditable 节点写入文本并派发 input 事件
            input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
        }

        // 7. 点击发送按钮（div.send-message 或 button.btn-send）
        Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        boolean sendSuccess = false;
        if (sendText.count() > 0) {
            sendText.first().click();
            PlaywrightUtil.sleep(1);
            sendSuccess = true;
            try {
                detailPage.locator("i.icon-close").first().click();
            } catch (Exception e) {
                log.error("发送文本小窗口关闭失败！");
            }
        } else {
            log.warn("未找到发送按钮，自动跳过！岗位：{}", job.getJobName());
        }

        // 8. 发送图片简历（可选）
        boolean imgResume = false;
        if (Boolean.TRUE.equals(config.getSendImgResume())) {
            imgResume = sendImageResume(detailPage);
        }

        log.info("投递完成 | 公司：{} | 岗位：{} | 薪资：{} | 招呼语：{} | 图片简历：{}", job.getCompanyName(), job.getJobName(), job.getSalary(), message, imgResume ? "已发送" : "未发送");

        // 9. 关闭新打开的详情页
        try {
            detailPage.close();
        } catch (Exception ignore) {
        }
        PlaywrightUtil.sleep(1);

        // 10. 更新数据库投递状态 & 成功投递加入结果
        if (sendSuccess) {
            // 从详情链接提取 encrypt_id，并映射到 encrypt_user_id
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "已投递");
                    log.info("投递成功 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为已投递失败：{}", e.getMessage());
                }
            } else {
                log.debug("未能找到 encryptId/encryptUserId 用于更新投递状态，detailUrl: {}", detailUrl);
            }
            resultList.add(job);
        } else {
            // 若发生发送失败，也进行状态更新
            String encryptId = extractEncryptId(detailUrl);
            String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
            if (encryptId != null && encryptUserId != null) {
                try {
        bossService.updateDeliveryStatus(encryptId, encryptUserId, "投递失败");
                    log.warn("投递失败 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                } catch (Exception e) {
                    log.warn("更新投递状态为投递失败异常：{}", e.getMessage());
                }
            }
        }
    }

    

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            imgContainer.first().click();
                        });
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private String generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        String requestMessage = (prompt != null)
                ? String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi())
                : buildDefaultPrompt(introduce, keyword, jobName, jd);

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null) {
                return config.getSayHi();
            }
            return result.toLowerCase().contains("false") ? config.getSayHi() : result;
        } catch (Exception e) {
            log.warn("AI请求失败，使用原有打招呼语: {}", e.getMessage());
            return config.getSayHi();
        }
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + keyword + "\n" +
                "职位名称：" + jobName + "\n" +
                "职位描述：" + jd + "\n" +
                "参考语：" + config.getSayHi();
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    private void waitForSliderVerify(Page page) {
        String SLIDER_URL = "https://www.zhipin.com/web/user/safe/verify-slider";
        // 最多等待5分钟（防呆，防止死循环）
        long start = System.currentTimeMillis();
        while (true) {
            String url = page.url();
            if (url != null && url.startsWith(SLIDER_URL)) {
                progressCallback.accept("请手动完成Boss直聘滑块验证，通过后在控制台回车继续...", 0, 0);
                System.out.println("\n【滑块验证】请手动完成Boss直聘滑块验证，通过后在控制台回车继续…");
                try {
                    System.in.read();
                } catch (Exception e) {
                    log.error("等待滑块验证输入异常: {}", e.getMessage());
                }
                PlaywrightUtil.sleep(1);
                // 验证通过后页面url会变，循环再检测一次
                continue;
            }
            if ((System.currentTimeMillis() - start) > 5 * 60 * 1000) {
                throw new RuntimeException("滑块验证超时！");
            }
            break;
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
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
