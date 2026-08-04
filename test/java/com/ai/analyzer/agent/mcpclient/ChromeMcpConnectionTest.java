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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chrome MCP（Streamable HTTP，/mcp 端点）连接专项测试。
 *
 * <p>模拟用户配置：{@code {"mcpServers":{"chrome":{"type":"streamable-http","url":"http://127.0.0.1:12306/mcp"}}}
 * 验证：/mcp 路径直接走 Streamable 模式、握手成功、工具列表可读、
 * 服务器不可达/拒绝时给出含原因链的失败信息。
 */
@DisplayName("Chrome MCP /mcp 端点连接测试")
class ChromeMcpConnectionTest {

    private HttpServer server;
    private ExecutorService executor;
    private boolean acceptPost = true;
    private int rejectWithStatus = 0;
    private String requireSessionId;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String mcpUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "{}", "application/json");
            return;
        }
        if (rejectWithStatus > 0) {
            respond(ex, rejectWithStatus, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"nope\"},\"id\":null}",
                    "application/json");
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
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
        String resp;
        if ("initialize".equals(method)) {
            if (requireSessionId != null) {
                ex.getResponseHeaders().set("Mcp-Session-Id", requireSessionId);
            }
            resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                    + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"chrome-mock\",\"version\":\"1.0.0\"}}}";
        } else if ("tools/list".equals(method)) {
            resp = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                    + ",\"result\":{\"tools\":[{\"name\":\"navigate\",\"description\":\"open a page\"}]}}";
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
    @DisplayName("/mcp 端点（streamable-http）：直接走 Streamable 模式握手成功")
    void mcp_endpoint_streamable_handshake_succeeds() throws Exception {
        McpClientWrapper client = AgentScopeMcpManager.buildTransportClient(
                "chrome-mcp", mcpUrl(), "streamableHttp", null, null);
        assertThat(client).isNotNull();
        assertThat(client.isInitialized()).isTrue();
        var tools = client.listTools().block();
        assertThat(tools).isNotNull();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("navigate");
        client.close();
    }

    @Test
    @DisplayName("URL 以 /mcp 结尾：仅尝试 Streamable，不尝试 SSE")
    void mcp_suffix_resolves_to_streamable_only() {
        String[] modes = AgentScopeMcpManager.resolveTransportModes(
                "http://127.0.0.1:12306/mcp", "streamableHttp");
        assertThat(modes).containsExactly("streamable");
    }

    @Test
    @DisplayName("服务器对 POST 返回 404：失败信息含模式前缀（定位连不上的根因）")
    void server_404_failure_contains_mode_info() {
        rejectWithStatus = 404;
        assertThatThrownBy(() -> AgentScopeMcpManager.buildTransportClient(
                "chrome-mcp", mcpUrl(), "streamableHttp", null, null))
                .isInstanceOf(Exception.class)
                .satisfies(e -> {
                    String msg = ((Throwable) e).getMessage();
                    assertThat(msg).contains("模式[streamable]连接失败");
                });
    }

    @Test
    @DisplayName("服务器要求 Mcp-Session-Id 时正常续用（无额外处理也不报错）")
    void session_id_server_still_connects() throws Exception {
        requireSessionId = "sess-abc-123";
        McpClientWrapper client = AgentScopeMcpManager.buildTransportClient(
                "chrome-mcp", mcpUrl(), "streamableHttp", null, null);
        assertThat(client).isNotNull();
        assertThat(client.isInitialized()).isTrue();
        client.close();
    }

    @Test
    @DisplayName("端口不可达：连接失败且异常信息可用于定位")
    void unreachable_port_fails_cleanly() {
        assertThatThrownBy(() -> AgentScopeMcpManager.buildTransportClient(
                "chrome-mcp", "http://127.0.0.1:1/mcp", "streamableHttp", null, null))
                .isInstanceOf(Exception.class)
                .satisfies(e -> assertThat(((Throwable) e).getMessage()).contains("模式[streamable]连接失败"));
    }
}
