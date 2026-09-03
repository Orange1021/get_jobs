package com.getjobs.worker.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanDelayTest {

    /** 收集延迟值而不真正睡眠的测试实例。 */
    private record CollectingDelay(RandomGenerator random, List<Long> collected) {
        CollectingDelay(RandomGenerator random) {
            this(random, new ArrayList<>());
        }

        HumanDelay toDelay() {
            return new HumanDelay(random, collected::add);
        }
    }

    @Test
    @DisplayName("延迟值应始终落在 [min, max] 区间内（含高斯极端值被夹住的情况）")
    void delaysAlwaysWithinBounds() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"));
        HumanDelay delay = cd.toDelay();

        // 极小均值 + 极大方差：逼出上下限夹取逻辑
        for (int i = 0; i < 5000; i++) {
            delay.sleepGaussMillis(1000, 5000, 500, 8000);
        }

        assertThat(cd.collected).hasSize(5000);
        assertThat(cd.collected).allSatisfy(v -> {
            assertThat(v).isBetween(500L, 8000L);
        });
    }

    @Test
    @DisplayName("延迟样本的均值应接近配置的平均值（统计特性）")
    void meanApproximatesConfiguredMean() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"));
        HumanDelay delay = cd.toDelay();

        double mean = 3000;
        double sigma = 500;
        int samples = 5000;
        for (int i = 0; i < samples; i++) {
            delay.sleepGaussMillis(mean, sigma, 0, Long.MAX_VALUE);
        }

        double observed = cd.collected.stream().mapToLong(Long::longValue).average().orElse(0);
        // 大数定律：5000 个样本的均值应在均值 ±10% 以内（概率上极不可能越界）
        assertThat(observed).isBetween(mean * 0.95, mean * 1.05);
    }

    @Test
    @DisplayName("延迟不应出现固定值（具备随机性）")
    void delaysAreNotConstant() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"));
        HumanDelay delay = cd.toDelay();

        for (int i = 0; i < 100; i++) {
            delay.sleepGaussMillis(2000, 400, 500, 5000);
        }

        assertThat(cd.collected.stream().distinct().count()).isGreaterThan(50);
    }

    @Test
    @DisplayName("sigma 为负数或 min>max 时应抛出 IllegalArgumentException")
    void rejectsInvalidParameters() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"));
        HumanDelay delay = cd.toDelay();

        assertThatThrownBy(() -> delay.nextDelayMillis(1000, -1, 100, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delay.nextDelayMillis(1000, 100, 3000, 2000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sigma=0 时应退化为固定延迟（恰好等于均值，若在区间内）")
    void zeroSigmaYieldsFixedDelay() {
        CollectingDelay cd = new CollectingDelay(RandomGenerator.of("L64X128MixRandom"));
        HumanDelay delay = cd.toDelay();

        for (int i = 0; i < 20; i++) {
            delay.sleepGaussMillis(2500, 0, 1000, 5000);
        }

        assertThat(cd.collected).containsOnly(2500L);
    }

    @Test
    @DisplayName("sleepGaussSeconds 应正确换算秒到毫秒")
    void secondsVariantConvertsToMillis() {
        List<Long> collected = new ArrayList<>();
        HumanDelay delay = new HumanDelay(RandomGenerator.of("L64X128MixRandom"), collected::add);

        delay.sleepGaussSeconds(3, 0.5, 1, 10);

        assertThat(collected).hasSize(1);
        assertThat(collected.get(0)).isBetween(1000L, 10000L);
    }
}
