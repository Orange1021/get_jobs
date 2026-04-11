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
}
