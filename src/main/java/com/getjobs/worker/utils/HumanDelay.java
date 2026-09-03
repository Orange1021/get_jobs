package com.getjobs.worker.utils;

import java.util.random.RandomGenerator;
import java.util.function.LongConsumer;

/**
 * 人类行为模拟延迟组件。
 *
 * <p>用高斯（正态）分布产生随机延迟，替代固定间隔的 sleep，降低自动化行为特征。
 * 延迟值始终被夹在 [minMillis, maxMillis] 区间内，保证不会出现 0 秒连发或过长停滞。</p>
 *
 * <p>可注入 {@link RandomGenerator} 和 {@link LongConsumer}（睡眠函数），
 * 便于单元测试中固定随机种子、收集延迟值而不真正睡眠。</p>
 */
public class HumanDelay {

    /** 全局默认实例：真实睡眠 + 系统随机源。 */
    private static final HumanDelay GLOBAL = new HumanDelay();

    private final RandomGenerator random;
    private final LongConsumer sleeper;

    /** 生产环境使用：真实随机源 + 真实睡眠。 */
    public HumanDelay() {
        this(new java.util.Random(), millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 测试环境使用：注入随机源与睡眠函数（测试中通常收集延迟值而不真睡）。 */
    public HumanDelay(RandomGenerator random, LongConsumer sleeper) {
        this.random = random;
        this.sleeper = sleeper;
    }

    /** 全局默认实例，供静态调用。 */
    public static HumanDelay global() {
        return GLOBAL;
    }

    /**
     * 计算一次高斯分布延迟值（毫秒），夹在 [minMillis, maxMillis] 内。
     *
     * @param meanMillis  平均延迟（毫秒）
     * @param sigmaMillis 标准差（毫秒），建议为 meanMillis 的 20%~40%
     * @param minMillis   下限（毫秒）
     * @param maxMillis   上限（毫秒）
     * @return 实际延迟毫秒值
     */
    public long nextDelayMillis(double meanMillis, double sigmaMillis, long minMillis, long maxMillis) {
        if (sigmaMillis < 0) {
            throw new IllegalArgumentException("sigmaMillis 不能为负数: " + sigmaMillis);
        }
        if (minMillis > maxMillis) {
            throw new IllegalArgumentException("minMillis(" + minMillis + ") 不能大于 maxMillis(" + maxMillis + ")");
        }
        double v = meanMillis + random.nextGaussian() * sigmaMillis;
        long rounded = Math.round(v);
        if (rounded < minMillis) {
            rounded = minMillis;
        }
        if (rounded > maxMillis) {
            rounded = maxMillis;
        }
        return rounded;
    }

    /**
     * 睡眠一段高斯分布随机时长（毫秒），返回实际睡眠毫秒数。
     * 睡眠函数抛出的运行时异常按"已睡满"处理，不中断投递主流程。
     */
    public long sleepGaussMillis(double meanMillis, double sigmaMillis, long minMillis, long maxMillis) {
        long delay = nextDelayMillis(meanMillis, sigmaMillis, minMillis, maxMillis);
        try {
            sleeper.accept(delay);
        } catch (RuntimeException e) {
            // 睡眠失败不应中断投递主流程（例如测试桩抛错），按已睡满处理
        }
        return delay;
    }

    /**
     * 睡眠一段高斯分布随机时长（秒为单位，便于贴合原有的秒级调用习惯）。
     */
    public void sleepGaussSeconds(double meanSeconds, double sigmaSeconds, long minSeconds, long maxSeconds) {
        sleepGaussMillis(meanSeconds * 1000.0, sigmaSeconds * 1000.0, minSeconds * 1000L, maxSeconds * 1000L);
    }
}
