package com.ai.analyzer.scan.pscan;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 会话批次收集器：按会话（sessionKey）将连续流量聚合为批次，供一次 LLM 调用做序列级分析。
 *
 * <p>逻辑漏洞（越权、状态机绕过、流程跳跃、支付逻辑）在单请求中不可见，必须在连续流量
 * 序列中才能发现。本收集器把同一会话的请求按窗口聚合：
 * <ul>
 *   <li>请求数达到 {@code maxRequests} → 立即产出批次（打包一次分析）；</li>
 *   <li>窗口时间超过 {@code windowMs} 无新请求 → 过期产出批次（由外部定时触发 expire）；</li>
 *   <li>非活跃会话自动清理，防止内存泄漏。</li>
 * </ul>
 */
public class SessionBatchCollector {

    /** 一个批次内的请求集合（有序）。 */
    public static final class Batch {
        public final String sessionKey;
        public final List<HttpRequestResponse> requests;
        public volatile long lastActivity;

        public Batch(String sessionKey, HttpRequestResponse first) {
            this.sessionKey = sessionKey;
            this.requests = new ArrayList<>();
            this.requests.add(first);
            this.lastActivity = System.currentTimeMillis();
        }
    }

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();

    /**
     * @param maxRequests 批次最大请求数（满即产出）
     * @param windowMs    窗口空闲时间（毫秒，超过则过期产出）
     */
    public SessionBatchCollector(int maxRequests, long windowMs) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMs = Math.max(1, windowMs);
    }

    /**
     * 加入一个请求。
     *
     * @return 批次已满需要立即提交时返回该批次（并已从收集器移除）；否则返回 null
     */
    public Batch add(HttpRequestResponse rr) {
        String sessionKey = RequestFingerprint.sessionKey(rr);
        synchronized (batches) {
            Batch batch = batches.get(sessionKey);
            if (batch == null) {
                batch = new Batch(sessionKey, rr);
                batches.put(sessionKey, batch);
                if (batch.requests.size() >= maxRequests) {
                    batches.remove(sessionKey);
                    return batch;
                }
                return null;
            }
            batch.requests.add(rr);
            batch.lastActivity = System.currentTimeMillis();
            if (batch.requests.size() >= maxRequests) {
                batches.remove(sessionKey);
                return batch;
            }
            return null;
        }
    }

    /**
     * 找出所有已空闲超过窗口时间的批次并移除。
     *
     * <p>边界语义：空闲时间必须严格大于窗口（{@code >}）才过期，
     * 恰好等于窗口的会话视为仍活跃，避免毫秒级时序抖动误杀低频会话。
     *
     * @param now 当前时间戳（毫秒）
     * @return 需提交的批次列表（可为空）
     */
    public List<Batch> expire(long now) {
        List<Batch> expired = new ArrayList<>();
        synchronized (batches) {
            batches.entrySet().removeIf(e -> {
                Batch b = e.getValue();
                if (now - b.lastActivity > windowMs) {
                    expired.add(b);
                    return true;
                }
                return false;
            });
        }
        return expired;
    }

    /**
     * 清空所有未提交批次（停止扫描时丢弃残留）。
     */
    public void clear() {
        synchronized (batches) {
            batches.clear();
        }
    }

    public int pendingBatchCount() {
        return batches.size();
    }

    public int pendingRequestCount() {
        synchronized (batches) {
            return batches.values().stream().mapToInt(b -> b.requests.size()).sum();
        }
    }

    public int maxRequests() {
        return maxRequests;
    }
}
