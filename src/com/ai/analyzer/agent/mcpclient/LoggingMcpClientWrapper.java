package com.ai.analyzer.agent.mcpclient;

import com.ai.analyzer.util.AppLogBuffer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在 MCP 客户端外再包一层，把每次工具调用记入 Logger（AppLogBuffer.McpTrafficEntry）。
 *
 * <p>AgentScope 的 {@code Toolkit.registerMcpClient} 会基于 wrapper 的 listTools/callTool
 * 注册工具，因此只要在注册前套上本类，所有 MCP 工具调用（含 Burp MCP）都会出现在
 * 「Logger」标签页；未包装时 recordMcpRequest/Response 没有任何调用方，页面恒为空。
 */
final class LoggingMcpClientWrapper extends McpClientWrapper {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final int MAX_ARGS_CHARS = 20_000;
    private static final int MAX_RESULT_CHARS = 64_000;

    private final McpClientWrapper delegate;
    private final String source;

    LoggingMcpClientWrapper(String source, McpClientWrapper delegate) {
        super(delegate.getName());
        this.delegate = delegate;
        this.source = source == null || source.isBlank() ? "mcp" : source;
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
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools();
    }

    @Override
    public McpSchema.Tool getCachedTool(String name) {
        return delegate.getCachedTool(name);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> args) {
        return callTool(toolName, args, null);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> args,
                                                   Map<String, Object> meta) {
        // defer：每次订阅只记录一条，避免 Mono 复用导致重复计数
        return Mono.defer(() -> {
            String requestId = "mcp-" + SEQ.incrementAndGet() + "-" + toolName;
            long startNanos = System.nanoTime();
            String argsText = abbreviate(safeJson(args), MAX_ARGS_CHARS);
            AppLogBuffer.recordMcpRequest(requestId, source, toolName, targetOf(args), "", argsText);
            Mono<McpSchema.CallToolResult> call = meta == null
                    ? delegate.callTool(toolName, args)
                    : delegate.callTool(toolName, args, meta);
            return call
                    .doOnSuccess(result -> AppLogBuffer.recordMcpResponse(requestId, source, toolName,
                            abbreviate(resultText(result), MAX_RESULT_CHARS),
                            result != null && Boolean.TRUE.equals(result.isError()),
                            elapsed(startNanos)))
                    .doOnError(error -> AppLogBuffer.recordMcpResponse(requestId, source, toolName,
                            String.valueOf(error), true, elapsed(startNanos)));
        });
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static String elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000 + " ms";
    }

    private static String safeJson(Object value) {
        try {
            return GSON.toJson(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String resultText(McpSchema.CallToolResult result) {
        if (result == null) return "";
        StringBuilder sb = new StringBuilder();
        List<McpSchema.Content> content = result.content();
        if (content != null) {
            for (McpSchema.Content item : content) {
                if (item instanceof McpSchema.TextContent text) {
                    sb.append(text.text()).append('\n');
                } else if (item != null) {
                    sb.append(item).append('\n');
                }
            }
        }
        Object structured = result.structuredContent();
        if (structured != null) {
            sb.append("structured: ").append(safeJson(structured)).append('\n');
        }
        return sb.toString();
    }

    /** 优先取参数里的 URL/host 作为表格「目标」列，找不到就用工具名。 */
    private static String targetOf(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        for (String key : List.of("url", "target", "host", "endpoint", "uri")) {
            Object value = args.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        for (Object value : args.values()) {
            String text = String.valueOf(value);
            if (text.startsWith("http://") || text.startsWith("https://")) {
                return text;
            }
        }
        return "";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + " …";
    }
}
