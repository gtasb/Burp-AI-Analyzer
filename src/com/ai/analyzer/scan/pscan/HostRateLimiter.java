package com.ai.analyzer.scan.pscan;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L4 Host 级限流：滑动 1 秒窗口令牌桶，防止单站点高频流量打爆扫描队列与 LLM API 配额。
 *
 * <p>每个 Host 独立计数，超限的请求在入队前被丢弃（高频资源通常与已分析请求高度重复，
 * 由 L1 去重兜底，丢弃损失可忽略）。
 */
public final class HostRateLimiter {

    private static final long WINDOW_MS = 1000;
    /** 跟踪的 host 数超过此阈值时触发一次全量清理，避免长期运行 Map 只增不减 */
    private static final int MAX_TRACKED_HOSTS = 10_000;

    private final int maxPerSecond;
    private final ConcurrentHashMap<String, LinkedList<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @param maxPerSecond 每 Host 每秒最大入队请求数，至少为 1
     */
    public HostRateLimiter(int maxPerSecond) {
        this.maxPerSecond = Math.max(1, maxPerSecond);
    }

    public int maxPerSecond() {
        return maxPerSecond;
    }

    /**
     * 尝试获取通行许可。
     *
     * @param host 目标主机（null/空视为不限制）
     * @return true 允许入队；false 超出限流，应丢弃
     */
    public boolean tryAcquire(String host) {
        if (host == null || host.isEmpty()) return true;
        long now = System.currentTimeMillis();
        LinkedList<Long> list = windows.computeIfAbsent(host, k -> new LinkedList<>());
        synchronized (list) {
            while (!list.isEmpty() && now - list.peekFirst() > WINDOW_MS) {
                list.pollFirst();
            }
            if (list.size() >= maxPerSecond) return false;
            list.addLast(now);
        }
        // 机会性清理：跟踪 host 过多时剪掉已整体过期的条目，控制内存增长
        if (windows.size() > MAX_TRACKED_HOSTS) {
            pruneExpired(now);
        }
        return true;
    }

    private void pruneExpired(long now) {
        windows.entrySet().removeIf(entry -> {
            LinkedList<Long> list = entry.getValue();
            synchronized (list) {
                while (!list.isEmpty() && now - list.peekFirst() > WINDOW_MS) {
                    list.pollFirst();
                }
                return list.isEmpty();
            }
        });
    }

    /**
     * 清空所有窗口（如扫描停止/重启时）。
     */
    public void reset() {
        windows.clear();
    }
}
