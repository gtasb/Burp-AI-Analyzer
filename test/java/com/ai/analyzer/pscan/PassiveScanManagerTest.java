package com.ai.analyzer.pscan;

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
            assertThat(manager.getThreadCount()).isEqualTo(3);
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
            assertThat(manager.getThreadCount()).isEqualTo(10);
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

            manager.setThreadCount(10);
            assertThat(manager.getThreadCount()).isEqualTo(10);
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
        @DisplayName("should_return_null_when_not_running")
        void should_return_null_when_not_running() {
            var mockReqResp = mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            var mockReq = mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            when(mockReqResp.request()).thenReturn(mockReq);
            when(mockReq.method()).thenReturn("GET");
            when(mockReq.url()).thenReturn("https://example.com/api");

            ScanResult result = manager.addRequest(mockReqResp);
            assertThat(result).isNull();
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
            manager.setOnStreamingChunk(chunk -> {});

            assertThat(manager.getCurrentStreamingScanResult()).isNull();
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
}
