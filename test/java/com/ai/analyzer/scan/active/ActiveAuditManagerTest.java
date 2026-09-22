package com.ai.analyzer.scan.active;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.Scanner;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.scanner.audit.AuditIssueHandler;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ActiveAuditManager - 主动审计闭环")
class ActiveAuditManagerTest {

    private HttpRequestResponse rr() {
        HttpRequestResponse item = mock(HttpRequestResponse.class);
        when(item.request()).thenReturn(mock(HttpRequest.class));
        return item;
    }

    @Test
    @DisplayName("startAudit 启动一个 Audit 任务并加入全部目标")
    void start_audit_adds_targets() {
        Scanner scanner = mock(Scanner.class);
        Audit audit = mock(Audit.class);
        when(scanner.startAudit(any())).thenReturn(audit);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        List<HttpRequestResponse> targets = new ArrayList<>();
        targets.add(rr());
        targets.add(rr());
        targets.add(null);
        int started = manager.startAudit(targets);

        assertThat(started).isEqualTo(2);
        verify(audit, times(2)).addRequestResponse(any());
        assertThat(manager.hasActiveTasks()).isTrue();
        assertThat(manager.activeTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("空目标或空列表不启动审计")
    void empty_targets_no_audit() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        assertThat(manager.startAudit(null)).isZero();
        assertThat(manager.startAudit(List.of())).isZero();
        verify(scanner, never()).startAudit(any());
    }

    @Test
    @DisplayName("scanner 不可用时不抛异常")
    void null_scanner_safe() {
        ActiveAuditManager manager = new ActiveAuditManager(null);
        assertThat(manager.startAudit(List.of(rr()))).isZero();
        assertThat(manager.hasActiveTasks()).isFalse();
    }

    @Test
    @DisplayName("startAudit 失败时回调状态且不记录任务")
    void start_audit_failure_notifies() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        when(scanner.startAudit(any())).thenThrow(new RuntimeException("scan failed"));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));
        AtomicInteger statuses = new AtomicInteger(0);
        manager.setOnStatusChanged(s -> statuses.incrementAndGet());

        assertThat(manager.startAudit(List.of(rr()))).isZero();
        assertThat(manager.hasActiveTasks()).isFalse();
        assertThat(statuses.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("注册的 AuditIssueHandler 把问题推回回调")
    void handler_pushes_issues_to_callback() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));
        ArgumentCaptor<AuditIssueHandler> handlerCaptor = ArgumentCaptor.forClass(AuditIssueHandler.class);
        verify(scanner).registerAuditIssueHandler(handlerCaptor.capture());

        List<AuditIssue> received = new ArrayList<>();
        manager.setOnIssueFound(received::add);

        // 生产实现仅在存在「插件主动发起的审计」时才回读 issue（activeAudits 非空）
        Audit audit = mock(Audit.class);
        when(scanner.startAudit(any())).thenReturn(audit);
        manager.startAudit(List.of(rr()));

        AuditIssue issue = mock(AuditIssue.class);
        handlerCaptor.getValue().handleNewAuditIssue(issue);

        assertThat(received).containsExactly(issue);
    }

    @Test
    @DisplayName("dispose 注销 handler 并清空任务")
    void dispose_deregisters_and_clears() {
        Scanner scanner = mock(Scanner.class);
        burp.api.montoya.core.Registration registration = mock(burp.api.montoya.core.Registration.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(registration);
        Audit audit = mock(Audit.class);
        when(scanner.startAudit(any())).thenReturn(audit);
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));
        manager.startAudit(List.of(rr()));

        manager.dispose();

        verify(registration).deregister();
        assertThat(manager.activeTaskCount()).isZero();
    }

    // ========== 待审计队列（被动 → 主动协作） ==========

    @Test
    @DisplayName("queueForAudit 加入队列并按 URL 去重")
    void queue_for_audit_dedupes_by_url() {
        HttpRequestResponse target = mock(HttpRequestResponse.class);
        HttpRequest request = mock(HttpRequest.class);
        when(target.request()).thenReturn(request);
        when(request.url()).thenReturn("https://target/a");

        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        assertThat(manager.queueForAudit(target)).isTrue();
        assertThat(manager.queueForAudit(target)).isFalse();
        assertThat(manager.queueForAudit(null)).isFalse();

        HttpRequestResponse other = mock(HttpRequestResponse.class);
        HttpRequest otherRequest = mock(HttpRequest.class);
        when(other.request()).thenReturn(otherRequest);
        when(otherRequest.url()).thenReturn("https://target/b");
        assertThat(manager.queueForAudit(other)).isTrue();

        assertThat(manager.pendingQueueSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("queueForAudit 触发 onQueueChanged")
    void queue_for_audit_notifies() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));
        AtomicInteger notifications = new AtomicInteger();
        manager.setOnQueueChanged(notifications::incrementAndGet);

        manager.queueForAudit(rr());
        manager.clearPendingQueue();

        assertThat(notifications.get()).isEqualTo(2);
        assertThat(manager.pendingQueueSize()).isZero();
    }

    @Test
    @DisplayName("auditPendingQueue 批量审计整个队列并清空")
    void audit_pending_queue_batches_and_clears() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        Audit audit = mock(Audit.class);
        when(scanner.startAudit(any())).thenReturn(audit);
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        manager.queueForAudit(rr());
        manager.queueForAudit(rr());
        manager.queueForAudit(rr());

        int started = manager.auditPendingQueue();

        assertThat(started).isEqualTo(3);
        verify(audit, times(3)).addRequestResponse(any());
        assertThat(manager.pendingQueueSize()).isZero();
        assertThat(manager.hasActiveTasks()).isTrue();
    }

    @Test
    @DisplayName("空队列 auditPendingQueue 不启动审计")
    void audit_pending_queue_empty_noop() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        assertThat(manager.auditPendingQueue()).isZero();
        verify(scanner, never()).startAudit(any());
    }

    @Test
    @DisplayName("审计启动失败时队列保留（不丢目标）")
    void audit_pending_queue_failure_keeps_queue() {
        Scanner scanner = mock(Scanner.class);
        when(scanner.registerAuditIssueHandler(any())).thenReturn(mock(burp.api.montoya.core.Registration.class));
        when(scanner.startAudit(any())).thenThrow(new RuntimeException("start failed"));
        ActiveAuditManager manager = new ActiveAuditManager(scanner, () -> mock(AuditConfiguration.class));

        manager.queueForAudit(rr());
        manager.queueForAudit(rr());

        int started = manager.auditPendingQueue();

        assertThat(started).isZero();
        assertThat(manager.pendingQueueSize()).isEqualTo(2);
    }
}
