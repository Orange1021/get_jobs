package com.getjobs.application.controller;

import com.getjobs.application.service.KeywordDeliveryQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递统计控制器：跨平台的投递漏斗数据接口。
 *
 * <p>数据来源为 keyword_daily_quota 表（各平台投递时实时写入），
 * 前端可据此渲染每日投递量、关键词分布等漏斗视图。</p>
 */
@RestController
@RequestMapping("/api/stats/delivery")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class DeliveryStatsController {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    private final KeywordDeliveryQuotaService quotaService;

    /**
     * 今日投递统计：全平台总量 + 各平台按关键词明细。
     */
    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today() {
        String today = LocalDate.now(ZONE_ID).toString();
        List<KeywordDeliveryQuotaService.KeywordCount> counts = quotaService.getDailyCounts(today);

        Map<String, Object> platforms = new LinkedHashMap<>();
        int total = 0;
        for (KeywordDeliveryQuotaService.KeywordCount kc : counts) {
            total += kc.count();
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) platforms.computeIfAbsent(kc.platform(),
                    k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("platform", k);
                        m.put("total", 0);
                        m.put("keywords", new ArrayList<Map<String, Object>>());
                        return m;
                    });
            p.put("total", (int) p.get("total") + kc.count());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> keywords = (List<Map<String, Object>>) p.get("keywords");
            keywords.add(Map.of("keyword", kc.keyword(), "count", kc.count()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", today);
        data.put("total", total);
        data.put("platforms", platforms.values());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("message", "获取今日投递统计成功");
        return ResponseEntity.ok(response);
    }

    /**
     * 近 N 天投递统计（默认 7 天，最多 90 天），按日期分组。
     */
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> recent(@RequestParam(defaultValue = "7") int days) {
        List<KeywordDeliveryQuotaService.KeywordCount> counts = quotaService.getRecentCounts(days);

        // 按日期分组并计算每日总量
        Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
        Map<String, Integer> dailyTotals = new LinkedHashMap<>();
        for (KeywordDeliveryQuotaService.KeywordCount kc : counts) {
            byDate.computeIfAbsent(kc.date(), k -> new ArrayList<>())
                    .add(Map.of("platform", kc.platform(), "keyword", kc.keyword(), "count", kc.count()));
            dailyTotals.merge(kc.date(), kc.count(), Integer::sum);
        }

        List<Map<String, Object>> days_ = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byDate.entrySet()) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", e.getKey());
            day.put("total", dailyTotals.getOrDefault(e.getKey(), 0));
            day.put("records", e.getValue());
            days_.add(day);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("statistics", days_);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("message", "获取近期投递统计成功");
        return ResponseEntity.ok(response);
    }
}
