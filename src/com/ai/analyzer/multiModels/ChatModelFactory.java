package com.ai.analyzer.multiModels;

import com.ai.analyzer.Client.AgentConfig.ApiProvider;
import com.ai.analyzer.utils.JsonParser;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

import java.util.Map;

/**
 * StreamingChatModel 统一工厂
 *
 * 集中管理 Qwen / OpenAI 兼容模型的创建逻辑，
 * 供 AgentApiClient 和 PassiveScanApiClient 共用。
 */
public class ChatModelFactory {

    private ChatModelFactory() {}

    // ========== 对外统一入口 ==========

    /**
     * 根据 provider 类型创建 StreamingChatModel
     *
     * @param provider         API 提供者类型
     * @param apiKey           API Key（必填）
     * @param apiUrl           API URL（可选，为空则使用默认值）
     * @param model            模型名称（可选，为空则使用默认值）
     * @param enableSearch     是否启用联网搜索（仅 Qwen 有效）
     * @param enableThinking   是否启用深度思考（仅 Qwen 有效）
     * @param customParameters JSON 格式自定义参数（仅 OpenAI 兼容和 Anthropic 有效，可为 null）
     * @return StreamingChatModel 实例
     * @throws IllegalArgumentException apiKey 为空时抛出
     */
    public static StreamingChatModel create(
            ApiProvider provider,
            String apiKey,
            String apiUrl,
            String model,
            boolean enableSearch,
            boolean enableThinking,
            String customParameters) {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        if (provider == ApiProvider.OPENAI_COMPATIBLE) {
            return createOpenAIModel(apiKey, apiUrl, model, customParameters);
        }
        if (provider == ApiProvider.ANTHROPIC) {
            return createAnthropicModel(apiKey, apiUrl, model, enableThinking, customParameters);
        }
        return createQwenModel(apiKey, apiUrl, model, enableSearch, enableThinking);
    }

    // ========== Qwen (DashScope) ==========

    private static StreamingChatModel createQwenModel(
            String apiKey, String apiUrl, String model,
            boolean enableSearch, boolean enableThinking) {

        String baseUrl = normalizeQwenBaseUrl(apiUrl);
        String modelName = isBlank(model) ? "qwen-max" : model.trim();

        QwenChatRequestParameters.SearchOptions searchOptions =
                QwenChatRequestParameters.SearchOptions.builder()
                        .searchStrategy("proactive")
                        .build();

        QwenChatRequestParameters parameters = QwenChatRequestParameters.builder()
                .enableSearch(enableSearch)
                .searchOptions(searchOptions)
                .enableThinking(enableThinking)
                .build();

        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.7f)
                .defaultRequestParameters(parameters)
                .build();
    }

    // ========== OpenAI 兼容 ==========

    private static StreamingChatModel createOpenAIModel(
            String apiKey, String apiUrl, String model, String customParameters) {

        String baseUrl = normalizeOpenAIBaseUrl(apiUrl);
        String modelName = isBlank(model) ? "gpt-3.5-turbo" : model.trim();

        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7);

        if (!isBlank(customParameters)) {
            try {
                Map<String, Object> paramsMap = JsonParser.parseJsonToMap(customParameters);
                if (!paramsMap.isEmpty()) {
                    OpenAiChatRequestParameters requestParams =
                            OpenAiChatRequestParameters.builder()
                                    .customParameters(paramsMap)
                                    .build();
                    builder.defaultRequestParameters(requestParams);
                }
            } catch (Exception ignored) {
                // 自定义参数解析失败不影响模型创建
            }
        }

        return builder.build();
    }

    // ========== Anthropic ==========

    private static StreamingChatModel createAnthropicModel(
            String apiKey, String apiUrl, String model,
            boolean enableThinking, String customParameters) {

        String modelName = isBlank(model) ? "claude-sonnet-4-5-20250514" : model.trim();

        var builder = AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .maxTokens(4096);

        if (!isBlank(apiUrl)) {
            builder.baseUrl(apiUrl.trim());
        }

        if (enableThinking) {
            builder.thinkingType("enabled")
                   .thinkingBudgetTokens(2048)
                   .maxTokens(2048 + 4096)
                   .returnThinking(true)
                   .sendThinking(true);
        }

        builder.cacheSystemMessages(true)
               .cacheTools(true);

        if (!isBlank(customParameters)) {
            try {
                Map<String, Object> paramsMap = JsonParser.parseJsonToMap(customParameters);
                if (!paramsMap.isEmpty()) {
                    builder.customParameters(paramsMap);
                }
            } catch (Exception ignored) {
            }
        }

        return builder.build();
    }

    // ========== URL 规范化 ==========

    /**
     * 规范化 Qwen (DashScope) 的 API URL
     */
    public static String normalizeQwenBaseUrl(String apiUrl) {
        String baseUrl = apiUrl;
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            baseUrl = baseUrl.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            if (baseUrl.contains("/chat/completions")) {
                baseUrl = baseUrl.substring(0, baseUrl.indexOf("/chat/completions"));
            }
            if (!baseUrl.endsWith("/v1")) {
                if (baseUrl.endsWith("/compatible-mode")) {
                    baseUrl = baseUrl + "/v1";
                } else if (baseUrl.contains("dashscope") && !baseUrl.contains("/v1")) {
                    baseUrl = baseUrl + "/api/v1";
                }
            }
        }
        return isBlank(baseUrl) ? "https://dashscope.aliyuncs.com/api/v1" : baseUrl;
    }

    /**
     * 规范化 OpenAI 兼容的 API URL
     */
    public static String normalizeOpenAIBaseUrl(String apiUrl) {
        String baseUrl = apiUrl;
        if (isBlank(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        baseUrl = baseUrl.trim();
        if (!baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
        }
        return baseUrl;
    }

    // ========== 辅助描述（供日志使用） ==========

    /**
     * 返回模型创建时的摘要描述，供调用方打日志
     */
    public static String describeConfig(ApiProvider provider, String apiUrl, String model,
                                        boolean enableSearch, boolean enableThinking) {
        String baseUrl;
        String defaultModel;
        switch (provider) {
            case OPENAI_COMPATIBLE -> { baseUrl = normalizeOpenAIBaseUrl(apiUrl); defaultModel = "gpt-3.5-turbo"; }
            case ANTHROPIC         -> { baseUrl = isBlank(apiUrl) ? "(default)" : apiUrl.trim(); defaultModel = "claude-sonnet-4-5-20250514"; }
            default                -> { baseUrl = normalizeQwenBaseUrl(apiUrl); defaultModel = "qwen-max"; }
        }
        String modelName = isBlank(model) ? defaultModel : model.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("Provider=").append(provider.getDisplayName());
        sb.append(", URL=").append(baseUrl);
        sb.append(", Model=").append(modelName);
        if (provider == ApiProvider.DASHSCOPE) {
            sb.append(", Search=").append(enableSearch);
            sb.append(", Thinking=").append(enableThinking);
        }
        if (provider == ApiProvider.ANTHROPIC) {
            sb.append(", Thinking=").append(enableThinking);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
