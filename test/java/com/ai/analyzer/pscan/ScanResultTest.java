package com.ai.analyzer.pscan;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ScanResult - 扫描结果数据模型")
class ScanResultTest {

    private HttpRequestResponse mockRequestResponse;
    private HttpRequest mockRequest;
    private HttpResponse mockResponse;
    private HttpService mockService;

    @BeforeEach
    void setUp() {
        mockRequestResponse = mock(HttpRequestResponse.class);
        mockRequest = mock(HttpRequest.class);
        mockResponse = mock(HttpResponse.class);
        mockService = mock(HttpService.class);

        when(mockRequestResponse.request()).thenReturn(mockRequest);
        when(mockRequestResponse.response()).thenReturn(mockResponse);
        when(mockRequest.method()).thenReturn("GET");
        when(mockRequest.url()).thenReturn("https://example.com/api/users?id=1");
        when(mockRequest.httpService()).thenReturn(mockService);
        when(mockService.host()).thenReturn("example.com");
    }

    @Nested
    @DisplayName("构造与初始状态")
    class Construction {

        @Test
        @DisplayName("should_initialize_with_pending_status_when_created")
        void should_initialize_with_pending_status_when_created() {
            ScanResult result = new ScanResult(1, mockRequestResponse);

            assertThat(result.getId()).isEqualTo(1);
            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.PENDING);
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
            assertThat(result.getMethod()).isEqualTo("GET");
            assertThat(result.getHost()).isEqualTo("example.com");
            assertThat(result.getUrl()).isEqualTo("https://example.com/api/users?id=1");
            assertThat(result.getTimestamp()).isNotNull();
            assertThat(result.getAnalysisResult()).isNull();
            assertThat(result.getErrorMessage()).isNull();
            assertThat(result.getCompletedTime()).isNull();
        }

        @Test
        @DisplayName("should_handle_null_request_response_when_created")
        void should_handle_null_request_response_when_created() {
            ScanResult result = new ScanResult(1, null);

            assertThat(result.getMethod()).isEmpty();
            assertThat(result.getUrl()).isEmpty();
            assertThat(result.getHost()).isEmpty();
        }

        @Test
        @DisplayName("should_handle_null_request_in_response_when_created")
        void should_handle_null_request_in_response_when_created() {
            when(mockRequestResponse.request()).thenReturn(null);
            ScanResult result = new ScanResult(1, mockRequestResponse);

            assertThat(result.getMethod()).isEmpty();
            assertThat(result.getUrl()).isEmpty();
            assertThat(result.getHost()).isEmpty();
        }

