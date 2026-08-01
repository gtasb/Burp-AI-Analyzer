package com.ai.analyzer.scan.active;

import burp.api.montoya.core.Registration;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.Scanner;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 主动审计闭环：基于 Burp Scanner 的 Audit 任务 + AuditIssueHandler 推送式回读。
 *
 * <p>与被动 AI 扫描互补：被动扫描在流量上做 AI 分析，主动审计用 Burp 引擎对选定请求
 * 发起真实扫描。审计过程中发现的问题由 {@link AuditIssueHandler} 实时推回，
 * 上层（UI）把 issues 合并进被动扫描结果列表，形成闭环。
 *
 * <p>协作显式化：被动扫描判定的高危结果可通过 {@link #queueForAudit} 自动进入
 * 待审计队列（被动 → 主动）；队列中的目标由用户一键 {@link #auditPendingQueue()}
 * 批量发起 Burp 主动审计（主动 → 被动合并回读）。
 */
public class ActiveAuditManager {

    /** 待审计队列容量上限，防止无限制堆积 */
    static final int MAX_PENDING_QUEUE = 500;

    private final Scanner scanner;
    private final Supplier<AuditConfiguration> configSupplier;
    private final List<Audit> activeAudits = new CopyOnWriteArrayList<>();
    private final Registration registration;
    /** 被动扫描高危结果自动入队的待审计目标（按 URL 去重） */
    private final List<HttpRequestResponse> pendingQueue = new CopyOnWriteArrayList<>();
    private final Set<String> pendingQueueKeys = new HashSet<>();
    private Consumer<AuditIssue> onIssueFound;
    private Consumer<String> onStatusChanged;
    private Runnable onQueueChanged;

    public ActiveAuditManager(Scanner scanner) {
        this(scanner, () -> AuditConfiguration.auditConfiguration(
                BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS));
    }

    ActiveAuditManager(Scanner scanner, Supplier<AuditConfiguration> configSupplier) {
        this.scanner = scanner;
        this.configSupplier = configSupplier;
        this.registration = scanner != null
                ? scanner.registerAuditIssueHandler(this::handleNewAuditIssue)
                : null;
    }

    /**
     * 对一组报文发起主动审计：启动一个 Audit 任务并批量加入目标请求。
     *
     * @return 成功加入审计的请求数
     */
    public int startAudit(List<HttpRequestResponse> targets) {
        if (scanner == null || targets == null || targets.isEmpty()) {
            return 0;
        }
        Audit audit;
        try {
            audit = scanner.startAudit(configSupplier != null ? configSupplier.get() : null);
        } catch (Exception e) {
            notifyStatus("启动主动审计失败: " + e.getMessage());
            return 0;
        }
        if (audit == null) {
            notifyStatus("无法启动主动审计（Scanner 不可用或非 Pro 版本）");
            return 0;
        }
        int added = 0;
        for (HttpRequestResponse target : targets) {
            if (target == null) {
                continue;
            }
            try {
                audit.addRequestResponse(target);
                added++;
            } catch (Exception e) {
                notifyStatus("加入审计目标失败: " + e.getMessage());
            }
        }
        if (added > 0) {
            activeAudits.add(audit);
            notifyStatus("已启动主动审计，共 " + added + " 个目标请求，问题将自动回读");
        } else {
            notifyStatus("没有可审计的请求");
        }
        return added;
    }

    private void handleNewAuditIssue(AuditIssue issue) {
        if (issue == null || onIssueFound == null) {
            return;
        }
        try {
            onIssueFound.accept(issue);
        } catch (Exception e) {
            notifyStatus("处理主动审计问题失败: " + e.getMessage());
        }
    }

    public boolean hasActiveTasks() {
        return !activeAudits.isEmpty();
    }

    public int activeTaskCount() {
        return activeAudits.size();
    }

    // ========== 待审计队列（被动 → 主动协作） ==========

    /**
     * 把一个报文加入待审计队列（按请求 URL 去重）。
     *
     * @return true 如果成功加入
     */
    public boolean queueForAudit(HttpRequestResponse target) {
        if (target == null) {
            return false;
        }
        String key;
        try {
            key = target.request().url();
        } catch (Exception e) {
            key = String.valueOf(System.identityHashCode(target));
        }
        if (key == null || key.isEmpty()) {
            key = String.valueOf(System.identityHashCode(target));
        }
        synchronized (pendingQueueKeys) {
            if (pendingQueueKeys.contains(key)) {
                return false;
            }
            if (pendingQueue.size() >= MAX_PENDING_QUEUE) {
                notifyStatus("待审计队列已满(" + MAX_PENDING_QUEUE + ")，忽略: " + key);
                return false;
            }
            pendingQueueKeys.add(key);
        }
        pendingQueue.add(target);
        notifyQueueChanged();
        return true;
    }

    /** 待审计队列的副本（不可变视图） */
    public List<HttpRequestResponse> getPendingQueue() {
        return new ArrayList<>(pendingQueue);
    }

    public int pendingQueueSize() {
        return pendingQueue.size();
    }

    /** 清空待审计队列 */
    public void clearPendingQueue() {
        if (pendingQueue.isEmpty()) {
            return;
        }
        pendingQueue.clear();
        synchronized (pendingQueueKeys) {
            pendingQueueKeys.clear();
        }
        notifyQueueChanged();
    }

    /**
     * 一键审计整个待审计队列：把所有目标作为一个 Audit 任务批量发起，
     * 成功后清空队列。
     *
     * @return 成功加入审计的请求数（0 表示队列为空或审计启动失败）
     */
    public int auditPendingQueue() {
        List<HttpRequestResponse> targets = getPendingQueue();
        if (targets.isEmpty()) {
            notifyStatus("待审计队列为空");
            return 0;
        }
        int added = startAudit(targets);
        if (added > 0) {
            synchronized (pendingQueueKeys) {
                pendingQueueKeys.clear();
            }
            pendingQueue.removeAll(targets);
            notifyQueueChanged();
        }
        return added;
    }

    public void setOnQueueChanged(Runnable onQueueChanged) {
        this.onQueueChanged = onQueueChanged;
    }

    private void notifyQueueChanged() {
        Runnable r = onQueueChanged;
        if (r != null) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** 注销 AuditIssueHandler（扩展卸载时调用） */
    public void dispose() {
        if (registration != null) {
            registration.deregister();
        }
        activeAudits.clear();
    }

    public void setOnIssueFound(Consumer<AuditIssue> onIssueFound) {
        this.onIssueFound = onIssueFound;
    }

    public void setOnStatusChanged(Consumer<String> onStatusChanged) {
        this.onStatusChanged = onStatusChanged;
    }

    private void notifyStatus(String message) {
        if (onStatusChanged != null) {
            onStatusChanged.accept(message);
        }
    }
}
