package com.getjobs.worker.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 投递会话预算状态机。
 *
 * <p>约束一次投递会话的最大岗位数与最长运行时长，任一维度耗尽即视为预算用尽，
 * 调用方应停止投递并收工。避免单次会话过量投递触发平台风控。</p>
 *
 * <p>实例非线程安全，投递循环在单线程内顺序调用即可；
 * 若需跨线程使用请在外部同步。</p>
 */
public class SessionBudget {

    private final int maxDeliveries;
    private final Duration maxDuration;
    private final Clock clock;
    private final Instant startedAt;
    private int delivered;

    private SessionBudget(Builder builder) {
        this.maxDeliveries = builder.maxDeliveries;
        this.maxDuration = builder.maxDuration;
        this.clock = builder.clock;
        this.startedAt = clock.instant();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 还可以继续投递返回 true；任一预算维度耗尽返回 false。 */
    public boolean canDeliver() {
        if (maxDeliveries >= 0 && delivered >= maxDeliveries) {
            return false;
        }
        if (maxDuration != null && Duration.between(startedAt, clock.instant()).compareTo(maxDuration) >= 0) {
            return false;
        }
        return true;
    }

    /** 记录一次投递。返回记录后的已投递数量。 */
    public int recordDelivery() {
        return ++delivered;
    }

    public int deliveredCount() {
        return delivered;
    }

    /** 剩余可投递数量；无上限时返回 -1。 */
    public int remainingDeliveries() {
        return maxDeliveries < 0 ? -1 : Math.max(0, maxDeliveries - delivered);
    }

    /** 剩余运行时长；无上限时返回 null。 */
    public Duration remainingTime() {
        if (maxDuration == null) {
            return null;
        }
        Duration elapsed = Duration.between(startedAt, clock.instant());
        Duration rest = maxDuration.minus(elapsed);
        return rest.isNegative() ? Duration.ZERO : rest;
    }

    /** 预算是否已全部耗尽（与 canDeliver() 相反）。 */
    public boolean isExhausted() {
        return !canDeliver();
    }

    public static final class Builder {
        private int maxDeliveries = -1;
        private Duration maxDuration = null;
        private Clock clock = Clock.systemDefaultZone();

        /** 单次会话最大投递岗位数，-1 表示不限制。 */
        public Builder maxDeliveries(int maxDeliveries) {
            this.maxDeliveries = maxDeliveries;
            return this;
        }

        /** 单次会话最长运行时长，null 表示不限制。 */
        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        /** 注入时钟，测试用。 */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public SessionBudget build() {
            return new SessionBudget(this);
        }
    }
}
