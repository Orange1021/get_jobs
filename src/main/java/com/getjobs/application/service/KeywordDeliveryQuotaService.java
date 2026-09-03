package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 按平台+关键词+日期统计当日已投递数量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordDeliveryQuotaService {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private final DataSource dataSource;

    @PostConstruct
    public void ensureTable() {
        final String createSql = """
                CREATE TABLE IF NOT EXISTS keyword_daily_quota (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  platform VARCHAR(32) NOT NULL,
                  keyword VARCHAR(255) NOT NULL,
                  quota_date VARCHAR(10) NOT NULL,
                  delivered_count INTEGER NOT NULL DEFAULT 0,
                  created_at DATETIME,
                  updated_at DATETIME,
                  UNIQUE(platform, keyword, quota_date)
                )
                """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
        } catch (Exception e) {
            log.warn("初始化 keyword_daily_quota 表失败: {}", e.getMessage());
        }
    }

    public int getTodayCount(String platform, String keyword) {
        final String p = normalize(platform);
        final String k = normalize(keyword);
        if (p.isEmpty() || k.isEmpty()) {
            return 0;
        }
        final String today = LocalDate.now(ZONE_ID).toString();
        final String querySql = """
                SELECT delivered_count
                FROM keyword_daily_quota
                WHERE platform = ? AND keyword = ? AND quota_date = ?
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(querySql)) {
            ps.setString(1, p);
            ps.setString(2, k);
            ps.setString(3, today);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0, rs.getInt(1));
                }
            }
        } catch (Exception e) {
            log.warn("读取关键词日投递计数失败 platform={}, keyword={}: {}", p, k, e.getMessage());
        }
        return 0;
    }

    /**
     * 记录一次投递动作并返回当日最新计数。
     */
    public int recordDelivery(String platform, String keyword) {
        final String p = normalize(platform);
        final String k = normalize(keyword);
        if (p.isEmpty() || k.isEmpty()) {
            return 0;
        }
        final String today = LocalDate.now(ZONE_ID).toString();
        final String now = LocalDateTime.now(ZONE_ID).toString();
        final String upsertSql = """
                INSERT INTO keyword_daily_quota(platform, keyword, quota_date, delivered_count, created_at, updated_at)
                VALUES(?, ?, ?, 1, ?, ?)
                ON CONFLICT(platform, keyword, quota_date)
                DO UPDATE SET delivered_count = delivered_count + 1, updated_at = excluded.updated_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, p);
            ps.setString(2, k);
            ps.setString(3, today);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("写入关键词日投递计数失败 platform={}, keyword={}: {}", p, k, e.getMessage());
        }
        return getTodayCount(p, k);
    }

    private String normalize(String v) {
        return v == null ? "" : v.trim();
    }

    // ===== 统计查询（投递漏斗数据基础） =====

    /** 单条投递统计记录。 */
    public record KeywordCount(String platform, String keyword, String date, int count) {
    }

    /**
     * 查询某一天的按平台+关键词投递计数（全平台）。
     */
    public List<KeywordCount> getDailyCounts(String date) {
        final String d = normalize(date);
        if (d.isEmpty()) {
            return List.of();
        }
        final String sql = """
                SELECT platform, keyword, delivered_count
                FROM keyword_daily_quota
                WHERE quota_date = ?
                ORDER BY platform, delivered_count DESC
                """;
        List<KeywordCount> result = new java.util.ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KeywordCount(rs.getString(1), rs.getString(2), d, rs.getInt(3)));
                }
            }
        } catch (Exception e) {
            log.warn("查询日投递统计失败 date={}: {}", d, e.getMessage());
        }
        return List.copyOf(result);
    }

    /**
     * 查询最近 N 天（含今天）的投递计数，按日期倒序。
     */
    public List<KeywordCount> getRecentCounts(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        String startDate = LocalDate.now(ZONE_ID).minusDays(safeDays - 1L).toString();
        final String sql = """
                SELECT platform, keyword, quota_date, delivered_count
                FROM keyword_daily_quota
                WHERE quota_date >= ?
                ORDER BY quota_date DESC, platform, delivered_count DESC
                """;
        List<KeywordCount> result = new java.util.ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KeywordCount(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getInt(4)));
                }
            }
        } catch (Exception e) {
            log.warn("查询近期投递统计失败 days={}: {}", safeDays, e.getMessage());
        }
        return List.copyOf(result);
    }

    /**
     * 汇总某一天各平台的总投递量：平台 -> 数量。
     */
    public Map<String, Integer> getDailyTotalsByPlatform(String date) {
        Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        for (KeywordCount kc : getDailyCounts(date)) {
            totals.merge(kc.platform(), kc.count(), Integer::sum);
        }
        return totals;
    }
}
