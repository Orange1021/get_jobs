package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryPacingTest {

    private record CollectingDelay(RandomGenerator random, List<Long> collected) {
        HumanDelay toDelay() {
            return new HumanDelay(random, collected::add);
        }
    }

    @Test
    @DisplayName("投递间隔应落在 [5s, 25s] 区间内且具备随机性")
    void betweenDeliveriesWithinBoundsAndRandom() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"), new ArrayList<>());
        DeliveryPacing pacing = new DeliveryPacing(cd.toDelay());

        for (int i = 0; i < 2000; i++) {
            pacing.betweenDeliveries();
        }

        assertThat(cd.collected).hasSize(2000);
        // 边界：最小 5 秒，最大 25 秒
        assertThat(cd.collected).allSatisfy(v -> assertThat(v).isBetween(5000L, 25000L));
        // 均值应接近 10 秒（夹取后略高于名义均值属正常，放宽到 9~13 秒）
        double observedMean = cd.collected.stream().mapToLong(Long::longValue).average().orElse(0);
        assertThat(observedMean).isBetween(9_000.0, 13_000.0);
        // 随机性：不同延迟值应足够多
        assertThat(cd.collected.stream().distinct().count()).isGreaterThan(100);
    }

    @Test
    @DisplayName("两次投递间隔不应小于最小安全间隔")
    void betweenDeliveriesNeverBelowSafetyMinimum() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"), new ArrayList<>());
        DeliveryPacing pacing = new DeliveryPacing(cd.toDelay());

        long minObserved = Long.MAX_VALUE;
        for (int i = 0; i < 5000; i++) {
            pacing.betweenDeliveries();
            minObserved = Math.min(minObserved, cd.collected.get(cd.collected.size() - 1));
        }

        // 任何情况下投递间隔不得低于 5 秒（风控安全下限）
        assertThat(minObserved).isGreaterThanOrEqualTo(5000L);
    }
}
