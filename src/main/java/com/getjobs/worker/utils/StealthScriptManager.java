package com.getjobs.worker.utils;

import com.microsoft.playwright.BrowserContext;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反自动化脚本加载器（最小化实现）。
 * 在 BrowserContext 创建后统一注入 init scripts。
 */
@Slf4j
public final class StealthScriptManager {
    private static final String SCRIPT_BASE = "stealth-scripts/";
    private static final Map<String, String> SCRIPT_CACHE = new ConcurrentHashMap<>();

    private static final List<String> DEFAULT_SCRIPTS = List.of(
            "ajax-interceptor.js",
            "extension-bypass.js",
            "playwright-stealth.js",
            "webdriver-hide.js",
            "chrome-runtime.js",
            "navigator-override.js"
    );

    private StealthScriptManager() {
    }

    public static void addDefaultStealthScripts(BrowserContext context) {
        int success = 0;
        int failed = 0;
        for (String scriptName : DEFAULT_SCRIPTS) {
            String script = loadScript(scriptName);
            if (script == null || script.isBlank()) {
                failed++;
                log.warn("反自动化脚本为空或不存在，跳过: {}", scriptName);
                continue;
            }
            try {
                context.addInitScript(script);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("注入反自动化脚本失败: {}, err={}", scriptName, e.getMessage());
            }
        }
        log.info("反自动化脚本注入完成：success={}, failed={}", success, failed);
    }

    private static String loadScript(String scriptName) {
        String cacheKey = SCRIPT_BASE + scriptName;
        String cached = SCRIPT_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = StealthScriptManager.class.getClassLoader().getResourceAsStream(cacheKey)) {
            if (in == null) {
                return null;
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SCRIPT_CACHE.put(cacheKey, script);
            return script;
        } catch (Exception e) {
            log.warn("加载反自动化脚本失败: {}, err={}", cacheKey, e.getMessage());
            return null;
        }
    }
}
