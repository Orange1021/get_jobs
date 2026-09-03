package com.getjobs.worker.utils;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 岗位质量评分服务：把"海投"升级为"精准投"的核心。
 *
 * <p>在投递前对岗位打分（0~100），得分构成：</p>
 * <ul>
 *   <li>基础分 40</li>
 *   <li>薪资符合度（对照期望薪资）：符合 +25，无法解析 +10（中性），未配置期望 +15</li>
 *   <li>关键词相关性：岗位名完整命中 +20，部分命中 +10，未命中 +0</li>
 *   <li>HR 活跃度：活跃（月/天/在线）+10，未知 +5，长期不活跃（含"年"）+0</li>
 *   <li>福利关键词：每命中一项 +4，上限 +12</li>
 * </ul>
 * <p>决策规则：调用方给定阈值 threshold，score &gt;= threshold 才投递；
 * threshold 为 null 视为 0，即保持原有"全部投递"行为（向后兼容）。</p>
 *
 * <p>纯函数设计，完全可离线单元测试。</p>
 */
@Service
public class JobScoreService {

    /** 福利加分关键词（高价值福利）。 */
    private static final List<String> WELFARE_MARKERS = List.of(
            "五险一金", "双休", "弹性工作", "远程", "年终奖", "股票期权", "餐补", "房补");

    public record JobFacts(
            String jobName,
            String companyName,
            String salaryText,
            String hrActiveStatus,
            String welfareText,
            String keyword) {
    }

    /**
     * 对岗位打分，范围 [0, 100]。
     */
    public int score(JobFacts facts) {
        if (facts == null) {
            return 0;
        }
        int score = 40;

        // 1. 薪资符合度
        score += salaryPoints(facts.salaryText(), null);

        // 2. 关键词相关性
        score += keywordPoints(facts.jobName(), facts.keyword());

        // 3. HR 活跃度
        score += hrActivityPoints(facts.hrActiveStatus());

        // 4. 福利加分
        score += welfarePoints(facts.welfareText());

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 带期望薪资的评分（与 score 相同规则，但使用调用方提供的期望薪资，单位：K/月）。
     *
     * @param expectedSalary 期望薪资区间，如 [15, 25]；null 或空表示未配置
     */
    public int score(JobFacts facts, List<Integer> expectedSalary) {
        if (facts == null) {
            return 0;
        }
        int score = 40;
        score += salaryPoints(facts.salaryText(), expectedSalary);
        score += keywordPoints(facts.jobName(), facts.keyword());
        score += hrActivityPoints(facts.hrActiveStatus());
        score += welfarePoints(facts.welfareText());
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 投递决策：score 达到 threshold 才投递。
     * threshold 为 null 或 &lt;= 0 时始终投递（向后兼容旧行为）。
     */
    public boolean shouldDeliver(int score, Integer threshold) {
        if (threshold == null || threshold <= 0) {
            return true;
        }
        return score >= threshold;
    }

    /**
     * 薪资得分。midpoint 为月薪中位数（元）；expected 为期望月薪区间（K）。
     */
    int salaryPoints(String salaryText, List<Integer> expected) {
        double midpoint = monthlyMidpointK(salaryText);
        if (midpoint < 0) {
            // 无法解析：中性处理
            return (expected == null || expected.isEmpty()) ? 10 : 10;
        }
        if (expected == null || expected.size() < 2) {
            // 未配置期望薪资：可解析即给中等偏上分
            return 15;
        }
        double expMin = expected.get(0);
        double expMax = expected.get(expected.size() - 1);
        // 岗位中位数月薪（K）落在期望区间 [expMin*0.9, +∞) 视为符合
        double expMinYuan = expMin;
        if (midpoint >= expMinYuan) {
            return 25;
        }
        // 略低于期望（80% 以上）给部分分
        if (midpoint >= expMinYuan * 0.8) {
            return 12;
        }
        return 0;
    }

    /**
     * 从薪资文本解析月薪中位数（单位：K）。
     * 支持 "15-25K"、"15-25K·14薪"、"200-400元/天"、"10-20万/年" 等常见格式；
     * 无法解析返回 -1。
     */
    static double monthlyMidpointK(String salaryText) {
        if (salaryText == null || salaryText.isBlank()) {
            return -1;
        }
        String s = salaryText.replace("，", "").replace(",", "").trim();
        java.util.regex.Matcher nums = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
        List<Double> numbers = new java.util.ArrayList<>();
        while (nums.find() && numbers.size() < 2) {
            numbers.add(Double.parseDouble(nums.group()));
        }
        if (numbers.isEmpty()) {
            return -1;
        }
        double low = numbers.get(0);
        double high = numbers.size() > 1 ? numbers.get(1) : numbers.get(0);
        if (high < low) {
            high = low;
        }
        double midK = (low + high) / 2.0;

        // 日薪（元/天）：按每月 21.75 个工作日折算为 K/月
        if (s.contains("元/天") || s.contains("元/日") || s.contains("/天") || s.contains("元一天")) {
            return midK * 21.75 / 1000.0;
        }
        // 年薪（万/年）：折算为 K/月
        if (s.contains("万/年") || s.contains("万一年") || s.contains("万/年薪")) {
            return midK * 10000 / 12.0 / 1000.0;
        }
        // 万/月
        if (s.contains("万/月") || s.contains("万每月")) {
            return midK * 10.0;
        }
        // 默认按 K/月（"15-25K" 或 "15-25千"）
        return midK;
    }

    int keywordPoints(String jobName, String keyword) {
        String name = safe(jobName);
        String kw = safe(keyword);
        if (name.isEmpty() || kw.isEmpty()) {
            return 0;
        }
        if (name.contains(kw)) {
            return 20;
        }
        // 关键词分词部分命中：先按常见分隔符拆分，再对中英混排词细分（如 "Java后端" → "Java" + "后端"）
        for (String token : kw.split("[\\s+/、，,|-]+")) {
            if (token.length() >= 2 && name.contains(token)) {
                return 10;
            }
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("[A-Za-z0-9]{2,}|\\p{IsHan}{2,}").matcher(token);
            while (m.find()) {
                String part = m.group();
                if (part.length() >= 2 && name.contains(part)) {
                    return 10;
                }
            }
        }
        return 0;
    }

    int hrActivityPoints(String hrActiveStatus) {
        String s = safe(hrActiveStatus);
        if (s.isEmpty()) {
            return 5;
        }
        if (s.contains("年")) {
            return 0;
        }
        return 10;
    }

    int welfarePoints(String welfareText) {
        String s = safe(welfareText);
        if (s.isEmpty()) {
            return 0;
        }
        int points = 0;
        for (String marker : WELFARE_MARKERS) {
            if (s.contains(marker)) {
                points += 4;
            }
        }
        return Math.min(12, points);
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
