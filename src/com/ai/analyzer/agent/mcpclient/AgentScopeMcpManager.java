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
 *
 * <p><b>类加载器说明</b>：MCP SDK 通过 {@code ServiceLoader.load(McpJsonMapperSupplier.class)}
 * 发现 JSON 序列化实现（jackson2 适配器）。该单参重载使用
 * {@code Thread.currentThread().getContextClassLoader()}。在 Burp 环境中注册通常发生在
 * EDT / 扩展初始化线程上，其 context classloader 是 Burp 主类加载器，找不到扩展 jar 内的
 * SPI 实现，导致 {@code No default McpJsonMapper implementation found}。
 * 因此所有注册路径统一切换 context classloader 为扩展类加载器。
 */
public class AgentScopeMcpManager {

    // 本地 MCP 服务器（127.0.0.1）响应应在秒级内；
    // 短超时让"SSE 失败 → 回退 Streamable HTTP"的尝试序列快速完成，避免每次失败干等 30s+
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

    private AgentScopeMcpManager() {}

    /**
     * 规范化 Authorization 值：若已含 "Bearer " 前缀（不区分大小写）则原样透传，
     * 否则补上前缀。避免用户填 "Bearer xxx" 时被拼成 "Bearer Bearer xxx" 导致 401。
     */
    public static String normalizeBearer(String authorization) {
        if (authorization == null) return null;
        String trimmed = authorization.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7)
                ? trimmed
                : "Bearer " + trimmed;
    }

    /**
     * 推导传输模式序列（按尝试顺序）。
     *
     * <ul>
     *   <li>{@code stdio} 传输类型 → 仅 stdio</li>
     *   <li>URL 含 "streamable" 或以 {@code /mcp} 结尾 → 仅 Streamable HTTP</li>
     *   <li>URL 以 {@code /sse} 结尾 → 仅 SSE</li>
     *   <li>其他（含根路径 {@code /}，如 BurpMCP-Ultra）→ SSE 优先，失败回退 Streamable HTTP</li>
     * </ul>
     */
    static String[] resolveTransportModes(String url, String transportType) {
        if (transportType != null && transportType.equalsIgnoreCase("stdio")) {
            return new String[]{"stdio"};
        }
        if (url == null) {
            return new String[]{"sse"};
        }
        String u = url.toLowerCase();
        if (u.contains("streamable") || u.endsWith("/mcp")) {
            return new String[]{"streamable"};
        }
        if (u.endsWith("/sse")) {
            return new String[]{"sse"};
        }
        // 根路径或未知端点：SSE 优先，失败回退 Streamable HTTP（同一 URL，不做路径改写）
        return new String[]{"sse", "streamable"};
    }

    private static McpClientBuilder baseBuilder(String serverName,
            java.util.function.Consumer<McpClientBuilder> configure) {
        McpClientBuilder builder = McpClientBuilder.create(serverName)
                .timeout(DEFAULT_TIMEOUT)
                .initializationTimeout(DEFAULT_INIT_TIMEOUT);
        if (configure != null) {
            configure.accept(builder);
        }
        return builder;
    }

    /**
     * 按 {@link #resolveTransportModes} 的模式序列尝试构建并初始化 MCP 客户端，
     * 前一个模式失败时记录为 suppressed 并尝试下一个；全部失败抛出首个异常。
     */
    static McpClientWrapper buildTransportClient(String serverName, String url,
            String transportType, List<String> args,
            java.util.function.Consumer<McpClientBuilder> configure) throws Exception {
        String[] modes = resolveTransportModes(url, transportType);
        Exception firstFailure = null;
        for (String mode : modes) {
            try {
                // 必须先设置传输再应用 configure：header() 仅在 transportConfig 为
                // HttpTransportConfig 时生效，先 configure 后设传输会导致 Authorization 头被静默丢弃
                McpClientBuilder builder = switch (mode) {
                    case "stdio" -> baseBuilder(serverName, null).stdioTransport(url.trim(),
                            args != null ? args.toArray(new String[0]) : new String[0]);
                    case "streamable" -> baseBuilder(serverName, null).streamableHttpTransport(url.trim());
                    default -> baseBuilder(serverName, null).sseTransport(url.trim());
                };
                if (configure != null) {
                    configure.accept(builder);
                }
                McpClientWrapper client = builder.buildSync();
                client.initialize().block();
                return client;
            } catch (Exception e) {
                Exception wrapped = new Exception("模式[" + mode + "]连接失败: " + url, e);
                if (firstFailure == null) {
                    firstFailure = wrapped;
                } else {
                    firstFailure.addSuppressed(wrapped);
                }
            }
        }
        throw firstFailure != null ? firstFailure
                : new RuntimeException("无法构建 MCP 客户端: " + url);
    }

    /**
     * 生成完整失败描述：主异常 + 各模式 suppressed 异常及原因链，用于日志定位。
     */
    private static String describeFailure(Throwable e) {
        StringBuilder sb = new StringBuilder();
        appendCause(sb, e);
        Throwable[] suppressed = e.getSuppressed();
        for (int i = 0; i < suppressed.length; i++) {
            sb.append("\n  ").append(i + 1).append(") ");
            appendCause(sb, suppressed[i]);
        }
        return sb.toString();
    }

    private static void appendCause(StringBuilder sb, Throwable t) {
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && cause != t && depth++ < 8) {
            sb.append("\n      ← ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
    }

    /**
     * 在扩展类加载器上下文中执行，确保 MCP SDK 的 ServiceLoader SPI 可见。
     */
    private static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader extension = AgentScopeMcpManager.class.getClassLoader();
        if (original == extension) {
            return action.call();
        }
        thread.setContextClassLoader(extension);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

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
            withExtensionClassLoader(() -> {
                // 传输协议按 URL 自动选择；根路径（如 BurpMCP-Ultra）SSE 优先并回退 Streamable HTTP
                McpClientWrapper client = buildTransportClient("burp-mcp", url, null, null,
                        builder -> {
                            String bearer = normalizeBearer(authorization);
                            if (bearer != null) {
                                builder.header("Authorization", bearer);
                            }
                        });

                toolkit.registerMcpClient(client).block();
                clients.add(client);
                return null;
            });
            AppLogBuffer.info("AgentScopeMcpManager", "Burp MCP registered: " + url);
            DebugContext.log("AgentScopeMcpManager", "burp_mcp_registered", Map.of("url", url));
        } catch (Exception e) {
            AppLogBuffer.info("AgentScopeMcpManager",
                    "Burp MCP registration failed (" + url + "): " + describeFailure(e));
            DebugContext.log("AgentScopeMcpManager", "burp_mcp_failed",
                    Map.of("url", url, "error", describeFailure(e)));
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
            McpClientWrapper client = withExtensionClassLoader(() ->
                    buildTransportClient(serverName.trim(), urlOrCommand.trim(), transportType, args,
                            builder -> {
                                if (headers != null) {
                                    for (var entry : headers.entrySet()) {
                                        builder.header(entry.getKey(), entry.getValue());
                                    }
                                }
                            }));
            AppLogBuffer.info("AgentScopeMcpManager", "Custom MCP registered: " + serverName + " (" + transportType + ")");
            DebugContext.log("AgentScopeMcpManager", "custom_mcp_registered", Map.of("server", serverName, "transport", String.valueOf(transportType)));
            return client;
        } catch (Exception e) {
            AppLogBuffer.info("AgentScopeMcpManager",
                    "Custom MCP registration failed (" + serverName + "): " + describeFailure(e));
            DebugContext.log("AgentScopeMcpManager", "custom_mcp_failed",
                    Map.of("server", serverName, "error", describeFailure(e)));
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
        return registerCustomMcp(toolkit, "chrome-mcp", "streamableHttp", chromeMcpUrl.trim(), null, null);
    }

    /**
     * 对单个自定义 MCP 配置做连通性测试：构建客户端并完成 initialize 握手，
     * 成功后立即关闭，不注册到 Toolkit。用于 UI「验证配置」按钮。
     *
     * @return 空串表示连通正常；否则返回失败原因（含模式与原因链）
     */
    public static String testConnection(CustomMcpConfig config) {
        if (config == null) return "配置为空";
        if (!config.isValid()) return "配置字段不完整（type 所需的 url 或 command 缺失）";

        String transportType = config.getRawType();
        String url = config.getUrl();
        List<String> command = config.getCommand();
        String urlOrCommand = url != null && !url.isEmpty() ? url
                : (!command.isEmpty() ? command.get(0) : "");
        List<String> args = command.size() > 1 ? command.subList(1, command.size()) : List.of();

        try {
            McpClientWrapper client = withExtensionClassLoader(() ->
                    buildTransportClient(config.getName(), urlOrCommand, transportType, args, null));
            try {
                if (client.isInitialized()) {
                    return "";
                }
                return "客户端已构建但未完成初始化";
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return describeFailure(e);
        }
    }
}