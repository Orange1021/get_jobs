package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelectorRepositoryTest {

    @Test
    @DisplayName("无覆盖时回落代码内置默认值")
    void fallsBackToDefault() {
        SelectorRepository repo = SelectorRepository.with(Map.of());
        assertThat(repo.get("boss", "DETAIL_HEADER", "div.job-detail-header"))
                .isEqualTo("div.job-detail-header");
        assertThat(repo.hasOverride("boss", "DETAIL_HEADER")).isFalse();
    }

    @Test
    @DisplayName("有覆盖时优先使用覆盖值")
    void overrideWins() {
        SelectorRepository repo = SelectorRepository.with(Map.of(
                "boss", Map.of("DETAIL_HEADER", "div.job-detail-header-v2")));
        assertThat(repo.get("boss", "DETAIL_HEADER", "div.job-detail-header"))
                .isEqualTo("div.job-detail-header-v2");
        assertThat(repo.hasOverride("boss", "DETAIL_HEADER")).isTrue();
    }

    @Test
    @DisplayName("空白覆盖值视为未覆盖，回落默认值")
    void blankOverrideFallsBack() {
        SelectorRepository repo = SelectorRepository.with(Map.of(
                "boss", Map.of("DETAIL_HEADER", "   ")));
        assertThat(repo.get("boss", "DETAIL_HEADER", "div.job-detail-header"))
                .isEqualTo("div.job-detail-header");
        assertThat(repo.hasOverride("boss", "DETAIL_HEADER")).isFalse();
    }

    @Test
    @DisplayName("平台之间覆盖互不影响")
    void platformsAreIsolated() {
        SelectorRepository repo = SelectorRepository.with(Map.of(
                "boss", Map.of("CHAT_BUTTON", "a.new-chat-btn")));
        assertThat(repo.get("boss", "CHAT_BUTTON", "a.op-btn-chat")).isEqualTo("a.new-chat-btn");
        assertThat(repo.get("zhilian", "CHAT_BUTTON", "a.op-btn-chat")).isEqualTo("a.op-btn-chat");
    }

    @Test
    @DisplayName("YAML 解析：嵌套结构正确转为覆盖表")
    void parseYaml() throws Exception {
        String yaml = """
                boss:
                  DETAIL_HEADER: "div.new-detail"
                  CHAT_BUTTON: "a.new-chat"
                zhilian:
                  JOB_CARD: "div.positionlist"
                """;
        try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            Map<String, Map<String, String>> overrides = SelectorRepository.parse(in);
            assertThat(overrides).containsKeys("boss", "zhilian");
            assertThat(overrides.get("boss").get("DETAIL_HEADER")).isEqualTo("div.new-detail");
            assertThat(overrides.get("zhilian").get("JOB_CARD")).isEqualTo("div.positionlist");
        }
    }

    @Test
    @DisplayName("YAML 中非字符串值应被转换为字符串")
    void parseYamlCoercesValues() throws Exception {
        String yaml = """
                boss:
                  SOME_KEY: 123
                """;
        try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            Map<String, Map<String, String>> overrides = SelectorRepository.parse(in);
            assertThat(overrides.get("boss").get("SOME_KEY")).isEqualTo("123");
        }
    }

    @Test
    @DisplayName("get 的默认值本身允许为 null（调用方自行处理）")
    void nullDefaultPassesThrough() {
        SelectorRepository repo = SelectorRepository.with(Map.of());
        assertThat(repo.get("boss", "UNKNOWN_KEY", null)).isNull();
    }
}
