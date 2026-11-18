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
    /**
     * 模型 HTTP 调用重试：默认 3 次（2s 起步、30s 上限）在长会话里太容易放弃。
     * 提高到 10 次并把退避稍微拉长；仍复用 AgentScope 的可重试错误判定，避免对 4xx 空转。
     */
    static final io.agentscope.core.model.ExecutionConfig MODEL_RETRY =
            io.agentscope.core.model.ExecutionConfig.builder()
                    .maxAttempts(10)
                    .initialBackoff(java.time.Duration.ofSeconds(3))
                    .maxBackoff(java.time.Duration.ofSeconds(60))
                    .backoffMultiplier(2.0)
                    .retryOn(io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS.getRetryOn())
                    .build();
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

        if (provider == ApiProvider.OPENAI_COMPATIBLE || provider == ApiProvider.OPENCODE_GO) {
            return createOpenAIModel(apiKey, apiUrl, model, customParameters);
        }
        if (provider == ApiProvider.ANTHROPIC) {
            return createAnthropicModel(apiKey, apiUrl, model, customParameters);
        }
        return createDashScopeModel(apiKey, apiUrl, model, enableSearch, customParameters);
    }

    // ========== OpenCode Go ==========

    /**
     * OpenCode Go 套餐模型：OpenAI chat 协议，默认端点 {@code https://opencode.ai/zen/go/v1}。
     * 网关校验由 {@link OpenCodeHeaderTransport} 注入（x-opencode-session + User-Agent）。
     */
    static Model createOpenCodeGoModel(String apiKey, String apiUrl, String model, String customParameters) {
        String baseUrl = isBlank(apiUrl) ? "https://opencode.ai/zen/go/v1" : normalizeUrl(apiUrl, "https://opencode.ai/zen/go/v1");
        return createOpenAIModel(apiKey, baseUrl, model, customParameters);
    }

    // ========== DashScope ==========

    private static Model createDashScopeModel(
            String apiKey, String apiUrl, String model,
            boolean enableSearch, String customParameters) {

        String baseUrl = normalizeUrl(apiUrl, "https://dashscope.aliyuncs.com/api/v1");
        String modelName = isBlank(model) ? "qwen-max" : model.trim();

        GenerateOptions defaultOptions = applyCustomParameters(
                GenerateOptions.builder().temperature(0.7).executionConfig(MODEL_RETRY),
                customParameters).build();

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
                GenerateOptions.builder().temperature(0.7).executionConfig(MODEL_RETRY),
                customParameters).build();

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .generateOptions(defaultOptions);
        // OpenCode Go 网关（opencode.ai）校验 x-opencode-session + User-Agent（缺 session
        // 直接 400 MissingSessionID，实测 2026-11）。仅该上游换用注入头装饰 transport，
        // 其他 provider 保持 AgentScope 默认 transport 解析（含重试/代理配置）不变。
        if (OpenCodeHeaderTransport.isOpenCodeUpstream(baseUrl)) {
            builder.httpTransport(OpenCodeHeaderTransport.wrapIfOpenCode(baseUrl,
                    io.agentscope.core.model.transport.OkHttpTransport.builder().build()));
        } else if (isDeepSeekEndpoint(baseUrl, modelName)) {
            // DeepSeek thinking 模式：所有带 tool_calls 的 assistant（含历史轮次）都必须回传
            // reasoning_content。不要用 compat.deepseek.DeepSeekFormatter——它会剥离旧轮次的
            // reasoning，长会话必然 400。这里用保留 reasoning 的 OpenAI 默认转换 + 关闭 strict。
            builder.formatter(new DeepSeekReasoningFormatter());
            builder.nativeStructuredOutput(false);
            com.ai.analyzer.util.AppLogBuffer.info("AgentScopeModelFactory",
                    "DeepSeek reasoning-preserving formatter enabled: " + modelName + " @ " + baseUrl);
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
                GenerateOptions.builder().temperature(0.7).maxTokens(4096).executionConfig(MODEL_RETRY),
                customParameters).build();

        var builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .defaultOptions(defaultOptions);

        if (!isBlank(apiUrl)) {
            // 官方 SDK 自己会拼 /v1/messages；用户填 .../v1 时不能拼成 /v1/v1/messages
            builder.baseUrl(normalizeAnthropicBaseUrl(apiUrl));
        }

        return builder.build();
    }

    /** 去掉用户误填的 /v1 或 /v1/messages 后缀（Anthropic SDK 会自行拼接）。 */
    static String normalizeAnthropicBaseUrl(String url) {
        String u = normalizeUrl(url, "https://api.anthropic.com");
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/v1/messages")) {
            return u.substring(0, u.length() - "/v1/messages".length());
        }
        if (u.endsWith("/v1")) {
            return u.substring(0, u.length() - "/v1".length());
        }
        return u;
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