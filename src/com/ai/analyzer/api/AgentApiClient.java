package com.ai.analyzer.api;

import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.mcpClient.AllMcpToolProvider;
import com.ai.analyzer.mcpClient.ToolExecutionFormatter;
import com.ai.analyzer.Tools.BurpTools;
import com.ai.analyzer.Tools.FileSystemAccessTools;
import com.ai.analyzer.skills.SkillManager;
import com.ai.analyzer.skills.SkillToolsProvider;
import com.ai.analyzer.utils.JsonParser;
import com.ai.analyzer.utils.RequestSourceDetector;
import com.ai.analyzer.rulesMatch.PreScanFilterManager;
import com.ai.analyzer.rulesMatch.PreScanFilter;
import com.ai.analyzer.rulesMatch.ScanMatch;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.api.AgentConfig.ApiProvider;
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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.Getter;

/**
 * AI Agent API 客户端
 * 负责与 AI 模型交互，支持流式输出、工具调用、上下文管理
 */
public class AgentApiClient {
    
    // ========== 配置 ==========
    @Getter
    private final AgentConfig config;
    
    // ========== 核心组件 ==========
    private MontoyaApi api;
    private StreamingChatModel chatModel;
    private Assistant assistant;
    private McpToolProvider mcpToolProvider; // 只用于 RAG MCP 和 Chrome MCP
    private MessageWindowChatMemory chatMemory;
    private SkillManager skillManager;
    private SkillToolsProvider skillToolsProvider;
    private PreScanFilterManager preScanFilterManager;
    
    // ========== 并发控制锁 ==========
    private final Object chatMemoryLock = new Object();
    private final Object assistantLock = new Object();
    private final Object streamingLock = new Object();
    
    // ========== Burp 工具 ==========
    private BurpTools burpTools;
    
    // ========== 状态标志 ==========
    private boolean isFirstInitialization = true;
    private boolean needsReinitialization = false;
    private volatile TokenStream currentTokenStream;
    private volatile StreamingHandle streamingHandle;
    private volatile boolean isStreamingCancelled = false;
    private volatile boolean isStreaming = false; // 标记是否正在流式输出

    // ========== 构造函数 ==========

    /**
     * 无参构造函数，自动从配置文件加载设置
     */
    public AgentApiClient() {
        this.config = new AgentConfig();
        loadSettingsFromFile();
        initializeChatModel();
    }

