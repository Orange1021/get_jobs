package com.getjobs.worker.utils;

import java.util.List;

/**
 * 平台风控熔断组件。
 *
 * <p>通过注入的 {@link PageProbe} 采集当前页面信号，依据规则判定风险等级；
 * 判定为 CONFIRMED 时调用方应立即熔断投递会话（停止、保存进度、告警），
 * 绝不带着风控状态继续投递。</p>
 *
 * <p>检测规则（按优先级）：</p>
 * <ol>
 *   <li>滑块验证页：URL 含 safe/verify（Boss直聘风控跳转特征）</li>
 *   <li>验证码组件：页面出现常见验证码容器（geetest / 阿里 NoCaptcha / captcha iframe）</li>
 *   <li>风控提示文本：「环境异常」「访问过于频繁」「安全验证」</li>
 *   <li>登录态失效：会话中途出现登录按钮</li>
 * </ol>
 *
 * <p>探测逻辑与规则判定分离，规则可完全离线单元测试。</p>
 */
public class RiskGuard {

    /** 页面信号集合，由平台适配层采集。 */
    public record Signals(
            String url,
            boolean sliderVerifyPage,
            boolean captchaElementPresent,
            boolean riskTextPresent,
            boolean loginRequired) {

        public static Signals empty() {
            return new Signals("", false, false, false, false);
        }
    }

    /** 页面探测接口：由平台适配层实现（如 Playwright 读取 URL 与元素）。 */
    @FunctionalInterface
    public interface PageProbe {
        Signals probe();
    }

    public enum Level { NONE, CONFIRMED }

    /** 风控判定结果。 */
    public record Result(Level level, String reason) {

        public static Result none() {
            return new Result(Level.NONE, "");
        }

        public boolean confirmed() {
            return level == Level.CONFIRMED;
        }
    }

    /** 已知的验证码容器选择器特征（URL/元素名片段，通用度高、误报率低）。 */
    private static final List<String> CAPTCHA_MARKERS = List.of(
            "geetest", "nc-container", "nc_1_wrapper", "captcha");

    /** 风控提示文本特征。 */
    private static final List<String> RISK_TEXT_MARKERS = List.of(
            "环境异常", "访问过于频繁", "安全验证");

    /** 滑块验证页 URL 特征（与 Boss 已知的 verify-slider 跳转一致）。 */
    private static final String SLIDER_VERIFY_URL_MARKER = "safe/verify";

    public Result check(PageProbe probe) {
        Signals s = probe == null ? null : probe.probe();
        if (s == null) {
            return Result.none();
        }
        String url = s.url() == null ? "" : s.url();

        if (s.sliderVerifyPage() || url.contains(SLIDER_VERIFY_URL_MARKER)) {
            return new Result(Level.CONFIRMED, "触发滑块验证（URL 特征: safe/verify）");
        }
        if (s.captchaElementPresent()) {
            return new Result(Level.CONFIRMED, "检测到验证码组件");
        }
        if (s.riskTextPresent()) {
            return new Result(Level.CONFIRMED, "页面出现风控提示文本");
        }
        if (s.loginRequired()) {
            return new Result(Level.CONFIRMED, "登录态失效（会话中途出现登录入口）");
        }
        return Result.none();
    }

    /** 辅助方法：给适配层用于判断某选择器是否命中验证码容器特征。 */
    public static boolean looksLikeCaptchaSelector(String selector) {
        if (selector == null) {
            return false;
        }
        String lower = selector.toLowerCase();
        return CAPTCHA_MARKERS.stream().anyMatch(lower::contains);
    }

    /** 辅助方法：给适配层用于判断页面文本是否命中风控提示特征。 */
    public static boolean looksLikeRiskText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return RISK_TEXT_MARKERS.stream().anyMatch(text::contains);
    }
}
