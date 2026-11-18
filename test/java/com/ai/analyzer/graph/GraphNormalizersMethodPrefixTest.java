package com.ai.analyzer.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL 前缀含 HTTP method 时的解析回归：
 * agent 常传 "GET https://host/path" 作为 rootOrUrl，
 * 不剥前缀会导致 scheme="GET https"、origin="get https://host:80"。
 */
@DisplayName("GraphNormalizers - method 前缀剥离")
class GraphNormalizersMethodPrefixTest {

    @Test
    @DisplayName("GET_prefix_stripped_from_url")
    void getPrefixStripped() {
        var parsed = GraphNormalizers.parse("GET", "GET https://uuyc.163.com/download/", null);
        assertThat(parsed.origin()).isEqualTo("https://uuyc.163.com:443");
        assertThat(parsed.path()).isEqualTo("/download");
    }

    @Test
    @DisplayName("POST_prefix_stripped")
    void postPrefixStripped() {
        var parsed = GraphNormalizers.parse("POST", "POST https://api.example.com/v1/login", null);
        assertThat(parsed.origin()).isEqualTo("https://api.example.com:443");
        assertThat(parsed.path()).isEqualTo("/v1/login");
    }

    @Test
    @DisplayName("normal_url_unchanged")
    void normalUrlUnchanged() {
        var parsed = GraphNormalizers.parse("GET", "https://example.com/path?q=1", null);
        assertThat(parsed.origin()).isEqualTo("https://example.com:443");
        assertThat(parsed.path()).isEqualTo("/path");
    }

    @Test
    @DisplayName("method_prefix_without_scheme")
    void methodPrefixWithoutScheme() {
        // "GET /path" 不含 ://，走 rootOrUrl.trim() 分支（在 GraphStore 里）
        // 但 parse 本身也应正确处理
        var parsed = GraphNormalizers.parse("GET", "GET /api/test", null);
        assertThat(parsed.path()).isEqualTo("/api/test");
    }

    @Test
    @DisplayName("lowercase_method_prefix")
    void lowercaseMethodPrefix() {
        var parsed = GraphNormalizers.parse("GET", "get https://host.com/x", null);
        assertThat(parsed.origin()).isEqualTo("https://host.com:443");
    }
}
