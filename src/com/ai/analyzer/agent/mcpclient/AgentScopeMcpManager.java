package com.ai.analyzer.agent.mcpclient;

import com.ai.analyzer.util.AppLogBuffer;
import com.ai.analyzer.util.DebugContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 原生 MCP 客户端管理器。
 *
 * <p>与 {@link AllMcpToolProvider} 并行存在，将相同的 MCP 配置（Burp MCP、自定义 MCP 服务器等）
 * 转换为 AgentScope 的 {@link McpClientWrapper} 并注册到 {@link Toolkit}。
 *
 * <p>迁移完成后，{@link AllMcpToolProvider} 将被移除，此类成为唯一的 MCP 管理器。
 */
public class AgentScopeMcpManager {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(60);

    private AgentScopeMcpManager() {}

    /**
     * 为 Burp MCP 服务器创建客户端并注册到 Toolkit。
     *
     * @param toolkit        AgentScope Toolkit
     * @param burpMcpUrl     Burp MCP 服务器 URL（SSE 或 Streamable HTTP）
     * @param authorization  Authorization header 值（可选）
     * @return 注册的 McpClientWrapper 列表
     */
    public static List<McpClientWrapper> registerBurpMcp(
            Toolkit toolkit, String burpMcpUrl, String authorization) {

        List<McpClientWrapper> clients = new ArrayList<>();
        if (burpMcpUrl == null || burpMcpUrl.trim().isEmpty()) return clients;

        String url = burpMcpUrl.trim();
        try {
            McpClientBuilder builder = McpClientBuilder.create("burp-mcp")
                    .timeout(DEFAULT_TIMEOUT)
                    .initializationTimeout(DEFAULT_INIT_TIMEOUT);

            if (authorization != null && !authorization.trim().isEmpty()) {
                builder.header("Authorization", "Bearer " + authorization.trim());
            }

            // 根据 URL 选择传输协议
            McpClientWrapper client;
            if (url.endsWith("/sse")) {
                client = builder.sseTransport(url).buildSync();
            } else if (url.endsWith("/mcp") || url.contains("streamable")) {
                client = builder.streamableHttpTransport(url).buildSync();
            } else {
                // 默认尝试 SSE
                client = builder.sseTransport(url).buildSync();
            }

            client.initialize().block();
            toolkit.registerMcpClient(client).block();
            clients.add(client);
            AppLogBuffer.info("AgentScopeMcpManager", "Burp MCP registered: " + url);
            DebugContext.log("AgentScopeMcpManager", "burp_mcp_registered", Map.of("url", url));
        } catch (Exception e) {
            AppLogBuffer.info("AgentScopeMcpManager", "Burp MCP registration failed (" + url + "): " + e.getMessage());
            DebugContext.log("AgentScopeMcpManager", "burp_mcp_failed", Map.of("url", url, "error", e.getMessage()));
        }

        return clients;
    }

    /**
     * 为自定义 MCP 服务器创建客户端并注册到 Toolkit。
     *
     * @param toolkit      AgentScope Toolkit
     * @param serverName   服务器名称
     * @param transportType 传输类型 ("sse", "streamableHttp", "stdio")
     * @param urlOrCommand  URL 或命令
     * @param args         stdio 参数（仅 stdio 传输）
     * @param headers      HTTP headers（仅 SSE/Streamable HTTP）
     * @return 注册的 McpClientWrapper，失败返回 null
     */
    public static McpClientWrapper registerCustomMcp(
            Toolkit toolkit, String serverName, String transportType,
            String urlOrCommand, List<String> args, Map<String, String> headers) {

        if (serverName == null || serverName.trim().isEmpty()) return null;
        if (urlOrCommand == null || urlOrCommand.trim().isEmpty()) return null;

        try {
            McpClientBuilder builder = McpClientBuilder.create(serverName.trim())
                    .timeout(DEFAULT_TIMEOUT)
                    .initializationTimeout(DEFAULT_INIT_TIMEOUT);

            if (headers != null) {
                for (var entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            McpClientWrapper client = switch (transportType != null ? transportType.toLowerCase() : "") {
                case "stdio" -> builder.stdioTransport(urlOrCommand.trim(),
                        args != null ? args.toArray(new String[0]) : new String[0]).buildSync();
                case "streamablehttp", "streamable-http" ->
                        builder.streamableHttpTransport(urlOrCommand.trim()).buildSync();
                default -> builder.sseTransport(urlOrCommand.trim()).buildSync();
            };

            client.initialize().block();
            toolkit.registerMcpClient(client).block();
            AppLogBuffer.info("AgentScopeMcpManager", "Custom MCP registered: " + serverName + " (" + transportType + ")");
            DebugContext.log("AgentScopeMcpManager", "custom_mcp_registered", Map.of("server", serverName, "transport", String.valueOf(transportType)));
            return client;
        } catch (Exception e) {
            AppLogBuffer.info("AgentScopeMcpManager", "Custom MCP registration failed (" + serverName + "): " + e.getMessage());
            DebugContext.log("AgentScopeMcpManager", "custom_mcp_failed", Map.of("server", serverName, "error", e.getMessage()));
            return null;
        }
    }

    /**
     * 为 RAG MCP 服务器创建客户端并注册到 Toolkit。
     */
    public static McpClientWrapper registerRagMcp(Toolkit toolkit, String ragMcpUrl) {
        if (ragMcpUrl == null || ragMcpUrl.trim().isEmpty()) return null;
        return registerCustomMcp(toolkit, "rag-mcp", "sse", ragMcpUrl.trim(), null, null);
    }

    /**
     * 为 Chrome MCP 服务器创建客户端并注册到 Toolkit。
     */
    public static McpClientWrapper registerChromeMcp(Toolkit toolkit, String chromeMcpUrl) {
        if (chromeMcpUrl == null || chromeMcpUrl.trim().isEmpty()) return null;
        return registerCustomMcp(toolkit, "chrome-mcp", "sse", chromeMcpUrl.trim(), null, null);
    }
}