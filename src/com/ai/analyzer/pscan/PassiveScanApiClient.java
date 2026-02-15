package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.mcpClient.AllMcpToolProvider;
import com.ai.analyzer.mcpClient.McpToolMappingConfig;
import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.utils.HttpFormatter;
import com.ai.analyzer.rulesMatch.PreScanFilterManager;
import com.ai.analyzer.rulesMatch.PreScanFilter;
import com.ai.analyzer.rulesMatch.ScanMatch;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.Getter;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 被动扫描专用的AI API客户端
 * 
 * 设计原则（DAST风格）：
 * 1. 共享ChatMemory - 多个扫描任务共享分析记录，AI可以积累上下文知识
 * 2. 完整功能 - 支持MCP工具调用、联网搜索等与AgentApiClient相同的功能
 * 3. 松耦合 - 与主AgentApiClient分离，有独立的状态管理
 * 4. 线程安全 - 使用同步机制保护共享资源
 */
public class PassiveScanApiClient {
    
    // API 提供者类型枚举
    public enum ApiProvider {
        DASHSCOPE("DashScope"),
        OPENAI_COMPATIBLE("OpenAI兼容");
        
        private final String displayName;
        
        ApiProvider(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public static ApiProvider fromDisplayName(String displayName) {
            for (ApiProvider provider : values()) {
                if (provider.displayName.equals(displayName)) {
                    return provider;
                }
            }
            return DASHSCOPE;
        }
    }
    
    // API配置
    @Getter
    private String apiKey;
    @Getter
    private String apiUrl;
    @Getter
    private String model;
    @Getter
    private ApiProvider apiProvider = ApiProvider.DASHSCOPE;
    
    // 共享的ChatModel（线程安全）
    private volatile StreamingChatModel chatModel;
    private final Object chatModelLock = new Object();
    
    // 共享的ChatMemory - DAST风格：多个Agent共享分析记录
    private volatile MessageWindowChatMemory chatMemory;
    private final Object chatMemoryLock = new Object();
    
    // 共享的Assistant实例
    private volatile Assistant assistant;
    private final Object assistantLock = new Object();
    
    // MCP 工具提供者
    private McpToolProvider mcpToolProvider;
    
    // Burp API引用
    private MontoyaApi api;
    
    // 前置扫描过滤器管理器
    private PreScanFilterManager preScanFilterManager;
    
    // 功能开关
    @Getter
    private boolean enableThinking = false;
    @Getter
    private boolean enableSearch = false;
    @Getter
    private boolean enableMcp = false;
    @Getter
    private String BurpMcpUrl = "http://127.0.0.1:9876/sse";
    @Getter
    private boolean enableRagMcp = false;
    @Getter
    private String ragMcpUrl = "";
    @Getter
    private String ragMcpDocumentsPath = "";
    @Getter
    private boolean enableChromeMcp = false;
    @Getter
    private String chromeMcpUrl = "";
    @Getter
    private boolean enableFileSystemAccess = false;
    
    // 标记是否需要重新初始化
    private boolean needsReinitialization = false;
    private boolean isFirstInitialization = true;
    
    /**
     * 无参构造函数，从配置文件加载设置
     */
    public PassiveScanApiClient() {
        loadSettingsFromFile();
    }
    
    /**
     * 带MontoyaApi的构造函数
     */
    public PassiveScanApiClient(MontoyaApi api) {
        this.api = api;
        loadSettingsFromFile();
    }
    
    /**
     * 完整参数的构造函数
     */
    public PassiveScanApiClient(MontoyaApi api, String apiUrl, String apiKey, String model) {
        this.api = api;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }
    
    /**
     * 设置MontoyaApi引用
     */
    public void setApi(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 从配置文件加载设置
     */
    private void loadSettingsFromFile() {
        String defaultApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String defaultApiKey = "";
        String defaultModel = "qwen3-max";
        
        File localSettingsFile = new File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(localSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultApiKey, defaultModel);
                return;
            }
        }
        
