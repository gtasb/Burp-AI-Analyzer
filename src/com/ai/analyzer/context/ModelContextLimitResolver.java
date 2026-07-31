package com.ai.analyzer.context;

import com.ai.analyzer.Client.AgentConfig;
import com.ai.analyzer.utils.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 从模型配置/提供商元数据中解析上下文窗口上限。
 *
 * 设计目标：
 * 1. 优先从自定义参数/模型配置中读取可能携带的上下文窗口字段；
 * 2. 如果没有显式信息，则根据 provider/model 做启发式回退；
 * 3. 所有解析都带有健壮性兜底，避免因为配置格式异常导致上下文预算失效。
 */
public class ModelContextLimitResolver {
    private static final int DEFAULT_CONTEXT_LIMIT = 128_000;
    private static final int MIN_CONTEXT_LIMIT = 4_000;

    private ModelContextLimitResolver() {}

    public static Integer resolveContextLimit(AgentConfig config) {
        if (config == null) {
            return DEFAULT_CONTEXT_LIMIT;
        }

        Integer fromExplicitMaxTokens = resolveFromExplicitMaxTokens(config.getMaxTokens());
        if (fromExplicitMaxTokens != null) {
            return fromExplicitMaxTokens;
        }

        Integer fromCustomConfig = resolveFromCustomParameters(config.getCustomParameters());
        if (fromCustomConfig != null) {
            return fromCustomConfig;
        }

        try {
            Integer fromRemoteMetadata = resolveFromRemoteMetadata(config.getApiProvider(), config.getApiUrl(), config.getModel(), config.getApiKey());
            if (fromRemoteMetadata != null) {
                return fromRemoteMetadata;
            }
        } catch (Exception ignored) {
            // 网络调用失败时继续走下游回退策略
        }

        Integer fromModelName = resolveFromModelName(config.getModel());
        if (fromModelName != null) {
            return fromModelName;
        }

        return resolveByProvider(config.getApiProvider(), config.getApiUrl(), config.getModel());
    }

    public static Integer resolveContextLimit(String customParameters, String maxTokens, String modelName, AgentConfig.ApiProvider provider, String apiUrl) {
        Integer fromExplicitMaxTokens = resolveFromExplicitMaxTokens(maxTokens);
        if (fromExplicitMaxTokens != null) {
            return fromExplicitMaxTokens;
        }

        if (customParameters != null) {
            Integer fromCustomConfig = resolveFromCustomParameters(customParameters);
            if (fromCustomConfig != null) {
                return fromCustomConfig;
            }
        }

        if (modelName != null) {
            Integer fromModelName = resolveFromModelName(modelName);
            if (fromModelName != null) {
                return fromModelName;
            }
        }

        try {
            Integer fromRemoteMetadata = resolveFromRemoteMetadata(provider, apiUrl, modelName, null);
            if (fromRemoteMetadata != null) {
                return fromRemoteMetadata;
            }
        } catch (Exception ignored) {
            // ignore
        }

        return resolveByProvider(provider, apiUrl, modelName);
    }

    public static Integer resolveFromExplicitMaxTokens(String maxTokens) {
        if (maxTokens == null || maxTokens.isBlank()) {
            return null;
        }
        Integer parsed = parseInteger(maxTokens);
        if (parsed != null && parsed > 0) {
            return normalize(parsed);
        }
        return null;
    }

    public static Integer resolveFromCustomParameters(String customParameters) {
        if (customParameters == null || customParameters.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> params = JsonParser.parseJsonToMap(customParameters);
            if (params == null || params.isEmpty()) {
                return null;
            }

            Object maxTokens = params.get("max_tokens");
            if (maxTokens != null) {
                Integer parsed = parseInteger(maxTokens);
                if (parsed != null && parsed > 0) {
                    return normalize(parsed);
                }
            }

            Object contextWindow = params.get("context_window");
            if (contextWindow != null) {
                Integer parsed = parseInteger(contextWindow);
                if (parsed != null && parsed > 0) {
                    return normalize(parsed);
                }
            }

            Object contextWindowTokens = params.get("context_window_tokens");
            if (contextWindowTokens != null) {
                Integer parsed = parseInteger(contextWindowTokens);
                if (parsed != null && parsed > 0) {
                    return normalize(parsed);
                }
            }

            Object numCtx = params.get("num_ctx");
            if (numCtx != null) {
                Integer parsed = parseInteger(numCtx);
                if (parsed != null && parsed > 0) {
                    return normalize(parsed);
                }
            }

            Object maxContextTokens = params.get("max_context_tokens");
            if (maxContextTokens != null) {
                Integer parsed = parseInteger(maxContextTokens);
                if (parsed != null && parsed > 0) {
                    return normalize(parsed);
                }
            }
        } catch (Exception ignored) {
            // 配置格式异常时，回退到默认策略，不影响主流程
        }

        return null;
    }

