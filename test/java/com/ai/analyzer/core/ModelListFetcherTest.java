package com.ai.analyzer.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelListFetcher - 模型列表 endpoint 与解析")
class ModelListFetcherTest {

    @Test
    @DisplayName("Anthropic base URL 带不带 /v1 都只拼一次")
    void anthropic_endpoint_no_double_v1() {
        assertThat(ModelListFetcher.endpoint("Anthropic兼容", ""))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(ModelListFetcher.endpoint("Anthropic兼容", "https://api.anthropic.com"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(ModelListFetcher.endpoint("Anthropic兼容", "https://api.anthropic.com/v1"))
                .isEqualTo("https://api.anthropic.com/v1/models");
        assertThat(ModelListFetcher.endpoint("Anthropic兼容", "https://gateway.example.com/v1/"))
                .isEqualTo("https://gateway.example.com/v1/models");
        assertThat(ModelListFetcher.endpoint("Anthropic兼容", "https://api.anthropic.com/v1/models"))
                .isEqualTo("https://api.anthropic.com/v1/models");
    }

    @Test
    @DisplayName("OpenAI / DashScope 的 base URL 归一化")
    void openai_and_dashscope_endpoints() {
        assertThat(ModelListFetcher.endpoint("OpenAI兼容", ""))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(ModelListFetcher.endpoint("OpenAI兼容", "https://api.openai.com"))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(ModelListFetcher.endpoint("OpenAI兼容", "https://proxy.example.com/openai/v1"))
                .isEqualTo("https://proxy.example.com/openai/v1/models");
        assertThat(ModelListFetcher.endpoint("DashScope", ""))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/models");
        assertThat(ModelListFetcher.endpoint("DashScope", "https://dashscope.aliyuncs.com"))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/models");
        assertThat(ModelListFetcher.endpoint("DashScope", "https://dashscope.aliyuncs.com/api/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/models");
    }

    @Test
    @DisplayName("Anthropic 用 x-api-key；官方域名不带 Bearer")
    void anthropic_headers() {
        Map<String, String> nativeHeaders = ModelListFetcher.headers(
                "Anthropic兼容", "https://api.anthropic.com", "sk-ant-1");
        assertThat(nativeHeaders).containsEntry("x-api-key", "sk-ant-1")
                .containsEntry("anthropic-version", "2023-06-01")
                .doesNotContainKey("Authorization");

        Map<String, String> gatewayHeaders = ModelListFetcher.headers(
                "Anthropic兼容", "https://gateway.example.com", "sk-ant-1");
        assertThat(gatewayHeaders).containsEntry("x-api-key", "sk-ant-1")
                .containsEntry("Authorization", "Bearer sk-ant-1");
    }

    @Test
    @DisplayName("兼容 data[].id 与 models[].name 两种响应")
    void parses_both_shapes() {
        assertThat(ModelListFetcher.parseModels(
                "{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"},{\"id\":\"a\"}]}"))
                .containsExactly("a", "b");
        assertThat(ModelListFetcher.parseModels(
                "{\"models\":[{\"name\":\"x-1\"},{\"id\":\"w-2\"}]}"))
                .containsExactly("w-2", "x-1");
        assertThat(ModelListFetcher.parseModels("{\"data\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("实际请求 Anthropic 风格 endpoint 时发送正确头与路径")
    void fetch_against_local_server() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> versionHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] body = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-test\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            List<String> models = ModelListFetcher.fetch("Anthropic兼容", base, "sk-ant-1");

            assertThat(models).containsExactly("claude-test");
            assertThat(path.get()).isEqualTo("/v1/models");
            assertThat(apiKeyHeader.get()).isEqualTo("sk-ant-1");
            assertThat(versionHeader.get()).isEqualTo("2023-06-01");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTP 错误信息包含状态码与响应体")
    void http_error_includes_body() {
        assertThatThrownBy(() -> {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> {
                byte[] body = "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            try {
                ModelListFetcher.fetch("OpenAI兼容",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "bad");
            } finally {
                server.stop(0);
            }
        }).hasMessageContaining("401").hasMessageContaining("bad key");
    }
}
