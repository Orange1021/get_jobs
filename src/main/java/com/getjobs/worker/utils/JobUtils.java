package com.getjobs.worker.utils;

import java.util.*;

import static com.getjobs.worker.utils.Constant.UNLIMITED_CODE;

/**
 * URL 参数拼接工具。
 *
 * <p>消融裁剪说明：formatDuration 两个重载、getRandomNumberInRange、调试用 main
 * 经调用图核查均无调用方（各平台 Worker 自带私有实现），已移除。</p>
 */
public class JobUtils {

    public static String appendParam(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Optional.of(value)
                .filter(v -> !Objects.equals(UNLIMITED_CODE, v))
                .map(v -> "&" + name + "=" + v)
                .orElse("");
    }

    public static String appendListParam(String name, List<String> values) {
        // 需求：如果列表包含 0（UNLIMITED_CODE），表示该参数不设置，直接返回 null
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.stream().anyMatch(v -> Objects.equals(UNLIMITED_CODE, v))) {
            return "";
        }
        return "&" + name + "=" + String.join(",", values);
    }
}
