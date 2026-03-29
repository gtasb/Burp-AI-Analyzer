package com.ai.analyzer.Client;

import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.Client.AgentConfig.ApiProvider;
import com.ai.analyzer.mcpClient.AllMcpToolProvider;
import com.ai.analyzer.mcpClient.McpToolMappingConfig;
import com.ai.analyzer.mcpClient.ToolExecutionFormatter;
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
import com.ai.analyzer.multiModels.ChatModelFactory;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.PartialResponseContext;
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
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.tool.ToolProvider;
import com.ai.analyzer.Tools.BurpExtTools;
import com.ai.analyzer.Tools.PythonScriptTool;
import com.ai.analyzer.Tools.NotebookTools;
import com.ai.analyzer.Tools.FileSystemAccessTools;
import com.ai.analyzer.Tools.WebSearchTools;
import lombok.Getter;

/**
 * AI Agent API 客户端
 * 负责与 AI 模型交互，支持流式输出、工具调用、上下文管理
 */
public class AgentApiClient {
    private static final int DEFAULT_CHAT_MEMORY_MAX_MESSAGES = 100;
    private static final int COMPACT_CHAT_MEMORY_MAX_MESSAGES = 30;
    private static final int RETRY_CONTENT_MAX_CHARS = 25000;
    private static final int RETRY_CONTENT_ULTRA_MAX_CHARS = 12000;
    private static final int RETRY_CONTENT_MINI_MAX_CHARS = 5000;
    
    // ========== 配置 ==========
    @Getter
    private final AgentConfig config;
    
    // ========== 核心组件 ==========
    private MontoyaApi api;
    private StreamingChatModel chatModel;
    private Assistant assistant;
    private McpToolProvider mcpToolProvider;
    private MessageWindowChatMemory chatMemory;
    private SkillManager skillManager;
    private SkillToolsProvider skillToolsProvider;
    private PreScanFilterManager preScanFilterManager;
    private volatile int chatMemoryMaxMessages = DEFAULT_CHAT_MEMORY_MAX_MESSAGES;
    private volatile Consumer<String> systemNoticeConsumer;

    // ========== 共享聊天 UI 历史（供多个 ChatPanel 实例同步显示） ==========
    private static final int MAX_SHARED_UI_HISTORY = 200;
    private final java.util.List<Object[]> sharedChatUiHistory = new java.util.ArrayList<>();
    private final java.util.List<Runnable> chatUiListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int sharedHistoryVersion = 0;

    public int getSharedHistoryVersion() { return sharedHistoryVersion; }

    public java.util.List<Object[]> getSharedChatUiHistorySnapshot() {
        synchronized (sharedChatUiHistory) {
            return new java.util.ArrayList<>(sharedChatUiHistory);
        }
    }
    public int getSharedChatUiHistorySize() {
        synchronized (sharedChatUiHistory) { return sharedChatUiHistory.size(); }
    }
    public Object[] getSharedChatUiHistoryEntry(int index) {
        synchronized (sharedChatUiHistory) { return sharedChatUiHistory.get(index); }
    }
    public void addChatUiEntryDirect(Object[] entry) {
        synchronized (sharedChatUiHistory) { sharedChatUiHistory.add(entry); }
    }
    public void addChatUiEntry(String sender, String content, boolean isUser) {
        synchronized (sharedChatUiHistory) {
            sharedChatUiHistory.add(new Object[]{sender, content, isUser});
            if (sharedChatUiHistory.size() > MAX_SHARED_UI_HISTORY) {
                sharedChatUiHistory.subList(0, sharedChatUiHistory.size() - MAX_SHARED_UI_HISTORY).clear();
            }
            sharedHistoryVersion++;
        }
        for (Runnable listener : chatUiListeners) {
            try { listener.run(); } catch (Exception ignored) {}
        }
    }
    public void addChatUiListener(Runnable listener) { chatUiListeners.add(listener); }
    public void removeChatUiListener(Runnable listener) { chatUiListeners.remove(listener); }
    public void clearSharedChatUiHistory() {
        synchronized (sharedChatUiHistory) {
            sharedChatUiHistory.clear();
            sharedHistoryVersion++;
        }
        for (Runnable listener : chatUiListeners) {
            try { listener.run(); } catch (Exception ignored) {}
        }
    }

