package com.ai.analyzer.api;

import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.mcpClient.McpToolProvider;
import com.ai.analyzer.mcpClient.McpToolMappingConfig;
import com.ai.analyzer.mcpClient.ToolExecutionFormatter;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.List;
import com.ai.analyzer.model.PluginSettings;
import burp.api.montoya.MontoyaApi;

import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import lombok.Getter;
import lombok.Setter;
//import dev.langchain4j.model.chat.listener.ChatModelListener;
//import com.ai.analyzer.listener.DebugChatModelListener;


public class QianwenApiClient {
    @Getter
    private String apiKey;
    // 获取API配置的方法
    @Getter
    private String apiUrl;
    @Getter
    private String model;
    private QwenStreamingChatModel chatModel;
    /**
     * -- SETTER --
     *  设置 MontoyaApi 引用
     */
    // private JsonArray tools; // 工具定义列表
    // private Consumer<ToolCall> toolCallHandler; // 工具调用处理器
    @Setter
    private MontoyaApi api; // Burp API 引用，用于日志输出
    @Getter
    private boolean enableThinking;// 是否启用思考过程
    @Getter
    private boolean enableSearch; // 是否启用搜索
    private boolean isFirstInitialization = true; // 是否是第一次初始化
    private boolean needsReinitialization = false; // 标记是否需要重新初始化（延迟初始化，避免频繁重建）
    private Assistant assistant; // 共享的 Assistant 实例，用于保持上下文
    private dev.langchain4j.mcp.McpToolProvider mcpToolProvider; // MCP 工具提供者（共享实例）
    private MessageWindowChatMemory chatMemory; // 共享的聊天记忆，用于保持上下文（即使 Assistant 重新创建也保留）
    private volatile TokenStream currentTokenStream; // 当前活动的 TokenStream，用于取消流式输出

    /**
     * 无参构造函数，自动从配置文件加载设置
     */
    public QianwenApiClient() {
        loadSettingsFromFile();
        initializeChatModel();
    }