    /**
     * 带参构造函数
     */
    public AgentApiClient(String apiUrl, String apiKey) {
        this.config = new AgentConfig();
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            loadSettingsFromFile();
        } else {
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
        }
        initializeChatModel();
    }
    
    /**
     * 带 MontoyaApi 的构造函数
     */
    public AgentApiClient(MontoyaApi api, String apiUrl, String apiKey) {
        this.api = api;
        this.config = new AgentConfig();
        if ((apiUrl == null || apiUrl.trim().isEmpty()) || 
            (apiKey == null || apiKey.trim().isEmpty())) {
            loadSettingsFromFile();
        } else {
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
        }
        initializeChatModel();
    }
    
    // ========== 配置 Getter/Setter（委托给 config）==========
    
    public void setApi(MontoyaApi api) {
        this.api = api;
    }
    
    public String getApiKey() { return config.getApiKey(); }
    public String getApiUrl() { return config.getApiUrl(); }
    public String getModel() { return config.getModel(); }
    public ApiProvider getApiProvider() { return config.getApiProvider(); }
    public boolean isEnableThinking() { return config.isEnableThinking(); }
    public boolean isEnableSearch() { return config.isEnableSearch(); }
    // Burp MCP 已移除，Burp 工具现在直接使用 Montoya API
    public boolean isEnableRagMcp() { return config.isEnableRagMcp(); }
    public String getRagMcpUrl() { return config.getRagMcpUrl(); }
    public String getRagMcpDocumentsPath() { return config.getRagMcpDocumentsPath(); }
    public boolean isEnableChromeMcp() { return config.isEnableChromeMcp(); }
    public String getChromeMcpUrl() { return config.getChromeMcpUrl(); }
    public boolean isEnableFileSystemAccess() { return config.isEnableFileSystemAccess(); }
    public boolean isEnableSkills() { return config.isEnableSkills(); }
    public String getCustomParameters() { return config.getCustomParameters(); }

    public void setApiUrl(String apiUrl) {
        if (config.getApiUrl() == null || !config.getApiUrl().equals(apiUrl)) {
            config.setApiUrl(apiUrl);
            reinitializeChatModel();
        }
    }
    
    public void setApiKey(String apiKey) {
        if (config.getApiKey() == null || !config.getApiKey().equals(apiKey)) {
            config.setApiKey(apiKey);
            reinitializeChatModel();
        }
    }
    
    public void setModel(String model) {
        if (config.getModel() == null || !config.getModel().equals(model)) {
            config.setModel(model);
            reinitializeChatModel();
        }
    }
    
    public void setApiProvider(ApiProvider apiProvider) {
        if (config.getApiProvider() != apiProvider) {
            config.setApiProvider(apiProvider);
            updateChatModelAndAssistant();
            logInfo("API 提供者已切换为: " + apiProvider.getDisplayName());
        }
    }
    
    public void setApiProvider(String providerName) {
        setApiProvider(ApiProvider.fromDisplayName(providerName));
    }
    
    public void setEnableThinking(boolean enableThinking) {
        if (config.isEnableThinking() != enableThinking) {
            config.setEnableThinking(enableThinking);
            updateChatModelAndAssistant();
        }
    }
    
    public void setEnableSearch(boolean enableSearch) {
        if (config.isEnableSearch() != enableSearch) {
            config.setEnableSearch(enableSearch);
            updateChatModelAndAssistant();
        }
    }
    
    public void setCustomParameters(String customParameters) {
        if (customParameters == null) customParameters = "";
        if (!config.getCustomParameters().equals(customParameters.trim())) {
            config.setCustomParameters(customParameters.trim());
            reinitializeChatModel();
            if (!customParameters.isEmpty()) {
                logInfo("自定义参数已更新: " + customParameters);
            }
        }
    }
    
    // Burp MCP 配置方法已移除（Burp 工具现在直接使用 Montoya API）
    
    public void setEnableRagMcp(boolean enableRagMcp) {
        if (config.isEnableRagMcp() != enableRagMcp) {
            config.setEnableRagMcp(enableRagMcp);
            assistant = null;
            logInfo("RAG MCP 工具调用已" + (enableRagMcp ? "启用" : "禁用"));
        }
    }
    
    public void setRagMcpUrl(String ragMcpUrl) {
        if (ragMcpUrl == null || ragMcpUrl.trim().isEmpty()) ragMcpUrl = "";
        if (!config.getRagMcpUrl().equals(ragMcpUrl.trim())) {
            config.setRagMcpUrl(ragMcpUrl.trim());
            if (config.isEnableRagMcp()) {
                assistant = null;
                logInfo("RAG MCP 地址已更新: " + ragMcpUrl);
            }
        }
    }
    
    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) {
        if (ragMcpDocumentsPath == null) ragMcpDocumentsPath = "";
        if (!config.getRagMcpDocumentsPath().equals(ragMcpDocumentsPath.trim())) {
            config.setRagMcpDocumentsPath(ragMcpDocumentsPath.trim());
            if (config.isEnableRagMcp()) {
                assistant = null;
                logInfo("RAG MCP 文档路径已更新: " + ragMcpDocumentsPath);
            }
        }
    }
    
    public void setEnableChromeMcp(boolean enableChromeMcp) {
        if (config.isEnableChromeMcp() != enableChromeMcp) {
            config.setEnableChromeMcp(enableChromeMcp);
            assistant = null;
            logInfo("Chrome MCP 工具调用已" + (enableChromeMcp ? "启用" : "禁用"));
        }
    }
    
    public void setChromeMcpUrl(String chromeMcpUrl) {
        if (chromeMcpUrl == null || chromeMcpUrl.trim().isEmpty()) chromeMcpUrl = "";
        if (!config.getChromeMcpUrl().equals(chromeMcpUrl.trim())) {
            config.setChromeMcpUrl(chromeMcpUrl.trim());
            if (config.isEnableChromeMcp()) {
                assistant = null;
                logInfo("Chrome MCP 地址已更新: " + chromeMcpUrl);
            }
        }
    }
    
    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) {
        if (config.isEnableFileSystemAccess() != enableFileSystemAccess) {
            config.setEnableFileSystemAccess(enableFileSystemAccess);
            assistant = null;
            logInfo("直接查找知识库已" + (enableFileSystemAccess ? "启用" : "禁用"));
        }
    }
    
    public void setEnableSkills(boolean enableSkills) {
        if (config.isEnableSkills() != enableSkills) {
            config.setEnableSkills(enableSkills);
            assistant = null;
            logInfo("Skills 已" + (enableSkills ? "启用" : "禁用"));
        }
    }
    
    public SkillManager getSkillManager() {
        if (skillManager == null) {
            skillManager = new SkillManager();
            if (api != null) {
                skillManager.setApi(api);
            }
        }
        return skillManager;
    }
    
    public void setSkillsDirectoryPath(String path) {
        getSkillManager().setSkillsDirectoryPath(path);
        skillToolsProvider = null;
        assistant = null;
        logInfo("Skills 目录已设置: " + path);
    }
    
    public void refreshSkills() {
        getSkillManager().loadSkills();
        skillToolsProvider = null;
        assistant = null;
    }
    
    /**
     * 设置前置扫描过滤器管理器
     */
    public void setPreScanFilterManager(PreScanFilterManager preScanFilterManager) {
        this.preScanFilterManager = preScanFilterManager;
    }

    // ========== ChatModel 初始化 ==========
    
    private void initializeChatModel() {
        if (config.getApiProvider() == ApiProvider.OPENAI_COMPATIBLE) {
            initializeOpenAIChatModel();
                } else {
            initializeQwenChatModel();
        }
    }

    private void initializeQwenChatModel() {
        if (!config.isValid()) {
            logInfo("警告: API Key为空，无法初始化ChatModel");
            return;
        }
        
        try {
            String baseUrl = normalizeQwenBaseUrl(config.getApiUrl());
            String modelName = config.getModel() != null && !config.getModel().trim().isEmpty() 
                ? config.getModel() : "qwen-max";
            
            if (isFirstInitialization) {
                logInfo("初始化LangChain4j ChatModel");
                logInfo("原始API URL: " + config.getApiUrl());
                logInfo("Model: " + modelName);
                logInfo("EnableThinking: " + config.isEnableThinking());
                logInfo("EnableSearch: " + config.isEnableSearch());
            }

            QwenChatRequestParameters.SearchOptions searchOptions = QwenChatRequestParameters.SearchOptions.builder()
                    .searchStrategy("max")
                    .build();

            QwenChatRequestParameters parameters = QwenChatRequestParameters.builder()
                    .enableSearch(config.isEnableSearch())
                    .searchOptions(searchOptions)
                    .enableThinking(config.isEnableThinking())
                    .build();
            
            this.chatModel = QwenStreamingChatModel.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(0.7f)
                    .defaultRequestParameters(parameters)
                    .build();
                    
            if (isFirstInitialization) {
                logInfo("LangChain4j ChatModel初始化成功");
                isFirstInitialization = false;
            }
        } catch (Exception e) {
            logError("初始化ChatModel失败: " + e.getMessage());
        }
    }
    
    private String normalizeQwenBaseUrl(String apiUrl) {
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
        return (baseUrl == null || baseUrl.trim().isEmpty()) 
            ? "https://dashscope.aliyuncs.com/api/v1" : baseUrl;
    }

    private void initializeOpenAIChatModel() {
        if (!config.isValid()) {
            logInfo("警告: API Key为空，无法初始化 OpenAI 兼容 ChatModel");
            return;
        }
        
        try {
            String baseUrl = config.getApiUrl();
            String modelName = config.getModel();
            
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = "https://api.openai.com/v1";
            }
            if (!baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
                baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
            }
            if (modelName == null || modelName.trim().isEmpty()) {
                modelName = "gpt-3.5-turbo";
            }
            
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.getApiKey())
                    .modelName(modelName)
                    .temperature(0.7);
            
            // 应用自定义参数
            if (config.getCustomParameters() != null && !config.getCustomParameters().trim().isEmpty()) {
                try {
                    Map<String, Object> customParamsMap = JsonParser.parseJsonToMap(config.getCustomParameters());
                    if (!customParamsMap.isEmpty()) {
                        dev.langchain4j.model.openai.OpenAiChatRequestParameters requestParams = 
                            dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                                .customParameters(customParamsMap)
                                .build();
                        builder.defaultRequestParameters(requestParams);
                        logInfo("已设置自定义参数: " + customParamsMap.keySet());
                    }
                } catch (Exception e) {
                    logError("解析自定义参数失败: " + e.getMessage());
                }
            }
            
            this.chatModel = builder.build();
            
            if (isFirstInitialization) {
                logInfo("OpenAI 兼容 ChatModel 初始化成功");
                logInfo("Base URL: " + baseUrl + ", Model: " + modelName);
                isFirstInitialization = false;
            }
        } catch (Exception e) {
            logError("初始化 OpenAI 兼容 ChatModel 失败: " + e.getMessage());
        }
    }
    
    private void reinitializeChatModel() {
        needsReinitialization = true;
    }
    
    private void updateChatModelAndAssistant() {
            assistant = null;
        chatModel = null;
        initializeChatModel();
        logInfo("ChatModel 已更新，使用新的配置 (Provider: " + config.getApiProvider() + 
                ", EnableThinking: " + config.isEnableThinking() + 
                ", EnableSearch: " + config.isEnableSearch() + ")");
    }
    
    private void ensureChatModelInitialized() {
        if (needsReinitialization || chatModel == null) {
            assistant = null;
            chatModel = null;
            initializeChatModel();
            needsReinitialization = false;
            logInfo("ChatModel 已重新初始化");
        }
    }

    // ========== Assistant 初始化 ==========
    
    private void ensureAssistantInitialized() {
        if (assistant != null) return;
        
        // 初始化 MCP 工具提供者
        initializeMcpToolProvider();
        
        // 创建 ChatMemory（线程安全）
        synchronized (chatMemoryLock) {
            if (chatMemory == null) {
                chatMemory = MessageWindowChatMemory.builder()
                        .maxMessages(20)
                        .build();
                logInfo("ChatMemory 已创建（最大20条消息）");
            }
        }
        
        // 创建 Assistant
        var assistantBuilder = AiServices.builder(Assistant.class)
                .streamingChatModel(this.chatModel)
                .chatMemory(chatMemory)
                .systemMessageProvider(memoryId -> buildSystemPrompt());
        
        // 添加 MCP 工具支持
        if (mcpToolProvider != null) {
            assistantBuilder.toolProvider(mcpToolProvider);
            logInfo("已启用 MCP 工具支持");
        }
        
        // 添加扩展工具
        if (api != null) {
            // BurpTools - Burp Suite 原生工具（直接使用 Montoya API）
            // 已包含所有 Burp 工具功能，包括 Intruder 批量测试
            if (burpTools == null) {
                burpTools = new BurpTools(api);
            }
            assistantBuilder.tools(burpTools);
            logInfo("已添加 BurpTools（Burp Suite 完整工具集，包括 HTTP 请求、Intruder 批量测试等）");
            
            // FileSystemAccessTools
            if (config.isEnableFileSystemAccess() && config.hasRagDocumentsPath()) {
                FileSystemAccessTools fsaTools = new FileSystemAccessTools(api);
                fsaTools.setAllowedRootPath(config.getRagMcpDocumentsPath());
                assistantBuilder.tools(fsaTools);
                logInfo("已添加 FileSystemAccessTools (知识库: " + config.getRagMcpDocumentsPath() + ")");
            }
            
            // SkillToolsProvider
            if (config.isEnableSkills() && skillManager != null && skillManager.hasEnabledTools()) {
                if (skillToolsProvider == null) {
                    skillToolsProvider = new SkillToolsProvider(skillManager, api);
                }
                assistantBuilder.tools(skillToolsProvider);
                logInfo("已添加 SkillToolsProvider (工具数: " + skillManager.getEnabledToolCount() + ")");
            }
        }
        
        assistant = assistantBuilder.build();
        logInfo("Assistant 实例已创建（共享实例，保持上下文）");
    }
    
    /**
     * 初始化 MCP 工具提供者
     * 注意：Burp MCP 已移除，现在只初始化 RAG MCP 和 Chrome MCP
     */
    private void initializeMcpToolProvider() {
        // 检查是否需要初始化 MCP（只检查 RAG 和 Chrome MCP）
        boolean needRagMcp = config.isEnableRagMcp() && config.hasRagDocumentsPath();
        boolean needChromeMcp = config.isEnableChromeMcp() && config.hasChromeMcpUrl();
        
        if (!needRagMcp && !needChromeMcp) {
            mcpToolProvider = null;
            return;
        }
        
        if (mcpToolProvider != null) {
            return; // 已初始化
        }
        
        try {
            AllMcpToolProvider mcpProviderHelper = new AllMcpToolProvider();
            List<McpClient> allMcpClients = new ArrayList<>();
            List<String> allFilterTools = new ArrayList<>();
            
            // RAG MCP - 知识库文档查询
            if (needRagMcp) {
                try {
                    McpTransport ragTransport = mcpProviderHelper.createRagMcpTransport(
                        config.getRagMcpDocumentsPath().trim()
                    );
                    McpClient ragMcpClient = mcpProviderHelper.createMcpClient(ragTransport, "RagMCPClient");
                    allMcpClients.add(ragMcpClient);
                    allFilterTools.addAll(List.of("index_document", "query_document"));
                    logInfo("RAG MCP 客户端已添加，知识库路径: " + config.getRagMcpDocumentsPath());
                } catch (Exception e) {
                    logError("RAG MCP 客户端初始化失败: " + e.getMessage());
                }
            }
            
            // Chrome MCP - 浏览器控制
            if (needChromeMcp) {
                try {
                    McpTransport chromeTransport = mcpProviderHelper.createStreamableHttpTransport(
                        config.getChromeMcpUrl().trim()
                    );
                    McpClient chromeMcpClient = mcpProviderHelper.createMcpClient(chromeTransport, "ChromeMCPClient");
                    allMcpClients.add(chromeMcpClient);
                    logInfo("Chrome MCP 客户端已添加，地址: " + config.getChromeMcpUrl());
                } catch (Exception e) {
                    logError("Chrome MCP 客户端初始化失败: " + e.getMessage());
                }
            }
            
            if (!allMcpClients.isEmpty()) {
                Thread.sleep(1000); // 等待 MCP 连接稳定
                String[] filterToolsArray = allFilterTools.isEmpty() ? null : allFilterTools.toArray(new String[0]);
                mcpToolProvider = mcpProviderHelper.createToolProviderWithMapping(
                    allMcpClients, null, filterToolsArray
                );
                logInfo("MCP 工具提供者初始化成功，已添加 " + allMcpClients.size() + " 个 MCP 客户端");
            }
        } catch (Exception e) {
            logError("MCP 工具提供者初始化失败: " + e.getMessage());
            mcpToolProvider = null;
        }
    }
    
    // ========== 流式输出控制 ==========
    
    public void cancelStreaming() {
        isStreamingCancelled = true;
        
        if (streamingHandle != null) {
            try {
                streamingHandle.cancel();
                logInfo("流式输出已取消（通过 StreamingHandle）");
            } catch (Exception e) {
                logError("取消流式输出失败: " + e.getMessage());
            } finally {
                streamingHandle = null;
                currentTokenStream = null;
            }
        } else if (currentTokenStream != null) {
            currentTokenStream = null;
            logInfo("TokenStream 引用已清空");
        }
    }
    
    public void clearContext() {
        cancelStreaming();
        synchronized (assistantLock) {
            assistant = null;
        }
        synchronized (chatMemoryLock) {
            chatMemory = null;
        }
        logInfo("聊天上下文已清空");
    }

    // ========== 配置加载 ==========
    
    private void loadSettingsFromFile() {
        String defaultApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String defaultModel = "qwen3-max";
        
        // 尝试从当前目录加载
        File localSettingsFile = new File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(localSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultModel);
                return;
            }
        }
        
        // 尝试从用户主目录加载
        File userSettingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(userSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultModel);
                return;
            }
        }
        
        // 使用默认值
        config.setApiUrl(defaultApiUrl);
        config.setApiKey("");
        config.setModel(defaultModel);
    }
    
    private void applySettings(PluginSettings settings, String defaultApiUrl, String defaultModel) {
        config.setApiUrl(settings.getApiUrl() != null && !settings.getApiUrl().isEmpty() 
            ? settings.getApiUrl() : defaultApiUrl);
        config.setApiKey(settings.getApiKey() != null ? settings.getApiKey() : "");
        config.setModel(settings.getModel() != null && !settings.getModel().isEmpty() 
            ? settings.getModel() : defaultModel);
        config.setEnableThinking(settings.isEnableThinking());
        config.setEnableSearch(settings.isEnableSearch());
    }
    
    private PluginSettings loadSettingsFromFile(File settingsFile) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
                return (PluginSettings) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    // ========== 流式分析方法 ==========
    
    public void analyzeRequestStream(HttpRequestResponse requestResponse, String userPrompt, Consumer<String> onChunk) throws Exception {
        RequestSourceDetector.RequestSourceInfo sourceInfo = null;
        if (api != null && requestResponse != null) {
            sourceInfo = RequestSourceDetector.detectSource(api, requestResponse);
        }

        logInfo("请求来源: " + (sourceInfo != null ? sourceInfo.format() : "未知"));
        
        String httpRequest = requestResponse != null 
            ? com.ai.analyzer.utils.HttpFormatter.formatHttpRequestResponse(requestResponse)
            : "";
        
        // ========== 前置扫描器集成 ==========
        String preScanHint = "";
        if (preScanFilterManager != null && preScanFilterManager.isEnabled() && requestResponse != null) {
            try {
                PreScanFilter filter = preScanFilterManager.getFilter();
                if (filter != null) {
                    // 执行扫描（500ms超时）
                    List<ScanMatch> matches = filter.scan(requestResponse, 
                        preScanFilterManager.getDefaultScanTimeout());
                    
                    if (!matches.isEmpty()) {
                        // 在UI中显示匹配结果
                        String uiMessage = PreScanFilter.buildUiMessage(matches);
                        if (onChunk != null) {
                            onChunk.accept("\n" + uiMessage + "\n");
                        }
                        
                        // 生成提示文本追加到UserPrompt
                        preScanHint = PreScanFilter.buildPromptHint(matches);
                        
                        logInfo("[PreScan] 检测到 " + matches.size() + " 个疑似漏洞特征");
                    }
                }
            } catch (Exception e) {
                logError("[PreScan] 扫描失败: " + e.getMessage());
            }
        }
        
        // 将前置扫描结果追加到 userPrompt
        String enhancedUserPrompt = userPrompt;
        if (!preScanHint.isEmpty()) {
            enhancedUserPrompt = (userPrompt != null ? userPrompt : "") + preScanHint;
        }
        
        analyzeRequestStream(httpRequest, enhancedUserPrompt, sourceInfo, onChunk);
    }
    
    public void analyzeRequestStream(String httpRequest, String userPrompt, Consumer<String> onChunk) throws Exception {
        analyzeRequestStream(httpRequest, userPrompt, null, onChunk);
    }
    
    private void analyzeRequestStream(String httpRequest, String userPrompt, 
            RequestSourceDetector.RequestSourceInfo sourceInfo, Consumer<String> onChunk) throws Exception {
        ensureChatModelInitialized();
        
        if (chatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API Key和URL配置");
        }

        String userContent = buildAnalysisContent(httpRequest, userPrompt, sourceInfo);
        UserMessage userMessage = new UserMessage(userContent);

        logInfo("使用LangChain4j发送流式请求");
        logInfo("模型: " + (config.getModel() != null ? config.getModel() : "qwen-max"));

        int maxRetries = 3;
        int retryCount = 0;
        Exception lastException = null;
        final int[] contentChunkCount = {0};

        // 等待之前的流式输出完成
        synchronized (streamingLock) {
            while (isStreaming) {
                try {
                    logInfo("等待之前的流式输出完成...");
                    streamingLock.wait(5000); // 最多等待5秒
                    if (isStreaming) {
                        logInfo("等待超时，强制继续");
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Exception("等待流式输出完成时被中断");
                }
            }
            isStreaming = true;
        }

        try {
            while (retryCount < maxRetries) {
                try {
                    ensureAssistantInitialized();
                    
                    TokenStream tokenStream;
                    synchronized (assistantLock) {
                        List<ChatMessage> messages = List.of(userMessage);
                        tokenStream = assistant.chat(messages);
                    }
                CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
                
                currentTokenStream = tokenStream;
                isStreamingCancelled = false;
                
                tokenStream
                    .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
                        if (isStreamingCancelled) return;
                        if (streamingHandle == null && context != null) {
                            streamingHandle = context.streamingHandle();
                        }
                        String text = partialResponse != null ? partialResponse.text() : null;
                        if (text != null && !text.isEmpty() && !isStreamingCancelled) {
                                onChunk.accept(text);
                                contentChunkCount[0]++;
                        }
                    })
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        if (!isStreamingCancelled) {
                        logDebug("Thinking: " + partialThinking);
                        }
                    })
                    .beforeToolExecution((BeforeToolExecution beforeToolExecution) -> {
                        if (isStreamingCancelled) return;
                        String toolInfoHtml = ToolExecutionFormatter.formatToolExecutionInfo(beforeToolExecution);
                        if (toolInfoHtml != null && !toolInfoHtml.isEmpty()) {
                            onChunk.accept(toolInfoHtml);
                        }
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        logDebug("Tool executed: " + toolExecution);
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        logInfo("流式输出完成，共收到 " + contentChunkCount[0] + " 个chunk");
                        futureResponse.complete(response);
                    })
                    .onError((Throwable error) -> {
                        logError("TokenStream错误: " + error.getMessage());
                        futureResponse.completeExceptionally(error);
                    })
                    .start();

                ChatResponse finalResponse = futureResponse.get(10, java.util.concurrent.TimeUnit.MINUTES);
                
                currentTokenStream = null;
                streamingHandle = null;
                isStreamingCancelled = false;

                if (finalResponse != null && finalResponse.tokenUsage() != null) {
                    logInfo("Token使用: " + finalResponse.tokenUsage().toString());
                }
                break;

                } catch (java.util.concurrent.TimeoutException e) {
                    cleanupStreamingState();
                    lastException = new Exception("流式输出超时（10分钟）", e);
                    retryCount++;
                } catch (java.util.concurrent.ExecutionException e) {
                    cleanupStreamingState();
                    Throwable cause = e.getCause();
                    lastException = cause instanceof Exception ? (Exception) cause : new Exception("流式输出失败", cause);
                    retryCount++;
                } catch (InterruptedException e) {
                    cleanupStreamingState();
                    Thread.currentThread().interrupt();
                    throw new Exception("等待流式输出被中断", e);
                } catch (Exception e) {
                    cleanupStreamingState();
                    lastException = e;
                    retryCount++;
                }
            }

            if (lastException != null && retryCount >= maxRetries) {
                throw new Exception(lastException.getMessage() + " (已重试 " + maxRetries + " 次)", lastException);
            }
        } finally {
            // 释放流式输出锁
            synchronized (streamingLock) {
                isStreaming = false;
                streamingLock.notifyAll();
            }
            logInfo("流式输出锁已释放");
        }
    }

    private void cleanupStreamingState() {
        currentTokenStream = null;
        streamingHandle = null;
        isStreamingCancelled = false;
    }

    private String buildAnalysisContent(String httpContent, String userPrompt, 
            RequestSourceDetector.RequestSourceInfo sourceInfo) {
        StringBuilder content = new StringBuilder();

        if (sourceInfo != null) {
            content.append(sourceInfo.format()).append("\n\n");
        }

        if (httpContent != null && !httpContent.trim().isEmpty()) {
            if (httpContent.contains("=== HTTP请求 ===") && httpContent.contains("=== HTTP响应 ===")) {
                content.append("以下是完整的HTTP请求和响应信息：\n\n");
            } else {
                content.append("以下是HTTP请求内容：\n\n");
            }
                content.append(httpContent);
        }

        if (userPrompt != null && !userPrompt.trim().isEmpty()) {
            if (content.length() > 0) {
                content.append("\n\n用户提示：").append(userPrompt);
            } else {
                content.append("用户提示：").append(userPrompt);
            }
        }

        return content.toString();
    }

    private String buildSystemPrompt() {
        return new SystemPromptBuilder()
                .enableSearch(config.isEnableSearch())
                // Burp MCP 已移除（Burp 工具现在直接使用 Montoya API，无需配置）
                .enableRagMcp(config.isEnableRagMcp())
                .enableChromeMcp(config.isEnableChromeMcp())
                .enableFileSystemAccess(config.isEnableFileSystemAccess())
                .enableSkills(config.isEnableSkills())
                .ragMcpDocumentsPath(config.getRagMcpDocumentsPath())
                .skillManager(skillManager)
                .build();
    }

    // ========== 日志方法 ==========
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[AgentApiClient] " + message);
        }
    }

    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[AgentApiClient] " + message);
        }
    }

    private void logDebug(String message) {
        if (api != null) {
            api.logging().logToOutput("[AgentApiClient] " + message);
        }
    }
}
