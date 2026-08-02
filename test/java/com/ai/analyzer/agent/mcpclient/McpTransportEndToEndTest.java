package com.ai.analyzer.agent.mcpclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentScopeMcpManager - 真实连接端到端（本地 mock MCP 服务器）")
class McpTransportEndToEndTest {

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> receivedAuth = new AtomicReference<>();
    private boolean acceptPost = true;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            // 非 POST（如 SSE 客户端的 GET）一律 404：模拟"根路径不是 SSE 端点"
            respond(ex, 404, "{}", "application/json");
            return;
        }
        if (!acceptPost) {
            respond(ex, 500,
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"boom\"},\"id\":null}",
                    "application/json");
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            receivedAuth.set(auth);
        }
        String body = readBody(ex.getRequestBody());
        ObjectMapper om = new ObjectMapper();
        JsonNode req;
        try {
            req = om.readTree(body);
        } catch (Exception e) {
            respond(ex, 400, "bad json", "text/plain");
            return;
        }
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) {
            respond(ex, 204, "", "");
            return;
        }
        String idJson = id.isIntegralNumber() ? id.asText() : "\"" + id.asText() + "\"";
        String method = req.path("method").asText("");
        String protocolVersion = req.path("params").path("protocolVersion").asText("2025-06-18");
        String resp;
        if ("initialize".equals(method)) {
            resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                    + ",\"result\":{\"protocolVersion\":\"" + protocolVersion
                    + "\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"mock-mcp\",\"version\":\"1.0.0\"}}}";
        } else if ("tools/list".equals(method)) {
            resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{\"tools\":[]}}";
        } else {
            resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{}}";
        }
        respond(ex, 200, resp, "application/json");
    }

    private static String readBody(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static void respond(HttpExchange ex, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!contentType.isEmpty()) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
        ex.close();
    }

    @Test
    @DisplayName("根路径 + Streamable-only 服务器：SSE 404 失败后自动回退 Streamable HTTP 成功")
    void root_path_streamable_only_succeeds_via_fallback() throws Exception {
        McpClientWrapper client = AgentScopeMcpManager.buildTransportClient(
                "burp-mcp", baseUrl(), null, null, null);
        assertThat(client).isNotNull();
        assertThat(client.isInitialized()).isTrue();
        assertThat(client.listTools().block()).isEmpty();
        client.close();
    }

    @Test
    @DisplayName("Authorization 头经 normalizeBearer 透传")
    void authorization_header_forwarded() throws Exception {
        McpClientWrapper client = AgentScopeMcpManager.buildTransportClient(
                "burp-mcp", baseUrl(), null, null,
                builder -> builder.header("Authorization",
                        AgentScopeMcpManager.normalizeBearer("MyToken")));
        assertThat(client).isNotNull();
        assertThat(receivedAuth.get()).isEqualTo("Bearer MyToken");
        client.close();
    }

    @Test
    @DisplayName("已含 Bearer 前缀时原样透传")
    void existing_bearer_prefix_forwarded_as_is() throws Exception {
        McpClientWrapper client = AgentScopeMcpManager.buildTransportClient(
                "burp-mcp", baseUrl(), null, null,
                builder -> builder.header("Authorization",
                        AgentScopeMcpManager.normalizeBearer("Bearer MyToken")));
        assertThat(client).isNotNull();
        assertThat(receivedAuth.get()).isEqualTo("Bearer MyToken");
        client.close();
    }

    @Test
    @DisplayName("全部模式失败：抛出首异常且含各模式 suppressed（供日志定位）")
    void all_modes_failed_throws_first_with_suppressed() {
        acceptPost = false;
        assertThatThrownBy(() -> AgentScopeMcpManager.buildTransportClient(
                "burp-mcp", baseUrl(), null, null, null))
                .isInstanceOf(Exception.class)
                .satisfies(e -> {
                    Throwable t = (Throwable) e;
                    assertThat(t.getMessage()).contains("模式[sse]连接失败");
                    assertThat(t.getSuppressed()).hasSize(1);
                    assertThat(t.getSuppressed()[0].getMessage()).contains("模式[streamable]连接失败");
                });
    }
}
