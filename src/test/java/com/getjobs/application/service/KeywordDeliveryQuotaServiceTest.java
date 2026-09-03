package com.getjobs.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordDeliveryQuotaServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    private KeywordDeliveryQuotaService service;
    private String today;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        // 使用临时文件库（而非 :memory:）：服务每次调用都会新开连接，
        // :memory: 库随连接关闭而销毁，临时文件才能跨连接持久，符合生产语义
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("quota-test.db").toAbsolutePath());
        service = new KeywordDeliveryQuotaService(ds);
        service.ensureTable();
        today = LocalDate.now(ZONE_ID).toString();
    }

    @Test
    @DisplayName("recordDelivery 累加计数并可通过 getTodayCount 读回")
    void recordAndCount() {
        assertThat(service.getTodayCount("boss", "Java")).isZero();

        service.recordDelivery("boss", "Java");
        service.recordDelivery("boss", "Java");
        service.recordDelivery("boss", "Java");

        assertThat(service.getTodayCount("boss", "Java")).isEqualTo(3);
    }

    @Test
    @DisplayName("不同平台/关键词计数相互隔离")
    void keysAreIsolated() {
        service.recordDelivery("boss", "Java");
        service.recordDelivery("boss", "Java");
        service.recordDelivery("zhilian", "Java");
        service.recordDelivery("51job", "Python");

        assertThat(service.getTodayCount("boss", "Java")).isEqualTo(2);
        assertThat(service.getTodayCount("zhilian", "Java")).isEqualTo(1);
        assertThat(service.getTodayCount("51job", "Python")).isEqualTo(1);
        assertThat(service.getTodayCount("51job", "Java")).isZero();
    }

    @Test
    @DisplayName("normalize：平台与关键词首尾空白不影响计数")
    void normalization() {
        service.recordDelivery(" boss ", " Java ");
        assertThat(service.getTodayCount("boss", "Java")).isEqualTo(1);
    }

    @Test
    @DisplayName("getDailyCounts 返回当日全平台明细")
    void dailyCounts() {
        service.recordDelivery("boss", "Java");
        service.recordDelivery("boss", "Java");
        service.recordDelivery("zhilian", "Python");

        List<KeywordDeliveryQuotaService.KeywordCount> counts = service.getDailyCounts(today);
        assertThat(counts).hasSize(2);
        assertThat(counts).allSatisfy(kc -> assertThat(kc.date()).isEqualTo(today));

        // 按 delivered_count DESC 排序：boss/Java(2) 在前
        assertThat(counts.get(0).platform()).isEqualTo("boss");
        assertThat(counts.get(0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("getDailyTotalsByPlatform 按平台汇总")
    void dailyTotalsByPlatform() {
        service.recordDelivery("boss", "Java");
        service.recordDelivery("boss", "Python");
        service.recordDelivery("zhilian", "Java");

        Map<String, Integer> totals = service.getDailyTotalsByPlatform(today);
        assertThat(totals).containsEntry("boss", 2).containsEntry("zhilian", 1);
    }

    @Test
    @DisplayName("getRecentCounts 包含今日记录且格式正确")
    void recentCounts() {
        service.recordDelivery("boss", "Java");

        List<KeywordDeliveryQuotaService.KeywordCount> recent = service.getRecentCounts(7);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).date()).isEqualTo(today);
        assertThat(recent.get(0).platform()).isEqualTo("boss");
        assertThat(recent.get(0).keyword()).isEqualTo("Java");
        assertThat(recent.get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("空库查询返回空列表而非异常")
    void emptyDatabaseSafe() {
        assertThat(service.getDailyCounts(today)).isEmpty();
        assertThat(service.getRecentCounts(7)).isEmpty();
        assertThat(service.getDailyTotalsByPlatform(today)).isEmpty();
    }

    @Test
    @DisplayName("非法入参（空白/null）安全返回 0")
    void invalidParamsSafe() {
        assertThat(service.getTodayCount("", "Java")).isZero();
        assertThat(service.getTodayCount(null, "Java")).isZero();
        assertThat(service.recordDelivery("", "Java")).isZero();
    }
}