    public static Integer resolveFromRemoteMetadata(AgentConfig.ApiProvider provider, String apiUrl, String modelName, String apiKey) {
        if (provider == null || apiUrl == null || apiUrl.isBlank()) {
            return null;
        }

        String normalizedUrl = apiUrl.trim();
        if (provider == AgentConfig.ApiProvider.OPENAI_COMPATIBLE) {
            String endpoint = buildMetadataEndpoint(normalizedUrl, modelName);
            if (endpoint == null) {
                return null;
            }
            return fetchContextLimitFromEndpoint(endpoint, apiKey);
        }

        if (provider == AgentConfig.ApiProvider.ANTHROPIC) {
            String endpoint = buildAnthropicModelsEndpoint(normalizedUrl);
            if (endpoint == null) {
                return null;
            }
            return fetchContextLimitFromEndpoint(endpoint, apiKey);
        }

        if (provider == AgentConfig.ApiProvider.DASHSCOPE) {
            String endpoint = buildDashScopeModelsEndpoint(normalizedUrl);
            if (endpoint == null) {
                return null;
            }
            return fetchContextLimitFromEndpoint(endpoint, apiKey);
        }

        return null;
    }

    public static Integer extractContextLimitFromMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> root = JsonParser.parseJsonToMap(metadataJson);
            if (root == null || root.isEmpty()) {
                return null;
            }

            Integer fromRoot = extractContextLimitFromPayload(root);
            if (fromRoot != null) {
                return fromRoot;
            }

            Object data = root.get("data");
            if (data instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Integer parsed = extractContextLimitFromPayload(map);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            }

            Object models = root.get("models");
            if (models instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Integer parsed = extractContextLimitFromPayload(map);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }

        return null;
    }

    private static Integer extractContextLimitFromPayload(Map<?, ?> payload) {
        if (payload == null) {
            return null;
        }

        Object maxModelLen = payload.get("max_model_len");
        if (maxModelLen != null) {
            Integer parsed = parseInteger(maxModelLen);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        Object maxTokens = payload.get("max_tokens");
        if (maxTokens != null) {
            Integer parsed = parseInteger(maxTokens);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        Object contextWindow = payload.get("context_window");
        if (contextWindow != null) {
            Integer parsed = parseInteger(contextWindow);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        Object contextWindowTokens = payload.get("context_window_tokens");
        if (contextWindowTokens != null) {
            Integer parsed = parseInteger(contextWindowTokens);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        Object numCtx = payload.get("num_ctx");
        if (numCtx != null) {
            Integer parsed = parseInteger(numCtx);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        Object maxContextTokens = payload.get("max_context_tokens");
        if (maxContextTokens != null) {
            Integer parsed = parseInteger(maxContextTokens);
            if (parsed != null && parsed > 0) {
                return normalize(parsed);
            }
        }

        return null;
    }

    private static Integer fetchContextLimitFromEndpoint(String endpoint, String apiKey) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return extractContextLimitFromMetadata(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String buildMetadataEndpoint(String apiUrl, String modelName) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }

        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (base.contains("openai") || base.contains("api.openai")) {
            return base + "/models";
        }

        if (modelName != null && !modelName.isBlank()) {
            return base + "/models";
        }
        return null;
    }

    private static String buildAnthropicModelsEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }

        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return base + "/models";
    }

    private static String buildDashScopeModelsEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }

        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return base + "/models";
    }

    public static Integer resolveFromModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }

        String normalized = modelName.trim().toLowerCase();
        if (normalized.contains("gpt-4.1") || normalized.contains("gpt-4o") || normalized.contains("gpt-4")) {
            return 128_000;
        }
        if (normalized.contains("gpt-3.5")) {
            return 16_385;
        }
        if (normalized.contains("claude-3") || normalized.contains("claude-4")) {
            return 200_000;
        }
        if (normalized.contains("qwen3") || normalized.contains("qwen2.5") || normalized.contains("qwen-max") || normalized.contains("qwen-plus")) {
            return 131_072;
        }
        if (normalized.contains("deepseek") || normalized.contains("kimi")) {
            return 64_000;
        }
        if (normalized.contains("llama3") || normalized.contains("llama-3")) {
            return 128_000;
        }
        return null;
    }

    public static Integer resolveByProvider(AgentConfig.ApiProvider provider, String apiUrl, String modelName) {
        if (provider == null) {
            return DEFAULT_CONTEXT_LIMIT;
        }

        switch (provider) {
            case ANTHROPIC -> {
                return 200_000;
            }
            case OPENAI_COMPATIBLE -> {
                Integer fromModel = resolveFromModelName(modelName);
                return fromModel != null ? fromModel : 128_000;
            }
            case DASHSCOPE -> {
                return 131_072;
            }
            default -> {
                return DEFAULT_CONTEXT_LIMIT;
            }
        }
    }

    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int normalize(int value) {
        if (value < MIN_CONTEXT_LIMIT) {
            return MIN_CONTEXT_LIMIT;
        }
        return value;
    }
}
