package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGuardTest {

    private final RiskGuard riskGuard = new RiskGuard();

    private static RiskGuard.Signals signals(String url, boolean captcha, boolean riskText, boolean login) {
        return new RiskGuard.Signals(url, false, captcha, riskText, login);
    }
    @Test
    @DisplayName("正常页面信号应判定为无风险")
    void normalPageIsNone() {
        RiskGuard.Result r = riskGuard.check(() ->
                signals("https://www.zhipin.com/web/geek/job-recommend?ka=header-job", false, false, false));
        assertThat(r.confirmed()).isFalse();
        assertThat(r.level()).isEqualTo(RiskGuard.Level.NONE);
    }

    @Test
    @DisplayName("滑块验证页特征（safe/verify）应判定为确认风控")
    void sliderVerifyUrlIsConfirmed() {
        RiskGuard.Result r = riskGuard.check(() ->
                signals("https://www.zhipin.com/web/user/safe/verify-slider?seed=abc", false, false, false));
        assertThat(r.confirmed()).isTrue();
        assertThat(r.reason()).contains("滑块验证");
    }

    @Test
    @DisplayName("信号对象携带的滑块标记应优先命中")
    void sliderFlagIsConfirmed() {
        RiskGuard.Result r = riskGuard.check(() ->
                new RiskGuard.Signals("https://www.zhipin.com/", true, false, false, false));
        assertThat(r.confirmed()).isTrue();
    }

    @Test
    @DisplayName("出现验证码组件应判定为确认风控")
    void captchaElementIsConfirmed() {
        RiskGuard.Result r = riskGuard.check(() ->
                signals("https://www.zhipin.com/web/geek/job-recommend", true, false, false));
        assertThat(r.confirmed()).isTrue();
        assertThat(r.reason()).contains("验证码");
    }

    @Test
    @DisplayName("出现风控提示文本应判定为确认风控")
    void riskTextIsConfirmed() {
        for (String text : new String[]{"环境异常", "访问过于频繁", "安全验证"}) {
            RiskGuard.Result r = riskGuard.check(() ->
                    signals("https://www.zhipin.com/web/geek/job-recommend", false, true, false));
            assertThat(r.confirmed()).as("文本特征: %s", text).isTrue();
        }
    }

    @Test
    @DisplayName("会话中途出现登录入口应判定为确认风控（登录态失效）")
    void loginRequiredIsConfirmed() {
        RiskGuard.Result r = riskGuard.check(() ->
                signals("https://www.zhipin.com/web/geek/job-recommend", false, false, true));
        assertThat(r.confirmed()).isTrue();
        assertThat(r.reason()).contains("登录态失效");
    }

    @Test
    @DisplayName("探测返回 null 时应安全判定为无风险（不抛异常）")
    void nullSignalsIsSafe() {
        RiskGuard.Result r = riskGuard.check(() -> null);
        assertThat(r.confirmed()).isFalse();
        assertThat(riskGuard.check(null).confirmed()).isFalse();
    }

    @Test
    @DisplayName("规则优先级：滑块验证优先于其他信号")
    void sliderHasHighestPriority() {
        RiskGuard.Result r = riskGuard.check(() ->
                new RiskGuard.Signals("https://www.zhipin.com/web/user/safe/verify-slider", false, true, true, true));
        assertThat(r.reason()).contains("滑块验证");
    }

    @Test
    @DisplayName("选择器与文本的辅助判断方法")
    void helperMethods() {
        assertThat(RiskGuard.looksLikeCaptchaSelector("iframe[src*='geetest']")).isTrue();
        assertThat(RiskGuard.looksLikeCaptchaSelector(null)).isFalse();
        assertThat(RiskGuard.looksLikeRiskText("当前环境异常，请完成验证")).isTrue();
        assertThat(RiskGuard.looksLikeRiskText("立即沟通")).isFalse();
        assertThat(RiskGuard.looksLikeRiskText("")).isFalse();
    }

    // 辅助：避免直接调用可能传 null 的场景产生歧义
}
