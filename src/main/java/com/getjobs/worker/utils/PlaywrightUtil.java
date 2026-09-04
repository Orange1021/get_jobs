package com.getjobs.worker.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Playwright 工具类（精简版）。
 *
 * <p>消融裁剪说明：原版本包含页面操作、Cookie 文件读写、截图、Stealth 注入等
 * 大量静态方法，经调用图核查全部无调用方（浏览器生命周期统一由
 * {@code PlaywrightManager} 管理，Cookie 由 {@code CookieService} 持久化），
 * 实际被使用的仅剩线程等待工具，故缩减为以下两个方法。</p>
 */
@Slf4j
public class PlaywrightUtil {

    /**
     * 等待指定时间（秒）
     *
     * @param seconds 等待的秒数
     */
    public static void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Sleep被中断", e);
        }
    }

    /**
     * 等待指定时间（毫秒）
     *
     * @param millis 等待的毫秒数
     */
    public static void sleepMillis(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Sleep被中断", e);
        }
    }
}