    // ========== 状态标志 ==========
    private boolean isFirstInitialization = true;
    private boolean needsReinitialization = false;
    private volatile TokenStream currentTokenStream;
    private volatile StreamingHandle streamingHandle;
    private volatile boolean isStreamingCancelled = false;

    // ========== 系统提示词缓存 ==========
    private volatile String cachedSystemPrompt;
    private volatile int cachedPromptConfigHash;

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

    public void setSystemNoticeConsumer(Consumer<String> systemNoticeConsumer) {
        this.systemNoticeConsumer = systemNoticeConsumer;
    }
    
    public String getApiKey() { return config.getApiKey(); }
    public String getApiUrl() { return config.getApiUrl(); }
    public String getModel() { return config.getModel(); }
    public ApiProvider getApiProvider() { return config.getApiProvider(); }
    public boolean isEnableThinking() { return config.isEnableThinking(); }
    public boolean isEnableSearch() { return config.isEnableSearch(); }
    public boolean isEnableMcp() { return config.isEnableMcp(); }
    public String getBurpMcpUrl() { return config.getBurpMcpUrl(); }
    public boolean isEnableRagMcp() { return config.isEnableRagMcp(); }
    public String getRagMcpUrl() { return config.getRagMcpUrl(); }
    public String getRagMcpDocumentsPath() { return config.getRagMcpDocumentsPath(); }
    public boolean isEnableChromeMcp() { return config.isEnableChromeMcp(); }
    public String getChromeMcpUrl() { return config.getChromeMcpUrl(); }
    public boolean isEnableFileSystemAccess() { return config.isEnableFileSystemAccess(); }
    public boolean isEnableSkills() { return config.isEnableSkills(); }
    public boolean isEnablePythonScript() { return config.isEnablePythonScript(); }
    public boolean isEnableNotebook() { return config.isEnableNotebook(); }
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

    public void setSearchMode(String searchMode) {
        if (searchMode == null) searchMode = "enableSearch";
        if (!searchMode.equals(config.getSearchMode())) {
            config.setSearchMode(searchMode);
            updateChatModelAndAssistant();
        }
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        if (tavilyApiKey == null) tavilyApiKey = "";
        if (!tavilyApiKey.equals(config.getTavilyApiKey())) {
            config.setTavilyApiKey(tavilyApiKey);
            updateChatModelAndAssistant();
        }
    }

    public void setTavilyBaseUrl(String tavilyBaseUrl) {
        if (tavilyBaseUrl == null) tavilyBaseUrl = "";
        if (!tavilyBaseUrl.equals(config.getTavilyBaseUrl())) {
            config.setTavilyBaseUrl(tavilyBaseUrl);
            updateChatModelAndAssistant();
        }
    }

    public void setGoogleSearchApiKey(String key) {
        if (key == null) key = "";
        if (!key.equals(config.getGoogleSearchApiKey())) {
            config.setGoogleSearchApiKey(key);
            updateChatModelAndAssistant();
        }
    }

    public void setGoogleSearchCsi(String csi) {
        if (csi == null) csi = "";
        if (!csi.equals(config.getGoogleSearchCsi())) {
            config.setGoogleSearchCsi(csi);
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
    
    public void setEnableMcp(boolean enableMcp) {
        if (config.isEnableMcp() != enableMcp) {
            config.setEnableMcp(enableMcp);
            if (!enableMcp) mcpToolProvider = null;
            assistant = null;
            logInfo("MCP 工具调用已" + (enableMcp ? "启用" : "禁用"));
        }
    }
    
    public void setBurpMcpUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            mcpUrl = "http://127.0.0.1:9876/";
        }
        if (!config.getBurpMcpUrl().equals(mcpUrl.trim())) {
            config.setBurpMcpUrl(mcpUrl.trim());
            if (config.isEnableMcp()) {
                mcpToolProvider = null;
                assistant = null;
                logInfo("Burp MCP 地址已更新: " + mcpUrl);
            }
        }
    }
    
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
    
    public void setEnablePythonScript(boolean enablePythonScript) {
        if (config.isEnablePythonScript() != enablePythonScript) {
            config.setEnablePythonScript(enablePythonScript);
            assistant = null;
            logInfo("Python 脚本执行已" + (enablePythonScript ? "启用" : "禁用"));
        }
    }

    public void setEnableNotebook(boolean enableNotebook) {
        if (config.isEnableNotebook() != enableNotebook) {
            config.setEnableNotebook(enableNotebook);
            assistant = null;
            logInfo("Notebook 工具已" + (enableNotebook ? "启用" : "禁用"));
        }
    }

