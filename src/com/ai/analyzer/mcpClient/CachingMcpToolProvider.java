package com.ai.analyzer.mcpClient;

import com.ai.analyzer.utils.ArtifactCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * MCP 工具返回结果的大响应缓存代理层。
 * <p>
 * LangChain4j 1.16+ 的 {@link ToolProvider} 接口通过 {@link #provideTools(ToolProviderRequest)}
 * 返回 {@link ToolProviderResult}，其中包含从 {@link ToolSpecification} 到 {@link ToolExecutor}
 * 的映射。本类包装一个现有的 {@link ToolProvider}，用缓存代理替换其 {@link ToolExecutor}，
 * 在工具执行返回前对超大结果进行后处理：
 * <ol>
 *   <li>阈值判断：超过阈值才缓存</li>
 *   <li>字段级缓存（针对 Burp MCP HTTP 工具）：只缓存 body / response 大字段，保留 status、headers 等元数据</li>
 *   <li>纯文本缓存（针对其他 MCP 工具）：保留预览 + fileId 引用</li>
 * </ol>
 * 模型收到 fileId 后，可调用已注册的 {@code read_cache_artifact} / {@code search_cache_artifact} 工具按需读取完整内容。
 */
public class CachingMcpToolProvider implements ToolProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 默认缓存阈值字符数 */
    private static final int DEFAULT_THRESHOLD_CHARS = 12_000;
    /** 字段级缓存时 body 字段单独阈值 */
    private static final int DEFAULT_BODY_THRESHOLD_CHARS = 6_000;
    /** 返回给模型的预览字符数 */
    private static final int PREVIEW_CHARS = 2_000;
    /** 字段级缓存时替换后保留的字符数 */
    private static final int BODY_PREVIEW_CHARS = 1_500;

    private final ToolProvider delegate;
    private final int defaultThresholdChars;
    private final int bodyThresholdChars;
    private final Function<String, Integer> thresholdProvider;

    /** 已知可能产生大返回的 Burp MCP HTTP 相关工具 */
    private static final Set<String> BURP_HTTP_TOOLS = Set.of(
            "send_http1_request",
            "send_http2_request",
            "get_proxy_http_history",
            "get_proxy_http_history_regex",
            "response_body_search",
            "get_scanner_issues",
            "get_active_editor_contents",
            "site_map",
            "site_map_regex"
    );

    /** 按工具名覆盖阈值的映射（当同一 provider 里有不同工具类型时很有用） */
    private static final Map<String, Integer> THRESHOLD_BY_TOOL = Map.of(
            "send_http1_request", 8_000,
            "send_http2_request", 8_000,
            "get_proxy_http_history", 6_000,
            "get_proxy_http_history_regex", 6_000,
            "response_body_search", 6_000,
            "query_document", 6_000
    );

    public CachingMcpToolProvider(ToolProvider delegate) {
        this(delegate, DEFAULT_THRESHOLD_CHARS);
    }

    public CachingMcpToolProvider(ToolProvider delegate, int defaultThresholdChars) {
        this(delegate, defaultThresholdChars, DEFAULT_BODY_THRESHOLD_CHARS);
    }

    public CachingMcpToolProvider(ToolProvider delegate, int defaultThresholdChars, int bodyThresholdChars) {
        this.delegate = delegate;
        this.defaultThresholdChars = Math.max(1_000, defaultThresholdChars);
        this.bodyThresholdChars = Math.max(1_000, bodyThresholdChars);
        this.thresholdProvider = toolName -> THRESHOLD_BY_TOOL.getOrDefault(toolName, this.defaultThresholdChars);
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult result = delegate.provideTools(request);
        if (result == null) {
            return null;
        }
        Map<ToolSpecification, ToolExecutor> wrappedTools = new LinkedHashMap<>();
        for (Map.Entry<ToolSpecification, ToolExecutor> entry : result.tools().entrySet()) {
            ToolSpecification spec = entry.getKey();
            ToolExecutor executor = entry.getValue();
            wrappedTools.put(spec, new CachingToolExecutor(spec.name(), executor));
        }
        return new ToolProviderResult(wrappedTools);
    }

    private final class CachingToolExecutor implements ToolExecutor {
        private final String toolName;
        private final ToolExecutor delegate;

        CachingToolExecutor(String toolName, ToolExecutor delegate) {
            this.toolName = toolName;
            this.delegate = delegate;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            String result = delegate.execute(request, memoryId);
            return maybeCache(toolName, result);
        }
    }

    private String maybeCache(String toolName, String result) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        // 1) Burp MCP HTTP 工具：尝试字段级缓存
        if (isBurpHttpTool(toolName)) {
            String structured = maybeCacheStructuredHttp(toolName, result);
            if (structured != null && !structured.equals(result)) {
                return structured;
            }
        }

        // 2) 通用纯文本缓存
        return maybeCachePlainText(toolName, result);
    }

    private String maybeCachePlainText(String toolName, String text) {
        int threshold = thresholdProvider.apply(toolName == null ? "" : toolName);
        if (text.length() <= threshold) {
            return text;
        }

        try {
            ArtifactCache.ArtifactRef ref = ArtifactCache.saveText(text, labelFor(toolName));
            String preview = text.substring(0, Math.min(PREVIEW_CHARS, text.length()));
            return preview
                    + "\n\n...[MCP 工具返回内容已缓存，完整长度 "
                    + text.length()
                    + " 字符]...\n"
                    + ref.toPromptText();
        } catch (Exception e) {
            int fallbackLimit = Math.min(threshold, text.length());
            return "[缓存 MCP 结果失败: " + e.getMessage() + "]\n\n"
                    + text.substring(0, fallbackLimit)
                    + "\n...[原始结果过长，截断]...";
        }
    }

    /**
     * 对 Burp MCP HTTP 返回做字段级缓存：只缓存 body / response 大字段，保留 status、headers 等摘要。
     * 如果解析失败或没有大字段，返回 null 让调用方走纯文本缓存。
     */
    private String maybeCacheStructuredHttp(String toolName, String text) {
        int threshold = thresholdProvider.apply(toolName);
        if (text.length() <= threshold) {
            return null; // 整体未超限，不做缓存
        }

        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (json == null || json.isJsonNull()) {
                return null;
            }

            String[] bigKeys = {"body", "response", "responseBody", "content", "rawResponse", "rawHttp"};
            boolean cachedAny = false;
            StringBuilder summary = new StringBuilder();
            summary.append("[MCP HTTP 工具返回已做字段级缓存]\n");

            for (String key : bigKeys) {
                if (!json.has(key)) continue;
                JsonElement el = json.get(key);
                if (el == null || el.isJsonNull()) continue;
                if (!el.isJsonPrimitive()) continue;

                JsonPrimitive primitive = el.getAsJsonPrimitive();
                if (!primitive.isString()) continue;

                String value = primitive.getAsString();
                if (value.length() <= bodyThresholdChars) continue;

                ArtifactCache.ArtifactRef ref = ArtifactCache.saveText(value, "mcp-" + labelFor(toolName) + "-" + key);
                String bodyPreview = value.substring(0, Math.min(BODY_PREVIEW_CHARS, value.length()));
                json.addProperty(key, bodyPreview + "...[该字段已缓存，可通过 fileId 分段读取]...");
                summary.append("字段 '").append(key).append("' 已缓存（长度 ")
                        .append(value.length()).append("）\n")
                        .append(ref.toPromptText()).append("\n");
                cachedAny = true;
            }

            if (!cachedAny) {
                return null; // 没有大字段，让外层整体缓存
            }

            return summary.append("\n[结构化摘要]\n")
                    .append(GSON.toJson(json))
                    .toString();
        } catch (Exception ignored) {
            return null; // 解析失败，让外层整体缓存
        }
    }

    private boolean isBurpHttpTool(String toolName) {
        if (toolName == null) return false;
        String lower = toolName.toLowerCase();
        return BURP_HTTP_TOOLS.contains(lower)
                || lower.contains("http")
                || lower.contains("response")
                || lower.contains("request");
    }

    private static String labelFor(String toolName) {
        if (toolName == null || toolName.isBlank()) return "mcp-output";
        String safe = toolName.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "mcp-output" : safe;
    }
}