        @Test
        @DisplayName("should_handle_null_http_service_when_created")
        void should_handle_null_http_service_when_created() {
            when(mockRequest.httpService()).thenReturn(null);
            ScanResult result = new ScanResult(1, mockRequestResponse);

            assertThat(result.getHost()).isEmpty();
        }
    }

    @Nested
    @DisplayName("状态转换")
    class StateTransitions {

        @Test
        @DisplayName("should_transition_to_scanning_when_markScanning")
        void should_transition_to_scanning_when_markScanning() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markScanning();

            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.SCANNING);
        }

        @Test
        @DisplayName("should_transition_to_completed_with_result_when_markCompleted")
        void should_transition_to_completed_with_result_when_markCompleted() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("## 风险等级: 高危\nSQL注入漏洞");

            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.COMPLETED);
            assertThat(result.getAnalysisResult()).isEqualTo("## 风险等级: 高危\nSQL注入漏洞");
            assertThat(result.getCompletedTime()).isNotNull();
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.HIGH);
        }

        @Test
        @DisplayName("should_transition_to_error_with_message_when_markError")
        void should_transition_to_error_with_message_when_markError() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markError("连接超时");

            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.ERROR);
            assertThat(result.getErrorMessage()).isEqualTo("连接超时");
            assertThat(result.getCompletedTime()).isNotNull();
        }

        @Test
        @DisplayName("should_transition_to_cancelled_when_markCancelled")
        void should_transition_to_cancelled_when_markCancelled() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCancelled();

            assertThat(result.getStatus()).isEqualTo(ScanResult.ScanStatus.CANCELLED);
            assertThat(result.getCompletedTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("风险等级解析 - 核心逻辑")
    class RiskLevelParsing {

        @Test
        @DisplayName("should_return_CRITICAL_when_response_contains_critical_keywords")
        void should_return_CRITICAL_when_response_contains_critical_keywords() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("发现严重的远程代码执行漏洞");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.CRITICAL);
        }

        @Test
        @DisplayName("should_return_CRITICAL_when_response_contains_rce")
        void should_return_CRITICAL_when_response_contains_rce() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("发现RCE漏洞，可远程执行命令");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.CRITICAL);
        }

        @Test
        @DisplayName("should_return_HIGH_when_response_contains_sql_injection")
        void should_return_HIGH_when_response_contains_sql_injection() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("存在SQL注入漏洞");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.HIGH);
        }

        @Test
        @DisplayName("should_return_HIGH_when_response_contains_ssrf")
        void should_return_HIGH_when_response_contains_ssrf() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("存在SSRF服务端请求伪造");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.HIGH);
        }

        @Test
        @DisplayName("should_return_MEDIUM_when_response_contains_xss")
        void should_return_MEDIUM_when_response_contains_xss() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("反射型XSS漏洞");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("should_return_MEDIUM_when_response_contains_csrf")
        void should_return_MEDIUM_when_response_contains_csrf() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("缺少CSRF防护");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("should_return_LOW_when_response_contains_low_risk")
        void should_return_LOW_when_response_contains_low_risk() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("严重程度: 低，缺少部分安全头");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.LOW);
        }

        @Test
        @DisplayName("should_return_INFO_when_response_contains_only_suggestions")
        void should_return_INFO_when_response_contains_only_suggestions() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("建议增加速率限制");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.INFO);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should_return_NONE_when_response_is_null_or_empty")
        void should_return_NONE_when_response_is_null_or_empty(String input) {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted(input);
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }

        @Test
        @DisplayName("should_return_NONE_when_response_says_no_vulnerabilities_found")
        void should_return_NONE_when_response_says_no_vulnerabilities_found() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("未发现任何安全漏洞");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }

        @Test
        @DisplayName("should_return_NONE_when_response_is_negative_with_problem_keyword [BUG#1]")
        void should_return_NONE_when_response_is_negative_with_problem_keyword() {
            // BUG: "没有明显的安全问题" 包含 "问题" 关键词，当前实现误判为 MEDIUM
            // 正确行为：否定语句应返回 NONE
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("经过分析，没有明显的安全问题，接口实现规范");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }

        @Test
        @DisplayName("should_return_NONE_when_response_says_no_issues [BUG#1]")
        void should_return_NONE_when_response_says_no_issues() {
            // BUG: "不存在安全问题" 包含 "问题"，被误判为 MEDIUM
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("该接口不存在安全问题，代码实现良好");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }

        @Test
        @DisplayName("should_return_NONE_when_response_is_safe_and_good")
        void should_return_NONE_when_response_is_safe_and_good() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("该接口安全性良好，无需担忧");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }

        @Test
        @DisplayName("should_return_NONE_when_response_says_no_risk [BUG#1]")
        void should_return_NONE_when_response_says_no_risk() {
            // BUG: "没有发现风险" 中的 "风险" 会触发 MEDIUM
            // 但 "没有发现" 在安全检查中匹配，应返回 NONE
            ScanResult result = new ScanResult(1, mockRequestResponse);
            result.markCompleted("没有发现风险点");
            assertThat(result.getRiskLevel()).isEqualTo(ScanResult.RiskLevel.NONE);
        }
    }

    @Nested
    @DisplayName("RiskLevel.fromString")
    class RiskLevelFromString {

        @ParameterizedTest
        @CsvSource({
            "'严重漏洞', CRITICAL",
            "'critical severity', CRITICAL",
            "'高危风险', HIGH",
            "'high severity', HIGH",
            "'中等风险', MEDIUM",
            "'medium severity', MEDIUM",
            "'低风险', LOW",
            "'low severity', LOW",
            "'信息提示', INFO",
            "'info level', INFO"
        })
        @DisplayName("should_parse_risk_level_from_text")
        void should_parse_risk_level_from_text(String input, ScanResult.RiskLevel expected) {
            assertThat(ScanResult.RiskLevel.fromString(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("should_return_NONE_when_input_is_null")
        void should_return_NONE_when_input_is_null() {
            assertThat(ScanResult.RiskLevel.fromString(null)).isEqualTo(ScanResult.RiskLevel.NONE);
        }
    }

    @Nested
    @DisplayName("去重键生成")
    class Deduplication {

        @Test
        @DisplayName("should_generate_key_without_query_string")
        void should_generate_key_without_query_string() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            // URL is "https://example.com/api/users?id=1"
            assertThat(result.getDeduplicationKey()).isEqualTo("GET|example.com|https://example.com/api/users");
        }

        @Test
        @DisplayName("should_generate_key_for_url_without_query")
        void should_generate_key_for_url_without_query() {
            when(mockRequest.url()).thenReturn("https://example.com/api/users");
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.getDeduplicationKey()).isEqualTo("GET|example.com|https://example.com/api/users");
        }
    }

    @Nested
    @DisplayName("URL 截断")
    class ShortUrl {

        @Test
        @DisplayName("should_truncate_url_at_query_mark_when_within_limit")
        void should_truncate_url_at_query_mark_when_within_limit() {
            when(mockRequest.url()).thenReturn("https://example.com/short?q=1");
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.getShortUrl()).isEqualTo("https://example.com/short");
        }

        @Test
        @DisplayName("should_truncate_long_url_with_ellipsis")
        void should_truncate_long_url_with_ellipsis() {
            String longUrl = "https://example.com/" + "a".repeat(100);
            when(mockRequest.url()).thenReturn(longUrl);
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.getShortUrl()).hasSize(63); // 60 + "..."
            assertThat(result.getShortUrl()).endsWith("...");
        }

        @Test
        @DisplayName("should_return_empty_string_when_url_is_null")
        void should_return_empty_string_when_url_is_null() {
            when(mockRequest.url()).thenReturn(null);
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.getShortUrl()).isEmpty();
        }
    }

    @Nested
    @DisplayName("hasResponse")
    class HasResponse {

        @Test
        @DisplayName("should_return_true_when_response_exists")
        void should_return_true_when_response_exists() {
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.hasResponse()).isTrue();
        }

        @Test
        @DisplayName("should_return_false_when_response_is_null")
        void should_return_false_when_response_is_null() {
            when(mockRequestResponse.response()).thenReturn(null);
            ScanResult result = new ScanResult(1, mockRequestResponse);
            assertThat(result.hasResponse()).isFalse();
        }

        @Test
        @DisplayName("should_return_false_when_request_response_is_null")
        void should_return_false_when_request_response_is_null() {
            ScanResult result = new ScanResult(1, null);
            assertThat(result.hasResponse()).isFalse();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should_format_correctly")
        void should_format_correctly() {
            ScanResult result = new ScanResult(42, mockRequestResponse);
            String str = result.toString();
            assertThat(str).contains("#42");
            assertThat(str).contains("GET");
            assertThat(str).contains("等待中");
            assertThat(str).contains("无");
        }
    }
}
