package com.ai.analyzer.api;

import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.mcpClient.AllMcpToolProvider;
import com.ai.analyzer.mcpClient.McpToolMappingConfig;
import com.ai.analyzer.mcpClient.ToolExecutionFormatter;
// import com.ai.analyzer.rag.RagContentManager; // 默认 RAG 暂时禁用
import com.ai.analyzer.utils.RequestSourceDetector;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import com.ai.analyzer.model.PluginSettings;
import burp.api.montoya.MontoyaApi;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.McpToolProvider;
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
    private MontoyaApi api; // Burp API 引用，用于日志输出
    @Getter
    private boolean enableThinking;// 是否启用思考过程
    @Getter
    private boolean enableSearch; // 是否启用搜索
    @Getter
    private boolean enableMcp = false; // 是否启用 Burp MCP 工具调用
    @Getter
    private String BurpMcpUrl = "http://127.0.0.1:9876/sse"; // Burp MCP 服务器地址
    @Getter
    private boolean enableRagMcp = false; // 是否启用 RAG MCP 工具调用
    @Getter
    private String ragMcpUrl = ""; // RAG MCP 服务器地址（stdio 模式不使用）
    @Getter
    private String ragMcpDocumentsPath = ""; // RAG MCP 知识库文档路径
    @Getter
    private boolean enableChromeMcp = false; // 是否启用 Chrome MCP 工具调用
    @Getter
    private String chromeMcpUrl = ""; // Chrome MCP 服务器地址
    @Getter
    private boolean enableFileSystemAccess = false; // 是否启用直接查找知识库（FileSystemAccess）
    // 默认 RAG 功能暂时禁用，改用 RAG MCP
    // @Getter
    // private boolean enableRag = false; // 是否启用 RAG
    // @Getter
    // private String ragDocumentsPath = ""; // RAG 文档路径
    private boolean isFirstInitialization = true; // 是否是第一次初始化
    private boolean needsReinitialization = false; // 标记是否需要重新初始化（延迟初始化，避免频繁重建）
    private Assistant assistant; // 共享的 Assistant 实例，用于保持上下文
    private McpToolProvider mcpToolProvider; // MCP 工具提供者（共享实例）
    // private RagContentManager ragContentManager; // 默认 RAG 功能暂时禁用
    private MessageWindowChatMemory chatMemory; // 共享的聊天记忆，用于保持上下文（即使 Assistant 重新创建也保留）
    private volatile TokenStream currentTokenStream; // 当前活动的 TokenStream，用于取消流式输出
    private volatile StreamingHandle streamingHandle; // 流式句柄，用于正确取消流

    public void setApi(MontoyaApi api) {
        this.api = api;
        // 默认 RAG 暂时禁用
        // if (ragContentManager != null) {
        //     ragContentManager.updateApi(api);
        // }
    }

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
                    //.forcedSearch(enableSearch)
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
            // 收集所有启用的 MCP 客户端
            // 参考文档: https://docs.langchain4j.dev/tutorials/mcp/#mcp-tool-provider
            // 一个 McpToolProvider 可以同时使用多个客户端
            if ((enableMcp || enableRagMcp || enableChromeMcp) && mcpToolProvider == null) {
                try {
                    AllMcpToolProvider mcpProviderHelper = new AllMcpToolProvider();
                    List<McpClient> allMcpClients = new ArrayList<>();
                    List<String> allFilterTools = new ArrayList<>();
                    McpToolMappingConfig mappingConfig = null;
                    
                    // 1. Burp MCP 客户端
                    if (enableMcp) {
                        try {
                            String burpMcpUrlValue = (this.BurpMcpUrl != null && !this.BurpMcpUrl.trim().isEmpty()) 
                                ? this.BurpMcpUrl.trim() 
                                : "http://127.0.0.1:9876/sse";
                            McpTransport burpTransport = mcpProviderHelper.createHttpTransport(burpMcpUrlValue);
                            McpClient burpMcpClient = mcpProviderHelper.createMcpClient(burpTransport, "BurpMCPClient");
                            allMcpClients.add(burpMcpClient);
                            
                            // Burp MCP 工具过滤
                            allFilterTools.addAll(List.of(
                                "send_http1_request", "send_http2_request", 
                                "get_proxy_http_history", "get_proxy_http_history_regex",
                                "get_proxy_websocket_history", "get_proxy_websocket_history_regex", 
                                "get_scanner_issues", "set_task_execution_engine_state",
                                "get_active_editor_contents", "set_active_editor_contents",
                                "create_repeater_tab"//, "send_to_intruder"
                            ));
                            
                            // Burp MCP 工具映射配置
                            mappingConfig = McpToolMappingConfig.createBurpMapping();
                            
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] Burp MCP 客户端已添加，地址: " + burpMcpUrlValue);
                            }
                        } catch (Exception e) {
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] Burp MCP 客户端初始化失败: " + e.getMessage());
                            }
                        }
                    }
                    
                    // 2. RAG MCP 客户端（使用 stdio 传输）
                    if (enableRagMcp && ragMcpDocumentsPath != null && !ragMcpDocumentsPath.trim().isEmpty()) {
                        try {
                            McpTransport ragTransport = mcpProviderHelper.createRagMcpTransport(ragMcpDocumentsPath.trim());
                            McpClient ragMcpClient = mcpProviderHelper.createMcpClient(ragTransport, "RagMCPClient");
                            allMcpClients.add(ragMcpClient);
                            
                            allFilterTools.addAll(List.of("index_document","query_document"));
                            
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] RAG MCP 客户端已添加，知识库路径: " + ragMcpDocumentsPath);
                            }
                        } catch (Exception e) {
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] RAG MCP 客户端初始化失败: " + e.getMessage());
                            }
                        }
                    }
                    
                    // 3. Chrome MCP 客户端（使用 Streamable HTTP 传输）
                    if (enableChromeMcp && chromeMcpUrl != null && !chromeMcpUrl.trim().isEmpty()) {
                        try {
                            McpTransport chromeTransport = mcpProviderHelper.createStreamableHttpTransport(chromeMcpUrl.trim());
                            McpClient chromeMcpClient = mcpProviderHelper.createMcpClient(chromeTransport, "ChromeMCPClient");
                            allMcpClients.add(chromeMcpClient);
                            
                            // Chrome MCP 可以使用所有工具，不添加过滤
                            // 如需过滤，可在此添加: allFilterTools.addAll(List.of(...));
                            
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] Chrome MCP 客户端已添加，地址: " + chromeMcpUrl);
                            }
                        } catch (Exception e) {
                            if (api != null) {
                                api.logging().logToOutput("[QianwenApiClient] Chrome MCP 客户端初始化失败: " + e.getMessage());
                            }
                        }
                    }
                    
                    // 等待连接稳定
                    if (!allMcpClients.isEmpty()) {
                    Thread.sleep(1000);
                    
                        // 创建组合的 Tool Provider
                        String[] filterToolsArray = allFilterTools.isEmpty() ? null : allFilterTools.toArray(new String[0]);
                        mcpToolProvider = mcpProviderHelper.createToolProviderWithMapping(
                            allMcpClients, 
                            mappingConfig, 
                            filterToolsArray
                        );

                    if (api != null) {
                            api.logging().logToOutput("[QianwenApiClient] MCP 工具提供者初始化成功，已添加 " + allMcpClients.size() + " 个 MCP 客户端");
                        }
                    }
                } catch (Exception e) {
                    // MCP 服务器不可用，不影响主要功能
                    if (api != null) {
                        api.logging().logToOutput("[QianwenApiClient] MCP 工具提供者初始化失败: " + e.getMessage());
                    }
                    mcpToolProvider = null;
                }
            } else if (!enableMcp && !enableRagMcp && !enableChromeMcp) {
                // 如果所有 MCP 都禁用了，确保 mcpToolProvider 为 null
                mcpToolProvider = null;
            }
            
            // 确保 ChatMemory 已创建（共享实例，保持上下文）
            // 使用 withMaxMessages 限制消息数量，避免上下文过长
            if (chatMemory == null) {
                chatMemory = MessageWindowChatMemory.builder()
                        .maxMessages(20) // 增加到20条，保留更多上下文
                        .build();
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] ChatMemory 已创建（最大20条消息）");
                }
            }
            
            // 创建 Assistant 实例
            // 使用 systemMessageProvider 动态提供系统消息
            // 参考: https://docs.langchain4j.dev/tutorials/ai-services#system-message-provider
            var assistantBuilder = AiServices.builder(Assistant.class)
                    .streamingChatModel(this.chatModel)
                    .chatMemory(chatMemory)
                    .systemMessageProvider(memoryId -> buildSystemPrompt()); // 动态系统消息
            
            // 如果 MCP 工具提供者可用，则添加工具支持
            if (mcpToolProvider != null) {
                assistantBuilder.toolProvider(mcpToolProvider);
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] 已启用 MCP 工具支持");
                }
            }
            
            // ========== 添加扩展工具 ==========
            if (api != null) {
                // 1. BurpExtTools - Intruder 批量 payload 支持（始终添加）
                com.ai.analyzer.Tools.BurpExtTools burpExtTools = new com.ai.analyzer.Tools.BurpExtTools(api);
                assistantBuilder.tools(burpExtTools);
                api.logging().logToOutput("[QianwenApiClient] 已添加 BurpExtTools");
                
                // 2. FileSystemAccessTools - 让 AI 主动探索知识库（按需添加）
                if (enableFileSystemAccess) {
                    if (ragMcpDocumentsPath != null && !ragMcpDocumentsPath.isEmpty()) {
                        com.ai.analyzer.Tools.FileSystemAccessTools fsaTools = new com.ai.analyzer.Tools.FileSystemAccessTools(api);
                        fsaTools.setAllowedRootPath(ragMcpDocumentsPath);
                        assistantBuilder.tools(fsaTools);
                        api.logging().logToOutput("[QianwenApiClient] 已添加 FileSystemAccessTools (知识库: " + ragMcpDocumentsPath + ")");
                    } else {
                        api.logging().logToOutput("[QianwenApiClient] 警告: 直接查找知识库已启用但未配置路径");
                    }
                }
            }
            
            // 默认 RAG 功能暂时禁用，改用 RAG MCP
            // 如果启用了 RAG，添加 ContentRetriever（使用缓存实例）
            // if (enableRag) {
            //     ensureRagInitialized();
            //     if (ragContentManager != null && ragContentManager.isReady()) {
            //         assistantBuilder.contentRetriever(ragContentManager.getContentRetriever());
            //         if (api != null) {
            //             api.logging().logToOutput("[QianwenApiClient] 已启用 RAG 内容检索");
            //         }
            //     } else if (api != null) {
            //         api.logging().logToOutput("[QianwenApiClient] 警告: RAG 已启用但内容检索器尚未就绪");
            //     }
            // }
            
            assistant = assistantBuilder.build();
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] Assistant 实例已创建（共享实例，保持上下文）");
            }
        }
    }
    
    /**
     * 取消流式输出
     * 当用户点击"停止"按钮或清空上下文时调用
     * 使用 LangChain4j 推荐的 StreamingHandle.cancel() 方法
     */
    public void cancelStreaming() {
        // 优先使用 StreamingHandle 取消（推荐方式）
        if (streamingHandle != null) {
            try {
                streamingHandle.cancel();
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] 流式输出已取消（通过 StreamingHandle）");
                }
            } catch (Exception e) {
                if (api != null) {
                    api.logging().logToError("[QianwenApiClient] 取消流式输出失败: " + e.getMessage());
                }
            } finally {
                streamingHandle = null;
                currentTokenStream = null;
            }
        } else if (currentTokenStream != null) {
            // 兼容旧逻辑：如果 StreamingHandle 不可用，清空 TokenStream 引用
            currentTokenStream = null;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] TokenStream 引用已清空");
            }
        } else {
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] 没有活动的流式输出可以取消");
            }
        }
    }
    
    /**
     * 清空聊天上下文（清空 Assistant 的聊天记忆）
     * 当用户点击"清空"按钮时调用
     * 注意：会先中断正在进行的流式输出
     */
    public void clearContext() {
        // 先中断正在进行的流式输出
        cancelStreaming();
        
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
                // this.enableRag = settings.isEnableRag(); // 默认 RAG 暂时禁用
                // this.ragDocumentsPath = settings.getRagDocumentsPath() != null ? settings.getRagDocumentsPath() : "";
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
                // this.enableRag = settings.isEnableRag(); // 默认 RAG 暂时禁用
                // this.ragDocumentsPath = settings.getRagDocumentsPath() != null ? settings.getRagDocumentsPath() : "";
                return;
            }
        }
        
        // 如果两个文件都不存在，使用默认值
        this.apiUrl = defaultApiUrl;
        this.apiKey = defaultApiKey;
        this.model = defaultModel;
        this.enableThinking = false;
        this.enableSearch = false;
        // this.enableRag = false; // 默认 RAG 暂时禁用
        // this.ragDocumentsPath = "";
    }

    // 默认 RAG 功能暂时禁用，改用 RAG MCP
    // /**
    //  * 在需要时初始化 RAG（例如 UI 完成加载配置后）
    //  */
    // public void ensureRagInitialized() {
    //     if (!enableRag) {
    //         return;
    //     }
    //     RagContentManager manager = getOrCreateRagContentManager();
    //     if (!manager.isReady()) {
    //         manager.load(ragDocumentsPath);
    //     }
    // }
    
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
    
    /**
     * 设置是否启用 MCP 工具调用
     * 如果启用状态改变，需要重新初始化 Assistant
     */
    public void setEnableMcp(boolean enableMcp) {
        if (this.enableMcp != enableMcp) {
            this.enableMcp = enableMcp;
            // 如果禁用 MCP，清空 mcpToolProvider
            if (!enableMcp) {
                mcpToolProvider = null;
            }
            // 清空 Assistant，下次使用时重新创建（会根据新的 enableMcp 状态决定是否启用 MCP）
            assistant = null;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] MCP 工具调用已" + (enableMcp ? "启用" : "禁用"));
            }
        }
    }
    
    /**
     * 设置 Burp MCP 服务器地址
     * 如果地址改变，需要重新初始化 MCP 工具提供者
     */
    public void setBurpMcpUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            mcpUrl = "http://127.0.0.1:9876/sse";
        }
        if (!this.BurpMcpUrl.equals(mcpUrl.trim())) {
            this.BurpMcpUrl = mcpUrl.trim();
            // 如果已启用 MCP，清空 mcpToolProvider，下次使用时重新创建
            if (enableMcp) {
                mcpToolProvider = null;
                assistant = null; // 清空 Assistant，下次使用时重新创建
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] Burp MCP 地址已更新: " + mcpUrl);
                }
            }
        }
    }
    
    /**
     * 设置是否启用 RAG MCP 工具调用
     * 如果启用状态改变，需要重新初始化 Assistant
     */
    public void setEnableRagMcp(boolean enableRagMcp) {
        if (this.enableRagMcp != enableRagMcp) {
            this.enableRagMcp = enableRagMcp;
            // 清空 Assistant，下次使用时重新创建
            assistant = null;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] RAG MCP 工具调用已" + (enableRagMcp ? "启用" : "禁用"));
            }
        }
    }
    
    /**
     * 设置 RAG MCP 服务器地址
     * 如果地址改变，需要重新初始化
     */
    public void setRagMcpUrl(String ragMcpUrl) {
        if (ragMcpUrl == null || ragMcpUrl.trim().isEmpty()) {
            ragMcpUrl = " ";
                }
        if (!this.ragMcpUrl.equals(ragMcpUrl.trim())) {
            this.ragMcpUrl = ragMcpUrl.trim();
            if (enableRagMcp) {
                assistant = null; // 清空 Assistant，下次使用时重新创建
            if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] RAG MCP 地址已更新: " + ragMcpUrl);
                }
            }
        }
    }
    
    /**
     * 设置 RAG MCP 知识库文档路径
     * 如果路径改变，需要重新初始化
     */
    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) {
        if (ragMcpDocumentsPath == null) {
            ragMcpDocumentsPath = "";
        }
        if (!this.ragMcpDocumentsPath.equals(ragMcpDocumentsPath.trim())) {
            this.ragMcpDocumentsPath = ragMcpDocumentsPath.trim();
            if (enableRagMcp) {
                assistant = null; // 清空 Assistant，下次使用时重新创建
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] RAG MCP 文档路径已更新: " + ragMcpDocumentsPath);
                }
            }
        }
    }
    
    /**
     * 设置是否启用 Chrome MCP 工具调用
     * 如果启用状态改变，需要重新初始化 Assistant
     */
    public void setEnableChromeMcp(boolean enableChromeMcp) {
        if (this.enableChromeMcp != enableChromeMcp) {
            this.enableChromeMcp = enableChromeMcp;
            // 清空 Assistant，下次使用时重新创建
            assistant = null;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] Chrome MCP 工具调用已" + (enableChromeMcp ? "启用" : "禁用"));
            }
        }
    }
    
    /**
     * 设置 Chrome MCP 服务器地址
     * 如果地址改变，需要重新初始化
     */
    public void setChromeMcpUrl(String chromeMcpUrl) {
        if (chromeMcpUrl == null || chromeMcpUrl.trim().isEmpty()) {
            chromeMcpUrl = " ";
        }
        if (!this.chromeMcpUrl.equals(chromeMcpUrl.trim())) {
            this.chromeMcpUrl = chromeMcpUrl.trim();
            if (enableChromeMcp) {
                assistant = null; // 清空 Assistant，下次使用时重新创建
                if (api != null) {
                    api.logging().logToOutput("[QianwenApiClient] Chrome MCP 地址已更新: " + chromeMcpUrl);
                }
            }
        }
    }
    
    /**
     * 设置是否启用直接查找知识库（FileSystemAccess）
     * 启用后 AI 可以主动浏览、搜索、读取知识库文件
     */
    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) {
        if (this.enableFileSystemAccess != enableFileSystemAccess) {
            this.enableFileSystemAccess = enableFileSystemAccess;
            // 清空 Assistant，下次使用时重新创建
            assistant = null;
            if (api != null) {
                api.logging().logToOutput("[QianwenApiClient] 直接查找知识库已" + (enableFileSystemAccess ? "启用" : "禁用"));
            }
        }
    }
    
    // ========== 默认 RAG 功能暂时禁用，改用 RAG MCP ==========
    // /**
    //  * 设置是否启用 RAG
    //  * 如果启用状态改变，需要重新初始化 RAG 和 Assistant
    //  */
    // public void setEnableRag(boolean enableRag) {
    //     if (this.enableRag != enableRag) {
    //         this.enableRag = enableRag;
    //         // 新逻辑：直接维护内存向量存储
    //         if (enableRag) {
    //             loadRagContent();
    //         } else {
    //             clearRagContentManager();
    //         }
    //         // 清空 Assistant，下次使用时重新创建
    //         assistant = null;
    //         if (api != null) {
    //             api.logging().logToOutput("[QianwenApiClient] RAG 已" + (enableRag ? "启用" : "禁用"));
    //         }
    //     }
    // }
    // 
    // /**
    //  * 设置 RAG 文档路径
    //  * 如果路径改变且已启用 RAG，需要重新加载文档
    //  */
    // public void setRagDocumentsPath(String ragDocumentsPath) {
    //     if (ragDocumentsPath == null) {
    //         ragDocumentsPath = "";
    //     }
    //     if (!this.ragDocumentsPath.equals(ragDocumentsPath.trim())) {
    //         this.ragDocumentsPath = ragDocumentsPath.trim();
    //         if (enableRag) {
    //             loadRagContent();
    //             assistant = null;
    //             if (api != null) {
    //                 api.logging().logToOutput("[QianwenApiClient] RAG 文档路径已更新: " + ragDocumentsPath);
    //             }
    //         } else {
    //             clearRagContentManager();
    //         }
    //     }
    // }
    // 
    // private void loadRagContent() {
    //     if (!enableRag) {
    //         clearRagContentManager();
    //         return;
    //     }
    //     getOrCreateRagContentManager().load(ragDocumentsPath);
    // }
    //
    // private RagContentManager getOrCreateRagContentManager() {
    //     if (ragContentManager == null) {
    //         ragContentManager = new RagContentManager(api);
    //     } else {
    //         ragContentManager.updateApi(api);
    //     }
    //     return ragContentManager;
    // }
    //
    // private void clearRagContentManager() {
    //     if (ragContentManager != null) {
    //         ragContentManager.clear();
    //     }
    // }
    // ========== 默认 RAG 功能暂时禁用结束 ==========


    // 流式输出方法 - 使用LangChain4j（带请求来源检测）
    public void analyzeRequestStream(HttpRequestResponse requestResponse, String userPrompt, Consumer<String> onChunk) throws Exception {
        // 检测请求来源
        RequestSourceDetector.RequestSourceInfo sourceInfo = null;
        if (api != null && requestResponse != null) {
            sourceInfo = RequestSourceDetector.detectSource(api, requestResponse);
        }

        api.logging().logToOutput("[QianwenApiClient] 请求来源: " + sourceInfo.format());
        
        // 格式化 HTTP 内容
        String httpRequest = requestResponse != null 
            ? com.ai.analyzer.utils.HttpFormatter.formatHttpRequestResponse(requestResponse)
            : "";
        
        // 调用原始方法
        analyzeRequestStream(httpRequest, userPrompt, sourceInfo, onChunk);
    }
    
    // 流式输出方法 - 使用LangChain4j（原始方法，保持向后兼容）
    public void analyzeRequestStream(String httpRequest, String userPrompt, Consumer<String> onChunk) throws Exception {
        analyzeRequestStream(httpRequest, userPrompt, null, onChunk);
    }
    
    // 流式输出方法 - 使用LangChain4j（内部方法，支持请求来源信息）
    private void analyzeRequestStream(String httpRequest, String userPrompt, RequestSourceDetector.RequestSourceInfo sourceInfo, Consumer<String> onChunk) throws Exception {
        // 确保 ChatModel 已初始化且使用最新的配置（延迟初始化）
        ensureChatModelInitialized();
        
        if (chatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API Key和URL配置");
        }

        // 构建用户消息内容
        // 注意：系统消息通过 systemMessageProvider 自动注入，无需手动添加
        String userContent = buildAnalysisContent(httpRequest, userPrompt, sourceInfo);
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
                // 确保 Assistant 实例已创建（共享实例，保持上下文）
                // 系统消息通过 systemMessageProvider 自动注入
                ensureAssistantInitialized();
                
                // 只传入用户消息，系统消息由 systemMessageProvider 提供
                List<ChatMessage> messages = List.of(userMessage);

                // 使用共享的 Assistant 实例
                Assistant assistant = this.assistant;

                // 调用流式聊天方法
                TokenStream tokenStream = assistant.chat(messages);
                CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
                
                // 保存当前 TokenStream 引用，以便可以取消
                currentTokenStream = tokenStream;

                // 使用链式调用配置所有回调
                tokenStream
                    // 处理部分响应（流式输出的主要内容）+ 获取 StreamingHandle 用于取消
                    .onPartialResponseWithContext((PartialResponse partialResponse, 
                            PartialResponseContext context) -> {
                        // 保存 StreamingHandle 引用，用于后续取消操作
                        if (streamingHandle == null && context != null) {
                            streamingHandle = context.streamingHandle();
                        }
                        String text = partialResponse != null ? partialResponse.text() : null;
                        if (text != null && !text.isEmpty()) {
                            onChunk.accept(text);
                        contentChunkCount[0]++;
                    }
                    })
                    // 可选：处理思考过程
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        logDebug("Thinking: " + partialThinking);
                    })
                    // 注意：移除了 onIntermediateResponse 回调，避免可能的重复输出问题
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
                streamingHandle = null;

                // 记录token使用信息（可选）
                if (finalResponse != null && finalResponse.tokenUsage() != null) {
                    logInfo("最终响应token使用: " + finalResponse.tokenUsage().toString());
                }

                // 成功，跳出重试循环
                break;

            } catch (java.util.concurrent.TimeoutException e) {
                // 超时时清除引用
                currentTokenStream = null;
                streamingHandle = null;
                lastException = new Exception("流式输出超时（10分钟）", e);
                retryCount++;
                logError("请求超时: " + e.getMessage());
            } catch (java.util.concurrent.ExecutionException e) {
                // 异常时清除引用
                currentTokenStream = null;
                streamingHandle = null;
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
                // 中断时清除引用
                currentTokenStream = null;
                streamingHandle = null;
                Thread.currentThread().interrupt();
                throw new Exception("等待流式输出被中断", e);
            } catch (Exception e) {
                // 异常时清除引用
                currentTokenStream = null;
                streamingHandle = null;
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

    private String buildAnalysisContent(String httpContent, String userPrompt, RequestSourceDetector.RequestSourceInfo sourceInfo) {
        StringBuilder content = new StringBuilder();

        // 如果有请求来源信息，添加到顶部
        if (sourceInfo != null) {
            content.append(sourceInfo.format()).append("\n\n");
        }

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
     * 构建系统提示词 - 核心决策框架
     * 设计原则：
     * 1. 角色清晰 - 明确 AI 的职责和能力边界
     * 2. 决策框架 - 提供清晰的 "观察-分析-决策-执行" 流程
     * 3. 工具优先级 - 明确工具调用的条件和顺序
     * 4. 安全边界 - 防止不当操作
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        // ========== 角色定位 ==========
        prompt.append("# 角色定位\n");
        prompt.append("你是一个专业的 Web 安全渗透测试 AI 助手，具备以下能力：\n");
        prompt.append("- **分析能力**：识别 HTTP 请求/响应中的安全风险（OWASP Top 10）\n");
        prompt.append("- **执行能力**：通过工具直接进行渗透测试验证\n");
        prompt.append("- **辅助能力**：为渗透测试工程师提供测试建议和 POC\n\n");
        
        // ========== 核心决策框架 ==========
        prompt.append("# 决策框架（必须遵循）\n\n");
        
        prompt.append("## 第一步：理解意图\n");
        prompt.append("分析用户请求属于哪种类型：\n");
        prompt.append("- **分析请求**：用户提供 HTTP 内容，要求分析安全风险 → 执行分析流程\n");
        prompt.append("- **执行请求**：用户明确要求测试/验证某个漏洞 → 执行测试流程\n");
        prompt.append("- **查询请求**：用户询问历史记录或扫描结果 → 使用查询工具\n");
        prompt.append("- **对话请求**：用户进行普通对话 → 直接回复，不调用工具\n\n");
        
        prompt.append("## 第二步：分析流程（当收到 HTTP 内容时）\n");
        prompt.append("1. 识别目标信息：主机、端口、协议、接口路径\n");
        prompt.append("2. 分析请求特征：参数类型、认证方式、数据格式\n");
        prompt.append("3. 评估风险等级：只报告中危及以上的风险\n");
        prompt.append("4. 根据风险自动决定是否需要测试验证\n\n");
        
        prompt.append("## 第三步：执行流程（发现可测试风险时）\n");
        prompt.append("1. 构造测试 payload（基于识别的风险类型）\n");
        prompt.append("2. 使用 `send_http1_request` 发送测试请求\n");
        prompt.append("3. 分析响应，判断漏洞是否存在\n");
        prompt.append("4. **必须**将测试请求发送到 `create_repeater_tab`，便于用户手动验证\n");
        prompt.append("5. 如需批量测试，额外发送到 `send_to_intruder`\n\n");
        
        // ========== 工具使用规则 ==========
        if (enableMcp || enableRagMcp || enableChromeMcp) {
            prompt.append("# 工具使用规则\n\n");
            
            if (enableMcp) {
                prompt.append("## Burp Suite 工具\n\n");
                
                prompt.append("### 查询类工具（被动，可随时使用）\n");
                prompt.append("| 工具 | 使用场景 | 注意事项 |\n");
                prompt.append("|------|---------|----------|\n");
                prompt.append("| `get_proxy_http_history` | 用户要求查看历史请求 | 使用分页：count=10, offset=0 |\n");
                prompt.append("| `get_proxy_http_history_regex` | 按关键词搜索历史 | regex 参数支持正则 |\n");
                prompt.append("| `get_scanner_issues` | 查看扫描器发现的问题 | 仅 Professional 版 |\n");
                prompt.append("| `get_active_editor_contents` | 获取当前编辑器内容 | 需要焦点在编辑器 |\n\n");
                
                prompt.append("### 执行类工具（主动测试验证）\n");
                prompt.append("| 工具 | 使用条件 | 说明 |\n");
                prompt.append("|------|---------|------|\n");
                prompt.append("| `send_http1_request` | 发现可测试的安全风险时 | 核心测试工具 |\n");
                prompt.append("| `send_http2_request` | HTTP/2 协议测试 | 核心测试工具 |\n\n");
                
                prompt.append("### 辅助工具（智能决策，按需调用）\n");
                prompt.append("| 工具 | 调用条件 | 说明 |\n");
                prompt.append("|------|---------|------|\n");
                prompt.append("| `create_repeater_tab` | 见下方决策规则 | 发送到 Repeater 供人类验证 |\n");
                //prompt.append("| `send_to_intruder` | 需要批量 fuzz | 发送到 Intruder 批量测试 |\n\n");
                
                prompt.append("### `create_repeater_tab` 智能决策规则（重要）\n");
                prompt.append("**原则：只有需要人类确认的请求才发送到 Repeater**\n\n");
                prompt.append("| 测试结果 | 是否发送 | 原因 |\n");
                prompt.append("|---------|---------|------|\n");
                prompt.append("| ✅ 发现漏洞/成功POC | **必须发送** | 人类必须确认漏洞真实性 |\n");
                prompt.append("| ⚠️ 疑似漏洞/不确定 | 建议发送 | 需要人类进一步分析判断 |\n");
                prompt.append("| ❌ 确认无漏洞 | **不发送** | 减少噪音，避免浪费时间 |\n\n");
                prompt.append("**示例**：\n");
                prompt.append("- 响应包含 `root:x:0:0` → 发现文件读取漏洞 → **必须发送**\n");
                prompt.append("- SQL 语法错误回显 → 可能存在注入 → **必须发送**\n");
                prompt.append("- 返回正常业务响应，无异常 → 确认无漏洞 → **不发送**\n");
                prompt.append("- 返回 403/404 → 无漏洞 → **不发送**\n\n");
                
                prompt.append("### 工具调用效率指南\n");
                prompt.append("1. **获取历史记录**：合并关键词 `regex=\".*(login|api|upload).*\"`\n");
                prompt.append("2. **测试流程**：`send_http1_request` → 分析响应 → 按决策规则决定是否调用 `create_repeater_tab`\n\n");
                
                prompt.append("### 禁止行为\n");
                prompt.append("- ❌ 测试结果为【无漏洞】时仍发送到 Repeater\n");
                prompt.append("- ❌ 串行调用多个相似的查询工具（应合并）\n");
                prompt.append("- ❌ 对非目标系统发送请求\n");
                prompt.append("- ❌ 发送破坏性 payload（DELETE、DROP 等）\n\n");
            }
            
            if (enableRagMcp) {
                prompt.append("## 知识库工具\n");
                prompt.append("| 工具 | 使用场景 |\n");
                prompt.append("|------|----------|\n");
                prompt.append("| `index_document` | 用户要求索引新文档 |\n");
                prompt.append("| `query_document` | 需要查询漏洞 POC/技术细节时，如果没找到相关知识库，则主动索引新文档 |\n\n");
                prompt.append("**使用时机**：当你不确定某个漏洞的测试方法，或需要参考已知 POC 时，主动查询知识库。\n\n");
            }
            
            if (enableChromeMcp) {
                prompt.append("## Chrome 浏览器工具\n");
                prompt.append("用于需要浏览器交互的测试场景（如 XSS 验证、前端漏洞测试）。\n\n");
            }
        }
        
        // ========== 扩展工具 ==========
        prompt.append("## 扩展工具\n\n");
        
        // BurpExtTools - 始终可用
        prompt.append("### Intruder 批量测试工具\n");
        prompt.append("| 工具 | 使用场景 | 说明 |\n");
        prompt.append("|------|---------|------|\n");
        prompt.append("| `BurpExtTools_send_to_intruder` | 需要批量 fuzz 测试时 | AI 生成 payloads 并发送到 Intruder |\n\n");
        prompt.append("**使用方法**：\n");
        prompt.append("1. 在请求中用 `§` 标记插入点，例如：`id=§1§`\n");
        prompt.append("2. 提供 payloads 数组，例如：`[\"' OR '1'='1\", \"admin'--\"]`\n");
        prompt.append("3. AI 会自动将请求发送到 Intruder 并配置 payloads\n");
        prompt.append("4. 用户只需在 Intruder 中选择 \"Extension-generated\" → \"AI Analyzer Payloads\" 即可开始攻击\n\n");
        prompt.append("**适用场景**：SQL 注入批量测试、XSS fuzz、参数枚举、弱口令爆破等\n\n");
        
        // FileSystemAccessTools - 按需启用
        if (enableFileSystemAccess && ragMcpDocumentsPath != null && !ragMcpDocumentsPath.isEmpty()) {
            prompt.append("### 知识库探索工具\n");
            prompt.append("**当前知识库路径**：`" + ragMcpDocumentsPath + "`\n\n");
            prompt.append("| 工具 | 功能 | 使用场景 |\n");
            prompt.append("|------|------|----------|\n");
            prompt.append("| `FSA_list_directory` | 列出目录内容 | 探索知识库结构，了解有哪些文档 |\n");
            prompt.append("| `FSA_read_file` | 读取文件内容 | 阅读 POC、漏洞详情、配置文件等 |\n");
            prompt.append("| `FSA_find_files` | 按文件名搜索 | 快速定位文件，支持 `*.md`、`poc_*.py` 等通配符 |\n");
            prompt.append("| `FSA_grep_search` | 在文件内容中搜索 | 查找特定漏洞、代码模式、关键词 |\n");
            prompt.append("| `FSA_file_info` | 获取文件信息 | 查看文件大小、修改时间等 |\n\n");
            prompt.append("**使用策略**：\n");
            prompt.append("1. 先用 `FSA_list_directory` 或 `FSA_find_files` 了解知识库结构\n");
            prompt.append("2. 用 `FSA_grep_search` 搜索相关漏洞或技术关键词\n");
            prompt.append("3. 用 `FSA_read_file` 阅读具体的 POC 或文档\n");
            prompt.append("4. 将知识库中的 payload 应用到实际测试中\n\n");
            prompt.append("**最佳实践**：当你不确定某个漏洞的测试方法时，主动搜索知识库查找参考资料。\n\n");
        }
        
        // ========== 漏洞类型与测试策略映射 ==========
        prompt.append("# 漏洞类型与测试策略\n\n");
        prompt.append("根据识别的风险类型，采用对应的测试策略：\n\n");
        prompt.append("| 漏洞类型 | 识别特征 | 测试策略 |\n");
        prompt.append("|---------|---------|----------|\n");
        prompt.append("| SQL注入 | 参数拼接、数字型参数、搜索功能 | 单引号、布尔盲注、时间盲注 |\n");
        prompt.append("| XSS | 输入反射、HTML参数、富文本 | script标签、事件处理器、编码绕过 |\n");
        prompt.append("| 命令注入 | 文件操作、系统调用、ping功能 | 管道符、命令分隔符、反引号 |\n");
        prompt.append("| 路径遍历 | 文件下载、图片加载、include参数 | ../序列、编码变体、绝对路径 |\n");
        prompt.append("| SSRF | URL参数、回调地址、webhook | 内网IP、localhost、协议绕过 |\n");
        prompt.append("| XXE | XML上传、SOAP接口、SVG处理 | 外部实体、参数实体、DTD注入 |\n");
        prompt.append("| 认证缺陷 | 登录接口、JWT、Session | 弱口令、爆破、会话固定 |\n");
        prompt.append("| 越权 | ID参数、用户标识、资源访问 | 水平越权、垂直越权、IDOR |\n\n");
        
        prompt.append("**测试原则**：\n");
        prompt.append("1. 优先测试高危漏洞（RCE > SQL注入 > XSS）\n");
        prompt.append("2. 构造无害的探测 payload，避免破坏性操作\n");
        prompt.append("3. 根据响应特征判断漏洞存在性\n\n");
        
        // ========== 输出格式 ==========
        prompt.append("# 输出格式\n\n");
        prompt.append("## 分析报告格式\n");
        prompt.append("```\n");
        prompt.append("### 风险名称 (严重程度: 高/中)\n");
        prompt.append("- **风险点**: 具体描述\n");
        prompt.append("- **影响**: 可能造成的危害\n");
        prompt.append("- **测试建议**: 如何验证\n");
        prompt.append("```\n\n");
        
        prompt.append("## 格式要求\n");
        prompt.append("- 使用 Markdown 格式\n");
        prompt.append("- 不使用表格（`|`）和分隔线（`---`）\n");
        prompt.append("- 简洁明了，突出重点\n");
        prompt.append("- 只报告中危及以上风险\n\n");
        
        // ========== 交互原则 ==========
        prompt.append("# 交互原则\n");
        prompt.append("1. **先分析后执行**：收到请求先分析风险，发现高危风险时主动测试验证\n");
        prompt.append("2. **解释决策**：调用工具前简要说明原因\n");
        prompt.append("3. **报告结果**：工具执行后清晰报告发现\n");
        prompt.append("4. **保持上下文**：记住之前的对话和测试结果\n");
        
        return prompt.toString();
    }
    
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