    /**
     * 带参构造函数，如果参数为空则尝试从配置文件加载
     */
    public QianwenApiClient(String apiUrl, String apiKey) {
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            // 参数为空，尝试从配置文件加载
            loadSettingsFromFile();
        } else {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }
        initializeChatModel();
    }
    
    /**
     * 带 MontoyaApi 的构造函数
     */
    public QianwenApiClient(MontoyaApi api, String apiUrl, String apiKey) {
        this.api = api;
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            // 参数为空，尝试从配置文件加载
            loadSettingsFromFile();
        } else {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }
        initializeChatModel();
    }

    /**
     * 初始化 LangChain4j ChatModel
     */
    private void initializeChatModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] 警告: API Key为空，无法初始化ChatModel");
            }
            return;
        }
        
        try {
            // 从 apiUrl 中提取 baseUrl（去除 /chat/completions 等路径）
            String baseUrl = apiUrl;
            if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                // 去除末尾的斜杠
                baseUrl = baseUrl.trim();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                // 如果包含 /chat/completions，去除这部分
                if (baseUrl.contains("/chat/completions")) {
                    baseUrl = baseUrl.substring(0, baseUrl.indexOf("/chat/completions"));
                }
                // 确保 baseUrl 以 /v1 结尾（QwenStreamingChatModel 可能需要）
                if (!baseUrl.endsWith("/v1")) {
                    // 如果以 /compatible-mode 结尾，添加 /v1
                    if (baseUrl.endsWith("/compatible-mode")) {
                        baseUrl = baseUrl + "/v1";
                    } else if (baseUrl.contains("dashscope") && !baseUrl.contains("/v1")) {
                        // 如果是 dashscope URL 但没有 /v1，尝试添加
                        if (baseUrl.contains("/compatible-mode")) {
                            baseUrl = baseUrl + "/v1";
                        } else {
                            baseUrl = baseUrl + "/api/v1";
                        }
                    }
                }
            }
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = "https://dashscope.aliyuncs.com/api/v1";
            }
            
            String modelName = model != null && !model.trim().isEmpty() ? model : "qwen3-max";
            
            // 只在第一次初始化时输出详细信息
            if (api != null && isFirstInitialization) {
                api.logging().logToOutput("[QianwenApiClient] 初始化LangChain4j ChatModel");
                api.logging().logToOutput("[QianwenApiClient] 原始API URL: " + apiUrl);
                //api.logging().logToOutput("[QianwenApiClient] 处理后的BaseURL: " + baseUrl);
                api.logging().logToOutput("[QianwenApiClient] Model: " + modelName);
                api.logging().logToOutput("[QianwenApiClient] EnableThinking: " + enableThinking);
                api.logging().logToOutput("[QianwenApiClient] EnableSearch: " + enableSearch);
            }

            // 使用类成员变量的值，确保每次初始化都使用最新的用户设置
            // 注意：enableSearch 和 enableThinking 的值来自类成员变量，会在用户改变UI开关时更新
            // 当用户改变UI中的复选框时，会调用 setEnableSearch() 或 setEnableThinking()
            // 这些方法会调用 reinitializeChatModel()，重新创建 chatModel 并使用新的参数值
            QwenChatRequestParameters.SearchOptions searchOptions = QwenChatRequestParameters.SearchOptions.builder()
                    // 使返回结果中包含搜索信息的来源
                    //.enableSource(true)
                    // 强制开启互联网搜索（根据用户设置）
                    .forcedSearch(enableSearch)
                    // 开启角标标注
                    //.enableCitation(true)
                    // 设置角标标注样式为[ref_i]
                    //.citationFormat("[ref_<number>]")
                    .searchStrategy("max")
                    .build();

            // 创建请求参数，使用当前的 enableSearch 和 enableThinking 值
            // 这些值会在用户改变UI开关时通过 setEnableSearch() 和 setEnableThinking() 更新
            // 然后通过 reinitializeChatModel() 重新创建 chatModel 时使用新值
            QwenChatRequestParameters parameters = QwenChatRequestParameters.builder()
                    .enableSearch(enableSearch)
                    .searchOptions(searchOptions)
                    .enableThinking(enableThinking)
                    .build();
            //.customParameters(customParameters)
            
            this.chatModel = QwenStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    //.enableSearch(enableSearch)
                    .temperature(0.7f) // 越小越确定，越大越随机
                    .defaultRequestParameters(parameters)
                    .build();

                    
            // 只在第一次初始化时输出成功信息
            if (api != null && isFirstInitialization) {
                api.logging().logToOutput("[QianwenApiClient] LangChain4j ChatModel初始化成功");
                isFirstInitialization = false; // 标记已完成第一次初始化
            }
        } catch (Exception e) {
            if (api != null) {
                api.logging().logToError("[QianwenApiClient] 初始化ChatModel失败: " + e.getMessage());
                //e.printStackTrace();
            }
        }
    }
    
    /**
     * 重新初始化ChatModel（当配置更新时调用）
     * 使用延迟初始化策略，避免频繁重建导致性能问题
     * 注意：enableThinking 和 enableSearch 的更新不通过此方法，而是通过 updateChatModelAndAssistant()
     */
    private void reinitializeChatModel() {
        // 标记需要重新初始化，但不立即执行
        // 实际重新初始化会在下次使用 chatModel 时进行（在 analyzeRequestStream 中）
        needsReinitialization = true;
    }
    
    /**
     * 更新 ChatModel 和 Assistant（当 enableThinking 或 enableSearch 改变时调用）
     * 立即重新创建 chatModel 和 Assistant，使用新的配置
     * 注意：ChatMemory 不会被清空，保留上下文记忆
     */
    private void updateChatModelAndAssistant() {
        // 先清理旧的 Assistant（因为它依赖于 chatModel）
        // 注意：不清理 chatMemory，保留上下文记忆
        if (assistant != null) {
            assistant = null;
        }
        // 清理旧的 chatModel
        if (chatModel != null) {
            chatModel = null;
        }
        // 重新初始化 chatModel，使用最新的 enableSearch 和 enableThinking 值
        initializeChatModel();
        // Assistant 会在下次调用 ensureAssistantInitialized() 时自动创建
        // 新的 Assistant 会使用同一个 chatMemory 实例，从而保留上下文记忆
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] ChatModel 已更新，使用新的配置 (EnableThinking: " + enableThinking + ", EnableSearch: " + enableSearch + ")");
            api.logging().logToOutput("[QianwenApiClient] Assistant 将在下次使用时使用新的 ChatModel（保留上下文记忆）");
        }
    }
    
    /**
     * 确保 ChatModel 已初始化且使用最新的配置
     * 如果标记了需要重新初始化，则执行重新初始化
     */
    private void ensureChatModelInitialized() {
        if (needsReinitialization || chatModel == null) {
            // 先清理旧的 Assistant（因为它依赖于 chatModel）
            if (assistant != null) {
                assistant = null; // Assistant 依赖于 chatModel，需要重新创建
            }
            // 清理旧的 chatModel
            if (chatModel != null) {
                chatModel = null;
            }
            // 重新初始化，使用最新的 enableSearch 和 enableThinking 值
            initializeChatModel();
            needsReinitialization = false;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] ChatModel 已重新初始化，使用新的配置 (EnableThinking: " + enableThinking + ", EnableSearch: " + enableSearch + ")");
            }
        }
    }
    
    /**
     * 确保 Assistant 实例已创建（共享实例，保持上下文）
     * 如果 Assistant 不存在，则创建新的 Assistant
     * 注意：此方法在 ensureChatModelInitialized() 之后调用，确保使用最新的 chatModel
     */
    private void ensureAssistantInitialized() {
        // 如果 assistant 为 null，创建新的实例（使用最新的 chatModel）
        if (assistant == null) {
            // 获取或创建 MCP 工具提供者（带映射配置）
            if (mcpToolProvider == null) {
                try {
                    McpToolProvider mcpProviderHelper = new McpToolProvider();
                    McpTransport transport = mcpProviderHelper.createTransport();
                    McpClient mcpClient = mcpProviderHelper.createMcpClient(transport);
                    // 等待连接稳定
                    Thread.sleep(1000);
                    
                    // 创建 Burp MCP 工具映射配置（包含中文名称和描述映射）
                    McpToolMappingConfig mappingConfig = McpToolMappingConfig.createBurpMapping();
                    
                    // 使用映射配置创建 Tool Provider
                    // 只保留 create_repeater_tab 和 send_to_intruder 工具
                    //mcpToolProvider = mcpProviderHelper.createToolProviderWithMapping(mcpClient, mappingConfig, 
                    //"create_repeater_tab", "send_to_intruder");
                    mcpToolProvider = mcpProviderHelper.createToolProviderWithMapping(mcpClient, mappingConfig);    

                    if (api != null) {
                        api.logging().logToOutput("[QianwenApiClient] MCP 工具提供者初始化成功（已应用工具名称和描述映射）");
                    }
                } catch (Exception e) {
                    // MCP 服务器不可用，不影响主要功能
                    if (api != null) {
                        api.logging().logToOutput("[QianwenApiClient] MCP 工具提供者初始化失败（MCP 服务器可能未运行）: " + e.getMessage());
                    }
                    mcpToolProvider = null;
                }
            }
            
            // 确保 ChatMemory 已创建（共享实例，保持上下文）
            if (chatMemory == null) {
                chatMemory = MessageWindowChatMemory.withMaxMessages(10);
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] ChatMemory 已创建（共享实例，保持上下文）");
                }
            }
            
            // 创建 Assistant 实例（使用共享的 ChatMemory）
            var assistantBuilder = AiServices.builder(Assistant.class)
                    .streamingChatModel(this.chatModel)
                    .chatMemory(chatMemory); // 使用共享的 ChatMemory，保留上下文记忆
            
            // 如果 MCP 工具提供者可用，则添加工具支持
            if (mcpToolProvider != null) {
                assistantBuilder.toolProvider(mcpToolProvider);
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] 已启用 MCP 工具支持");
                }
            }
            
            assistant = assistantBuilder.build();
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] Assistant 实例已创建（共享实例，保持上下文）");
            }
        }
    }
    
    /**
     * 取消流式输出
     * 当用户点击"停止"按钮时调用
     */
    public void cancelStreaming() {
        if (currentTokenStream != null) {
            try {
                // 尝试调用 TokenStream 的 cancel 方法（如果存在）
                java.lang.reflect.Method cancelMethod = currentTokenStream.getClass().getMethod("cancel");
                cancelMethod.invoke(currentTokenStream);
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] 流式输出已取消");
                }
            } catch (NoSuchMethodException e) {
                // 如果 TokenStream 没有 cancel 方法，尝试通过反射查找其他取消方法
                try {
                    // 尝试查找 stop、abort 等方法
                    java.lang.reflect.Method[] methods = currentTokenStream.getClass().getMethods();
                    for (java.lang.reflect.Method method : methods) {
                        String methodName = method.getName().toLowerCase();
                        if ((methodName.contains("cancel") || methodName.contains("stop") || methodName.contains("abort"))
                            && method.getParameterCount() == 0) {
                            method.invoke(currentTokenStream);
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] 流式输出已取消（通过 " + method.getName() + " 方法）");
                            }
                            break;
                        }
                    }
                } catch (Exception ex) {
                    if (api != null) {
                        api.logging().logToError("[QianwenApiClient] 取消流式输出失败: " + ex.getMessage());
                    }
                }
            } catch (Exception e) {
                if (api != null) {
                    api.logging().logToError("[QianwenApiClient] 取消流式输出失败: " + e.getMessage());
                }
            }
            currentTokenStream = null;
        } else {
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] 没有活动的流式输出可以取消");
            }
        }
    }
    
    /**
     * 清空聊天上下文（清空 Assistant 的聊天记忆）
     * 当用户点击"清空"按钮时调用
     */
    public void clearContext() {
        // 清空 Assistant 和 ChatMemory，从而清空所有上下文记忆
        if (assistant != null) {
            assistant = null;
        }
        if (chatMemory != null) {
            chatMemory = null; // 创建新的 ChatMemory 以清空记忆
        }
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] 聊天上下文已清空");
        }
    }
    
    /**
     * 从配置文件自动加载设置
     * 支持多个配置文件路径：
     * 1. 当前目录下的 ai_analyzer_settings.dat
     * 2. 用户主目录下的 .burp_ai_analyzer_settings
     */
    private void loadSettingsFromFile() {
        // 默认值
        String defaultApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String defaultApiKey = "";
        String defaultModel = "qwen3-max";
        
        // 尝试从当前目录加载
        File localSettingsFile = new File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(localSettingsFile);
            if (settings != null) {
                this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
                    ? settings.getApiUrl() : defaultApiUrl;
                this.apiKey = settings.getApiKey() != null && !settings.getApiKey().isEmpty() 
                    ? settings.getApiKey() : defaultApiKey;
                this.model = settings.getModel() != null && !settings.getModel().isEmpty() 
                    ? settings.getModel() : defaultModel;
                this.enableThinking = settings.isEnableThinking();
                this.enableSearch = settings.isEnableSearch();
                return;
            }
        }
        
        // 尝试从用户主目录加载
        File userSettingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(userSettingsFile);
            if (settings != null) {
                this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
                    ? settings.getApiUrl() : defaultApiUrl;
                this.apiKey = settings.getApiKey() != null && !settings.getApiKey().isEmpty() 
                    ? settings.getApiKey() : defaultApiKey;
                this.model = settings.getModel() != null && !settings.getModel().isEmpty() 
                    ? settings.getModel() : defaultModel;
                this.enableThinking = settings.isEnableThinking();
                this.enableSearch = settings.isEnableSearch();
                return;
            }
        }
        
        // 如果两个文件都不存在，使用默认值
        this.apiUrl = defaultApiUrl;
        this.apiKey = defaultApiKey;
        this.model = defaultModel;
        this.enableThinking = false;
        this.enableSearch = false;
    }
    
    /**
     * 从指定文件加载设置
     */
    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
                return (PluginSettings) ois.readObject();
            }
        } catch (Exception e) {
            // 加载失败，返回null
            return null;
        }
    }
    
    public void setApiUrl(String apiUrl) {
        // 只有值真的改变时才标记需要重新初始化
        if (this.apiUrl == null || !this.apiUrl.equals(apiUrl)) {
            this.apiUrl = apiUrl;
            reinitializeChatModel(); // 只标记，不立即重新初始化
        }
    }
    
    public void setApiKey(String apiKey) {
        // 只有值真的改变时才标记需要重新初始化
        if (this.apiKey == null || !this.apiKey.equals(apiKey)) {
            this.apiKey = apiKey;
            reinitializeChatModel(); // 只标记，不立即重新初始化
        }
    }
    
    public void setModel(String model) {
        // 只有值真的改变时才标记需要重新初始化
        if (this.model == null || !this.model.equals(model)) {
            this.model = model;
            reinitializeChatModel(); // 只标记，不立即重新初始化
        }
    }
    
    public void setEnableThinking(boolean enableThinking) {
        // 只有值真的改变时才更新
        if (this.enableThinking != enableThinking) {
            this.enableThinking = enableThinking;
            // 立即重新创建 chatModel 和 Assistant，使用新的配置
            updateChatModelAndAssistant();
        }
    }
    
    public void setEnableSearch(boolean enableSearch) {
        // 只有值真的改变时才更新
        if (this.enableSearch != enableSearch) {
            this.enableSearch = enableSearch;
            // 立即重新创建 chatModel 和 Assistant，使用新的配置
            updateChatModelAndAssistant();
        }
    }

    /*
      设置工具定义列表
     */
    
    /**
     * 设置工具调用处理器
     */
    // public void setToolCallHandler(Consumer<ToolCall> handler) {
    //     this.toolCallHandler = handler;
    // }

    // 流式输出方法 - 使用LangChain4j
    public void analyzeRequestStream(String httpRequest, String userPrompt, Consumer<String> onChunk) throws Exception {
        // 确保 ChatModel 已初始化且使用最新的配置（延迟初始化）
        ensureChatModelInitialized();
        
        if (chatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API Key和URL配置");
        }

        // 构建系统消息
        String systemContent = "你是一个专业的Web安全测试专家，擅长分析HTTP请求和响应中的潜在漏洞，也能直接进行渗透测试。\n\n"
            + "工作要求：\n"
            + "只输出可能存在的owasp top 10或中危及以上安全风险，不要输出低危和无风险的项，并且给出对风险点的渗透测试建议，根据上下文信息，辅助渗透测试工程师继续进行渗透测试；\n"
            //+ "你只有在用户主动请求你使用提供的工具来调用Burp Suite的功能时，才能使用提供的工具来调用Burp Suite的功能，\n"
            + "可以以markdown格式输出，但不要输出代码表格格式，不要输出'---'；\n"
            + "格式简洁，突出重点，不要冗长描述。";

        SystemMessage systemMessage = new SystemMessage(systemContent);
        String userContent = buildAnalysisContent(httpRequest, userPrompt);
        UserMessage userMessage = new UserMessage(userContent);

        logInfo("使用LangChain4j发送流式请求");
        logInfo("模型: " + (model != null ? model : "qwen-max"));
        logInfo("用户消息长度: " + userContent.length() + " 字符");

        // 添加重试机制
        int maxRetries = 3;
        int retryCount = 0;
        Exception lastException = null;
        // 使用数组包装，以便在匿名内部类中修改
        final int[] contentChunkCount = {0};

        while (retryCount < maxRetries) {
            try {
                // 调用流式生成方法（使用 AI Service 和 StreamingResponseHandler）
                List<ChatMessage> messages = List.of(systemMessage, userMessage);

                // 确保 Assistant 实例已创建（共享实例，保持上下文）
                ensureAssistantInitialized();

                // 使用共享的 Assistant 实例
                Assistant assistant = this.assistant;

                // 调用流式聊天方法
                TokenStream tokenStream = assistant.chat(messages);
                CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
                
                // 保存当前 TokenStream 引用，以便可以取消
                currentTokenStream = tokenStream;

                // 尝试使用带 context 的回调方法以获取 StreamingHandle
                // 根据 LangChain4j 文档，应该使用 onPartialResponse(PartialResponse, PartialResponseContext)
                // 但 TokenStream 的 API 可能使用简化的版本，所以先尝试带 context 的版本，如果失败则回退
                try {
                    // 尝试使用反射调用带 context 的方法
                    java.lang.reflect.Method onPartialResponseMethod = null;
                    for (java.lang.reflect.Method method : tokenStream.getClass().getMethods()) {
                        if (method.getName().equals("onPartialResponse") && method.getParameterCount() == 2) {
                            onPartialResponseMethod = method;
                            break;
                        }
                    }
                    
                    if (onPartialResponseMethod != null) {
                        // 使用带 context 的版本
                        java.util.function.BiConsumer<String, Object> biConsumer = (String partialResponse, Object context) -> {
                            // 尝试从 context 获取 StreamingHandle
                            if (context != null) {
                                try {
                                    java.lang.reflect.Method streamingHandleMethod = context.getClass().getMethod("streamingHandle");
                                    Object handle = streamingHandleMethod.invoke(context);
                                    if (handle != null) {
                                        // 保存 StreamingHandle 到 currentTokenStream（复用字段）
                                        currentTokenStream = (TokenStream) handle;
                                        if (api != null) {
                                            api.logging().logToOutput("[QianwenApiClient] 已获取 StreamingHandle");
                                        }
                                    }
                                } catch (Exception e) {
                                    // 忽略，继续使用 TokenStream
                                }
                            }
                            
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                onChunk.accept(partialResponse);
                                contentChunkCount[0]++;
                            }
                        };
                        onPartialResponseMethod.invoke(tokenStream, biConsumer);
                    } else {
                        // 回退到不带 context 的版本
                        tokenStream.onPartialResponse((String partialResponse) -> {
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                onChunk.accept(partialResponse);
                                contentChunkCount[0]++;
                            }
                        });
                    }
                } catch (Exception e) {
                    // 如果反射失败，使用标准方法
                    tokenStream.onPartialResponse((String partialResponse) -> {
                        if (partialResponse != null && !partialResponse.isEmpty()) {
                            onChunk.accept(partialResponse);
                            contentChunkCount[0]++;
                        }
                    });
                }
                
                tokenStream
                    // 可选：处理思考过程
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        logDebug("Thinking: " + partialThinking);
                    })
                    // 处理中间响应（工具执行后的 AI 继续输出）
                    // 这是关键：当工具执行完成后，AI 会基于工具结果继续输出
                    // 我们需要从中间响应中提取文本内容并传递给 UI
                    .onIntermediateResponse((ChatResponse intermediateResponse) -> {
                        logDebug("Intermediate response received");
                        // 检查中间响应是否包含文本内容（工具执行后的 AI 继续输出）
                        if (intermediateResponse != null && intermediateResponse.aiMessage() != null) {
                            String text = intermediateResponse.aiMessage().text();
                            if (text != null && !text.isEmpty()) {
                                // 将中间响应的文本内容传递给 UI
                                // 这样 AI 在工具执行后继续输出的内容能够实时显示
                                onChunk.accept(text);
                                contentChunkCount[0]++;
                                logDebug("Intermediate response text: " + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
                            }
                        }
                    })
                    // 工具执行前的回调
                    .beforeToolExecution((BeforeToolExecution beforeToolExecution) -> {
                        logDebug("Before tool execution: " + beforeToolExecution);
                        String toolInfoHtml = ToolExecutionFormatter.formatToolExecutionInfo(beforeToolExecution);
                        if (toolInfoHtml != null && !toolInfoHtml.isEmpty()) {
                            onChunk.accept(toolInfoHtml);
                        }
                    })
                    // 工具执行后的回调
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        logDebug("Tool executed: " + toolExecution);
                        // 工具执行完成后，AI 会基于工具结果继续输出
                        // 这些输出会通过 onIntermediateResponse 和 onPartialResponse 传递
                        if (toolExecution != null) {
                            // result() 返回的是 String，直接使用
                            String resultText = toolExecution.result();
                            if (resultText != null && !resultText.isEmpty()) {
                                logDebug("Tool execution result: " + (resultText.length() > 200 ? resultText.substring(0, 200) + "..." : resultText));
                            }
                        }
                    })
                    // 完成响应时
                    .onCompleteResponse((ChatResponse response) -> {
                        logInfo("流式输出完成，共收到 " + contentChunkCount[0] + " 个chunk");
                        futureResponse.complete(response);
                    })
                    // 错误处理
                    .onError((Throwable error) -> {
                        String errorMsg = error.getMessage();
                        String fullErrorMsg = errorMsg;
                        
                        // 获取完整的错误堆栈信息
                        if (error.getCause() != null) {
                            fullErrorMsg = errorMsg + " | Cause: " + error.getCause().getMessage();
                            // 打印堆栈跟踪
                            if (api != null) {
                                api.logging().logToError("[QianwenApiClient] TokenStream错误堆栈:");
                                error.printStackTrace();
                            }
                        }
                        
                        if (errorMsg != null && errorMsg.contains("404")) {
                            logError("TokenStream错误 (404 Not Found): " + fullErrorMsg);
                            logError("请检查：1) API URL是否正确 2) API Key是否有效 3) 模型名称是否正确");
                            logError("当前配置 - API URL: " + apiUrl + ", Model: " + model);
                        } else {
                            logError("TokenStream错误: " + fullErrorMsg);
                            logError("错误类型: " + error.getClass().getName());
                        }
                        
                        futureResponse.completeExceptionally(error);
                    })
                    .start();

                // 等待流式输出完成（最多等待10分钟，因为工具执行可能需要较长时间）
                ChatResponse finalResponse = futureResponse.get(10, java.util.concurrent.TimeUnit.MINUTES);
                
                // 流式输出完成，清除引用
                currentTokenStream = null;

                // 记录token使用信息（可选）
                if (finalResponse != null && finalResponse.tokenUsage() != null) {
                    logInfo("最终响应token使用: " + finalResponse.tokenUsage().toString());
                }

                // 成功，跳出重试循环
                break;

            } catch (java.util.concurrent.TimeoutException e) {
                lastException = new Exception("流式输出超时（10分钟）", e);
                retryCount++;
                logError("请求超时: " + e.getMessage());
            } catch (java.util.concurrent.ExecutionException e) {
                // 直接使用原始异常，避免过度包装
                Throwable cause = e.getCause();
                String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                
                // 打印完整的错误信息
                logError("ExecutionException捕获: " + errorMsg);
                if (cause != null) {
                    logError("异常类型: " + cause.getClass().getName());
                    logError("异常消息: " + cause.getMessage());
                    // 打印堆栈跟踪
                    if (api != null) {
                        api.logging().logToError("[QianwenApiClient] ExecutionException堆栈:");
                        cause.printStackTrace();
                    }
                }
                
                // 检查是否是404错误
                if (errorMsg != null && (errorMsg.contains("404") || errorMsg.contains("Not Found"))) {
                    logError("API请求失败 (404 Not Found): " + errorMsg);
                    logError("可能的原因：");
                    logError("1. API URL配置不正确，当前BaseURL: " + (apiUrl != null ? apiUrl : "未设置"));
                    logError("2. API Key无效或已过期");
                    logError("3. 模型名称不正确，当前模型: " + (model != null ? model : "未设置"));
                    logError("4. 端点路径不正确，请确认使用dashscope模式URL: https://dashscope.aliyuncs.com/v1");
                } else {
                    // 对于其他错误，也输出详细信息
                    logError("API请求失败，错误详情: " + errorMsg);
                }
                
                if (cause instanceof Exception) {
                    lastException = (Exception) cause;
                } else {
                    lastException = new Exception("流式输出失败: " + errorMsg, cause);
                }
                retryCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("等待流式输出被中断", e);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                logError("请求异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                // 打印堆栈跟踪
                if (api != null) {
                    api.logging().logToError("[QianwenApiClient] 异常堆栈:");
                    e.printStackTrace();
                }
            }

        }

        // 检查最终结果
        if (lastException != null && retryCount >= maxRetries) {
            logError("所有重试均失败，共尝试 " + maxRetries + " 次");
            throw new Exception(lastException.getMessage() + " (已重试 " + maxRetries + " 次)", lastException);
        }

        logInfo("流式输出处理完成，总chunk数: " + contentChunkCount[0]);
    }

    private String buildAnalysisContent(String httpContent, String userPrompt) {
        StringBuilder content = new StringBuilder();

        // 如果有HTTP内容，先添加HTTP内容（不重复分析要求，system message已说明）
        if (httpContent != null && !httpContent.trim().isEmpty()) {
            if (httpContent.contains("=== HTTP请求 ===") && httpContent.contains("=== HTTP响应 ===")) {
                content.append("以下是完整的HTTP请求和响应信息：\n\n");
                content.append(httpContent);
            } else if (httpContent.contains("=== HTTP请求 ===")) {
                content.append("以下是HTTP请求内容：\n\n");
                content.append(httpContent);
            } else {
                // 没有特定格式，直接添加
                content.append("以下是HTTP请求内容：\n\n");
                content.append(httpContent);
            }
        }

        // 添加用户提示
        if (userPrompt != null && !userPrompt.trim().isEmpty()) {
            if (content.length() > 0) {
                content.append("\n\n用户提示：").append(userPrompt);
            } else {
                content.append("用户提示：").append(userPrompt);
            }
        }

        return content.toString();
    }

    /**
     * 工具调用数据类
     */
    /* Tools call 相关代码已注释
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;
        
        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
        
        public String getId() {
            return id;
        }
        
        public String getName() {
            return name;
        }
        
        public String getArguments() {
            return arguments;
        }
    }
    */
    
    // 日志辅助方法
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] " + message);
        }
    }

    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[QianwenApiClient] " + message);
        }
    }

    private void logDebug(String message) {
        if (api != null) {
            api.logging().logToOutput("[QianwenApiClient] " + message);
        }
    }


}
