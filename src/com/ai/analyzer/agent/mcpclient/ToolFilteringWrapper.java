package com.ai.analyzer.agent.mcpclient;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按名称过滤 MCP 工具的包装：listTools 只暴露未屏蔽的工具，
 * 被屏蔽工具的 getCachedTool 返回 null（模型请求会得到「工具不存在」的报错，
 * 而不是拿到一个可用但经常 60 秒超时的工具）。
 *
 * <p>用途：Burp MCP 的发包工具（http_send_request / http_send_requests_parallel /
 * http_race 等）经常性 60s 超时挂死（Reactor 无 fallback），默认屏蔽，
 * 迫使 agent 优先用命令行工具（execute + curl）发请求——最可控、可超时、可带 UA。
 */
final class ToolFilteringWrapper extends McpClientWrapper {

    private final McpClientWrapper delegate;
    private final Set<String> blocked;

    ToolFilteringWrapper(McpClientWrapper delegate, Set<String> blockedTools) {
        super(delegate.getName());
        this.delegate = delegate;
        this.blocked = Set.copyOf(blockedTools);
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
        return delegate.listTools().map(tools -> {
            if (blocked.isEmpty()) return tools;
            List<McpSchema.Tool> visible = new ArrayList<>(tools.size());
            int removed = 0;
            for (McpSchema.Tool t : tools) {
                if (t == null || t.name() == null) continue;
                if (blocked.contains(t.name().toLowerCase(Locale.ROOT))) {
                    removed++;
                    continue;
                }
                visible.add(t);
            }
            if (removed > 0) {
                com.ai.analyzer.util.AppLogBuffer.info("AgentScopeMcpManager",
                        "已屏蔽 " + removed + " 个 Burp MCP 发包工具（http_send_request 等，"
                                + "设置里可关闭屏蔽）: " + blocked);
            }
            return visible;
        });
    }

    @Override
    public McpSchema.Tool getCachedTool(String name) {
        if (name != null && blocked.contains(name.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return delegate.getCachedTool(name);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return delegate.callTool(toolName, arguments);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments,
                                                   Map<String, Object> meta) {
        return delegate.callTool(toolName, arguments, meta);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
