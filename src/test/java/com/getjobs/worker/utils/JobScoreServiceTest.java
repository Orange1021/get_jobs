package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobScoreServiceTest {

    private final JobScoreService service = new JobScoreService();

    private static JobScoreService.JobFacts facts(String jobName, String salary, String hr, String welfare) {
        return new JobScoreService.JobFacts(jobName, "某公司", salary, hr, welfare, "Java");
    }

    @Nested
    @DisplayName("薪资解析")
    class SalaryParsing {

        @Test
        @DisplayName("K/月 格式取中位数")
        void kFormat() {
            assertThat(JobScoreService.monthlyMidpointK("15-25K")).isEqualTo(20.0);
        }

        @Test
        @DisplayName("K·14薪 格式忽略年薪月数，仍取月薪中位数")
        void kWithBonusMonths() {
            assertThat(JobScoreService.monthlyMidpointK("15-25K·14薪")).isEqualTo(20.0);
        }

        @Test
        @DisplayName("元/天 按每月21.75个工作日折算")
        void dailyWage() {
            // 400元/天 中位数400 → 400*21.75/1000 = 8.7K
            assertThat(JobScoreService.monthlyMidpointK("300-500元/天")).isEqualTo(8.7);
        }

        @Test
        @DisplayName("万/年 折算为 K/月")
        void yearlyWage() {
            // 24-36万/年 → 中位数30万 → 30*10000/12/1000 = 25K
            assertThat(JobScoreService.monthlyMidpointK("24-36万/年")).isEqualTo(25.0);
        }

        @Test
        @DisplayName("无法解析返回 -1")
        void unparseable() {
            assertThat(JobScoreService.monthlyMidpointK("面议")).isEqualTo(-1);
            assertThat(JobScoreService.monthlyMidpointK(null)).isEqualTo(-1);
            assertThat(JobScoreService.monthlyMidpointK("")).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("评分规则")
    class Scoring {

        @Test
        @DisplayName("理想岗位（薪资符合+关键词命中+HR活跃+福利多）应得高分")
        void idealJobScoresHigh() {
            int score = service.score(facts("Java后端开发工程师", "20-35K·16薪", "在线", "五险一金,双休,年终奖"), List.of(15, 25));
            assertThat(score).isGreaterThanOrEqualTo(85);
        }

        @Test
        @DisplayName("薪资远低于期望、HR长期不活跃、无关键词命中应得低分")
        void badJobScoresLow() {
            int score = service.score(facts("销售专员", "4-6K", "3年以上经验", ""), List.of(20, 30));
            assertThat(score).isLessThanOrEqualTo(45);
        }

        @Test
        @DisplayName("全部信息未知时得分中性（基础分+中性薪资+无命中）")
        void unknownInfoNeutralScore() {
            int score = service.score(facts("", "", "", ""), null);
            // 40 + 10(未配置期望且无法解析) + 0 + 5(活跃未知) + 0 = 55
            assertThat(score).isEqualTo(55);
        }

        @Test
        @DisplayName("评分始终在 [0, 100] 区间内")
        void scoreAlwaysBounded() {
            int high = service.score(facts("Java", "30-60K", "在线", "五险一金,双休,年终奖,股票期权,远程,餐补"), List.of(15, 25));
            assertThat(high).isLessThanOrEqualTo(100);
            assertThat(high).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("null facts 得 0 分但不抛异常")
        void nullFactsSafe() {
            assertThat(service.score(null)).isZero();
        }
    }

    @Nested
    @DisplayName("投递决策")
    class Decision {

        @Test
        @DisplayName("阈值 null 或 <=0 时始终投递（向后兼容）")
        void nullThresholdAlwaysDelivers() {
            assertThat(service.shouldDeliver(0, null)).isTrue();
            assertThat(service.shouldDeliver(0, 0)).isTrue();
            assertThat(service.shouldDeliver(0, -5)).isTrue();
        }

        @Test
        @DisplayName("阈值生效时低于阈值不投递")
        void thresholdFiltersLowScores() {
            assertThat(service.shouldDeliver(70, 60)).isTrue();
            assertThat(service.shouldDeliver(50, 60)).isFalse();
        }
    }

    @Test
    @DisplayName("关键词分词部分命中给部分分")
    void keywordPartialMatch() {
        // 关键词 "Java后端" 拆出 "Java" 命中岗位名
        int withHit = service.score(new JobScoreService.JobFacts("Java开发", "公司", "15-25K", "在线", "", "Java后端"));
        int withoutHit = service.score(new JobScoreService.JobFacts("Golang开发", "公司", "15-25K", "在线", "", "Java后端"));
        assertThat(withHit).isGreaterThan(withoutHit);
    }
}
