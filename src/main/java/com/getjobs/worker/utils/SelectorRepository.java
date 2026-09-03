package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 选择器外部覆盖仓库。
 *
 * <p>页面选择器默认集中定义在各平台的 Locators 常量类中；当平台改版时，
 * 用户可在工作目录放置 {@code selectors.yml}（或打包进 classpath）覆盖默认值，
 * <b>无需修改代码、无需重新编译</b>即可完成适配。</p>
 *
 * <p>文件格式（键名与平台代码约定一致）：</p>
 * <pre>
 * boss:
 *   DETAIL_HEADER: "div.job-detail-header-new"
 *   CHAT_BUTTON: "a.op-btn-chat"
 * </pre>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>优先级：工作目录 selectors.yml &gt; classpath selectors.yml &gt; 代码默认值</li>
 *   <li>值为空白的覆盖项视为未覆盖，回落默认值</li>
 *   <li>文件不存在或解析失败一律安全降级为"无覆盖"，绝不阻断投递主流程</li>
 * </ul>
 */
@Slf4j
public class SelectorRepository {

    private static volatile SelectorRepository instance;

    /** 平台 -> 键 -> 选择器 的覆盖表。 */
    private final Map<String, Map<String, String>> overrides;

    private SelectorRepository(Map<String, Map<String, String>> overrides) {
        this.overrides = overrides;
    }

    /** 全局单例：启动时加载一次覆盖配置。 */
    public static SelectorRepository getInstance() {
        if (instance == null) {
            synchronized (SelectorRepository.class) {
                if (instance == null) {
                    instance = new SelectorRepository(loadOverrides());
                }
            }
        }
        return instance;
    }

    /** 测试用：以给定覆盖表构造实例。 */
    public static SelectorRepository with(Map<String, Map<String, String>> overrides) {
        return new SelectorRepository(overrides == null ? Map.of() : overrides);
    }

    /**
     * 查询选择器：有覆盖用覆盖，否则返回代码内置默认值。
     */
    public String get(String platform, String key, String defaultValue) {
        Map<String, String> platformOverrides = overrides.get(platform);
        if (platformOverrides != null) {
            String v = platformOverrides.get(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return defaultValue;
    }

    /** 是否存在针对某平台某键的（有效）覆盖。 */
    public boolean hasOverride(String platform, String key) {
        Map<String, String> platformOverrides = overrides.get(platform);
        return platformOverrides != null
                && platformOverrides.get(key) != null
                && !platformOverrides.get(key).isBlank();
    }

    /**
     * 从 工作目录 selectors.yml 与 classpath selectors.yml 加载覆盖表；
     * 任何失败都安全降级为空表。
     */
    static Map<String, Map<String, String>> loadOverrides() {
        // 1. 工作目录文件（用户本地覆盖，优先级最高）
        File local = new File("selectors.yml");
        if (local.exists()) {
            try (InputStream in = new java.io.FileInputStream(local)) {
                Map<String, Map<String, String>> parsed = parse(in);
                if (!parsed.isEmpty()) {
                    log.info("已从工作目录 selectors.yml 加载 {} 个平台的选择器覆盖", parsed.size());
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("解析工作目录 selectors.yml 失败，忽略覆盖: {}", e.getMessage());
            }
        }
        // 2. classpath 内置覆盖
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("selectors.yml")) {
            if (in != null) {
                Map<String, Map<String, String>> parsed = parse(in);
                if (!parsed.isEmpty()) {
                    log.info("已从 classpath selectors.yml 加载 {} 个平台的选择器覆盖", parsed.size());
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.warn("解析 classpath selectors.yml 失败，忽略覆盖: {}", e.getMessage());
        }
        return Map.of();
    }

    /** 解析 YAML 输入流为覆盖表。 */
    static Map<String, Map<String, String>> parse(InputStream in) throws java.io.IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> raw = mapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map.Entry<String, Object> platformEntry : raw.entrySet()) {
            if (!(platformEntry.getValue() instanceof Map<?, ?> selectors)) {
                continue;
            }
            Map<String, String> selectorMap = new HashMap<>();
            for (Map.Entry<?, ?> e : selectors.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    selectorMap.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            if (!selectorMap.isEmpty()) {
                result.put(platformEntry.getKey(), selectorMap);
            }
        }
        return result;
    }
}
