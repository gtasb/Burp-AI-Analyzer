package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Set;

/**
 * AgentScope {@link Model} 统一工厂。
 *
 * <p>与 {@link ChatModelFactory} 并行存在，将相同的用户配置（provider、apiKey、model 等）
 * 转换为 AgentScope 原生 {@link Model} 实例。
 *
 * <p>迁移完成后，{@link ChatModelFactory} 将被移除，此类成为唯一的模型工厂。
 */
public class AgentScopeModelFactory {

    private static final Gson GSON = new Gson();
    /** 已映射到 GenerateOptions 字段的键；其余自定义键原样放进 additionalBodyParams */
    private static final Set<String> KNOWN_PARAMS = Set.of(
            "temperature", "top_p", "topP", "top_k", "topK", "max_tokens", "maxTokens",
            "max_completion_tokens", "frequency_penalty", "presence_penalty",
            "thinking_budget", "reasoning_effort", "seed", "parallel_tool_calls",
            "cache_control", "stream");

    private AgentScopeModelFactory() {}

    /**
     * 根据 provider 类型创建 AgentScope {@link Model}。
     *
     * @param provider       API 提供者类型
     * @param apiKey         API Key（必填）
     * @param apiUrl         API URL（可选，为空则使用默认值）
     * @param model          模型名称（可选，为空则使用默认值）
     * @param enableSearch   是否启用联网搜索（仅 DashScope 有效）
     * @param customParameters JSON 格式自定义参数（可为 null）
     * @return AgentScope Model 实例
     */
    public static Model create(
            ApiProvider provider,
            String apiKey,
            String apiUrl,
            String model,
            boolean enableSearch,
            String customParameters) {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        if (provider == ApiProvider.OPENAI_COMPATIBLE) {
            return createOpenAIModel(apiKey, apiUrl, model, customParameters);
        }
        if (provider == ApiProvider.ANTHROPIC) {
            return createAnthropicModel(apiKey, apiUrl, model, customParameters);
        }
        return createDashScopeModel(apiKey, apiUrl, model, enableSearch, customParameters);
    }

    // ========== DashScope ==========

    private static Model createDashScopeModel(
            String apiKey, String apiUrl, String model,
            boolean enableSearch, String customParameters) {

        String baseUrl = normalizeUrl(apiUrl, "https://dashscope.aliyuncs.com/api/v1");
        String modelName = isBlank(model) ? "qwen-max" : model.trim();

        GenerateOptions defaultOptions = applyCustomParameters(
                GenerateOptions.builder().temperature(0.7), customParameters).build();

        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .enableSearch(enableSearch)
                .endpointType(EndpointType.AUTO)
                .defaultOptions(defaultOptions)
                .build();
    }

    // ========== OpenAI 兼容 ==========

    private static Model createOpenAIModel(
            String apiKey, String apiUrl, String model, String customParameters) {

        String baseUrl = normalizeUrl(apiUrl, "https://api.openai.com/v1");
        String modelName = isBlank(model) ? "gpt-3.5-turbo" : model.trim();

        GenerateOptions defaultOptions = applyCustomParameters(
                GenerateOptions.builder().temperature(0.7), customParameters).build();

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .generateOptions(defaultOptions);
        if (isDeepSeekEndpoint(baseUrl, modelName)) {
            // DeepSeek 思考模式要求把上一轮 assistant 的 reasoning_content 原样回传，
            // 默认 OpenAI 格式化器会丢弃，导致工具调用后的下一次请求 400：
            // "The `reasoning_content` in the thinking mode must be passed back to the API."
            builder.formatter(new io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter());
            builder.nativeStructuredOutput(false);
            com.ai.analyzer.util.AppLogBuffer.info("AgentScopeModelFactory",
                    "DeepSeek thinking formatter enabled: " + modelName + " @ " + baseUrl);
            com.ai.analyzer.util.DebugContext.log("AgentScopeModelFactory", "deepseek_formatter_enabled",
                    java.util.Map.of("model", modelName, "url", baseUrl));
        }
        return builder.build();
    }

    /** DeepSeek 端点/模型判定（用于启用其专用的消息格式化器）。 */
    static boolean isDeepSeekEndpoint(String apiUrl, String modelName) {
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(java.util.Locale.ROOT);
        String name = modelName == null ? "" : modelName.toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("deepseek") || url.contains("deepseek");
    }

