package com.ai.analyzer.util;

import java.util.function.Consumer;

/**
 * Token 用量统计与预算控制（线程安全）。
 *
 * <p>跨主动分析 / 被动扫描 / 侧栏共享一个实例（{@link #instance()}），
 * 每次模型调用通过 {@link #record(int, int, int, int)} 上报真实 usage
 * （由 AgentScope {@code ModelCallEndEvent} 提供），累计后可展示总用量。
 *
 * <p>预算语义：{@code budgetTokens} 表示单次扫描周期的输入 token 预算
 * （input + cached）。预算用尽后 {@link #isOverBudget()} 返回 true，
 * 调用方应停止发起新的模型调用。{@code budgetTokens <= 0} 表示不限制。
 *
 * <p>状态变化（累计 / 重置 / 预算用尽）通过 {@link #setListener} 通知 UI，
 * listener 在任意调用方线程上触发，UI 需要自行切回 EDT。
 */
public final class TokenUsageTracker {

    private final Object lock = new Object();
    private long inputTokens;
    private long outputTokens;
    private long cachedTokens;
    private long totalTokens;
    private long modelCalls;
    private long budgetTokens;
    private boolean overBudget;
    /** 多播监听：主动面板与被动状态栏各自注册，互不覆盖（setListener 曾是单消费者替换语义，
     *  后注册的把先注册的顶掉，另一处 UI 就永远停在 0） */
    private final java.util.List<Consumer<UsageSnapshot>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 静态共享实例（主动 / 被动 / 侧栏共用同一份统计） */
    private static volatile TokenUsageTracker sharedInstance = new TokenUsageTracker();

    public TokenUsageTracker() {
    }

    /**
     * 记录一次模型调用的 token 用量。
     *
     * @param input  输入 token（不含缓存）
     * @param output 输出 token
     * @param cached 缓存命中 token
     * @param total  总 token（若 <= 0 则按 input + output + cached 推算）
     */
    public void record(int input, int output, int cached, int total) {
        if (input < 0) input = 0;
        if (output < 0) output = 0;
        if (cached < 0) cached = 0;
        long effectiveTotal = total > 0 ? total : (long) input + output + cached;

        UsageSnapshot snapshot;
        synchronized (lock) {
            modelCalls++;
            inputTokens += input;
            outputTokens += output;
            cachedTokens += cached;
            totalTokens += effectiveTotal;
            if (budgetTokens > 0 && inputTokens + cachedTokens >= budgetTokens) {
                overBudget = true;
            }
            snapshot = snapshotLocked();
        }
        notifyListener(snapshot);
    }

    /**
     * 预算检查：预算用尽后返回 true，调用方应拒绝新的模型调用。
     */
    public boolean isOverBudget() {
        synchronized (lock) {
            return overBudget;
        }
    }

    /**
     * 剩余预算（input + cached 口径），预算未设置时返回 {@link Long#MAX_VALUE}。
     */
    public long remainingTokens() {
        synchronized (lock) {
            if (budgetTokens <= 0) return Long.MAX_VALUE;
            return Math.max(0, budgetTokens - (inputTokens + cachedTokens));
        }
    }

    /**
     * 设置预算（input + cached 口径），<= 0 表示不限制。
     */
    public void setBudget(long budgetTokens) {
        synchronized (lock) {
            this.budgetTokens = Math.max(0, budgetTokens);
            this.overBudget = false;
        }
        notifyListener(snapshot());
    }

    /**
     * 清零所有统计（预算保留）。
     */
    public void reset() {
        UsageSnapshot snapshot;
        synchronized (lock) {
            modelCalls = 0;
            inputTokens = 0;
            outputTokens = 0;
            cachedTokens = 0;
            totalTokens = 0;
            overBudget = false;
            snapshot = snapshotLocked();
        }
        notifyListener(snapshot);
    }

    /**
     * 注册状态变化监听（累计 / 重置 / 预算变更时触发）。支持多个监听者；
     * 同一实例重复注册会去重。
     */
    public void setListener(Consumer<UsageSnapshot> listener) {
        addListener(listener);
    }

    /** 追加监听者（不覆盖已有）。 */
    public void addListener(Consumer<UsageSnapshot> listener) {
        if (listener == null) return;
        synchronized (lock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    /** 移除监听者。 */
    public void removeListener(Consumer<UsageSnapshot> listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    public UsageSnapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private UsageSnapshot snapshotLocked() {
        return new UsageSnapshot(
                modelCalls,
                inputTokens,
                outputTokens,
                cachedTokens,
                totalTokens,
                budgetTokens,
                overBudget);
    }

    private void notifyListener(UsageSnapshot snapshot) {
        java.util.List<Consumer<UsageSnapshot>> toNotify;
        synchronized (lock) {
            toNotify = new java.util.ArrayList<>(listeners);
        }
        for (Consumer<UsageSnapshot> l : toNotify) {
            try {
                l.accept(snapshot);
            } catch (Exception ignored) {
                // 单个 UI 监听者抛异常不影响其他监听者
            }
        }
    }

    /** 共享实例快照（含预算）。 */
    public static UsageSnapshot sharedSnapshot() {
        return sharedInstance.snapshot();
    }

    /** 共享实例的预算检查。 */
    public static boolean sharedOverBudget() {
        return sharedInstance.isOverBudget();
    }

    /**
     * 设置共享实例（供测试替换，或后续按扫描周期切换）。
     */
    public static void setSharedInstance(TokenUsageTracker tracker) {
        sharedInstance = tracker != null ? tracker : new TokenUsageTracker();
    }

    /** 获取共享实例。 */
    public static TokenUsageTracker instance() {
        return sharedInstance;
    }

    /**
     * 不可变的用量快照。
     */
    public record UsageSnapshot(
            long modelCalls,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long totalTokens,
            long budgetTokens,
            boolean overBudget) {

        /** 人类可读摘要，如 "128 次调用 / 45.2K 输入 / 12.8K 输出" */
        public String summary() {
            return modelCalls + " 次调用 / " + formatK(inputTokens + cachedTokens)
                    + " 输入 / " + formatK(outputTokens) + " 输出";
        }

        private static String formatK(long v) {
            if (v >= 1_000_000) {
                return String.format("%.1fM", v / 1_000_000.0);
            }
            if (v >= 1_000) {
                return String.format("%.1fK", v / 1_000.0);
            }
            return String.valueOf(v);
        }
    }
}
