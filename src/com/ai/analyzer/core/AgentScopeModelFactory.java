package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * AgentScope {@link Model} 统一工厂。
 *
 * <p>与 {@link ChatModelFactory} 并行存在，将相同的用户配置（provider、apiKey、model 等）
 * 转换为 AgentScope 原生 {@link Model} 实例。
 *
 * <p>迁移完成后，{@link ChatModelFactory} 将被移除，此类成为唯一的模型工厂。
 */
public class AgentScopeModelFactory {

    private AgentScopeModelFactory() {}

    /**
     * 根据 provider 类型创建 AgentScope {@link Model}。
     *
     * @param provider       API 提供者类型
     * @param apiKey         API Key（必填）
     * @param apiUrl         API URL（可选，为空则使用默认值）
     * @param model          模型名称（可选，为空则使用默认值）
     * @param enableSearch   是否启用联网搜索（仅 DashScope 有效）
     * @param enableThinking 是否启用深度思考
     * @param customParameters JSON 格式自定义参数（可为 null）
     * @return AgentScope Model 实例
     */
    public static Model create(
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
            return createOpenAIModel(apiKey, apiUrl, model);
        }
        if (provider == ApiProvider.ANTHROPIC) {
            return createAnthropicModel(apiKey, apiUrl, model, enableThinking);
        }
        return createDashScopeModel(apiKey, apiUrl, model, enableSearch, enableThinking);
    }

    // ========== DashScope ==========

    private static Model createDashScopeModel(
            String apiKey, String apiUrl, String model,
            boolean enableSearch, boolean enableThinking) {

        String baseUrl = normalizeUrl(apiUrl, "https://dashscope.aliyuncs.com/api/v1");
        String modelName = isBlank(model) ? "qwen-max" : model.trim();

        GenerateOptions defaultOptions = GenerateOptions.builder()
                .temperature(0.7)
                .build();

        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .enableThinking(enableThinking)
                .enableSearch(enableSearch)
                .endpointType(EndpointType.AUTO)
                .defaultOptions(defaultOptions)
                .build();
    }

    // ========== OpenAI 兼容 ==========

    private static Model createOpenAIModel(
            String apiKey, String apiUrl, String model) {

        String baseUrl = normalizeUrl(apiUrl, "https://api.openai.com/v1");
        String modelName = isBlank(model) ? "gpt-3.5-turbo" : model.trim();

        GenerateOptions defaultOptions = GenerateOptions.builder()
                .temperature(0.7)
                .build();

        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .generateOptions(defaultOptions)
                .build();
    }

    // ========== Anthropic ==========

    private static Model createAnthropicModel(
            String apiKey, String apiUrl, String model,
            boolean enableThinking) {

        String modelName = isBlank(model) ? "claude-sonnet-4-5-20250514" : model.trim();

        GenerateOptions.Builder optionsBuilder = GenerateOptions.builder()
                .temperature(0.7)
                .maxTokens(4096);

        if (enableThinking) {
            optionsBuilder.thinkingBudget(2048)
                    .maxTokens(2048 + 4096);
        }

        var builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .defaultOptions(optionsBuilder.build());

        if (!isBlank(apiUrl)) {
            builder.baseUrl(apiUrl.trim());
        }

        return builder.build();
    }

    // ========== 辅助 ==========

    /**
     * 返回模型创建时的摘要描述，供调用方打日志。
     */
    public static String describeConfig(ApiProvider provider, String apiUrl, String model,
                                        boolean enableSearch, boolean enableThinking) {
        return String.format("AgentScope[provider=%s, model=%s, url=%s, search=%s, thinking=%s]",
                provider.getDisplayName(), model, apiUrl, enableSearch, enableThinking);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalizeUrl(String url, String defaultUrl) {
        return isBlank(url) ? defaultUrl : url.trim();
    }
}