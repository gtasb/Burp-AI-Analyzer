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
    /** stdio 服务可能要先下载/启动（如 uvx），保留较长的初始化预算。 */
    private static final Duration STDIO_INIT_TIMEOUT = Duration.ofSeconds(30);
    /** HTTP MCP 握手：本地 5s / 远程 8s，首次分析最多被单个坏端点拖这么久。 */
    private static final Duration LOCAL_INIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REMOTE_INIT_TIMEOUT = Duration.ofSeconds(8);

    private AgentScopeMcpManager() {}

    /** 同一 MCP 端点注册计数（主动/被动共享连接后，>1 表示多 toolkit 复用） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> endpointRegistrations =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 按端点共享的 MCP 客户端：同一端点全插件只建一条连接（主动/被动注册同一 wrapper，根除双连接互相踢线） */
    private static final java.util.concurrent.ConcurrentHashMap<String, McpClientWrapper> SHARED_CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 共享连接的引用计数。每个 HarnessAgent 关闭时都会调用 {@code toolkit.closeMcpClients()}，
     * 若直接关闭共享连接会把其它 agent 仍在使用的 MCP 一起关掉；这里由最后一次释放真正关闭。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> SHARED_REFS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 获取共享连接并为当前 toolkit 增加一次引用。 */
    private static McpClientWrapper acquireSharedClient(String key, CheckedSupplier<McpClientWrapper> factory)
            throws Exception {
        McpClientWrapper shared = endpointClient(key, factory);
        SHARED_REFS.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        return new SharedHandle(key, shared);
    }

    /** 释放一次引用；归零时才真正关闭底层连接。 */
    static void releaseSharedClient(String key) {
        java.util.concurrent.atomic.AtomicInteger refs = SHARED_REFS.get(key);
        if (refs == null) return;
        if (refs.decrementAndGet() <= 0) {
            SHARED_REFS.remove(key, refs);
            McpClientWrapper client = SHARED_CLIENTS.remove(key);
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 插件卸载：绕过引用计数，强制关闭全部共享连接。 */
    public static void closeAllSharedClients() {
        for (String key : new java.util.ArrayList<>(SHARED_CLIENTS.keySet())) {
            SHARED_REFS.remove(key);
            McpClientWrapper client = SHARED_CLIENTS.remove(key);
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 引用计数包装：Toolkit 关闭只减引用，不直接关闭共享连接。 */
    private static final class SharedHandle extends McpClientWrapper {
        private final String key;
        private final McpClientWrapper delegate;
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        SharedHandle(String key, McpClientWrapper delegate) {
            super(delegate.getName());
            this.key = key;
            this.delegate = delegate;
        }

        @Override
        public boolean isInitialized() {
            return delegate.isInitialized();
        }

        @Override
        public boolean isPropagateMeta() {
            return delegate.isPropagateMeta();
        }

        @Override
        public void setPropagateMeta(boolean propagateMeta) {
            delegate.setPropagateMeta(propagateMeta);
        }

        @Override
        public reactor.core.publisher.Mono<Void> initialize() {
            return delegate.initialize();
        }

        @Override
        public reactor.core.publisher.Mono<List<io.modelcontextprotocol.spec.McpSchema.Tool>> listTools() {
            return delegate.listTools();
        }

        @Override
        public io.modelcontextprotocol.spec.McpSchema.Tool getCachedTool(String name) {
            return delegate.getCachedTool(name);
        }

        @Override
        public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments) {
            return delegate.callTool(toolName, arguments);
        }

        @Override
        public reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            return delegate.callTool(toolName, arguments, meta);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseSharedClient(key);
            }
        }
    }

    private static void recordRegistration(String endpointKey) {
        int count = endpointRegistrations.merge(endpointKey, 1, Integer::sum);
        if (count == 1) {
            AppLogBuffer.info("AgentScopeMcpManager", "已建立共享 MCP 连接: " + endpointKey);
        } else if (count == 2) {
            AppLogBuffer.info("AgentScopeMcpManager",
                    "MCP 端点被多个 toolkit 复用共享连接（主动/被动共用，不再重复建连）: " + endpointKey);
        }
    }

    /**
     * 取（或创建）某端点的共享客户端：已缓存且已初始化的直接复用；
     * 缓存连接失效（isInitialized=false）时丢弃重建，避免“死连接一直粘住”。
     */
    private static McpClientWrapper endpointClient(String key, CheckedSupplier<McpClientWrapper> factory) throws Exception {
        McpClientWrapper existing = SHARED_CLIENTS.get(key);
        if (existing != null && existing.isInitialized()) {
            return existing;
        }
        if (existing != null) {
            SHARED_CLIENTS.remove(key, existing);
            try {
                existing.close();
            } catch (Exception ignored) {
            }
        }
        McpClientWrapper created = factory.get();
        McpClientWrapper raced = SHARED_CLIENTS.putIfAbsent(key, created);
        if (raced != null) {
            // 并发初始化竞争失败：关闭自己刚建的客户端，避免泄漏一条 MCP 连接
            try {
                created.close();
            } catch (Exception ignored) {
            }
            return raced;
        }
        return created;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

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
     * 合并自定义配置的额外请求头与 Authorization（自动补 Bearer 前缀）。
     * 注册、验证、共享客户端 key 都必须用同一份结果，否则 header 认证的服务器会 401。
     */
    public static Map<String, String> headersOf(CustomMcpConfig config) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (config == null) return headers;
        headers.putAll(config.getHeaders());
        String auth = normalizeBearer(config.getAuthorization());
        if (auth != null) {
            headers.put("Authorization", auth);
        }
        return headers;
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

    private static McpClientBuilder baseBuilder(String serverName, Duration initTimeout) {
        return McpClientBuilder.create(serverName)
                .timeout(DEFAULT_TIMEOUT)
                .initializationTimeout(initTimeout);
    }

    /** HTTP MCP 初始化超时：本机回环地址给 5s，其余 8s。 */
    static Duration initTimeoutFor(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        if (u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]")) {
            return LOCAL_INIT_TIMEOUT;
        }
        return REMOTE_INIT_TIMEOUT;
    }

    /**
     * 按 {@link #resolveTransportModes} 的模式序列尝试构建并初始化 MCP 客户端，
     * 前一个模式失败时记录为 suppressed 并尝试下一个；全部失败抛出首个异常。
     */
    static McpClientWrapper buildTransportClient(String serverName, String url,
            String transportType, List<String> args,
            java.util.function.Consumer<McpClientBuilder> configure) throws Exception {
        return buildTransportClient(serverName, url, transportType, args, null, configure);
    }

    /** @param env stdio 子进程的环境变量（UI 的 env 字段此前被解析但从未传给进程） */
    static McpClientWrapper buildTransportClient(String serverName, String url,
            String transportType, List<String> args, Map<String, String> env,
            java.util.function.Consumer<McpClientBuilder> configure) throws Exception {
        String[] modes = resolveTransportModes(url, transportType);
        Exception firstFailure = null;
        for (String mode : modes) {
            McpClientWrapper client = null;
            try {
                // 必须先设置传输再应用 configure：header() 仅在 transportConfig 为
                // HttpTransportConfig 时生效，先 configure 后设传输会导致 Authorization 头被静默丢弃
                Duration initTimeout = "stdio".equals(mode) ? STDIO_INIT_TIMEOUT : initTimeoutFor(url);
                McpClientBuilder builder = switch (mode) {
                    case "stdio" -> {
                        McpClientBuilder b = baseBuilder(serverName, initTimeout);
                        String[] argv = args != null ? args.toArray(new String[0]) : new String[0];
                        if (env != null && !env.isEmpty()) {
                            b.stdioTransport(url.trim(), java.util.List.of(argv), env);
                        } else {
                            b.stdioTransport(url.trim(), argv);
                        }
                        yield b;
                    }
                    case "streamable" -> baseBuilder(serverName, initTimeout).streamableHttpTransport(url.trim());
                    default -> baseBuilder(serverName, initTimeout).sseTransport(url.trim());
                };
                if (configure != null) {
                    configure.accept(builder);
                }
                long started = System.nanoTime();
                client = builder.buildSync();
                client.initialize().block();
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                if (elapsedMs > 1500) {
                    AppLogBuffer.info("AgentScopeMcpManager", "MCP 握手较慢: " + serverName
                            + " mode=" + mode + " " + elapsedMs + "ms url=" + url);
                }
                return client;
            } catch (Exception e) {
                // 构建成功但握手失败：关闭该客户端，避免每次回退尝试泄漏 socket
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
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
        return registerBurpMcp(toolkit, burpMcpUrl, authorization, "burp-mcp");
    }

    /**
     * Burp MCP 的发包工具黑名单（可经 blockedTools 参数清空）：
     * http_send_request / http_send_requests_parallel / http_race 经常性 60 秒
     * 超时挂死（Reactor 无 fallback），屏蔽后 agent 改走 curl_send_request /
     * execute+curl 等可靠通道。
     */
    public static final java.util.Set<String> BURP_SEND_TOOLS = java.util.Set.of(
            "http_send_request", "http_send_requests_parallel", "http_race",
            "http_send_request_chain", "http_send_raw_bytes");

    public static List<McpClientWrapper> registerBurpMcp(
            Toolkit toolkit, String burpMcpUrl, String authorization, String logSource) {
        return registerBurpMcp(toolkit, burpMcpUrl, authorization, logSource, BURP_SEND_TOOLS);
    }

    /** 带发包工具黑名单的注册：blockedTools 为空集合 = 全部放行。 */
    public static List<McpClientWrapper> registerBurpMcp(
            Toolkit toolkit, String burpMcpUrl, String authorization, String logSource,
            java.util.Set<String> blockedTools) {

        List<McpClientWrapper> clients = new ArrayList<>();
        if (burpMcpUrl == null || burpMcpUrl.trim().isEmpty()) return clients;

        String url = burpMcpUrl.trim();
        try {
            // key 必须包含鉴权指纹：token 更换 + runtime 重建后，旧 key 会把旧 token
            // 的连接永远复用下去（401 直到重启 Burp）。与 registerCustomMcp 同规则。
            String bearer = normalizeBearer(authorization);
            String key = "burp-mcp|" + url + "|"
                    + (bearer == null ? "" : Integer.toHexString(bearer.hashCode()));
            // 走引用计数包装（acquireSharedClient）：注册失败时 AgentScope 会自动
            // close 包装实例，裸 endpointClient 会让一次瞬时 listTools 失败把
            // 其它 agent 仍在用的共享连接关掉
            McpClientWrapper client = withExtensionClassLoader(() -> acquireSharedClient(key, () ->
                    buildTransportClient("burp-mcp", url, null, null,
                            builder -> {
                                if (bearer != null) {
                                    builder.header("Authorization", bearer);
                                }
                            })));
            if (client == null) {
                throw new RuntimeException("MCP 客户端构建失败: " + url);
            }
            boolean registered = false;
            try {
                // 发包工具黑名单（BURP_SEND_TOOLS）：默认屏蔽经常 60s 超时的
                // http_send_request 系列，迫使 agent 走 curl_send_request 等可靠通道
                McpClientWrapper wrapped = (blockedTools != null && !blockedTools.isEmpty())
                        ? new ToolFilteringWrapper(client, blockedTools) : client;
                toolkit.registerMcpClient(new LoggingMcpClientWrapper(logSource, wrapped)).block();
                registered = true;
            } finally {
                if (!registered) {
                    client.close();
                }
            }
            clients.add(client);
            recordRegistration(key);
            AppLogBuffer.info("AgentScopeMcpManager", "Burp MCP registered (shared): " + url);
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
        return registerCustomMcp(toolkit, serverName, transportType, urlOrCommand, args, headers, null,
                serverName);
    }

    public static McpClientWrapper registerCustomMcp(
            Toolkit toolkit, String serverName, String transportType,
            String urlOrCommand, List<String> args, Map<String, String> headers, String logSource) {
        return registerCustomMcp(toolkit, serverName, transportType, urlOrCommand, args, headers, null,
                logSource);
    }

    public static McpClientWrapper registerCustomMcp(
            Toolkit toolkit, String serverName, String transportType,
            String urlOrCommand, List<String> args, Map<String, String> headers,
            Map<String, String> env, String logSource) {

        if (serverName == null || serverName.trim().isEmpty()) return null;
        if (urlOrCommand == null || urlOrCommand.trim().isEmpty()) return null;

        try {
            // 共享 key 必须包含 header 与 env 指纹：同 URL 不同 token / 不同环境变量的
            // 两个配置不能复用同一条连接（stdio 下 env 变化必须重启子进程）
            String key = transportType + "|" + urlOrCommand.trim() + "|"
                    + (headers == null ? "" : Integer.toHexString(headers.hashCode()))
                    + "|" + (env == null || env.isEmpty() ? "" : Integer.toHexString(env.hashCode()));
            // 共享客户端：同一端点只建一条连接，主动/被动 toolkit 复用；多 agent 关闭时按引用计数释放
            McpClientWrapper client = withExtensionClassLoader(() -> acquireSharedClient(key, () ->
                    buildTransportClient(serverName.trim(), urlOrCommand.trim(), transportType, args, env,
                            builder -> {
                                if (headers != null) {
                                    for (var entry : headers.entrySet()) {
                                        builder.header(entry.getKey(), entry.getValue());
                                    }
                                }
                            })));
            if (client == null) {
                throw new RuntimeException("MCP 客户端构建失败: " + serverName);
            }
            boolean registered = false;
            try {
                toolkit.registerMcpClient(new LoggingMcpClientWrapper(logSource, client)).block();
                registered = true;
            } finally {
                if (!registered) {
                    client.close();
                }
            }
            recordRegistration(key);
            AppLogBuffer.info("AgentScopeMcpManager", "Custom MCP registered (shared): " + serverName + " (" + transportType + ")");
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
        return registerRagMcp(toolkit, ragMcpUrl, "rag-mcp");
    }

    public static McpClientWrapper registerRagMcp(Toolkit toolkit, String ragMcpUrl, String logSource) {
        if (ragMcpUrl == null || ragMcpUrl.trim().isEmpty()) return null;
        return registerCustomMcp(toolkit, "rag-mcp", "sse", ragMcpUrl.trim(), null, null, logSource);
    }

    /**
     * 为 Chrome MCP 服务器创建客户端并注册到 Toolkit。
     */
    public static McpClientWrapper registerChromeMcp(Toolkit toolkit, String chromeMcpUrl) {
        return registerChromeMcp(toolkit, chromeMcpUrl, "chrome-mcp");
    }

    public static McpClientWrapper registerChromeMcp(Toolkit toolkit, String chromeMcpUrl, String logSource) {
        if (chromeMcpUrl == null || chromeMcpUrl.trim().isEmpty()) return null;
        return registerCustomMcp(toolkit, "chrome-mcp", "streamableHttp", chromeMcpUrl.trim(), null, null, logSource);
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
        Map<String, String> headers = headersOf(config);

        try {
            McpClientWrapper client = withExtensionClassLoader(() ->
                    buildTransportClient(config.getName(), urlOrCommand, transportType, args,
                            builder -> headers.forEach(builder::header)));
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