    public void setCustomSystemPrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt;
        if (!java.util.Objects.equals(config.getCustomSystemPrompt(), normalized)) {
            config.setCustomSystemPrompt(normalized);
            cachedSystemPrompt = null;
        }
    }

    public void setWorkplaceDirectoryPath(String workplaceDirectoryPath) {
        String normalized = workplaceDirectoryPath == null ? "" : workplaceDirectoryPath.trim();
        if (!java.util.Objects.equals(config.getWorkplaceDirectoryPath(), normalized)) {
            config.setWorkplaceDirectoryPath(normalized);
            if (!normalized.isEmpty()) {
                setSkillsDirectoryPath(new File(normalized, "skills").getAbsolutePath());
                config.setRagMcpDocumentsPath(new File(normalized, "rag").getAbsolutePath());
            }
            assistant = null;
            logInfo("Workplace 目录已更新: " + normalized);
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
        if (!config.isValid()) {
            logInfo("警告: API Key为空，无法初始化ChatModel");
            return;
        }
        
        try {
            if (isFirstInitialization) {
                logInfo("初始化 ChatModel: " + ChatModelFactory.describeConfig(
                        config.getApiProvider(), config.getApiUrl(), config.getModel(),
                        config.isEnableSearch(), config.isEnableThinking()));
            }
            
            this.chatModel = ChatModelFactory.create(
                    config.getApiProvider(),
                    config.getApiKey(),
                    config.getApiUrl(),
                    config.getModel(),
                    config.isModelSearchEnabled(),
                    config.isEnableThinking(),
                    config.getCustomParameters());
            
            if (isFirstInitialization) {
                logInfo("ChatModel 初始化成功");
                isFirstInitialization = false;
            }
        } catch (Exception e) {
            logError("初始化ChatModel失败: " + e.getMessage());
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
        
        // 创建 ChatMemory
        // maxMessages 需足够大以容纳多轮工具调用（每轮占 2 条消息），
        // 过小会淘汰原始 UserMessage，导致 DashScope InputRequiredException
        if (chatMemory == null) {
            chatMemory = MessageWindowChatMemory.builder()
                    .maxMessages(chatMemoryMaxMessages)
                    .build();
            logInfo("ChatMemory 已创建（最大" + chatMemoryMaxMessages + "条消息）");
        }
        
        // 创建 Assistant
        var assistantBuilder = AiServices.builder(Assistant.class)
                .streamingChatModel(this.chatModel)
                .chatMemory(chatMemory)
                .systemMessageProvider(memoryId -> buildSystemPrompt());
        
        // ToolProviders: 组合 MCP + 官方 Skills (activate_skill / read_skill_resource)
        {
            List<ToolProvider> providers = new ArrayList<>();
            if (mcpToolProvider != null) {
                providers.add(mcpToolProvider);
                logInfo("已启用 MCP 工具支持");
            }
            if (config.isEnableSkills() && skillManager != null && skillManager.hasEnabledSkills()) {
                ToolProvider skillsTP = skillManager.getSkillsToolProvider();
                if (skillsTP != null) {
                    providers.add(skillsTP);
                    logInfo("已启用官方 Skills ToolProvider (activate_skill + read_skill_resource)");
                }
            }
            if (!providers.isEmpty()) {
                for (ToolProvider tp : providers) {
                    assistantBuilder.toolProvider(tp);
                }
            }
        }
        
        // @Tool 注解工具
        if (api != null) {
/*             if (config.isEnableMcp()) {
                BurpExtTools burpExtTools = new BurpExtTools(api);
                assistantBuilder.tools(burpExtTools);
                logInfo("已添加 BurpExtTools");
            } */
            
            if (config.isEnablePythonScript()) {
                PythonScriptTool pythonTool = new PythonScriptTool();
                String pythonWorkingDir = config.getEffectivePythonWorkingDirectoryPath();
                if (!pythonWorkingDir.isEmpty()) {
                    pythonTool.setWorkingDirectory(pythonWorkingDir);
                }
                assistantBuilder.tools(pythonTool);
                logInfo("已添加 PythonScriptTool");
            }

            if (config.isEnableNotebook()) {
                NotebookTools notebookTools = new NotebookTools();
                if (config.getWorkplaceDirectoryPath() != null && !config.getWorkplaceDirectoryPath().trim().isEmpty()) {
                    notebookTools.setWorkplaceDirectory(config.getWorkplaceDirectoryPath().trim());
                }
                assistantBuilder.tools(notebookTools);
                logInfo("已添加 NotebookTools");
            }
            
            if (config.isTavilySearchEnabled()) {
                assistantBuilder.tools(WebSearchTools.tavily(config.getTavilyApiKey(), config.getTavilyBaseUrl()));
                logInfo("已添加 WebSearchTools (Tavily)");
            } else if (config.isGoogleSearchEnabled()) {
                assistantBuilder.tools(WebSearchTools.google(config.getGoogleSearchApiKey(), config.getGoogleSearchCsi()));
                logInfo("已添加 WebSearchTools (Google Custom Search)");
            } else if (config.isDuckDuckGoSearchEnabled()) {
                assistantBuilder.tools(WebSearchTools.duckDuckGo());
                logInfo("已添加 WebSearchTools (DuckDuckGo)");
            }

            if (config.isEnableFileSystemAccess() && config.hasRagDocumentsPath()) {
                FileSystemAccessTools fsaTools = new FileSystemAccessTools(api);
                fsaTools.setAllowedRootPath(config.getEffectiveRagDocumentsPath());
                assistantBuilder.tools(fsaTools);
                logInfo("已添加 FileSystemAccessTools (知识库: " + config.getEffectiveRagDocumentsPath() + ")");
            }
            
            // SkillToolsProvider: execute_skill_tool + list_skill_tools（二进制执行）
            if (config.isEnableSkills() && skillManager != null && skillManager.hasEnabledTools()) {
                if (skillToolsProvider == null) {
                    skillToolsProvider = new SkillToolsProvider(skillManager, api);
                }
                assistantBuilder.tools(skillToolsProvider);
                logInfo("已添加 SkillToolsProvider (二进制工具数: " + skillManager.getEnabledToolCount() + ")");
            }
        }
        
        assistant = assistantBuilder.build();
        logInfo("Assistant 实例已创建（共享实例，保持上下文）");
    }
    
    private void initializeMcpToolProvider() {
        if (!config.hasAnyMcpEnabled() || mcpToolProvider != null) {
            if (!config.hasAnyMcpEnabled()) mcpToolProvider = null;
            return;
        }
        
                try {
                    AllMcpToolProvider mcpProviderHelper = new AllMcpToolProvider();
                    List<McpClient> allMcpClients = new ArrayList<>();
                    List<String> allFilterTools = new ArrayList<>();
                    McpToolMappingConfig mappingConfig = null;
                    
            // Burp MCP（带 URL fallback：主/备地址均使用根路径）
            if (config.isEnableMcp()) {
                String burpUrl = config.getEffectiveBurpMcpUrl();
                String altUrl = AllMcpToolProvider.getAlternateBurpMcpUrl(burpUrl);
                for (String tryUrl : new String[]{burpUrl, altUrl}) {
                    try {
                        McpTransport burpTransport = mcpProviderHelper.createHttpTransport(tryUrl);
                        McpClient burpMcpClient = mcpProviderHelper.createMcpClient(burpTransport, "BurpMCPClient");
                        allMcpClients.add(burpMcpClient);
                        allFilterTools.addAll(List.of(
                            "send_http1_request", "send_http2_request", 
                            "get_proxy_http_history", "get_proxy_http_history_regex",
                            "get_proxy_websocket_history", "get_proxy_websocket_history_regex", 
                            "get_scanner_issues", "set_task_execution_engine_state",
                            "get_active_editor_contents", "set_active_editor_contents",
                            "create_repeater_tab","repeater_tab_with_payload",
                            "params_extract","diff_params",
                            "url_encode","url_decode",
                            "base64_encode","base64_decode",
                            "jwt_decode","decode_as",
                            "generate_random_string",
                            "request_parse","response_parse",
                            "find_reflected","comparer_send",
                            "proxy_history_annotate","response_body_search",
                            "site_map","site_map_regex",
                            "scan_audit_start","scan_audit_start_mode","scan_audit_start_requests","scan_crawl_start","scan_task_status","scan_task_delete"
                        ));
                        logInfo("Burp MCP 客户端已添加，地址: " + tryUrl);
                        break;
                    } catch (Exception e) {
                        if (tryUrl.equals(altUrl)) {
                            logInfo("Burp MCP 客户端初始化失败（已尝试 " + burpUrl + " 和 " + altUrl + "）: " + e.getMessage());
                        } else {
                            logInfo("Burp MCP 首次连接失败，尝试备用 URL: " + e.getMessage());
                        }
                    }
                }
            }
                    
            // RAG MCP
            if (config.isEnableRagMcp() && config.hasRagDocumentsPath()) {
                        try {
                    McpTransport ragTransport = mcpProviderHelper.createRagMcpTransport(config.getEffectiveRagDocumentsPath().trim());
                            McpClient ragMcpClient = mcpProviderHelper.createMcpClient(ragTransport, "RagMCPClient");
                            allMcpClients.add(ragMcpClient);
                    allFilterTools.addAll(List.of("index_document", "query_document"));
                    logInfo("RAG MCP 客户端已添加，知识库路径: " + config.getEffectiveRagDocumentsPath());
                        } catch (Exception e) {
                    logInfo("RAG MCP 客户端初始化失败: " + e.getMessage());
                        }
                    }
                    
            // Chrome MCP
            if (config.isEnableChromeMcp() && config.hasChromeMcpUrl()) {
                        try {
                    McpTransport chromeTransport = mcpProviderHelper.createStreamableHttpTransport(config.getChromeMcpUrl().trim());
                            McpClient chromeMcpClient = mcpProviderHelper.createMcpClient(chromeTransport, "ChromeMCPClient");
                            allMcpClients.add(chromeMcpClient);
                    logInfo("Chrome MCP 客户端已添加，地址: " + config.getChromeMcpUrl());
                        } catch (Exception e) {
                    logInfo("Chrome MCP 客户端初始化失败: " + e.getMessage());
                        }
                    }
                    
                    if (!allMcpClients.isEmpty()) {
                    Thread.sleep(1000);
                        String[] filterToolsArray = allFilterTools.isEmpty() ? null : allFilterTools.toArray(new String[0]);
                        // 不使用工具规范映射，直接创建工具提供者（避免额外的描述映射）
                        mcpToolProvider = mcpProviderHelper.createToolProvider(
                    allMcpClients, filterToolsArray
                        );
                logInfo("MCP 工具提供者初始化成功，已添加 " + allMcpClients.size() + " 个 MCP 客户端");
                    }
                } catch (Exception e) {
            logInfo("MCP 工具提供者初始化失败: " + e.getMessage());
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
            assistant = null;
        chatMemory = null;
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
        config.setSearchMode(settings.getSearchMode());
        config.setTavilyApiKey(settings.getTavilyApiKey());
        config.setTavilyBaseUrl(settings.getTavilyBaseUrl());
        config.setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
        config.setGoogleSearchCsi(settings.getGoogleSearchCsi());
        config.setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        if (settings.getRagMcpDocumentsPath() != null && !settings.getRagMcpDocumentsPath().isEmpty()) {
            config.setRagMcpDocumentsPath(settings.getRagMcpDocumentsPath());
        }
        if (settings.isEnableSkills() && settings.getSkillsDirectoryPath() != null && !settings.getSkillsDirectoryPath().isEmpty()) {
            setSkillsDirectoryPath(settings.getSkillsDirectoryPath());
        } else if (settings.hasWorkplaceDirectory()) {
            setSkillsDirectoryPath(settings.resolveSkillsDirectoryPath());
        }
        config.setEnableSkills(settings.isEnableSkills());
        config.setEnablePythonScript(settings.isEnablePythonScript());
        config.setEnableNotebook(settings.isEnableNotebook());
        config.setEnableFileSystemAccess(settings.isEnableFileSystemAccess());
        config.setEnableRagMcp(settings.isEnableRagMcp());
        config.setEnableChromeMcp(settings.isEnableChromeMcp());
        config.setChromeMcpUrl(settings.getChromeMcpUrl());
        config.setBurpMcpUrl(settings.getMcpUrl());
        config.setRagMcpUrl(settings.getRagMcpUrl());
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

        logInfo("使用LangChain4j发送流式请求");
        logInfo("模型: " + (config.getModel() != null ? config.getModel() : "qwen-max"));

        int maxRetries = 5;
        int retryCount = 0;
        Exception lastException = null;
        final int[] contentChunkCount = {0};
        boolean retriedWithCompressedMessage = false;
        boolean retriedWithCompactedContext = false;
        boolean retriedWithMinimalMode = false;
        userContent = compressContentForRetry(userContent, 90000);
        UserMessage userMessage = new UserMessage(userContent);

        while (retryCount < maxRetries) {
            try {
                ensureAssistantInitialized();
                final boolean[] thinkingNoticeShown = {false};
                
                List<ChatMessage> messages = List.of(userMessage);
                TokenStream tokenStream = assistant.chat(messages);
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
                                if (thinkingNoticeShown[0]) {
                                    thinkingNoticeShown[0] = false;
                                }
                                onChunk.accept(text);
                                contentChunkCount[0]++;
                        }
                    })
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        if (!isStreamingCancelled) {
                            logDebug("Thinking: " + partialThinking);
                            if (!thinkingNoticeShown[0]) {
                                emitSystemNotice("思考中...");
                                thinkingNoticeShown[0] = true;
                            }
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
                String errorText = extractErrorText(cause);
                if (isInputLengthExceededError(errorText)) {
                    if (!retriedWithCompressedMessage) {
                        userContent = compressContentForRetry(userContent, RETRY_CONTENT_MAX_CHARS);
                        userMessage = new UserMessage(userContent);
                        retriedWithCompressedMessage = true;
                        retryCount++;
                        logInfo("检测到输入长度超限，已自动压缩当前消息并重试");
                        emitSystemNotice("输入过长，已自动压缩当前消息后重试。");
                        continue;
                    }
                    if (!retriedWithCompactedContext) {
                        userContent = compressContentForRetry(userContent, RETRY_CONTENT_ULTRA_MAX_CHARS);
                        userMessage = new UserMessage(userContent);
                        emitSystemNotice("压缩上下文中...");
                        summarizeAndCompactConversationContext(userContent);
                        retriedWithCompactedContext = true;
                        retryCount++;
                        logInfo("输入仍超限，已自动压缩上下文窗口并重试");
                        emitSystemNotice("已进一步压缩消息并收缩上下文窗口后重试。");
                        continue;
                    }
                    if (!retriedWithMinimalMode) {
                        compactConversationContextOnly();
                        userContent = buildMinimalFallbackContent(userContent);
                        userMessage = new UserMessage(userContent);
                        retriedWithMinimalMode = true;
                        retryCount++;
                        logInfo("输入持续超限，已切换轻量分析模式重试");
                        emitSystemNotice("上下文仍过长，已切换轻量分析模式重试。");
                        continue;
                    }
                }
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
        chatMemoryMaxMessages = DEFAULT_CHAT_MEMORY_MAX_MESSAGES;
    }

    private void cleanupStreamingState() {
        currentTokenStream = null;
        streamingHandle = null;
        isStreamingCancelled = false;
    }

    private boolean isInputLengthExceededError(String errorText) {
        if (errorText == null) return false;
        return errorText.contains("Range of input length should be")
                || errorText.contains("InternalError.Algo.InvalidParameter")
                || errorText.contains("input length");
    }

    private String extractErrorText(Throwable error) {
        if (error == null) return "";
        String msg = error.getMessage();
        if (msg != null && !msg.isEmpty()) return msg;
        return extractErrorText(error.getCause());
    }

    private String compressContentForRetry(String content, int maxChars) {
        if (content == null) return "";
        String normalized = content.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n");
        if (normalized.length() <= maxChars) return normalized;

        int headLen = (int) (maxChars * 0.75);
        int tailLen = Math.max(0, maxChars - headLen - 60);
        if (tailLen <= 0 || headLen <= 0 || normalized.length() < headLen + tailLen) {
            return normalized.substring(0, Math.min(maxChars, normalized.length()));
        }

        return normalized.substring(0, headLen)
                + "\n\n...[中间内容已压缩省略，以控制输入长度]...\n\n"
                + normalized.substring(normalized.length() - tailLen);
    }

    private void summarizeAndCompactConversationContext(String latestUserContent) {
        String contextSummary = buildContextSummary();
        chatMemoryMaxMessages = COMPACT_CHAT_MEMORY_MAX_MESSAGES;
        assistant = null;
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(chatMemoryMaxMessages)
                .build();
        if (contextSummary != null && !contextSummary.isEmpty()) {
            chatMemory.add(new UserMessage(
                    "[系统自动压缩的历史上下文摘要]\n"
                            + contextSummary
                            + "\n\n[当前请求摘要]\n"
                            + compressContentForRetry(latestUserContent, 3000)
            ));
        }
        logInfo("已自动收缩上下文窗口至 " + COMPACT_CHAT_MEMORY_MAX_MESSAGES + " 条消息");
    }

    private void compactConversationContextOnly() {
        chatMemoryMaxMessages = COMPACT_CHAT_MEMORY_MAX_MESSAGES;
        assistant = null;
        chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(chatMemoryMaxMessages)
                .build();
        logInfo("已重置并收缩上下文窗口至 " + COMPACT_CHAT_MEMORY_MAX_MESSAGES + " 条消息");
    }

    private void emitSystemNotice(String message) {
        Consumer<String> consumer = this.systemNoticeConsumer;
        if (consumer == null || message == null || message.trim().isEmpty()) return;
        try {
            consumer.accept(message.trim());
        } catch (Exception e) {
            logDebug("系统提示回调失败: " + e.getMessage());
        }
    }

    private String buildMinimalFallbackContent(String currentUserContent) {
        String compact = compressContentForRetry(currentUserContent, RETRY_CONTENT_MINI_MAX_CHARS);
        return "请基于以下压缩后的信息给出结论，避免再次调用大量工具，优先输出最终风险评估与可执行建议：\n\n"
                + compact;
    }

    private String buildContextSummary() {
        if (chatMemory == null) return "";
        try {
            List<ChatMessage> history = chatMemory.messages();
            if (history == null || history.isEmpty()) return "";

            int keepFrom = Math.max(0, history.size() - 20);
            StringBuilder sb = new StringBuilder();
            sb.append("已保留最近对话要点（压缩版）:\n");
            for (int i = keepFrom; i < history.size(); i++) {
                ChatMessage msg = history.get(i);
                String role = msg.getClass().getSimpleName();
                String text = msg.toString();
                if (text == null) text = "";
                text = text.replace('\r', ' ').replace('\n', ' ');
                if (text.length() > 240) {
                    text = text.substring(0, 240) + "...";
                }
                sb.append("- ").append(role).append(": ").append(text).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logDebug("构建上下文摘要失败: " + e.getMessage());
            return "";
        }
    }

    private String buildAnalysisContent(String httpContent, String userPrompt, 
            RequestSourceDetector.RequestSourceInfo sourceInfo) {
        StringBuilder content = new StringBuilder();

        if (sourceInfo != null) {
            content.append(sourceInfo.format()).append("\n\n");
        }

        if (httpContent != null && !httpContent.trim().isEmpty()) {
            com.ai.analyzer.utils.HttpFormatter.CompressResult compressed = 
                com.ai.analyzer.utils.HttpFormatter.compressIfTooLong(httpContent);
            if (compressed.wasCompressed) {
                logInfo("HTTP内容过长已自动压缩: " + compressed.originalLength + " → " + compressed.compressedLength + " 字符");
            }
            String finalHttp = compressed.content;
            
            if (finalHttp.contains("=== HTTP请求 ===") && finalHttp.contains("=== HTTP响应 ===")) {
                content.append("以下是完整的HTTP请求和响应信息：\n\n");
            } else {
                content.append("以下是HTTP请求内容：\n\n");
            }
            content.append(finalHttp);
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
        int hash = java.util.Objects.hash(
                config.isEnableSearch(), config.isEnableMcp(),
                config.isEnableRagMcp(), config.isEnableChromeMcp(),
                config.isEnableFileSystemAccess(), config.isEnableSkills(),
                config.isEnableNotebook(),
                config.getRagMcpDocumentsPath(),
                config.getCustomSystemPrompt(),
                skillManager != null ? skillManager.getEnabledSkillCount() : 0);
        String cached = cachedSystemPrompt;
        if (cached != null && hash == cachedPromptConfigHash) return cached;

        cached = new SystemPromptBuilder()
                .enableSearch(config.isEnableSearch())
                .enableMcp(config.isEnableMcp())
                .enableRagMcp(config.isEnableRagMcp())
                .enableChromeMcp(config.isEnableChromeMcp())
                .enableFileSystemAccess(config.isEnableFileSystemAccess())
                .enableSkills(config.isEnableSkills())
                .ragMcpDocumentsPath(config.getRagMcpDocumentsPath())
                .skillManager(skillManager)
                .customBasePrompt(config.getCustomSystemPrompt())
                .build();
        cachedSystemPrompt = cached;
        cachedPromptConfigHash = hash;
        return cached;
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
