package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.application.service.KeywordDeliveryQuotaService;
import com.getjobs.worker.utils.DeliveryPacing;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobScoreService;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.utils.SessionBudget;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 智联招聘自动投递 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private boolean isLimit = false;

    private static final String HOME_URL = "https://www.zhaopin.com/sou/";

    // 限流常量
    private static final int MAX_DAILY_DELIVERIES_PER_KEYWORD = 10;
    private static final String PLATFORM_NAME = "zhilian";

    // ===== 会话防封预算 =====
    private static final int SESSION_MAX_DELIVERIES = 60;
    private static final Duration SESSION_MAX_DURATION = Duration.ofHours(2);

    private final DeliveryPacing pacing = new DeliveryPacing();
    private final JobScoreService jobScoreService = new JobScoreService();
    private SessionBudget sessionBudget;

    private final ZhilianService zhilianService;
    private final KeywordDeliveryQuotaService keywordDeliveryQuotaService;

    private static class PageJob {
        int index;
        String jobId;
        String jobTitle;
        String companyName;
        String salary;

        PageJob(int index, String jobId, String jobTitle, String companyName, String salary) {
            this.index = index;
            this.jobId = jobId;
            this.jobTitle = jobTitle;
            this.companyName = companyName;
            this.salary = salary;
        }
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        log.info("智联招聘准备工作开始...");
        resultList.clear();
        isLimit = false;
        log.info("智联招聘准备工作完成");
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        log.info("智联招聘投递任务开始...");
        long startTime = System.currentTimeMillis();

        // 每次会话重置预算：防止单次会话过量投递触发风控
        sessionBudget = SessionBudget.builder()
                .maxDeliveries(SESSION_MAX_DELIVERIES)
                .maxDuration(SESSION_MAX_DURATION)
                .build();
        log.info("会话预算已初始化: 最大投递 {} 个岗位, 最长运行 {} 分钟",
                SESSION_MAX_DELIVERIES, SESSION_MAX_DURATION.toMinutes());

        try {
            // 遍历所有关键词进行投递
            for (String keyword : config.getKeywords()) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    break;
                }

                if (sessionBudget.isExhausted()) {
                    log.info("会话预算耗尽，提前收工 | 已投递 {} 个岗位", sessionBudget.deliveredCount());
                    sendProgress("会话预算耗尽，停止投递（已投 " + sessionBudget.deliveredCount() + " 个）", null, null);
                    break;
                }

                // 前置检查当日投递计数
                int todayCount = keywordDeliveryQuotaService.getTodayCount(PLATFORM_NAME, keyword);
                if (todayCount >= MAX_DAILY_DELIVERIES_PER_KEYWORD) {
                    log.info("关键词【{}】当日投递已达上限，跳过", keyword);
                    continue;
                }

                String baseUrl = buildBaseUrl(1);
                deliverByKeyword(keyword, baseUrl, todayCount);
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = String.format("智联招聘投递完成，共投递%d个岗位，用时%s",
                resultList.size(), formatDuration(duration));
            log.info(message);
            sendProgress(message, null, null);

            if (!resultList.isEmpty()) {
                log.info("新投递公司如下:");
                resultList.forEach(job -> log.info(job.toString()));
            } else {
                log.info("未投递新的岗位...");
            }

        } catch (Exception e) {
            log.error("智联招聘投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String baseUrl, int todayCount) {
        if (isLimit) {
            return;
        }

        try {
            log.info("开始投递关键词: {}", keyword);
            sendProgress("正在搜索关键词: " + keyword, null, null);

            // 导航到搜索页面（路径参数：jl+城市码 + p1 + sl）
            page.navigate(baseUrl);
            PlaywrightUtil.sleep(2);

            // 在搜索框输入关键词并触发搜索（Enter键更稳健）
            try {
                Locator keywordInput = findKeywordInput();
                if (keywordInput == null || keywordInput.count() == 0) {
                    log.warn("未找到搜索输入框，跳过关键词: {}", keyword);
                    return;
                }
                keywordInput.fill("");
                keywordInput.fill(keyword);
                try { keywordInput.press("Enter"); } catch (Exception ignored) {}
                PlaywrightUtil.sleep(2);
            } catch (Exception e) {
                log.warn("搜索框输入关键词失败，跳过当前关键词: {}", e.getMessage());
                return;
            }

            // 等待岗位列表加载（CSS选择器）
            try {
                page.waitForSelector("div.joblist-box__item",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            } catch (Exception e) {
                log.warn("等待岗位列表超时，跳过当前关键词");
                return;
            }

            // 遍历所有页面：仅以“下一页”按钮禁用状态为主，最多50页
            int pageNum = 1;
            while (pageNum <= 50) {
                // 检查是否达到日上限
                if (todayCount >= MAX_DAILY_DELIVERIES_PER_KEYWORD) {
                    log.info("关键词【{}】达到日上限，停止投递", keyword);
                    return;
                }

                // 会话预算耗尽则整体收工
                if (sessionBudget.isExhausted()) {
                    log.info("会话预算在投递中途耗尽 | 已投递 {} 个岗位", sessionBudget.deliveredCount());
                    sendProgress("会话预算耗尽，停止投递（已投 " + sessionBudget.deliveredCount() + " 个）", null, null);
                    return;
                }

                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return;
                }

                log.info("开始投递【{}】关键词，第【{}】页...", keyword, pageNum);
                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, 50);

                // 等待岗位列表出现（CSS选择器）
                try {
                    page.waitForSelector("div.positionlist",
                        new Page.WaitForSelectorOptions().setTimeout(10_000));
                } catch (Exception e) {
                    log.warn("等待岗位列表失败，刷新页面重试");
                    page.reload();
                    PlaywrightUtil.sleep(1);
                }

                // 计算剩余配额并投递
                int remaining = MAX_DAILY_DELIVERIES_PER_KEYWORD - todayCount;
                int deliveredThisPage = deliverCurrentPage(keyword, remaining);
                
                if (deliveredThisPage > 0) {
                    // 记录投递次数
                    for (int d = 0; d < deliveredThisPage; d++) {
                        todayCount = keywordDeliveryQuotaService.recordDelivery(PLATFORM_NAME, keyword);
                        sessionBudget.recordDelivery();
                        if (todayCount >= MAX_DAILY_DELIVERIES_PER_KEYWORD) {
                            log.info("关键词【{}】达到日上限，停止投递", keyword);
                            return;
                        }
                    }
                }

                PlaywrightUtil.sleep(2);

                // 判断是否还有下一页
                if (isNextDisabled()) {
                    log.info("下一页按钮不可点击，结束翻页");
                    break;
                }

                // 点击下一页
                Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
                if (nextBtn.count() > 0) {
                    try { nextBtn.first().scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    nextBtn.first().click();
                    PlaywrightUtil.sleep(2);
                    pageNum++;
                } else {
                    log.info("未找到下一页按钮，结束翻页");
                    break;
                }
            }

            log.info("关键词【{}】投递完成", keyword);
        } catch (Exception e) {
            log.error("投递关键词【{}】时出现异常", keyword, e);
        }
    }

    /**
     * 投递当前页面的所有职位
     * @param keyword 关键词
     * @param maxDeliverCount 最大投递数量
     * @return 实际投递数量
     */
    private int deliverCurrentPage(String keyword, int maxDeliverCount) {
        int delivered = 0;
        try {
            page.waitForSelector("div.joblist-box__item",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            if (checkIsLimit()) {
                sendProgress("用户取消投递或已达上限", null, null);
                return delivered;
            }

            Locator cards = page.locator("div.joblist-box__item");
            int count = cards.count();
            log.info("检测到当前页岗位数量: {}", count);

            List<PageJob> jobs = new ArrayList<>();
            // 统一采集当前页岗位后再保存（不在循环中逐个入库）
            List<ZhilianJobDataEntity> toInsert = new ArrayList<>();

            for (int i = 0; i < count && delivered < maxDeliverCount; i++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return delivered;
                }

                Locator card = cards.nth(i);
                String jobTitle = safeGetText(card, "a.jobinfo__name");
                String jobLink = null;
                try { jobLink = card.locator("a.jobinfo__name").getAttribute("href"); } catch (Exception ignored) {}
                String salary = safeGetText(card, "p.jobinfo__salary");
                String location = safeGetText(card, "div.jobinfo__other-info div.jobinfo__other-info-item > span");
                String experience = safeGetText(card, "div.jobinfo__other-info-item:nth-child(2)");
                String degree = safeGetText(card, "div.jobinfo__other-info-item:nth-child(3)");
                String companyName = safeGetText(card, "div.companyinfo__name");

                String jobId = extractJobIdFromLink(jobLink);

                try {
                    String jid = jobId == null ? "" : jobId.trim();
                    String jtitle = jobTitle == null ? "" : jobTitle.trim();
                    if (jid.isEmpty() || jtitle.isEmpty()) {
                        log.info("岗位缺少jobId或jobTitle，跳过采集：title={}，company={}", jtitle, companyName);
                    } else {
                        boolean exists = false;
                        try { exists = zhilianService.existsByJobId(jid); } catch (Exception checkEx) {
                            log.warn("查询jobId是否已存在失败: {}", checkEx.getMessage());
                        }
                        if (exists) {
                            log.info("jobId已存在，跳过采集：jobId={}，title={}", jid, jtitle);
                        } else {
                            ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
                            entity.setJobId(jid);
                            entity.setJobTitle(jtitle);
                            entity.setJobLink(jobLink);
                            entity.setSalary(salary);
                            entity.setLocation(location);
                            entity.setExperience(experience);
                            entity.setDegree(degree);
                            entity.setCompanyName(companyName);
                            entity.setDeliveryStatus("未投递");
                            toInsert.add(entity);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("采集岗位数据失败: {}", ex.getMessage());
                }

                jobs.add(new PageJob(i, jobId, jobTitle, companyName, salary));
            }

            // 统一保存采集到的一整页岗位
            if (!toInsert.isEmpty()) {
                for (ZhilianJobDataEntity entity : toInsert) {
                    try {
                        zhilianService.insertJob(entity);
                        log.info("已保存岗位数据：jobId={}，title={}，company={}", entity.getJobId(), entity.getJobTitle(), entity.getCompanyName());
                    } catch (Exception ex) {
                        log.warn("保存岗位数据失败: {}", ex.getMessage());
                    }
                }
            }

            for (PageJob pj : jobs) {
                if (delivered >= maxDeliverCount) {
                    break;
                }
                
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return delivered;
                }

                Locator card = page.locator("div.joblist-box__item").nth(pj.index);

                // 岗位质量评分门控：低分岗位跳过，把配额留给高分岗位
                JobScoreService.JobFacts scoreFacts = new JobScoreService.JobFacts(
                        pj.jobTitle, pj.companyName, pj.salary, null, "", keyword);
                int jobScore = jobScoreService.score(scoreFacts);
                if (!jobScoreService.shouldDeliver(jobScore, config.getQualityScoreThreshold())) {
                    log.info("被过滤：岗位评分不足 | 岗位：{} | 评分：{} | 阈值：{}",
                            pj.jobTitle, jobScore, config.getQualityScoreThreshold());
                    continue;
                }

                Locator applyBtn = card.locator("button.collect-and-apply__btn");
                if (applyBtn.count() == 0) {
                    log.info("岗位【{}】未找到立即投递按钮，跳过", pj.jobTitle);
                    continue;
                }
                try {
                    // 点击前：注册监听器，统一关闭由当前页面打开的新窗口（弹出页）
                    java.util.function.Consumer<Page> closer = (Page newPage) -> {
                        try {
                            // 只关闭由当前 page 打开的子窗口，避免误伤
                            if (newPage.opener() == page) {
                                try { newPage.waitForLoadState(); } catch (Exception ignored) {}
                                try { PlaywrightUtil.sleep(200); } catch (Exception ignored) {}
                                try { newPage.close(); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    };
                    page.context().onPage(closer);

                    // 仅通过监听器捕捉并关闭由当前页打开的新窗口，避免与 waitForPopup 产生竞态
                    try {
                        applyBtn.click(); /* 点击投递按钮（关键定位注释：delivery-click-line）*/
                    } finally {
                        // 取消监听，避免影响后续流程
                        try { page.context().offPage(closer); } catch (Exception ignored) {}
                    }

                    try {
                        if (pj.jobId != null && !pj.jobId.isEmpty()) {
                            zhilianService.markDeliveredByJobId(pj.jobId);
                            log.info("已标记投递：jobId={}，title={}，company={}", pj.jobId, pj.jobTitle, pj.companyName);
                            delivered++;
                            // 每个岗位投递成功后：高斯随机延迟，避免固定间隔暴露自动化特征
                            pacing.betweenDeliveries();
                        } else if (pj.jobTitle != null && pj.companyName != null) {
                            zhilianService.markDeliveredByTitleAndCompany(pj.jobTitle, pj.companyName);
                            log.info("已标记投递：title={}，company={}", pj.jobTitle, pj.companyName);
                            delivered++;
                            // 每个岗位投递成功后：高斯随机延迟，避免固定间隔暴露自动化特征
                            pacing.betweenDeliveries();
                        }
                    } catch (Exception ex) {
                        log.warn("更新投递状态失败: {}", ex.getMessage());
                    }
                } catch (Exception clickEx) {
                    log.warn("投递失败，继续下一个岗位: {}", clickEx.getMessage());
                }

                if (checkIsLimit()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return delivered;
                }
            }

            return delivered;
        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            try {
                saveCurrentPageHtml();
                log.info("已保存当前页面到 src/main/java/com/getjobs/worker/zhilian/page.html 以便排查");
            } catch (Exception saveEx) {
                log.warn("保存当前页面HTML失败: {}", saveEx.getMessage());
            }
            return delivered;
        }
    }


    /**
     * 检查是否达到投递上限
     */
    private boolean checkIsLimit() {
        try {
            PlaywrightUtil.sleep(1);
            Locator result = page.locator("//div[@class='a-job-apply-workflow']");
            if (result.count() > 0) {
                String text = result.textContent();
                if (text != null && text.contains("达到上限")) {
                    log.info("今日投递已达上限！");
                    isLimit = true;
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 构建基础搜索URL（不含关键词，由搜索框触发）
     */
    private String buildBaseUrl(int pageNum) {
        StringBuilder url = new StringBuilder(HOME_URL);
        url.append("jl").append(config.getCityCode()).append("/");
        url.append("p").append(pageNum).append("?");
        url.append(JobUtils.appendParam("sl", config.getSalary()));
        return url.toString();
    }

    /**
     * 查找搜索关键词输入框（多候选选择器，提高鲁棒性）
     */
    private Locator findKeywordInput() {
        String[] candidates = new String[] {
            "input[placeholder*='职位']",
            "input[placeholder*='公司']",
            "input[name='kw']",
            "input[type='text']",
            "input[class*='search'], input[class*='sou'], input[class*='input']"
        };
        for (String sel : candidates) {
            try {
                Locator lc = page.locator(sel);
                if (lc != null && lc.count() > 0) {
                    return lc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 判断“下一页”是否不可点击
     */
    private boolean isNextDisabled() {
        try {
            Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
            if (nextBtn.count() == 0) return true;
            String cls = null;
            try { cls = nextBtn.first().getAttribute("class"); } catch (Exception ignored) {}
            String disabledAttr = null;
            try { disabledAttr = nextBtn.first().getAttribute("disabled"); } catch (Exception ignored) {}
            if (cls != null && cls.contains("soupager__btn--disable")) return true;
            return disabledAttr != null && ("disabled".equalsIgnoreCase(disabledAttr) || "true".equalsIgnoreCase(disabledAttr));
        } catch (Exception e) {
            return false;
        }
    }

    private String extractJobIdFromLink(String link) {
        if (link == null) return null;
        try {
            int i = link.indexOf("jobdetail/");
            int j = link.lastIndexOf(".htm");
            if (i >= 0 && j > i) {
                return link.substring(i + "jobdetail/".length(), j);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 安全获取文本内容
     */
    private String safeGetText(Locator parent, String selector) {
        try {
            Locator element = parent.locator(selector);
            if (element.count() > 0) {
                return element.textContent();
            }
        } catch (Exception e) {
            log.debug("获取文本失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 将当前页面内容保存到项目内 page.html，覆盖原文件
     */
    private void saveCurrentPageHtml() {
        try {
            String html = page.content();
            java.nio.file.Path path = java.nio.file.Paths.get("src/main/java/com/getjobs/worker/zhilian/page.html");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("写入 page.html 失败", e);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
