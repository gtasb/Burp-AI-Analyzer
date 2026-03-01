package com.ai.analyzer.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpFormatter - HTTP 格式化工具")
class HttpFormatterTest {

    @Nested
    @DisplayName("formatHttpRequestResponse(String, String)")
    class FormatStringPair {

        @Test
        @DisplayName("should_format_request_and_response_when_both_provided")
        void should_format_request_and_response_when_both_provided() {
            String request = "GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n";
            String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"users\":[]}";

            String result = HttpFormatter.formatHttpRequestResponse(request, response);

            assertThat(result).startsWith("=== HTTP请求 ===\n");
            assertThat(result).contains(request);
            assertThat(result).contains("=== HTTP响应 ===\n");
            assertThat(result).contains(response);
        }

        @Test
        @DisplayName("should_format_request_only_when_response_is_null")
        void should_format_request_only_when_response_is_null() {
            String request = "GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n";

            String result = HttpFormatter.formatHttpRequestResponse(request, null);

            assertThat(result).startsWith("=== HTTP请求 ===\n");
            assertThat(result).contains(request);
            assertThat(result).doesNotContain("=== HTTP响应 ===");
        }

        @Test
        @DisplayName("should_format_request_only_when_response_is_empty")
        void should_format_request_only_when_response_is_empty() {
            String request = "GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n";

            String result = HttpFormatter.formatHttpRequestResponse(request, "   ");

            assertThat(result).doesNotContain("=== HTTP响应 ===");
        }

        @Test
        @DisplayName("should_return_empty_when_request_is_null")
        void should_return_empty_when_request_is_null() {
            String result = HttpFormatter.formatHttpRequestResponse(null, "response");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should_return_empty_when_request_is_whitespace")
        void should_return_empty_when_request_is_whitespace() {
            String result = HttpFormatter.formatHttpRequestResponse("   ", "response");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("sanitizeForApi")
    class SanitizeForApi {

        @Test
        @DisplayName("should_remove_null_bytes_when_present")
        void should_remove_null_bytes_when_present() {
            String input = "hello\0world";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo("helloworld");
        }

        @Test
        @DisplayName("should_preserve_tabs_and_newlines_when_sanitizing")
        void should_preserve_tabs_and_newlines_when_sanitizing() {
            String input = "line1\nline2\tindented\r\nline3";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo(input);
        }

        @Test
        @DisplayName("should_remove_control_characters_when_present")
        void should_remove_control_characters_when_present() {
            String input = "hello\u0001\u0002\u0003world";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo("helloworld");
        }

        @Test
        @DisplayName("should_remove_unicode_replacement_characters_when_present")
        void should_remove_unicode_replacement_characters_when_present() {
            String input = "hello\uFFFDworld";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo("helloworld");
        }

        @Test
        @DisplayName("should_replace_hex_escapes_with_safe_text_when_present")
        void should_replace_hex_escapes_with_safe_text_when_present() {
            String input = "data\\x41\\xFF";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo("data[hex:41][hex:FF]");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should_return_input_unchanged_when_null_or_empty")
        void should_return_input_unchanged_when_null_or_empty(String input) {
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo(input);
        }

        @Test
        @DisplayName("should_preserve_normal_text_when_sanitizing")
        void should_preserve_normal_text_when_sanitizing() {
            String input = "GET /api/users HTTP/1.1\r\nHost: example.com\r\n\r\n{\"id\": 1}";
            String result = HttpFormatter.sanitizeForApi(input);
            assertThat(result).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("compressIfTooLong")
    class CompressIfTooLong {

        @Test
        @DisplayName("should_not_compress_when_within_limit")
        void should_not_compress_when_within_limit() {
            String content = "Short content";
            HttpFormatter.CompressResult result = HttpFormatter.compressIfTooLong(content, 1000);

            assertThat(result.wasCompressed).isFalse();
            assertThat(result.content).isEqualTo(content);
            assertThat(result.originalLength).isEqualTo(content.length());
            assertThat(result.compressedLength).isEqualTo(content.length());
        }

        @Test
        @DisplayName("should_compress_when_exceeds_limit")
        void should_compress_when_exceeds_limit() {
            String content = "x".repeat(20000);
            HttpFormatter.CompressResult result = HttpFormatter.compressIfTooLong(content, 5000);

            assertThat(result.wasCompressed).isTrue();
            assertThat(result.compressedLength).isLessThanOrEqualTo(6000); // some overhead from truncation message
            assertThat(result.originalLength).isEqualTo(20000);
        }

        @Test
        @DisplayName("should_handle_null_input_when_compressing")
        void should_handle_null_input_when_compressing() {
            HttpFormatter.CompressResult result = HttpFormatter.compressIfTooLong(null);

            assertThat(result.wasCompressed).isFalse();
            assertThat(result.content).isEmpty();
            assertThat(result.originalLength).isZero();
        }

        @Test
        @DisplayName("should_split_request_and_response_sections_when_compressing")
        void should_split_request_and_response_sections_when_compressing() {
            String request = "=== HTTP请求 ===\nGET /api HTTP/1.1\r\nHost: example.com\r\n\r\n" + "x".repeat(10000);
            String response = "=== HTTP响应 ===\nHTTP/1.1 200 OK\r\n\r\n" + "y".repeat(10000);
            String content = request + "\n\n" + response;

            HttpFormatter.CompressResult result = HttpFormatter.compressIfTooLong(content, 5000);

            assertThat(result.wasCompressed).isTrue();
            assertThat(result.content).contains("=== HTTP请求 ===");
            assertThat(result.content).contains("=== HTTP响应 ===");
        }

        @Test
        @DisplayName("should_preserve_headers_and_truncate_body_when_compressing")
        void should_preserve_headers_and_truncate_body_when_compressing() {
            String headers = "GET /api HTTP/1.1\r\nHost: example.com\r\n\r\n";
            String body = "B".repeat(20000);
            String content = headers + body;

            HttpFormatter.CompressResult result = HttpFormatter.compressIfTooLong(content, 1000);

            assertThat(result.wasCompressed).isTrue();
            assertThat(result.content).contains("GET /api HTTP/1.1");
            assertThat(result.content).contains("Host: example.com");
            assertThat(result.content).contains("已截断");
        }
    }

    @Nested
    @DisplayName("isBinaryContent")
    class IsBinaryContent {

        @Test
        @DisplayName("should_return_false_when_content_is_normal_text")
        void should_return_false_when_content_is_normal_text() {
            String text = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>Hello</html>";
            assertThat(HttpFormatter.isBinaryContent(text)).isFalse();
        }

        @Test
        @DisplayName("should_return_true_when_content_has_many_control_chars")
        void should_return_true_when_content_has_many_control_chars() {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n\r\n");
            // Add >15% non-printable characters in the body
            for (int i = 0; i < 100; i++) {
                sb.append('\u0001');
            }
            for (int i = 0; i < 100; i++) {
                sb.append('A');
            }
            assertThat(HttpFormatter.isBinaryContent(sb.toString())).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should_return_false_when_content_is_null_or_empty")
        void should_return_false_when_content_is_null_or_empty(String input) {
            assertThat(HttpFormatter.isBinaryContent(input)).isFalse();
        }

        @Test
        @DisplayName("should_check_body_not_headers_when_separator_found")
        void should_check_body_not_headers_when_separator_found() {
            // Headers may have normal content, body has binary
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n";
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                body.append('\u0001');
            }
            assertThat(HttpFormatter.isBinaryContent(headers + body.toString())).isTrue();
        }
    }
}
