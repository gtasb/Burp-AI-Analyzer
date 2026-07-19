package com.ai.analyzer.pscan;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("PassiveScanTask - 被动扫描任务")
class PassiveScanTaskTest {

    private HttpRequestResponse mockRequestResponse;
    private HttpRequest mockRequest;
    private HttpResponse mockResponse;

    @BeforeEach
    void setUp() {
        mockRequestResponse = mock(HttpRequestResponse.class);
        mockRequest = mock(HttpRequest.class);
        mockResponse = mock(HttpResponse.class);
        when(mockRequestResponse.request()).thenReturn(mockRequest);
        when(mockRequestResponse.response()).thenReturn(mockResponse);
    }

    @Nested
    @DisplayName("shouldSkipRequest - 静态资源过滤")
    class ShouldSkipRequest {

        @Test
        @DisplayName("should_return_true_when_request_response_is_null")
        void should_return_true_when_request_response_is_null() {
            assertThat(PassiveScanTask.shouldSkipRequest(null)).isTrue();
        }

        @Test
        @DisplayName("should_return_true_when_request_is_null")
        void should_return_true_when_request_is_null() {
            when(mockRequestResponse.request()).thenReturn(null);
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @Test
        @DisplayName("should_return_true_when_url_is_null")
        void should_return_true_when_url_is_null() {
            when(mockRequest.url()).thenReturn(null);
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com/style.css",
            "https://example.com/app.js",
            "https://example.com/logo.png",
            "https://example.com/photo.jpg",
            "https://example.com/icon.ico",
            "https://example.com/font.woff2",
            "https://example.com/document.pdf",
            "https://example.com/archive.zip",
            "https://example.com/map.js.map"
        })
        @DisplayName("should_return_true_when_url_has_static_extension")
        void should_return_true_when_url_has_static_extension(String url) {
            when(mockRequest.url()).thenReturn(url);
            when(mockRequest.method()).thenReturn("GET");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com/style.css?v=1.0",
            "https://example.com/app.js?hash=abc123",
            "https://example.com/logo.png?w=200"
        })
        @DisplayName("should_return_true_when_static_url_has_query_params")
        void should_return_true_when_static_url_has_query_params(String url) {
            when(mockRequest.url()).thenReturn(url);
            when(mockRequest.method()).thenReturn("GET");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://example.com/images/logo.png",
            "https://example.com/img/photo.jpg",
            "https://example.com/css/style.css",
            "https://example.com/js/app.js",
            "https://example.com/fonts/arial.woff",
            "https://example.com/static/bundle.js",
            "https://example.com/assets/main.css"
        })
        @DisplayName("should_return_true_when_url_contains_static_path")
        void should_return_true_when_url_contains_static_path(String url) {
            when(mockRequest.url()).thenReturn(url);
            when(mockRequest.method()).thenReturn("GET");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @Test
        @DisplayName("should_return_false_when_url_is_api_endpoint")
        void should_return_false_when_url_is_api_endpoint() {
            when(mockRequest.url()).thenReturn("https://example.com/api/users");
            when(mockRequest.method()).thenReturn("GET");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isFalse();
        }

        @Test
        @DisplayName("should_return_false_when_url_is_dynamic_page")
        void should_return_false_when_url_is_dynamic_page() {
            when(mockRequest.url()).thenReturn("https://example.com/login");
            when(mockRequest.method()).thenReturn("POST");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isFalse();
        }

        @Test
        @DisplayName("should_return_true_when_GET_response_is_image_content_type")
        void should_return_true_when_GET_response_is_image_content_type() {
            when(mockRequest.url()).thenReturn("https://example.com/avatar");
            when(mockRequest.method()).thenReturn("GET");
            when(mockResponse.toString()).thenReturn(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\n<binary>");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @Test
        @DisplayName("should_return_false_when_POST_response_is_image_content_type")
        void should_return_false_when_POST_response_is_image_content_type() {
            when(mockRequest.url()).thenReturn("https://example.com/upload");
            when(mockRequest.method()).thenReturn("POST");
            when(mockResponse.toString()).thenReturn(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\n<binary>");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isFalse();
        }

        @Test
        @DisplayName("should_return_true_when_GET_response_is_wasm")
        void should_return_true_when_GET_response_is_wasm() {
            when(mockRequest.url()).thenReturn("https://example.com/module");
            when(mockRequest.method()).thenReturn("GET");
            when(mockResponse.toString()).thenReturn(
                "HTTP/1.1 200 OK\r\nContent-Type: application/wasm\r\n\r\n<binary>");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isTrue();
        }

        @Test
        @DisplayName("should_return_false_when_response_is_null_for_GET")
        void should_return_false_when_response_is_null_for_GET() {
            when(mockRequest.url()).thenReturn("https://example.com/api/data");
            when(mockRequest.method()).thenReturn("GET");
            when(mockRequestResponse.response()).thenReturn(null);
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isFalse();
        }

        @Test
        @DisplayName("should_return_false_when_GET_response_is_json")
        void should_return_false_when_GET_response_is_json() {
            when(mockRequest.url()).thenReturn("https://example.com/api/data");
            when(mockRequest.method()).thenReturn("GET");
            when(mockResponse.toString()).thenReturn(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"data\":1}");
            assertThat(PassiveScanTask.shouldSkipRequest(mockRequestResponse)).isFalse();
        }
    }
}
