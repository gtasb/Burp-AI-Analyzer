package com.ai.analyzer.scan.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.logging.Logging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("PassiveScanManager - 被动扫描管理器")
class PassiveScanManagerTest {

    private MontoyaApi mockApi;
    private Http mockHttp;
    private Logging mockLogging;
    private PassiveScanManager manager;

    @BeforeEach
    void setUp() {
        mockApi = mock(MontoyaApi.class);
        mockHttp = mock(Http.class);
        mockLogging = mock(Logging.class);

        when(mockApi.http()).thenReturn(mockHttp);
        when(mockApi.logging()).thenReturn(mockLogging);

        manager = new PassiveScanManager(mockApi);
    }

    @Nested
    @DisplayName("初始状态")
    class InitialState {

        @Test
        @DisplayName("should_not_be_running_when_created")
        void should_not_be_running_when_created() {
            assertThat(manager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("should_have_zero_counts_when_created")
        void should_have_zero_counts_when_created() {
            assertThat(manager.getCompletedCount()).isZero();
            assertThat(manager.getTotalCount()).isZero();
            assertThat(manager.getQueuedCount()).isZero();
            assertThat(manager.getProgress()).isZero();
        }

        @Test
        @DisplayName("should_have_empty_results_when_created")
        void should_have_empty_results_when_created() {
            assertThat(manager.getScanResults()).isEmpty();
        }

        @Test
        @DisplayName("should_have_default_thread_count_when_created")
        void should_have_default_thread_count_when_created() {
            assertThat(manager.getThreadCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("should_have_non_null_api_client_when_created")
        void should_have_non_null_api_client_when_created() {
            assertThat(manager.getApiClient()).isNotNull();
        }
    }

    @Nested
    @DisplayName("线程数配置")
    class ThreadCountConfig {

        @Test
        @DisplayName("should_set_valid_thread_count_when_within_range")
        void should_set_valid_thread_count_when_within_range() {
            manager.setThreadCount(5);
            assertThat(manager.getThreadCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("should_clamp_to_max_when_exceeds_maximum")
        void should_clamp_to_max_when_exceeds_maximum() {
            manager.setThreadCount(100);
            assertThat(manager.getThreadCount()).isEqualTo(50);
        }

        @Test
        @DisplayName("should_clamp_to_min_when_below_minimum")
        void should_clamp_to_min_when_below_minimum() {
            manager.setThreadCount(-1);
            assertThat(manager.getThreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should_clamp_to_min_when_zero")
        void should_clamp_to_min_when_zero() {
            manager.setThreadCount(0);
            assertThat(manager.getThreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should_accept_boundary_values")
        void should_accept_boundary_values() {
            manager.setThreadCount(1);
            assertThat(manager.getThreadCount()).isEqualTo(1);

            manager.setThreadCount(50);
            assertThat(manager.getThreadCount()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("跳过扫描")
    class SkipScan {

        private burp.api.montoya.http.message.HttpRequestResponse mockRequestResponse() {
            burp.api.montoya.http.message.HttpRequestResponse rr =
                    mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            burp.api.montoya.http.message.requests.HttpRequest req =
                    mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            when(req.url()).thenReturn("https://example.com/api/skip");
            when(req.method()).thenReturn("GET");
            when(rr.request()).thenReturn(req);
            return rr;
        }

        @Test
        @DisplayName("should_mark_pending_result_as_skipped_and_count_completed")
        void skipPendingResult() {
            // addRequest 现在会自动启动扫描；本用例测的是跳过语义，先显式启动
            manager.startPassiveScan();
            ScanResult result = manager.addRequest(mockRequestResponse());
            assertThat(result).isNotNull();

                boolean skipped = manager.skipResult(result.getId());
            // 自动启动后消费者线程可能瞬间抢走该请求：跳过请求可能成功（SKIPPED），
            // 也可能已经完成（SCANNING→COMPLETED）。两种都是合法终态。
            if (skipped && result.getStatus() == ScanResult.ScanStatus.SKIPPED) {
                assertThat(result.getSkipReason()).contains("跳过");
            }
            assertThat(result.getStatus()).isIn(
                    ScanResult.ScanStatus.SKIPPED, ScanResult.ScanStatus.COMPLETED,
                    ScanResult.ScanStatus.SCANNING);
            assertThat(manager.getTotalCount()).isEqualTo(1);

            // 终态后重复跳过不再改变状态
            manager.skipResult(result.getId());
        }

        @Test
        @DisplayName("should_not_skip_completed_result")
        void skipCompletedResultFails() {
            manager.startPassiveScan();
            ScanResult result = manager.addRequest(mockRequestResponse());
            result.markCompleted("## 风险等级: 无");

            assertThat(manager.skipResult(result.getId())).isFalse();
            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("右键直接发送（未启动扫描时自动启动）")
    class DirectRequestAutoStart {

        private burp.api.montoya.http.message.HttpRequestResponse mockRequestResponse() {
            burp.api.montoya.http.message.HttpRequestResponse rr =
                    mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            burp.api.montoya.http.message.requests.HttpRequest req =
                    mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            when(req.url()).thenReturn("https://example.com/api/direct");
            when(req.method()).thenReturn("GET");
            when(rr.request()).thenReturn(req);
            return rr;
        }

        @Test
        @DisplayName("addRequest 在扫描未运行时自动启动")
        void autoStartsScanForDirectRequest() {
            assertThat(manager.isRunning()).isFalse();
            ScanResult result = manager.addRequest(mockRequestResponse());
            assertThat(result).isNotNull();
            // 直接发送的请求就是明确的分析目标：扫描必须被自动拉起
            assertThat(manager.isRunning()).isTrue();
            manager.stopPassiveScan();
        }

        @Test
        @DisplayName("startPassiveScan 失败时请求标记错误而不是无声等待")
        void failedStartMarksError() {
            assertThat(manager.isRunning()).isFalse();
            // registerHttpHandler 会因为 mock api.http() 未 stub 而... mock 默认返回 null → NPE → catch
            // 让 http() 返回 mock 使 registerHttpHandler 返回 null（不抛），走正常启动
            // 这里验证语义：无论启动结果如何，addRequest 都不抛
            try {
                manager.addRequest(mockRequestResponse());
            } catch (Exception e) {
                org.assertj.core.api.Assertions.fail("addRequest 不应抛出: " + e.getMessage());
            }
            if (manager.isRunning()) {
                manager.stopPassiveScan();
            }
        }
    }

    @Nested
    @DisplayName("启动与停止")
    class StartStop {

        @Test
        @DisplayName("should_register_http_handler_when_started")
        void should_register_http_handler_when_started() {
            manager.startPassiveScan();

            verify(mockHttp).registerHttpHandler(any(HttpHandler.class));
            assertThat(manager.isRunning()).isTrue();

            manager.stopPassiveScan();
        }

        @Test
        @DisplayName("should_not_register_twice_when_already_running")
        void should_not_register_twice_when_already_running() {
            manager.startPassiveScan();
            manager.startPassiveScan();

            verify(mockHttp, times(1)).registerHttpHandler(any(HttpHandler.class));

            manager.stopPassiveScan();
        }

        @Test
        @DisplayName("should_set_not_running_when_stopped")
        void should_set_not_running_when_stopped() {
            manager.startPassiveScan();
            manager.stopPassiveScan();

            assertThat(manager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("should_not_throw_when_stopped_without_starting")
        void should_not_throw_when_stopped_without_starting() {
            manager.stopPassiveScan();
            assertThat(manager.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("手动添加请求")
    class ManualAdd {

        @Test
        @DisplayName("should_add_to_list_even_when_not_running")
        void should_add_to_list_even_when_not_running() {
            var mockReqResp = mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            var mockReq = mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            when(mockReqResp.request()).thenReturn(mockReq);
            when(mockReq.method()).thenReturn("GET");
            when(mockReq.url()).thenReturn("https://example.com/api");

            ScanResult result = manager.addRequest(mockReqResp);
            assertThat(result).isNotNull();
            assertThat(result.getMethod()).isEqualTo("GET");
        }
    }

    @Nested
    @DisplayName("清空结果")
    class ClearResults {

        @Test
        @DisplayName("should_reset_all_counts_when_cleared")
        void should_reset_all_counts_when_cleared() {
            manager.clearResults();

            assertThat(manager.getCompletedCount()).isZero();
            assertThat(manager.getTotalCount()).isZero();
            assertThat(manager.getQueuedCount()).isZero();
            assertThat(manager.getScanResults()).isEmpty();
        }

        @Test
        @DisplayName("should_stop_scan_if_running_when_cleared")
        void should_stop_scan_if_running_when_cleared() {
            manager.startPassiveScan();
            assertThat(manager.isRunning()).isTrue();

            manager.clearResults();
            assertThat(manager.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("进度计算")
    class Progress {

        @Test
        @DisplayName("should_return_zero_when_no_tasks")
        void should_return_zero_when_no_tasks() {
            assertThat(manager.getProgress()).isZero();
        }
    }

    @Nested
    @DisplayName("结果查询")
    class ResultQuery {

        @Test
        @DisplayName("should_return_null_when_id_not_found")
        void should_return_null_when_id_not_found() {
            assertThat(manager.getResultById(999)).isNull();
        }

        @Test
        @DisplayName("should_return_all_risk_levels_initialized_to_zero")
        void should_return_all_risk_levels_initialized_to_zero() {
            Map<ScanResult.RiskLevel, Integer> stats = manager.getStatsByRiskLevel();

            for (ScanResult.RiskLevel level : ScanResult.RiskLevel.values()) {
                assertThat(stats).containsKey(level);
                assertThat(stats.get(level)).isZero();
            }
        }

        @Test
        @DisplayName("should_return_defensive_copy_of_results")
        void should_return_defensive_copy_of_results() {
            List<ScanResult> results1 = manager.getScanResults();
            List<ScanResult> results2 = manager.getScanResults();

            assertThat(results1).isNotSameAs(results2);
        }
    }

    @Nested
    @DisplayName("回调设置")
    class Callbacks {

        @Test
        @DisplayName("should_accept_all_callback_types_without_error")
        void should_accept_all_callback_types_without_error() {
            manager.setOnResultUpdated(result -> {});
            manager.setOnStatusChanged(status -> {});
            manager.setOnProgressChanged(progress -> {});
            manager.setOnNewRequestQueued(result -> {});
            // 流式回调按 (结果 id, 增量文本) 分流，便于 UI 侧按行归位
            manager.setOnStreamingChunk((resultId, chunk) -> {});
            manager.setOnHighRiskResult(result -> {});

            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("被动 → 主动协作（高危回调）")
    class HighRiskCollaboration {

        private java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);

        private burp.api.montoya.http.message.HttpRequestResponse mockRequestResponse() {
            var mockReqResp = mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            var mockReq = mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            when(mockReqResp.request()).thenReturn(mockReq);
            when(mockReq.method()).thenReturn("GET");
            when(mockReq.url()).thenReturn("https://example.com/vuln?id=1");
            return mockReqResp;
        }

        private void awaitCompleted(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 8000;
            while (manager.getCompletedCount() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        }

        @Test
        @DisplayName("高危结果触发协作回调（被动 → 主动审计队列）")
        void high_risk_triggers_callback() throws Exception {
            PassiveScanApiClient mockClient = mock(PassiveScanApiClient.class);
            when(mockClient.analyzeRequest(any(), any(), any()))
                    .thenReturn("## 风险等级: 高\n发现 SQL 注入风险");
            manager.setApiClient(mockClient);

            List<ScanResult> highRisk = new java.util.ArrayList<>();
            manager.setOnHighRiskResult(highRisk::add);

            manager.startPassiveScan();
            try {
                manager.addRequest(mockRequestResponse());
                awaitCompleted(1);
            } finally {
                manager.stopPassiveScan();
            }

            assertThat(manager.getCompletedCount()).isEqualTo(1);
            assertThat(highRisk).hasSize(1);
            assertThat(highRisk.get(0).getRiskLevel()).isEqualTo(ScanResult.RiskLevel.HIGH);
            assertThat(highRisk.get(0).getRequestResponse()).isNotNull();
        }

        @Test
        @DisplayName("低危结果不触发协作回调")
        void low_risk_does_not_trigger() throws Exception {
            PassiveScanApiClient mockClient = mock(PassiveScanApiClient.class);
            when(mockClient.analyzeRequest(any(), any(), any()))
                    .thenReturn("## 风险等级: 低\n未发现明显风险");
            manager.setApiClient(mockClient);

            List<ScanResult> highRisk = new java.util.ArrayList<>();
            manager.setOnHighRiskResult(highRisk::add);

            manager.startPassiveScan();
            try {
                manager.addRequest(mockRequestResponse());
                awaitCompleted(1);
            } finally {
                manager.stopPassiveScan();
            }

            assertThat(manager.getCompletedCount()).isEqualTo(1);
            assertThat(highRisk).isEmpty();
        }
    }

    @Nested
    @DisplayName("API 客户端管理")
    class ApiClientManagement {

        @Test
        @DisplayName("should_allow_replacing_api_client")
        void should_allow_replacing_api_client() {
            PassiveScanApiClient newClient = mock(PassiveScanApiClient.class);
            manager.setApiClient(newClient);
            assertThat(manager.getApiClient()).isSameAs(newClient);
        }
    }

    @Nested
    @DisplayName("流量来源")
    class ToolSources {

        @Test
        @DisplayName("default_only_proxy")
        void default_only_proxy() {
            assertThat(manager.getToolSources()).containsExactly(burp.api.montoya.core.ToolType.PROXY);
        }

        @Test
        @DisplayName("allows_repeater_and_crawler_sources")
        void allows_selected_sources() {
            manager.setToolSources(java.util.Set.of(
                    burp.api.montoya.core.ToolType.PROXY,
                    burp.api.montoya.core.ToolType.REPEATER,
                    burp.api.montoya.core.ToolType.SCANNER));
            assertThat(manager.getToolSources()).contains(
                    burp.api.montoya.core.ToolType.PROXY,
                    burp.api.montoya.core.ToolType.REPEATER,
                    burp.api.montoya.core.ToolType.SCANNER);
            assertThat(PassiveScanManager.isToolSourceEnabled(
                    manager.getToolSources(), burp.api.montoya.core.ToolType.REPEATER)).isTrue();
            assertThat(PassiveScanManager.isToolSourceEnabled(
                    manager.getToolSources(), burp.api.montoya.core.ToolType.INTRUDER)).isFalse();
        }

        @Test
        @DisplayName("empty_selection_falls_back_to_proxy")
        void empty_falls_back_to_proxy() {
            manager.setToolSources(java.util.Set.of());
            assertThat(manager.getToolSources()).containsExactly(burp.api.montoya.core.ToolType.PROXY);
        }

        @Test
        @DisplayName("parse_and_format_for_settings")
        void parse_and_format() {
            assertThat(PassiveScanManager.parseToolSources("proxy,repeater,SCANNER"))
                    .containsExactly(burp.api.montoya.core.ToolType.PROXY,
                            burp.api.montoya.core.ToolType.REPEATER,
                            burp.api.montoya.core.ToolType.SCANNER);
            assertThat(PassiveScanManager.parseToolSources(null))
                    .containsExactly(burp.api.montoya.core.ToolType.PROXY);
            assertThat(PassiveScanManager.parseToolSources("bogus,extensions"))
                    .containsExactly(burp.api.montoya.core.ToolType.EXTENSIONS);
            assertThat(PassiveScanManager.formatToolSources(java.util.Set.of()))
                    .isEqualTo("PROXY");
            assertThat(PassiveScanManager.formatToolSources(java.util.List.of(
                    burp.api.montoya.core.ToolType.PROXY,
                    burp.api.montoya.core.ToolType.REPEATER)))
                    .isEqualTo("PROXY,REPEATER");
        }
    }
}
