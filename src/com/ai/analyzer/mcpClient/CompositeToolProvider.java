package com.ai.analyzer.mcpClient;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将多个 ToolProvider 组合为一个统一的 ToolProvider。
 * 主要用于让不同 MCP Client 可以保留各自的过滤规则（如 toolWhitelist），
 * 再统一挂载到 Assistant 上。
 */
public class CompositeToolProvider implements ToolProvider {

    private final List<ToolProvider> delegates;

    public CompositeToolProvider(List<ToolProvider> delegates) {
        this.delegates = delegates != null ? List.copyOf(delegates) : List.of();
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        Map<ToolSpecification, ToolExecutor> merged = new LinkedHashMap<>();
        for (ToolProvider delegate : delegates) {
            if (delegate == null) continue;
            ToolProviderResult result = delegate.provideTools(request);
            if (result == null || result.tools() == null || result.tools().isEmpty()) continue;
            merged.putAll(result.tools());
        }
        return new ToolProviderResult(merged);
    }
}