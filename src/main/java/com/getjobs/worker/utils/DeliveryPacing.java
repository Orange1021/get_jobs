package com.getjobs.worker.utils;

/**
 * 投递节奏策略：封装投递流程各环节的随机延迟参数。
 *
 * <p>所有延迟均为高斯分布并夹取上下限，避免固定间隔暴露自动化特征。
 * 参数集中在此处定义，便于统一调整和审查。</p>
 */
public class DeliveryPacing {

    private final HumanDelay humanDelay;

    // 投递间隔：均值 10 秒（对齐原 DELIVERY_DELAY_SECONDS），标准差 ±30%
    public static final double BETWEEN_DELIVERIES_MEAN_S = 10.0;
    public static final double BETWEEN_DELIVERIES_SIGMA_S = 3.0;
    public static final long BETWEEN_DELIVERIES_MIN_S = 5;
    public static final long BETWEEN_DELIVERIES_MAX_S = 25;

    /** 生产环境使用：默认 HumanDelay。 */
    public DeliveryPacing() {
        this(new HumanDelay());
    }

    /** 测试环境使用：注入 HumanDelay 以收集延迟值。 */
    public DeliveryPacing(HumanDelay humanDelay) {
        this.humanDelay = humanDelay;
    }

    /** 两次投递之间的间隔（替代原固定 DELIVERY_DELAY_SECONDS）。 */
    public void betweenDeliveries() {
        humanDelay.sleepGaussSeconds(
                BETWEEN_DELIVERIES_MEAN_S,
                BETWEEN_DELIVERIES_SIGMA_S,
                BETWEEN_DELIVERIES_MIN_S,
                BETWEEN_DELIVERIES_MAX_S);
    }
}
