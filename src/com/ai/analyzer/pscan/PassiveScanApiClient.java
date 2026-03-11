package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.Agent.Assistant;
import com.ai.analyzer.Client.AgentConfig.ApiProvider;
import com.ai.analyzer.Tools.BurpExtTools;
import com.ai.analyzer.Tools.CurlTools;
import com.ai.analyzer.Tools.FileSystemAccessTools;
import com.ai.analyzer.Tools.PythonScriptTool;
import com.ai.analyzer.Tools.NotebookTools;
import com.ai.analyzer.mcpClient.AllMcpToolProvider;
import com.ai.analyzer.mcpClient.McpToolMappingConfig;
import com.ai.analyzer.mcpClient.ToolExecutionFormatter;
import com.ai.analyzer.model.PluginSettings;
import com.ai.analyzer.multiModels.ChatModelFactory;
import com.ai.analyzer.skills.SkillManager;
import com.ai.analyzer.skills.SkillToolsProvider;
import com.ai.analyzer.utils.HttpFormatter;
import com.ai.analyzer.rulesMatch.PreScanFilterManager;
import com.ai.analyzer.rulesMatch.PreScanFilter;
import com.ai.analyzer.rulesMatch.ScanMatch;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
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
    private volatile McpToolProvider mcpToolProvider;
    private final Object mcpInitLock = new Object();
    
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
    private String BurpMcpUrl = "http://127.0.0.1:9876/";
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
    @Getter
    private boolean enablePythonScript = false;
    @Getter
    private boolean enableNotebook = false;
    @Getter
    private boolean enableSkills = false;
    @Getter
    private String skillsDirectoryPath = "";
    @Getter
    private String workplaceDirectoryPath = "";
    
    // Skills 管理
    private volatile SkillManager skillManager;
    private volatile SkillToolsProvider skillToolsProvider;
    
    // 标记是否需要重新初始化
    private boolean needsReinitialization = false;
    private boolean isFirstInitialization = true;

    // 系统提示词缓存
    private volatile String cachedSystemPrompt;
    private volatile int cachedPromptConfigHash;
    
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
        this.enableNotebook = settings.isEnableNotebook();
        this.enableSkills = settings.isEnableSkills();
        this.workplaceDirectoryPath = settings.getWorkplaceDirectoryPath();
        this.skillsDirectoryPath = settings.getSkillsDirectoryPath();
        if (this.workplaceDirectoryPath != null && !this.workplaceDirectoryPath.trim().isEmpty()) {
            this.ragMcpDocumentsPath = settings.resolveRagDocumentsPath();
            this.skillsDirectoryPath = settings.resolveSkillsDirectoryPath();
        }
        
        // 初始化 SkillManager
        if (enableSkills && skillsDirectoryPath != null && !skillsDirectoryPath.isEmpty()) {
            initializeSkillManager(settings);
        }
    }
    
    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
            return (PluginSettings) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    private void initializeSkillManager(PluginSettings settings) {
        try {
            skillManager = new SkillManager(skillsDirectoryPath);
            if (api != null) skillManager.setApi(api);
            skillManager.loadSkills();
            if (settings.getEnabledSkillNames() != null && !settings.getEnabledSkillNames().isEmpty()) {
                skillManager.setEnabledSkillNames(settings.getEnabledSkillNames());
            }
            logInfo("SkillManager 已初始化 (技能数: " + skillManager.getAllSkills().size()
                    + ", 已启用: " + skillManager.getEnabledSkillCount() + ")");
        } catch (Exception e) {
            logError("SkillManager 初始化失败: " + e.getMessage());
            skillManager = null;
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
            mcpUrl = "http://127.0.0.1:9876/";
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
    
    public void setEnablePythonScript(boolean enablePythonScript) {
        if (this.enablePythonScript != enablePythonScript) {
            this.enablePythonScript = enablePythonScript;
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }

    public void setEnableNotebook(boolean enableNotebook) {
        if (this.enableNotebook != enableNotebook) {
            this.enableNotebook = enableNotebook;
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }

    public void setEnableSkills(boolean enableSkills) {
        if (this.enableSkills != enableSkills) {
            this.enableSkills = enableSkills;
            if (!enableSkills) {
                this.skillManager = null;
                this.skillToolsProvider = null;
            } else if (this.skillsDirectoryPath != null && !this.skillsDirectoryPath.isEmpty()) {
                initializeSkillManager(new PluginSettings());
            }
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }

    public void setSkillsDirectoryPath(String skillsDirectoryPath) {
        String normalized = skillsDirectoryPath == null ? "" : skillsDirectoryPath.trim();
        if (!java.util.Objects.equals(this.skillsDirectoryPath, normalized)) {
            this.skillsDirectoryPath = normalized;
            if (enableSkills && !normalized.isEmpty()) {
                initializeSkillManager(new PluginSettings());
            }
            synchronized (assistantLock) {
                assistant = null;
            }
        }
    }

    public void setWorkplaceDirectoryPath(String workplaceDirectoryPath) {
        String normalized = workplaceDirectoryPath == null ? "" : workplaceDirectoryPath.trim();
        if (!java.util.Objects.equals(this.workplaceDirectoryPath, normalized)) {
            this.workplaceDirectoryPath = normalized;
            if (!normalized.isEmpty()) {
                this.ragMcpDocumentsPath = new File(normalized, "rag").getAbsolutePath();
                this.skillsDirectoryPath = new File(normalized, "skills").getAbsolutePath();
            }
            synchronized (assistantLock) {
                assistant = null;
            }
            if (enableSkills) {
                skillManager = null;
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
     * 创建ChatModel（委托给 ChatModelFactory）
     */
    private StreamingChatModel createChatModel() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logError("API Key为空，无法创建ChatModel");
            return null;
        }
        
        try {
            if (isFirstInitialization) {
                logInfo("创建 ChatModel: " + ChatModelFactory.describeConfig(
                        apiProvider, apiUrl, model, enableSearch, enableThinking));
                isFirstInitialization = false;
            }
            
            return ChatModelFactory.create(
                    apiProvider, apiKey, apiUrl, model,
                    enableSearch, enableThinking, null);
        } catch (Exception e) {
            logError("创建ChatModel失败: " + e.getMessage());
            return null;
        }
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
                    
                    // ToolProviders: MCP + 官方 Skills (activate_skill / read_skill_resource)
                    if (mcpToolProvider != null) {
                        assistantBuilder.toolProvider(mcpToolProvider);
                        logInfo("已启用 MCP 工具支持");
                    }
                    if (enableSkills && skillManager != null && skillManager.hasEnabledSkills()) {
                        ToolProvider skillsTP = skillManager.getSkillsToolProvider();
                        if (skillsTP != null) {
                            assistantBuilder.toolProvider(skillsTP);
                            logInfo("已启用官方 Skills ToolProvider (activate_skill + read_skill_resource)");
                        }
                    }
                    
                    // @Tool 注解工具
                    if (api != null) {
/*                         CurlTools curlTools = new CurlTools(api);
                        assistantBuilder.tools(curlTools);
                        logInfo("已添加 CurlTools"); */
                        
/*                         if (enableMcp) {
                            BurpExtTools burpExtTools = new BurpExtTools(api);
                            assistantBuilder.tools(burpExtTools);
                            logInfo("已添加 BurpExtTools");
                        } */
                        
                        if (enableFileSystemAccess && getEffectiveRagDocumentsPath() != null && !getEffectiveRagDocumentsPath().isEmpty()) {
                            FileSystemAccessTools fsaTools = new FileSystemAccessTools(api);
                            fsaTools.setAllowedRootPath(getEffectiveRagDocumentsPath());
                            assistantBuilder.tools(fsaTools);
                            logInfo("已添加 FileSystemAccessTools");
                        }
                        
                        if (enablePythonScript) {
                            PythonScriptTool pythonTool = new PythonScriptTool();
                            String pythonWorkdir = getEffectivePythonWorkdir();
                            if (!pythonWorkdir.isEmpty()) {
                                pythonTool.setWorkingDirectory(pythonWorkdir);
                            }
                            assistantBuilder.tools(pythonTool);
                            logInfo("已添加 PythonScriptTool");
                        }

                        if (enableNotebook) {
                            NotebookTools notebookTools = new NotebookTools();
                            if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
                                notebookTools.setWorkplaceDirectory(workplaceDirectoryPath.trim());
                            }
                            assistantBuilder.tools(notebookTools);
                            logInfo("已添加 NotebookTools");
                        }
                        
                        // SkillToolsProvider: execute_skill_tool + list_skill_tools（二进制执行）
                        if (enableSkills && skillManager != null && skillManager.hasEnabledTools()) {
                            if (skillToolsProvider == null) {
                                skillToolsProvider = new SkillToolsProvider(skillManager, api);
                            }
                            assistantBuilder.tools(skillToolsProvider);
                            logInfo("已添加 SkillToolsProvider (二进制工具数: " + skillManager.getEnabledToolCount() + ")");
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
                            : "http://127.0.0.1:9876/";
                        McpTransport burpTransport = mcpProviderHelper.createHttpTransport(burpMcpUrlValue);
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
                            "get_scanner_issues","scan_audit_start","scan_audit_start_mode","scan_audit_start_requests","scan_crawl_start","scan_task_status","scan_task_delete"
                        ));
                        
                        // 注释掉工具规范映射（不再添加额外的描述映射）
                        //mappingConfig = McpToolMappingConfig.createBurpMapping();
                        logInfo("Burp MCP 客户端已添加，地址: " + burpMcpUrlValue);
                    } catch (Exception e) {
                        logError("Burp MCP 客户端初始化失败: " + e.getMessage());
                    }
                }
                
                // 2. RAG MCP 客户端
                if (enableRagMcp && getEffectiveRagDocumentsPath() != null && !getEffectiveRagDocumentsPath().trim().isEmpty()) {
                    try {
                        McpTransport ragTransport = mcpProviderHelper.createRagMcpTransport(getEffectiveRagDocumentsPath().trim());
                        McpClient ragMcpClient = mcpProviderHelper.createMcpClient(ragTransport, "RagMCPClient");
                        allMcpClients.add(ragMcpClient);
                        allFilterTools.addAll(List.of("index_document", "query_document"));
                        logInfo("RAG MCP 客户端已添加，知识库路径: " + getEffectiveRagDocumentsPath());
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
                        // allFilterTools.addAll(List.of("get_windows_and_tabs", "chrome_navigate"));
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
        String url = "(unknown)";
        try {
            url = requestResponse.request().url();
            if (url != null && url.length() > 80) url = url.substring(0, 80) + "...";
        } catch (Exception ignored) {}
        
        logDebug("开始分析: " + url);
        
        ensureChatModelInitialized();
        // 捕获 chatModel 的本地引用，防止后续被其他线程 reinitialize 为 null
        final StreamingChatModel localChatModel = this.chatModel;
        if (localChatModel == null) {
            throw new Exception("ChatModel未初始化，请检查API配置");
        }
        // 确保 MCP 工具提供者已初始化（缓存复用，线程安全）
        synchronized (mcpInitLock) {
            initializeMcpToolProvider();
        }
        
        // 格式化 + 清洗 HTTP 内容
        String httpContent = HttpFormatter.formatHttpRequestResponse(requestResponse);
        
        if (httpContent == null || httpContent.trim().isEmpty()) {
            logDebug("HTTP内容为空，跳过: " + url);
            return "## 风险等级: 无\n请求内容为空，无法分析。";
        }
        
        // 压缩过长内容（保留请求头，截断请求/响应体）
        HttpFormatter.CompressResult compressed = HttpFormatter.compressIfTooLong(httpContent);
        if (compressed.wasCompressed) {
            httpContent = compressed.content;
            logDebug("内容已压缩: " + compressed.originalLength + " → " + compressed.compressedLength + " 字符");
        }
        
        final int API_MAX_LENGTH = 28000; // 留充足余量给系统提示词和工具定义
        if (httpContent.length() > API_MAX_LENGTH) {
            httpContent = httpContent.substring(0, API_MAX_LENGTH) + 
                "\n\n[内容已截断至 " + API_MAX_LENGTH + " 字符]";
        }
        
        String userContent = buildScanPrompt(httpContent);
        
        // ========== 前置扫描器集成 ==========
        if (preScanFilterManager != null && preScanFilterManager.isEnabled()) {
            try {
                PreScanFilter filter = preScanFilterManager.getFilter();
                if (filter != null) {
                    List<ScanMatch> matches = filter.scan(requestResponse, 
                        preScanFilterManager.getDefaultScanTimeout());
                    if (!matches.isEmpty()) {
                        String promptHint = PreScanFilter.buildPromptHint(matches);
                        userContent += promptHint;
                        logDebug("[PreScan] 检测到 " + matches.size() + " 个疑似漏洞特征");
                    }
                }
            } catch (Exception e) {
                logError("[PreScan] 扫描失败: " + e.getMessage());
            }
        }
        
        if (userContent == null || userContent.trim().isEmpty()) {
            return "## 风险等级: 无\n无法构建有效的分析请求。";
        }
        
        UserMessage userMessage = new UserMessage(userContent);
        List<ChatMessage> messages = List.of(userMessage);
        
        logDebug("消息长度: " + userContent.length() + " 字符, 工具: MCP=" + (mcpToolProvider != null) + 
                ", CurlTools=" + (api != null) + ", BurpExt=" + enableMcp + ", Python=" + enablePythonScript);
        
        // 重试逻辑覆盖整个 TokenStream 生命周期
        final int MAX_RETRIES = 3;
        Exception lastException = null;
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Assistant requestAssistant = createPerRequestAssistant(localChatModel);
                TokenStream tokenStream = requestAssistant.chat(messages);
                
                StringBuilder resultBuilder = new StringBuilder();
                CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
                
                tokenStream
                    .onPartialResponse(text -> {
                        if (cancelFlag != null && cancelFlag.get()) return;
                        if (text != null && !text.isEmpty()) {
                            resultBuilder.append(text);
                            if (onChunk != null) {
                                onChunk.accept(text);
                            }
                        }
                    })
                    .beforeToolExecution((BeforeToolExecution beforeToolExecution) -> {
                        if (cancelFlag != null && cancelFlag.get()) return;
                        String toolInfo = ToolExecutionFormatter.formatToolExecutionInfo(beforeToolExecution);
                        if (toolInfo != null && !toolInfo.isEmpty() && onChunk != null) {
                            onChunk.accept(toolInfo);
                        }
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        logDebug("工具执行完成: " + toolExecution);
                    })
                    .onCompleteResponse(futureResponse::complete)
                    .onError(error -> {
                        Throwable ex = error != null ? error : new Exception("TokenStream未知错误");
                        if (!futureResponse.isDone()) {
                            logError("TokenStream错误: " + ex.getMessage());
                            futureResponse.completeExceptionally(ex);
                        }
                    })
                    .start();
                
                try {
                    futureResponse.get(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        throw new Exception("扫描已取消");
                    }
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
                }
                
                String result = resultBuilder.toString();
                if (result == null || result.trim().isEmpty()) {
                    return "## 风险等级: 无\n未收到AI分析结果。";
                }
                logDebug("分析完成: " + url + ", 结果长度: " + result.length());
                return result;
                
            } catch (Exception e) {
                lastException = e;
                String errMsg = extractErrorMessage(e);
                
                // 非致命错误：直接返回结果，不再重试
                if (isNonFatalApiError(errMsg)) {
                    logInfo("非致命API错误（跳过）: " + url + " → " + categorizeError(errMsg));
                    return "## 风险等级: 无\n" + categorizeError(errMsg);
                }
                
                // 可重试的瞬时错误
                if (attempt < MAX_RETRIES - 1 && isRetryableError(errMsg)) {
                    logInfo("可重试错误（第 " + (attempt + 1) + "/" + MAX_RETRIES + "），将重试: " + url);
                    try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                    continue;
                }
                
                // 所有重试耗尽 → 返回错误结果（不抛异常，避免刷屏）
                logError("分析失败（重试耗尽）: " + url + " → " + errMsg);
                return "## 风险等级: 无\n分析失败: " + (errMsg != null && errMsg.length() > 120 ? errMsg.substring(0, 120) + "..." : errMsg);
            }
        }
        
        String errMsg = lastException != null ? extractErrorMessage(lastException) : "未知错误";
        return "## 风险等级: 无\n分析失败: " + errMsg;
    }
    
    /**
     * 判断是否为非致命的 API 错误（不需要重试，直接跳过该请求）
     * 仅限确定无法通过重试解决的错误（如内容审核）
     */
    private boolean isNonFatalApiError(String errMsg) {
        if (errMsg == null) return false;
        return errMsg.contains("DataInspectionFailed") ||           // 内容审核
               errMsg.contains("inappropriate content") ||          // 内容审核
               errMsg.contains("Range of input length");            // 输入太长
    }
    
    /**
     * 判断是否为可重试的瞬时错误
     */
    private boolean isRetryableError(String errMsg) {
        if (errMsg == null) return false;
        return errMsg.contains("content field is") ||
               errMsg.contains("InputRequiredException") ||
               errMsg.contains("must not all null") ||
               errMsg.contains("InvalidParameter") ||
               errMsg.contains("JsonParseException") ||             // SSE 流解析瞬时错误
               errMsg.contains("JsonEOFException") ||               // SSE 流解析瞬时错误
               errMsg.contains("MismatchedInputException") ||       // SSE 流返回格式异常（如数组代替对象）
               errMsg.contains("JsonMappingException") ||           // Jackson 映射异常
               errMsg.contains("Cannot deserialize") ||             // 通用 Jackson 反序列化失败
               errMsg.contains("response cannot be null");          // SDK 二次回调
    }
    
    /**
     * 将 API 错误分类为用户友好的描述
     */
    private String categorizeError(String errMsg) {
        if (errMsg == null) return "未知错误，已跳过。";
        if (errMsg.contains("DataInspectionFailed") || errMsg.contains("inappropriate content"))
            return "请求内容触发了云端内容审核，已跳过。";
        if (errMsg.contains("Range of input length"))
            return "请求内容超出 API 输入长度限制，已跳过。";
        return "API 调用异常，已跳过。";
    }
    
    /**
     * 为单次请求创建独立的 Assistant 实例。
     * 每个请求拥有独立的 ChatMemory，确保：
     * 1. 工具调用产生的中间消息不会污染其他请求
     * 2. 多线程并发请求互不干扰
     * 3. DashScope API 不会因历史消息中的空 content 字段报错
     *
     * maxMessages 设为 100：每轮工具调用占 2 条消息（assistant+tool_call, tool_result），
     * 20 条上限仅够 ~9 轮工具调用就会淘汰原始 UserMessage，
     * 导致 DashScope 抛出 InputRequiredException。
     *
     * @param localChatModel 调用方捕获的 chatModel 本地引用，避免字段竞态
     */
    private Assistant createPerRequestAssistant(StreamingChatModel localChatModel) {
        if (localChatModel == null) {
            throw new IllegalStateException("ChatModel 未初始化，无法创建 Assistant");
        }
        
        MessageWindowChatMemory requestMemory = MessageWindowChatMemory.builder()
                .maxMessages(100)
                .build();
        
        var builder = AiServices.builder(Assistant.class)
                .streamingChatModel(localChatModel)
                .chatMemory(requestMemory)
                .systemMessageProvider(memoryId -> buildSystemPrompt());
        
        // ToolProviders: MCP + 官方 Skills
        if (mcpToolProvider != null) {
            builder.toolProvider(mcpToolProvider);
        }
        if (enableSkills && skillManager != null && skillManager.hasEnabledSkills()) {
            ToolProvider skillsTP = skillManager.getSkillsToolProvider();
            if (skillsTP != null) {
                builder.toolProvider(skillsTP);
            }
        }
        
        // @Tool 注解工具
        if (api != null) {
/*             CurlTools curlTools = new CurlTools(api);
            builder.tools(curlTools); */
            
/*             if (enableMcp) {
                BurpExtTools burpExtTools = new BurpExtTools(api);
                builder.tools(burpExtTools);
            } */
            
            if (enableFileSystemAccess && getEffectiveRagDocumentsPath() != null && !getEffectiveRagDocumentsPath().isEmpty()) {
                FileSystemAccessTools fsaTools = new FileSystemAccessTools(api);
                fsaTools.setAllowedRootPath(getEffectiveRagDocumentsPath());
                builder.tools(fsaTools);
            }
            
            if (enablePythonScript) {
                PythonScriptTool pythonTool = new PythonScriptTool();
                String pythonWorkdir = getEffectivePythonWorkdir();
                if (!pythonWorkdir.isEmpty()) {
                    pythonTool.setWorkingDirectory(pythonWorkdir);
                }
                builder.tools(pythonTool);
            }

            if (enableNotebook) {
                NotebookTools notebookTools = new NotebookTools();
                if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
                    notebookTools.setWorkplaceDirectory(workplaceDirectoryPath.trim());
                }
                builder.tools(notebookTools);
            }
            
            if (enableSkills && skillManager != null && skillManager.hasEnabledTools()) {
                if (skillToolsProvider == null) {
                    skillToolsProvider = new SkillToolsProvider(skillManager, api);
                }
                builder.tools(skillToolsProvider);
            }
        }
        
        return builder.build();
    }
    
    /**
     * 从异常链中提取错误消息（递归检查 cause）
     */
    private String extractErrorMessage(Throwable e) {
        if (e == null) return null;
        String msg = e.getMessage();
        if (msg != null && !msg.isEmpty()) return msg;
        return extractErrorMessage(e.getCause());
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
     * 构建被动扫描的系统提示词，委托给 {@link SystemPromptBuilder}
     */
    private String buildSystemPrompt() {
        int hash = java.util.Objects.hash(
                enableSearch, enableSkills, enableNotebook,
                skillManager != null ? skillManager.getEnabledSkillCount() : 0);
        String cached = cachedSystemPrompt;
        if (cached != null && hash == cachedPromptConfigHash) return cached;

        cached = new SystemPromptBuilder()
                .enableSearch(enableSearch)
                .enableSkills(enableSkills)
                .skillManager(skillManager)
                .build();
        cachedSystemPrompt = cached;
        cachedPromptConfigHash = hash;
        return cached;
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
     * 转义 HTTP 内容中的模板分隔符，防止 LangChain4j Mustache 引擎将其解析为变量。
     * 处理：{{var}}、${var}、{{{var}}} 等所有模板语法
     */
    private String escapeTemplateDelimiters(String content) {
        if (content == null || content.isEmpty()) return content;
        // 将所有 { 替换为 ZWSP+{，彻底阻断 Mustache 的 {{ 模式匹配
        // 性能可接受：被动扫描内容已经过截断，不会太长
        return content
            .replace("{", "\u200B{")   // { -> ZWSP{  (阻断 {{ 和 {{{)
            .replace("${", "$\u200B{"); // 冗余但保留语义清晰
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
    
    /**
     * 调试级别日志 - 仅输出到 Output（非 Error），便于追踪请求生命周期
     */
    private void logDebug(String message) {
        if (api != null) {
            api.logging().logToOutput("[PassiveScan-DEBUG] " + message);
        }
    }

    private String getEffectiveRagDocumentsPath() {
        if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
            return new File(workplaceDirectoryPath.trim(), "rag").getAbsolutePath();
        }
        return ragMcpDocumentsPath != null ? ragMcpDocumentsPath.trim() : "";
    }

    private String getEffectivePythonWorkdir() {
        if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
            return new File(workplaceDirectoryPath.trim(), "python-workdir").getAbsolutePath();
        }
        return "";
    }
}
