package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBudgetTest {

    /** 可手动拨动的测试时钟。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("未配置上限时永不耗尽")
    void unlimitedBudgetNeverExhausts() {
        SessionBudget budget = SessionBudget.builder().build();

        for (int i = 0; i < 10000; i++) {
            assertThat(budget.canDeliver()).isTrue();
            budget.recordDelivery();
        }
        assertThat(budget.remainingDeliveries()).isEqualTo(-1);
        assertThat(budget.remainingTime()).isNull();
    }

    @Test
    @DisplayName("达到最大投递数后预算耗尽")
    void exhaustsByDeliveryCount() {
        SessionBudget budget = SessionBudget.builder().maxDeliveries(3).build();

        assertThat(budget.canDeliver()).isTrue();
        budget.recordDelivery();
        assertThat(budget.canDeliver()).isTrue();
        budget.recordDelivery();
        assertThat(budget.canDeliver()).isTrue();
        assertThat(budget.remainingDeliveries()).isEqualTo(1);
        budget.recordDelivery();

        assertThat(budget.canDeliver()).isFalse();
        assertThat(budget.isExhausted()).isTrue();
        assertThat(budget.remainingDeliveries()).isZero();
        assertThat(budget.deliveredCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("超过最长运行时长后预算耗尽")
    void exhaustsByDuration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        SessionBudget budget = SessionBudget.builder()
                .maxDuration(Duration.ofMinutes(30))
                .clock(clock)
                .build();

        assertThat(budget.canDeliver()).isTrue();

        clock.advance(Duration.ofMinutes(29));
        assertThat(budget.canDeliver()).isTrue();
        assertThat(budget.remainingTime()).isEqualTo(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        assertThat(budget.canDeliver()).isFalse();
        assertThat(budget.remainingTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("任一维度耗尽即收工：数量未满但超时也应停止")
    void anyDimensionExhaustedStops() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-03T12:00:00Z"));
        SessionBudget budget = SessionBudget.builder()
                .maxDeliveries(100)
                .maxDuration(Duration.ofMinutes(10))
                .clock(clock)
                .build();

        budget.recordDelivery();
        clock.advance(Duration.ofMinutes(11));

        assertThat(budget.canDeliver()).isFalse();
        assertThat(budget.deliveredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("剩余投递数随记录递减")
    void remainingDeliveriesDecrements() {
        SessionBudget budget = SessionBudget.builder().maxDeliveries(5).build();

        budget.recordDelivery();
        budget.recordDelivery();
        assertThat(budget.remainingDeliveries()).isEqualTo(3);
    }
}