    // ========== Anthropic ==========

    private static Model createAnthropicModel(
            String apiKey, String apiUrl, String model, String customParameters) {

        String modelName = isBlank(model) ? "claude-sonnet-4-5-20250514" : model.trim();

        GenerateOptions defaultOptions = applyCustomParameters(
                GenerateOptions.builder().temperature(0.7).maxTokens(4096), customParameters).build();

        var builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .defaultOptions(defaultOptions);

        if (!isBlank(apiUrl)) {
            builder.baseUrl(apiUrl.trim());
        }

        return builder.build();
    }

    // ========== 辅助 ==========

    /**
     * 把 UI 的「自定义参数」JSON 应用到 GenerateOptions。未知键作为 additionalBodyParams
     * 原样发给 provider；非法 JSON 静默忽略，避免一个手误让整个模型初始化失败。
     */
    private static GenerateOptions.Builder applyCustomParameters(GenerateOptions.Builder builder,
                                                                 String customParameters) {
        if (isBlank(customParameters)) {
            return builder;
        }
        try {
            JsonElement root = JsonParser.parseString(customParameters);
            if (!root.isJsonObject()) {
                return builder;
            }
            JsonObject obj = root.getAsJsonObject();
            if (has(obj, "temperature")) builder.temperature(obj.get("temperature").getAsDouble());
            if (has(obj, "top_p")) builder.topP(obj.get("top_p").getAsDouble());
            if (has(obj, "topP")) builder.topP(obj.get("topP").getAsDouble());
            if (has(obj, "top_k")) builder.topK(obj.get("top_k").getAsInt());
            if (has(obj, "topK")) builder.topK(obj.get("topK").getAsInt());
            if (has(obj, "max_tokens")) builder.maxTokens(obj.get("max_tokens").getAsInt());
            if (has(obj, "maxTokens")) builder.maxTokens(obj.get("maxTokens").getAsInt());
            if (has(obj, "max_completion_tokens")) {
                builder.maxCompletionTokens(obj.get("max_completion_tokens").getAsInt());
            }
            if (has(obj, "frequency_penalty")) {
                builder.frequencyPenalty(obj.get("frequency_penalty").getAsDouble());
            }
            if (has(obj, "presence_penalty")) {
                builder.presencePenalty(obj.get("presence_penalty").getAsDouble());
            }
            if (has(obj, "thinking_budget")) builder.thinkingBudget(obj.get("thinking_budget").getAsInt());
            if (has(obj, "reasoning_effort")) builder.reasoningEffort(obj.get("reasoning_effort").getAsString());
            if (has(obj, "seed")) builder.seed(obj.get("seed").getAsLong());
            if (has(obj, "parallel_tool_calls")) {
                builder.parallelToolCalls(obj.get("parallel_tool_calls").getAsBoolean());
            }
            if (has(obj, "cache_control")) builder.cacheControl(obj.get("cache_control").getAsBoolean());
            if (has(obj, "stream")) builder.stream(obj.get("stream").getAsBoolean());
            for (var entry : obj.entrySet()) {
                if (!KNOWN_PARAMS.contains(entry.getKey())) {
                    builder.additionalBodyParam(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON / 类型不匹配：保持默认参数
        }
        return builder;
    }

    private static boolean has(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull();
    }

    /**
     * 返回模型创建时的摘要描述，供调用方打日志。
     */
    public static String describeConfig(ApiProvider provider, String apiUrl, String model,
                                        boolean enableSearch) {
        return String.format("AgentScope[provider=%s, model=%s, url=%s, search=%s]",
                provider.getDisplayName(), model, apiUrl, enableSearch);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalizeUrl(String url, String defaultUrl) {
        if (isBlank(url)) return defaultUrl;
        String trimmed = url.trim();
        // 用户常粘贴出 "https://host//v1" 这类双斜杠，避免拼出错误 endpoint
        String scheme = "";
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd > 0) {
            scheme = trimmed.substring(0, schemeEnd + 3);
            trimmed = trimmed.substring(schemeEnd + 3);
        }
        trimmed = trimmed.replaceAll("/{2,}", "/");
        return scheme + trimmed;
    }
}