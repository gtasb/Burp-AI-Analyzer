package com.ai.analyzer.util;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BatchPromptBuilder - 批量报文提示词构建")
class BatchPromptBuilderTest {

    private HttpRequestResponse item(String method, String url, String statusLine) {
        ByteArray requestBytes = bytesOf(method + " " + url + " HTTP/1.1\r\nHost: t\r\n\r\n");
        HttpRequest request = mock(HttpRequest.class);
        when(request.toByteArray()).thenReturn(requestBytes);
        HttpResponse response = mock(HttpResponse.class);
        ByteArray responseBytes = statusLine != null ? bytesOf(statusLine + "\r\nContent-Length: 0\r\n\r\n") : null;
        when(response.toByteArray()).thenReturn(responseBytes);
        HttpRequestResponse rr = mock(HttpRequestResponse.class);
        when(rr.request()).thenReturn(request);
        when(rr.response()).thenReturn(statusLine != null ? response : null);
        return rr;
    }

    private ByteArray bytesOf(String content) {
        ByteArray bytes = mock(ByteArray.class);
        when(bytes.getBytes()).thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return bytes;
    }

    @Test
    @DisplayName("空列表返回空字符串")
    void empty_list_returns_empty() {
        assertThat(BatchPromptBuilder.buildBatchAnalysisPrompt(null)).isEmpty();
        assertThat(BatchPromptBuilder.buildBatchAnalysisPrompt(List.of())).isEmpty();
    }

    @Test
    @DisplayName("单条报文包含编号、请求与响应")
    void single_item_contains_request_and_response() {
        String prompt = BatchPromptBuilder.buildBatchAnalysisPrompt(
                List.of(item("GET", "/api/user", "HTTP/1.1 200 OK")));
        assertThat(prompt).contains("1 个 HTTP 报文");
        assertThat(prompt).contains("===== 报文 1 =====");
        assertThat(prompt).contains("请求:");
        assertThat(prompt).contains("GET /api/user HTTP/1.1");
        assertThat(prompt).contains("响应:");
        assertThat(prompt).contains("HTTP/1.1 200 OK");
    }

    @Test
    @DisplayName("多条报文依次编号并标注总数")
    void multiple_items_numbered() {
        String prompt = BatchPromptBuilder.buildBatchAnalysisPrompt(
                List.of(item("GET", "/a", "HTTP/1.1 200 OK"), item("POST", "/b", "HTTP/1.1 403 Forbidden")));
        assertThat(prompt).contains("2 个 HTTP 报文");
        assertThat(prompt).contains("===== 报文 1 =====");
        assertThat(prompt).contains("===== 报文 2 =====");
        assertThat(prompt.indexOf("报文 1")).isLessThan(prompt.indexOf("报文 2"));
    }

    @Test
    @DisplayName("超长报文被截断到上限")
    void oversized_item_truncated() {
        String bigBody = "A".repeat(10_000);
        String raw = "POST /big HTTP/1.1\r\nHost: t\r\nContent-Length: 10000\r\n\r\n" + bigBody;
        ByteArray requestBytes = bytesOf(raw);
        HttpRequest request = mock(HttpRequest.class);
        when(request.toByteArray()).thenReturn(requestBytes);
        HttpRequestResponse rr = mock(HttpRequestResponse.class);
        when(rr.request()).thenReturn(request);
        when(rr.response()).thenReturn(null);
        String prompt = BatchPromptBuilder.buildBatchAnalysisPrompt(List.of(rr));
        assertThat(prompt.length()).isLessThan(raw.length());
        assertThat(prompt).contains("已截断");
        assertThat(prompt).doesNotContain(bigBody);
    }

    @Test
    @DisplayName("null 条目显示占位")
    void null_item_shows_placeholder() {
        List<HttpRequestResponse> items = new java.util.ArrayList<>();
        items.add(item("GET", "/a", "HTTP/1.1 200 OK"));
        items.add(null);
        String prompt = BatchPromptBuilder.buildBatchAnalysisPrompt(items);
        assertThat(prompt).contains("(空报文)");
    }

    @Test
    @DisplayName("无响应报文标记响应为无")
    void no_response_marks_missing() {
        String prompt = BatchPromptBuilder.buildBatchAnalysisPrompt(List.of(item("GET", "/a", null)));
        assertThat(prompt).contains("响应: (无)");
    }
}