        File userSettingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(userSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultApiKey, defaultModel);
                return;
            }
        }
        
        // 使用默认值
        this.apiUrl = defaultApiUrl;
        this.apiKey = defaultApiKey;
        this.model = defaultModel;
    }
    
    private void applySettings(PluginSettings settings, String defaultApiUrl, String defaultApiKey, String defaultModel) {
        this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
            ? settings.getApiUrl() : defaultApiUrl;
        this.apiKey = settings.getApiKey() != null && !settings.getApiKey().isEmpty() 
            ? settings.getApiKey() : defaultApiKey;
        this.model = settings.getModel() != null && !settings.getModel().isEmpty() 
            ? settings.getModel() : defaultModel;
        this.enableThinking = settings.isEnableThinking();
        this.enableSearch = settings.isEnableSearch();
        this.apiProvider = ApiProvider.fromDisplayName(settings.getApiProvider());
        this.enableMcp = settings.isEnableMcp();
        this.BurpMcpUrl = settings.getMcpUrl();
        this.enableRagMcp = settings.isEnableRagMcp();
        this.ragMcpUrl = settings.getRagMcpUrl();
        this.ragMcpDocumentsPath = settings.getRagMcpDocumentsPath();
        this.enableChromeMcp = settings.isEnableChromeMcp();
        this.chromeMcpUrl = settings.getChromeMcpUrl();
        this.enableFileSystemAccess = settings.isEnableFileSystemAccess();
    }
    
    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
            return (PluginSettings) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    // ========== 配置设置方法 ==========
    
    public void setApiUrl(String apiUrl) {
        if (this.apiUrl == null || !this.apiUrl.equals(apiUrl)) {
            this.apiUrl = apiUrl;
            needsReinitialization = true;
        }
    }
    
    public void setApiKey(String apiKey) {
        if (this.apiKey == null || !this.apiKey.equals(apiKey)) {
            this.apiKey = apiKey;
            needsReinitialization = true;
        }
    }
    
    public void setModel(String model) {
        if (this.model == null || !this.model.equals(model)) {
            this.model = model;
            needsReinitialization = true;
        }
    }
    
    public void setApiProvider(ApiProvider provider) {
        if (this.apiProvider != provider) {
            this.apiProvider = provider;
            needsReinitialization = true;
        }
    }
    
    public void setApiProvider(String providerName) {
        setApiProvider(ApiProvider.fromDisplayName(providerName));
    }
    
    public void setEnableThinking(boolean enableThinking) {
        if (this.enableThinking != enableThinking) {
            this.enableThinking = enableThinking;
            needsReinitialization = true;
        }
    }
    
    public void setEnableSearch(boolean enableSearch) {
        if (this.enableSearch != enableSearch) {
            this.enableSearch = enableSearch;
            needsReinitialization = true;
        }
    }
    
    public void setEnableMcp(boolean enableMcp) {
        if (this.enableMcp != enableMcp) {
            this.enableMcp = enableMcp;
            synchronized (assistantLock) {
                assistant = null;
                if (!enableMcp) {
                    mcpToolProvider = null;
                }
            }
        }
    }
    
    public void setBurpMcpUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            mcpUrl = "http://127.0.0.1:9876/sse";
        }
        if (!this.BurpMcpUrl.equals(mcpUrl.trim())) {
            this.BurpMcpUrl = mcpUrl.trim();
            if (enableMcp) {
                synchronized (assistantLock) {
                    mcpToolProvider = null;
                    assistant = null;
                }
            }
        }
    }
    
    public void setEnableRagMcp(boolean enableRagMcp) {
        if (this.enableRagMcp != enableRagMcp) {
            this.enableRagMcp = enableRagMcp;
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }
    
    public void setRagMcpUrl(String ragMcpUrl) {
        if (ragMcpUrl == null) ragMcpUrl = "";
        if (!this.ragMcpUrl.equals(ragMcpUrl.trim())) {
            this.ragMcpUrl = ragMcpUrl.trim();
            if (enableRagMcp) {
                synchronized (assistantLock) {
                    assistant = null;
                }
            }
        }
    }
    
    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) {
        if (ragMcpDocumentsPath == null) ragMcpDocumentsPath = "";
        if (!this.ragMcpDocumentsPath.equals(ragMcpDocumentsPath.trim())) {
            this.ragMcpDocumentsPath = ragMcpDocumentsPath.trim();
            if (enableRagMcp) {
                synchronized (assistantLock) {
                    assistant = null;
                }
            }
        }
    }
    
    public void setEnableChromeMcp(boolean enableChromeMcp) {
        if (this.enableChromeMcp != enableChromeMcp) {
            this.enableChromeMcp = enableChromeMcp;
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }
    
    public void setChromeMcpUrl(String chromeMcpUrl) {
        if (chromeMcpUrl == null) chromeMcpUrl = "";
        if (!this.chromeMcpUrl.equals(chromeMcpUrl.trim())) {
            this.chromeMcpUrl = chromeMcpUrl.trim();
            if (enableChromeMcp) {
                synchronized (assistantLock) {
                    assistant = null;
                }
            }
        }
    }
    
    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) {
        if (this.enableFileSystemAccess != enableFileSystemAccess) {
            this.enableFileSystemAccess = enableFileSystemAccess;
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }
    
    /**
     * 设置前置扫描过滤器管理器
     */
    public void setPreScanFilterManager(PreScanFilterManager preScanFilterManager) {
        this.preScanFilterManager = preScanFilterManager;
    }
    
    /**
     * 确保ChatModel已初始化（延迟初始化，线程安全）
     */
    private void ensureChatModelInitialized() {
        if (needsReinitialization || chatModel == null) {
            synchronized (chatModelLock) {
                if (needsReinitialization || chatModel == null) {
                    // 清理旧的Assistant
                    synchronized (assistantLock) {
                        assistant = null;
                    }
                    chatModel = createChatModel();
                    needsReinitialization = false;
                }
            }
        }
    }
    
    /**
     * 创建ChatModel
     */
    private StreamingChatModel createChatModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logError("API Key为空，无法创建ChatModel");
            return null;
        }
        
        try {
            if (apiProvider == ApiProvider.OPENAI_COMPATIBLE) {
                return createOpenAIChatModel();
            } else {
                return createQwenChatModel();
            }
        } catch (Exception e) {
            logError("创建ChatModel失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 创建Qwen ChatModel（支持联网搜索和深度思考）
     */
    private StreamingChatModel createQwenChatModel() {
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
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://dashscope.aliyuncs.com/api/v1";
        }
        
        String modelName = model != null && !model.trim().isEmpty() ? model : "qwen-max";
        
        if (isFirstInitialization) {
            logInfo("创建Qwen ChatModel - URL: " + baseUrl + ", Model: " + modelName);
            logInfo("EnableThinking: " + enableThinking + ", EnableSearch: " + enableSearch);
            isFirstInitialization = false;
        }
        
        QwenChatRequestParameters.SearchOptions searchOptions = QwenChatRequestParameters.SearchOptions.builder()
                .searchStrategy("max")
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
                .temperature(0.5f) // 被动扫描使用适中温度
                .defaultRequestParameters(parameters)
                .build();
    }
    
    /**
     * 创建OpenAI兼容ChatModel
     */
    private StreamingChatModel createOpenAIChatModel() {
        String baseUrl = this.apiUrl;
        String modelName = this.model;
        
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://api.openai.com/v1";
        }
        
        if (!baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "v1";
            } else {
                baseUrl = baseUrl + "/v1";
            }
        }
        
        if (modelName == null || modelName.trim().isEmpty()) {
            modelName = "gpt-3.5-turbo";
        }
        
        if (isFirstInitialization) {
            logInfo("创建OpenAI ChatModel - URL: " + baseUrl + ", Model: " + modelName);
            isFirstInitialization = false;
        }
        
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.5)
                .build();
    }
    
    /**
     * 确保Assistant已初始化（共享实例，支持MCP工具调用）
     */
    private void ensureAssistantInitialized() {
        if (assistant == null) {
            synchronized (assistantLock) {
                if (assistant == null) {
                    // 初始化MCP工具提供者
                    initializeMcpToolProvider();
                    
                    // 确保共享的ChatMemory已创建
                    ensureChatMemoryInitialized();
                    
                    // 创建Assistant实例
                    var assistantBuilder = AiServices.builder(Assistant.class)
                            .streamingChatModel(this.chatModel)
                            .chatMemory(chatMemory)
                            .systemMessageProvider(memoryId -> buildSystemPrompt());
                    
                    // 如果MCP工具提供者可用，则添加工具支持
                    if (mcpToolProvider != null) {
                        assistantBuilder.toolProvider(mcpToolProvider);
                        logInfo("已启用 MCP 工具支持");
                    }
                    
                    // 添加扩展工具
                    if (api != null) {
                        // CurlTools（替代 send_http1_request 和 send_http2_request，更稳定）
                        com.ai.analyzer.Tools.CurlTools curlTools = new com.ai.analyzer.Tools.CurlTools(api);
                        assistantBuilder.tools(curlTools);
                        logInfo("已添加 CurlTools（替代 send_http1_request/send_http2_request）");
                        
                        if (enableMcp) {
                            com.ai.analyzer.Tools.BurpExtTools burpExtTools = new com.ai.analyzer.Tools.BurpExtTools(api);
                            assistantBuilder.tools(burpExtTools);
                            logInfo("已添加 BurpExtTools");
                        }
                        
                        if (enableFileSystemAccess && ragMcpDocumentsPath != null && !ragMcpDocumentsPath.isEmpty()) {
                            com.ai.analyzer.Tools.FileSystemAccessTools fsaTools = new com.ai.analyzer.Tools.FileSystemAccessTools(api);
                            fsaTools.setAllowedRootPath(ragMcpDocumentsPath);
                            assistantBuilder.tools(fsaTools);
                            logInfo("已添加 FileSystemAccessTools");
                        }
                    }
                    
                    assistant = assistantBuilder.build();
                    logInfo("Assistant 实例已创建（共享实例，DAST风格）");
                }
            }
        }
    }
    
    /**
     * 确保共享的ChatMemory已初始化
     */
    private void ensureChatMemoryInitialized() {
        if (chatMemory == null) {
            synchronized (chatMemoryLock) {
                if (chatMemory == null) {
                    // 使用较大的消息窗口，保留更多上下文（DAST风格：共享分析记录）
                    chatMemory = MessageWindowChatMemory.builder()
                            .maxMessages(50) // 保留50条消息，积累分析知识
                            .build();
                    logInfo("共享ChatMemory已创建（最大50条消息，DAST风格）");
                }
            }
        }
    }
    
    /**
     * 初始化MCP工具提供者
     */
    private void initializeMcpToolProvider() {
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
                        
                        allFilterTools.addAll(List.of(
                            // 注释掉 send_http1_request 和 send_http2_request（容易超时和报错，改用 curl 工具）
                            "send_http1_request", "send_http2_request", 
                            "get_proxy_http_history", "get_proxy_http_history_regex",
                            "get_proxy_websocket_history", "get_proxy_websocket_history_regex", 
                            "get_scanner_issues", "set_task_execution_engine_state",
                            "get_active_editor_contents", "set_active_editor_contents",
                            "create_repeater_tab"
                        ));
                        
                        // 注释掉工具规范映射（不再添加额外的描述映射）
                        //mappingConfig = McpToolMappingConfig.createBurpMapping();
                        logInfo("Burp MCP 客户端已添加，地址: " + burpMcpUrlValue);
                    } catch (Exception e) {
                        logError("Burp MCP 客户端初始化失败: " + e.getMessage());
                    }
                }
                
                // 2. RAG MCP 客户端
                if (enableRagMcp && ragMcpDocumentsPath != null && !ragMcpDocumentsPath.trim().isEmpty()) {
                    try {
                        McpTransport ragTransport = mcpProviderHelper.createRagMcpTransport(ragMcpDocumentsPath.trim());
                        McpClient ragMcpClient = mcpProviderHelper.createMcpClient(ragTransport, "RagMCPClient");
                        allMcpClients.add(ragMcpClient);
                        allFilterTools.addAll(List.of("index_document", "query_document"));
                        logInfo("RAG MCP 客户端已添加，知识库路径: " + ragMcpDocumentsPath);
                    } catch (Exception e) {
                        logError("RAG MCP 客户端初始化失败: " + e.getMessage());
                    }
                }
                
                // 3. Chrome MCP 客户端
                if (enableChromeMcp && chromeMcpUrl != null && !chromeMcpUrl.trim().isEmpty()) {
                    try {
                        McpTransport chromeTransport = mcpProviderHelper.createStreamableHttpTransport(chromeMcpUrl.trim());
                        McpClient chromeMcpClient = mcpProviderHelper.createMcpClient(chromeTransport, "ChromeMCPClient");
                        allMcpClients.add(chromeMcpClient);
                        logInfo("Chrome MCP 客户端已添加，地址: " + chromeMcpUrl);
                    } catch (Exception e) {
                        logError("Chrome MCP 客户端初始化失败: " + e.getMessage());
                    }
                }
                
                // 等待连接稳定
                if (!allMcpClients.isEmpty()) {
                    Thread.sleep(1000);
                    
                    String[] filterToolsArray = allFilterTools.isEmpty() ? null : allFilterTools.toArray(new String[0]);
                    // 不使用工具规范映射，直接创建工具提供者（避免额外的描述映射）
                    mcpToolProvider = mcpProviderHelper.createToolProvider(
                        allMcpClients, 
                        filterToolsArray
                    );
                    
                    logInfo("MCP 工具提供者初始化成功，已添加 " + allMcpClients.size() + " 个 MCP 客户端");
                }
            } catch (Exception e) {
                logError("MCP 工具提供者初始化失败: " + e.getMessage());
                mcpToolProvider = null;
            }
        } else if (!enableMcp && !enableRagMcp && !enableChromeMcp) {
            mcpToolProvider = null;
        }
    }
    
    /**
     * 执行被动扫描分析（同步方法，线程安全）
     * 
     * @param requestResponse 要分析的HTTP请求响应
     * @return 分析结果字符串
     * @throws Exception 分析过程中的异常
     */
    public String analyzeRequest(HttpRequestResponse requestResponse) throws Exception {
        return analyzeRequest(requestResponse, null, null);
    }
    
    /**
     * 执行被动扫描分析（支持取消和流式回调）
     * 
     * @param requestResponse 要分析的HTTP请求响应
     * @param cancelFlag 取消标志
     * @param onChunk 流式输出回调（可选）
     * @return 分析结果字符串
     * @throws Exception 分析过程中的异常
     */
    public String analyzeRequest(HttpRequestResponse requestResponse, AtomicBoolean cancelFlag, Consumer<String> onChunk) throws Exception {
        // 确保ChatModel和Assistant已初始化
        ensureChatModelInitialized();
        if (chatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API配置");
        }
        ensureAssistantInitialized();
        
        // 格式化HTTP内容
        String httpContent = HttpFormatter.formatHttpRequestResponse(requestResponse);
        
        // 检查内容是否为空或无效
        if (httpContent == null || httpContent.trim().isEmpty()) {
            logInfo("HTTP内容为空，跳过分析");
            return "## 风险等级: 无\n请求内容为空，无法分析。";
        }
        
        // 估算系统提示词长度（大约2000字符）
        final int ESTIMATED_SYSTEM_PROMPT_LENGTH = 2000;
        final int API_MAX_LENGTH = 30720;
        final int MAX_USER_CONTENT_LENGTH = API_MAX_LENGTH - ESTIMATED_SYSTEM_PROMPT_LENGTH - 500; // 留500字符余量
        
        // 如果内容太长，截断并添加提示
        if (httpContent.length() > MAX_USER_CONTENT_LENGTH) {
            int originalLength = httpContent.length();
            httpContent = httpContent.substring(0, MAX_USER_CONTENT_LENGTH) + 
                "\n\n[内容已截断，原始长度: " + originalLength + " 字符，已截断至 " + MAX_USER_CONTENT_LENGTH + " 字符]";
            logInfo("HTTP内容过长，已截断: " + originalLength + " -> " + MAX_USER_CONTENT_LENGTH + " 字符");
        }
        
        // 构建用户消息
        String userContent = buildScanPrompt(httpContent);
        
        // ========== 前置扫描器集成 ==========
        if (preScanFilterManager != null && preScanFilterManager.isEnabled()) {
            try {
                PreScanFilter filter = preScanFilterManager.getFilter();
                if (filter != null) {
                    // 执行前置扫描（500ms超时）
                    List<ScanMatch> matches = filter.scan(requestResponse, 
                        preScanFilterManager.getDefaultScanTimeout());
                    
                    if (!matches.isEmpty()) {
                        // 生成提示文本追加到扫描提示词
                        String promptHint = PreScanFilter.buildPromptHint(matches);
                        userContent += promptHint;
                        
                        logInfo("[PassiveScan-PreScan] 检测到 " + matches.size() + " 个疑似漏洞特征");
                    }
                }
            } catch (Exception e) {
                logError("[PassiveScan-PreScan] 扫描失败: " + e.getMessage());
            }
        }
        
        // 最终检查：确保用户消息不为空
        if (userContent == null || userContent.trim().isEmpty()) {
            logInfo("用户消息为空，跳过分析");
            return "## 风险等级: 无\n无法构建有效的分析请求。";
        }
        
        UserMessage userMessage = new UserMessage(userContent);
        
        // 执行分析（使用共享的Assistant和ChatMemory）
        List<ChatMessage> messages = List.of(userMessage);
        TokenStream tokenStream = null;
        
        // 第一次尝试，如果遇到空 content 错误则清空 ChatMemory 并重试
        int retryCount = 0;
        while (tokenStream == null && retryCount < 2) {
            try {
                synchronized (assistantLock) {
                    tokenStream = assistant.chat(messages);
                }
            } catch (Exception e) {
                String errMsg = e.getMessage();
                // 如果是空 content 错误且未重试过，清空 ChatMemory 后重试
                if (retryCount == 0 && errMsg != null && 
                    (errMsg.contains("content field is") || 
                     (errMsg.contains("content") && errMsg.contains("required")))) {
                    logInfo("检测到空 content 错误，清空 ChatMemory 后重试...");
                    clearContext();
                    ensureAssistantInitialized(); // 重新初始化
                    retryCount++;
                } else {
                    // 其他错误或已重试过，直接抛出
                    throw e;
                }
            }
        }
        
        if (tokenStream == null) {
            throw new Exception("无法创建 TokenStream");
        }
        
        // 收集结果
        StringBuilder resultBuilder = new StringBuilder();
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        
        tokenStream
            .onPartialResponse(text -> {
                // 检查取消标志
                if (cancelFlag != null && cancelFlag.get()) {
                    return;
                }
                // text 已经是 String 类型
                if (text != null && !text.isEmpty()) {
                    resultBuilder.append(text);
                    if (onChunk != null) {
                        onChunk.accept(text);
                    }
                }
            })
            .onCompleteResponse(response -> {
                futureResponse.complete(response);
            })
            .onError(error -> {
                String errorMsg = error != null ? error.getMessage() : "未知错误";
                logError("TokenStream错误: " + errorMsg);
                // 如果是输入长度错误，提供更友好的错误信息
                if (errorMsg != null && errorMsg.contains("Range of input length")) {
                    futureResponse.completeExceptionally(new Exception("API输入长度超出限制（1-30720字符），请检查请求内容大小"));
                } else {
                    futureResponse.completeExceptionally(error);
                }
            })
            .start();
        
        // 等待完成（最多10分钟，因为可能有工具调用）
        try {
            futureResponse.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (cancelFlag != null && cancelFlag.get()) {
                throw new Exception("扫描已取消");
            }
            // 提供更详细的错误信息
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Range of input length")) {
                throw new Exception("API输入长度超出限制（1-30720字符）。原始HTTP内容长度: " + 
                    (httpContent != null ? httpContent.length() : 0) + " 字符", e);
            }
            throw e;
        }
        
        String result = resultBuilder.toString();
        // 如果结果为空，返回默认消息
        if (result == null || result.trim().isEmpty()) {
            return "## 风险等级: 无\n未收到AI分析结果。";
        }
        
        return result;
    }
    
    /**
     * 清空共享的聊天记忆
     */
    public void clearContext() {
        synchronized (chatMemoryLock) {
            chatMemory = null;
        }
        synchronized (assistantLock) {
            assistant = null;
        }
        logInfo("共享聊天上下文已清空");
    }
    
    /**
     * 构建被动扫描的系统提示词（完整版，基于SystemPromptBuilder改造）
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        // ========== 角色定位 ==========
        buildRoleSection(prompt);
        
        // ========== 核心决策框架（被动扫描场景）==========
        buildDecisionFramework(prompt);
        
        // ========== 使用联网搜索功能 ==========
        if (enableSearch) {
            buildSearchSection(prompt);
        }
        
        // ========== 漏洞类型与测试策略映射 ==========
        buildVulnerabilityStrategies(prompt);
        
        // ========== 输出格式 ==========
        buildOutputFormat(prompt);
        
        // ========== 交互原则（被动扫描场景）==========
        buildInteractionPrinciples(prompt);
        
        return prompt.toString();
    }
    
    private void buildRoleSection(StringBuilder prompt) {
        prompt.append("# 角色定位\n");
        prompt.append("你是一个专业的Web安全被动扫描AI（DAST风格），负责自动分析HTTP流量中的安全风险。\n");
        prompt.append("- **分析能力**：识别 HTTP 请求/响应中的安全风险（OWASP Top 10）\n");
        prompt.append("- **执行能力**：通过工具直接进行渗透测试验证\n");
        prompt.append("- **批量处理**：你正在进行批量扫描任务，会分析多个请求，请记住之前的分析结果以积累知识\n\n");
    }
    
    private void buildDecisionFramework(StringBuilder prompt) {
        prompt.append("# 决策框架（被动扫描场景，必须遵循）\n\n");
        
        prompt.append("## 第一步：分析HTTP请求\n");
        prompt.append("1. 识别目标信息：主机、端口、协议、接口路径\n");
        prompt.append("2. 分析请求特征：参数类型、认证方式、数据格式\n");
        prompt.append("3. 识别潜在漏洞：根据参数特征判断可能的漏洞类型\n");
        prompt.append("4. 评估风险等级：只报告中危及以上的风险\n\n");
        
        prompt.append("## 第二步：主动测试验证（发现可测试风险时）\n");
        prompt.append("**核心原则：不要只报告可能存在，必须实际测试验证！**\n\n");
        prompt.append("1. 构造测试 payload（基于识别的风险类型）\n");
        prompt.append("2. 使用 HTTP 请求工具发送测试请求\n");
        prompt.append("3. 分析响应，判断漏洞是否存在\n");
        prompt.append("4. **必须**将成功验证的漏洞请求发送到 Repeater，便于用户手动验证\n");
        prompt.append("5. 如需批量测试，使用 Intruder 工具\n\n");
        
        prompt.append("## 第三步：报告结果\n");
        prompt.append("1. 如果已测试，报告测试结果（不要只说可能存在）\n");
        prompt.append("2. 如果未测试，说明测试方法和验证步骤\n");
        prompt.append("3. 记住测试结果，用于后续关联分析\n\n");
        
        prompt.append("## `create_repeater_tab` 智能决策规则\n");
        prompt.append("**原则：只有需要人类确认的请求才发送到 Repeater**\n");
        prompt.append("- ✅ **发现漏洞/成功POC** → **必须发送**\n");
        prompt.append("- ⚠️ **疑似漏洞/不确定** → **建议发送**\n");
        prompt.append("- ❌ **确认无漏洞** → **不发送**\n\n");
        
        prompt.append("## 禁止行为\n");
        prompt.append("- ❌ 测试结果为【无漏洞】时仍发送到 Repeater\n");
        prompt.append("- ❌ 只报告可能存在而不实际测试\n");
        prompt.append("- ❌ 对非目标系统发送请求\n");
        prompt.append("- ❌ 发送破坏性 payload（DELETE、DROP 等）\n\n");
    }
    
    private void buildSearchSection(StringBuilder prompt) {
        prompt.append("# 联网搜索功能\n");
        prompt.append("当遇到可能需要搜索新漏洞或历史漏洞的POC时，主动联网搜索相关信息。\n");
        prompt.append("搜索范围：Github、漏洞库、技术文档、POC等。\n\n");
    }
    
    private void buildVulnerabilityStrategies(StringBuilder prompt) {
        prompt.append("# 漏洞类型与测试策略\n\n");
        prompt.append("根据识别的风险类型，采用对应的测试策略：\n\n");
        
        prompt.append("**1. SQL注入**\n");
        prompt.append("- 识别特征：参数拼接、数字型参数、搜索功能\n");
        prompt.append("- 测试策略：单引号、布尔盲注、时间盲注\n\n");
        
        prompt.append("**2. XSS（跨站脚本）**\n");
        prompt.append("- 识别特征：输入反射、HTML参数、富文本\n");
        prompt.append("- 测试策略：script标签、事件处理器、编码绕过\n\n");
        
        prompt.append("**3. 命令注入**\n");
        prompt.append("- 识别特征：文件操作、系统调用、ping功能\n");
        prompt.append("- 测试策略：管道符、命令分隔符、反引号\n\n");
        
        prompt.append("**4. 路径遍历**\n");
        prompt.append("- 识别特征：文件下载、图片加载、include参数\n");
        prompt.append("- 测试策略：../序列、编码变体、绝对路径\n\n");
        
        prompt.append("**5. SSRF（服务器端请求伪造）- 重点关注**\n");
        prompt.append("- **识别特征**：URL参数包含URL（`src`、`url`、`redirect`、`link`、`target`、`destination`、`callback`、`webhook`、`uri`、`path`）\n");
        prompt.append("- **测试策略**：\n");
        prompt.append("  * 内网IP：`http://127.0.0.1`、`http://localhost`、`http://0.0.0.0`\n");
        prompt.append("  * 云元数据：`http://169.254.169.254/latest/meta-data/`\n");
        prompt.append("  * 协议绕过：`file:///etc/passwd`、`gopher://`\n");
        prompt.append("- **必须主动测试**：发现这类参数时，不要只报告可能存在，必须实际发送测试请求\n\n");
        
        prompt.append("**6. XXE（XML外部实体注入）**\n");
        prompt.append("- 识别特征：XML上传、SOAP接口、SVG处理\n");
        prompt.append("- 测试策略：外部实体、参数实体、DTD注入\n\n");
        
        prompt.append("**7. 认证缺陷**\n");
        prompt.append("- 识别特征：登录接口、JWT、Session\n");
        prompt.append("- 测试策略：弱口令、爆破、会话固定\n\n");
        
        prompt.append("**8. 越权**\n");
        prompt.append("- 识别特征：ID参数、用户标识、资源访问\n");
        prompt.append("- 测试策略：水平越权、垂直越权、IDOR\n\n");
        
        prompt.append("**测试原则**：\n");
        prompt.append("1. 优先测试高危漏洞（RCE > SSRF > SQL注入 > XSS）\n");
        prompt.append("2. 构造无害的探测 payload，避免破坏性操作\n");
        prompt.append("3. 根据响应特征判断漏洞存在性\n");
        prompt.append("4. **发现SSRF等高危漏洞时，必须主动测试，不要只报告可能存在**\n\n");
    }
    
    private void buildOutputFormat(StringBuilder prompt) {
        prompt.append("# 输出格式\n");
        prompt.append("- 使用 Markdown 格式，**禁止使用表格**（不要用 `|` 和 `---`）\n");
        prompt.append("- 风险等级: [严重/高/中/无]\n");
        prompt.append("- 如有发现，报告：问题名称、风险点、测试结果、验证方法\n");
        prompt.append("- 无中危以上问题则输出：风险等级: 无，未发现明显的安全问题\n\n");
    }
    
    private void buildInteractionPrinciples(StringBuilder prompt) {
        prompt.append("# 交互原则（被动扫描场景）\n");
        prompt.append("1. **先分析后执行**：收到请求先分析风险，发现高危风险时主动测试验证\n");
        prompt.append("2. **必须测试验证**：不要只报告可能存在，必须实际发送测试请求\n");
        prompt.append("3. **报告测试结果**：工具执行后清晰报告发现和测试结果\n");
        prompt.append("4. **保持上下文**：记住之前的对话和测试结果，用于关联分析\n");
        prompt.append("5. **批量处理优化**：在批量扫描中，记住同一应用的漏洞特征，提高效率\n\n");
    }
    
    /**
     * 构建扫描提示词
     */
    private String buildScanPrompt(String httpContent) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下HTTP请求/响应中的安全风险：\n\n");
        prompt.append("**特别注意**：\n");
        prompt.append("- 如果URL参数中包含URL（如 `src=http://...`、`url=https://...`、`redirect=...`），这是潜在的SSRF漏洞，**必须主动测试验证**\n");
        prompt.append("- 不要只报告可能存在，必须实际发送测试请求验证\n");
        prompt.append("- 如果发现可测试的高危漏洞，使用工具主动测试\n\n");
        // 转义页面中的模板语法（如 {{data.tablename}}），避免被 LangChain4j/API 当作占位符导致 "Value for the variable ... is missing"
        prompt.append(escapeTemplateDelimiters(httpContent));
        return prompt.toString();
    }
    
    /**
     * 转义 HTTP 内容中的模板分隔符，避免被解析为占位符。
     * 页面中常见的 {{variable}}、${variable} 等会被转义为字面量。
     */
    private String escapeTemplateDelimiters(String content) {
        if (content == null || content.isEmpty()) return content;
        return content
            .replace("{{", "\u200B{\u200B{")  // {{ -> ZWSP{ZWSP{
            .replace("}}", "}\u200B}\u200B")  // }} -> }ZWSP}ZWSP
            .replace("${", "$\u200B{");       // ${ -> $ZWSP{
    }
    
    // ========== 日志方法 ==========
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[PassiveScanApiClient] " + message);
        }
    }
    
    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[PassiveScanApiClient] " + message);
        }
    }
}
