package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.ai.analyzer.util.JsonParser;
import com.ai.analyzer.util.RequestSourceDetector;
import com.ai.analyzer.scan.rulesmatch.PreScanFilterManager;
import com.ai.analyzer.scan.rulesmatch.PreScanFilter;
import com.ai.analyzer.scan.rulesmatch.ScanMatch;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.ai.analyzer.core.PluginSettings;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.tools.BatchFuzzTool;
import com.ai.analyzer.tools.CurlTools;
import com.ai.analyzer.tools.WebSearchTools;
import com.ai.analyzer.util.AppLogBuffer;
import com.ai.analyzer.util.DebugContext;
import com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime;
import com.ai.analyzer.agent.runtime.RuntimeEvent;
import com.ai.analyzer.agent.runtime.RuntimeEventListener;
import com.ai.analyzer.core.AgentScopeModelFactory;
import com.ai.analyzer.agent.mcpclient.AgentScopeMcpManager;
import io.agentscope.core.tool.Toolkit;
import lombok.Getter;

/**
 * AI Agent API 客户端 (AgentScope)
 * 负责与 AI 模型交互，支持流式输出、工具调用
 */
public class AgentApiClient {

    // ========== 配置 ==========
    @Getter
    private final AgentConfig config;

    // ========== 核心组件 ==========
    private MontoyaApi api;
    private PreScanFilterManager preScanFilterManager;

    // ========== AgentScope 运行时 ==========
    private AgentScopeAgentRuntime agentScopeRuntime;
    private Toolkit asToolkit;
    private volatile Consumer<String> systemNoticeConsumer;
    /** 模型行为消费者（thinking/工具调用流），支持多订阅者（侧栏 ChatPanel 与主动分析页可同时显示） */
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<String>> modelBehaviorConsumers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile com.ai.analyzer.agent.runtime.RequireConfirmHandler confirmHandler;

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
    private final AtomicLong streamGeneration = new AtomicLong(0);
    /** 当前正在进行的流式会话（可能为 null），供 cancelStreaming 真正取消底层 agent 执行 */
    private volatile com.ai.analyzer.agent.runtime.StreamSession currentSession;
    /**
     * 当前对话的稳定 sessionId。一次「新会话」周期内保持不变，
     * 撤销后重新生成，让暂停→继续可在同一会话上下文内恢复。
     * 值格式：agent-session-<UUID>，UUID 在 startNewSession 时刷新。
     */
    private volatile String currentSessionId;

    // ========== 主动分析历史（共享，面板重建不丢失） ==========
    private final com.ai.analyzer.ui.active.AnalysisHistoryStore analysisHistoryStore =
            new com.ai.analyzer.ui.active.AnalysisHistoryStore();

    /** 返回主动分析历史存储（同一 apiClient 实例内的所有面板共享，重建不丢失） */
    public com.ai.analyzer.ui.active.AnalysisHistoryStore getAnalysisHistoryStore() {
        return analysisHistoryStore;
    }

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
        generateNewSessionId();
    }

    private void generateNewSessionId() {
        currentSessionId = "agent-session-" + java.util.UUID.randomUUID();
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
    }

    // ========== 配置 Getter/Setter（委托给 config）==========

    public void setApi(MontoyaApi api) {
        this.api = api;
    }

    public void setSystemNoticeConsumer(Consumer<String> systemNoticeConsumer) {
        this.systemNoticeConsumer = systemNoticeConsumer;
    }

    /**
     * 注册模型行为回调（thinking 增量 / 工具调用开始与结束）。
     * 消息格式 {@code TYPE|detail}：THINKING / TOOL_START / TOOL_END。
     * 与 {@link #setSystemNoticeConsumer} 一样由 UI 在流式会话期间设置、结束后清除。
     */
    public void setModelBehaviorConsumer(Consumer<String> modelBehaviorConsumer) {
        modelBehaviorConsumers.clear();
        if (modelBehaviorConsumer != null) {
            modelBehaviorConsumers.add(modelBehaviorConsumer);
        }
    }

    /** 追加一个模型行为订阅者（不覆盖已有订阅，供多个 UI 面板同时监听） */
    public void addModelBehaviorConsumer(Consumer<String> consumer) {
        if (consumer != null) {
            modelBehaviorConsumers.add(consumer);
        }
    }

    /** 移除之前添加的模型行为订阅者 */
    public void removeModelBehaviorConsumer(Consumer<String> consumer) {
        modelBehaviorConsumers.remove(consumer);
    }

    private void emitModelBehavior(String type, String detail) {
        if (detail == null) return;
        for (Consumer<String> consumer : modelBehaviorConsumers) {
            try {
                consumer.accept(type + "|" + detail);
            } catch (Exception e) {
                logDebug("模型行为回调失败: " + e.getMessage());
            }
        }
    }

    public String getApiKey() { return config.getApiKey(); }
    public String getApiUrl() { return config.getApiUrl(); }
    public String getModel() { return config.getModel(); }
    public ApiProvider getApiProvider() { return config.getApiProvider(); }
    public boolean isEnableSearch() { return config.isEnableSearch(); }
    public boolean isEnableMcp() { return config.isEnableMcp(); }
    public String getBurpMcpUrl() { return config.getBurpMcpUrl(); }
    public String getBurpMcpAuthorization() { return config.getBurpMcpAuthorization(); }
    public boolean isEnableRagMcp() { return config.isEnableRagMcp(); }
    public String getRagMcpUrl() { return config.getRagMcpUrl(); }
    public String getRagMcpDocumentsPath() { return config.getRagMcpDocumentsPath(); }
    public boolean isEnableChromeMcp() { return config.isEnableChromeMcp(); }
    public String getChromeMcpUrl() { return config.getChromeMcpUrl(); }
    public boolean isEnableFileSystemAccess() { return config.isEnableFileSystemAccess(); }
    public boolean isEnableSkills() { return config.isEnableSkills(); }
    public boolean isEnablePythonScript() { return config.isEnablePythonScript(); }
    public boolean isEnableUnrestrictedCliTool() { return config.isEnableUnrestrictedCliTool(); }
    public String getCustomParameters() { return config.getCustomParameters(); }
    public String getMaxTokens() { return config.getMaxTokens(); }

    public void setApiUrl(String apiUrl) {
        if (config.getApiUrl() == null || !config.getApiUrl().equals(apiUrl)) {
            config.setApiUrl(apiUrl);
            invalidateAgentScopeRuntime();
        }
    }

    public void setApiKey(String apiKey) {
        if (config.getApiKey() == null || !config.getApiKey().equals(apiKey)) {
            config.setApiKey(apiKey);
            invalidateAgentScopeRuntime();
        }
    }

    public void setModel(String model) {
        if (config.getModel() == null || !config.getModel().equals(model)) {
            config.setModel(model);
            invalidateAgentScopeRuntime();
        }
    }

    public void setApiProvider(ApiProvider apiProvider) {
        if (config.getApiProvider() != apiProvider) {
            config.setApiProvider(apiProvider);
            invalidateAgentScopeRuntime();
            logInfo("API 提供者已切换为: " + apiProvider.getDisplayName());
        }
    }

    public void setApiProvider(String providerName) {
        setApiProvider(ApiProvider.fromDisplayName(providerName));
    }

    public void setEnableSearch(boolean enableSearch) {
        if (config.isEnableSearch() != enableSearch) {
            config.setEnableSearch(enableSearch);
            invalidateAgentScopeRuntime();
        }
    }

    public void setSearchMode(String searchMode) {
        if (searchMode == null) searchMode = "enableSearch";
        if (!searchMode.equals(config.getSearchMode())) {
            config.setSearchMode(searchMode);
            invalidateAgentScopeRuntime();
        }
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        if (tavilyApiKey == null) tavilyApiKey = "";
        if (!tavilyApiKey.equals(config.getTavilyApiKey())) {
            config.setTavilyApiKey(tavilyApiKey);
            invalidateAgentScopeRuntime();
        }
    }

    public void setTavilyBaseUrl(String tavilyBaseUrl) {
        if (tavilyBaseUrl == null) tavilyBaseUrl = "";
        if (!tavilyBaseUrl.equals(config.getTavilyBaseUrl())) {
            config.setTavilyBaseUrl(tavilyBaseUrl);
            invalidateAgentScopeRuntime();
        }
    }

    public void setGoogleSearchApiKey(String key) {
        if (key == null) key = "";
        if (!key.equals(config.getGoogleSearchApiKey())) {
            config.setGoogleSearchApiKey(key);
            invalidateAgentScopeRuntime();
        }
    }

    public void setGoogleSearchCsi(String csi) {
        if (csi == null) csi = "";
        if (!csi.equals(config.getGoogleSearchCsi())) {
            config.setGoogleSearchCsi(csi);
            invalidateAgentScopeRuntime();
        }
    }

    public void setCustomParameters(String customParameters) {
        if (customParameters == null) customParameters = "";
        if (!config.getCustomParameters().equals(customParameters.trim())) {
            config.setCustomParameters(customParameters.trim());
            invalidateAgentScopeRuntime();
            if (!customParameters.isEmpty()) {
                logInfo("自定义参数已更新: " + customParameters);
            }
        }
    }

    public void setMaxTokens(String maxTokens) {
        if (maxTokens == null) maxTokens = "";
        String normalized = maxTokens.trim();
        if (!config.getMaxTokens().equals(normalized)) {
            config.setMaxTokens(normalized);
            if (!normalized.isEmpty()) {
                logInfo("显式 max_tokens 已更新: " + normalized);
            }
        }
    }

    public void setEnableMcp(boolean enableMcp) {
        if (config.isEnableMcp() != enableMcp) {
            config.setEnableMcp(enableMcp);
            invalidateAgentScopeRuntime();
            logInfo("MCP 工具调用已" + (enableMcp ? "启用" : "禁用"));
        }
    }

    public void setBurpMcpUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            mcpUrl = "http://127.0.0.1:9876/";
        }
        if (!config.getBurpMcpUrl().equals(mcpUrl.trim())) {
            config.setBurpMcpUrl(mcpUrl.trim());
            invalidateAgentScopeRuntime();
            logInfo("Burp MCP 地址已更新: " + mcpUrl);
        }
    }

    public void setBurpMcpAuthorization(String authorization) {
        if (authorization == null) authorization = "";
        if (!config.getBurpMcpAuthorization().equals(authorization.trim())) {
            config.setBurpMcpAuthorization(authorization.trim());
            invalidateAgentScopeRuntime();
            logInfo("Burp MCP Authorization 已更新");
        }
    }

    public void setEnableRagMcp(boolean enableRagMcp) {
        if (config.isEnableRagMcp() != enableRagMcp) {
            config.setEnableRagMcp(enableRagMcp);
            invalidateAgentScopeRuntime();
            logInfo("RAG MCP 工具调用已" + (enableRagMcp ? "启用" : "禁用"));
        }
    }

    public void setRagMcpUrl(String ragMcpUrl) {
        if (ragMcpUrl == null || ragMcpUrl.trim().isEmpty()) ragMcpUrl = "";
        if (!config.getRagMcpUrl().equals(ragMcpUrl.trim())) {
            config.setRagMcpUrl(ragMcpUrl.trim());
            invalidateAgentScopeRuntime();
            logInfo("RAG MCP 地址已更新: " + ragMcpUrl);
        }
    }

    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) {
        if (ragMcpDocumentsPath == null) ragMcpDocumentsPath = "";
        if (!config.getRagMcpDocumentsPath().equals(ragMcpDocumentsPath.trim())) {
            config.setRagMcpDocumentsPath(ragMcpDocumentsPath.trim());
            invalidateAgentScopeRuntime();
            logInfo("RAG MCP 文档路径已更新: " + ragMcpDocumentsPath);
        }
    }

    public void setEnableChromeMcp(boolean enableChromeMcp) {
        if (config.isEnableChromeMcp() != enableChromeMcp) {
            config.setEnableChromeMcp(enableChromeMcp);
            invalidateAgentScopeRuntime();
            logInfo("Chrome MCP 工具调用已" + (enableChromeMcp ? "启用" : "禁用"));
        }
    }

    public void setChromeMcpUrl(String chromeMcpUrl) {
        if (chromeMcpUrl == null || chromeMcpUrl.trim().isEmpty()) chromeMcpUrl = "";
        if (!config.getChromeMcpUrl().equals(chromeMcpUrl.trim())) {
            config.setChromeMcpUrl(chromeMcpUrl.trim());
            invalidateAgentScopeRuntime();
            logInfo("Chrome MCP 地址已更新: " + chromeMcpUrl);
        }
    }

    public void setCustomMcpConfigJson(String customMcpConfigJson) {
        String normalized = customMcpConfigJson == null ? "" : customMcpConfigJson;
        if (!config.getCustomMcpConfigJson().equals(normalized)) {
            config.setCustomMcpConfigJson(normalized);
            invalidateAgentScopeRuntime();
            logInfo("自定义 MCP 配置已更新" + (normalized.isEmpty() ? "（已清空）" : ""));
        }
    }

    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) {
        if (config.isEnableFileSystemAccess() != enableFileSystemAccess) {
            config.setEnableFileSystemAccess(enableFileSystemAccess);
            invalidateAgentScopeRuntime();
            logInfo("直接查找知识库已" + (enableFileSystemAccess ? "启用" : "禁用"));
        }
    }

    public void setEnableSkills(boolean enableSkills) {
        if (config.isEnableSkills() != enableSkills) {
            config.setEnableSkills(enableSkills);
            invalidateAgentScopeRuntime();
            logInfo("Skills 已" + (enableSkills ? "启用" : "禁用"));
        }
    }
    public void setSkillsDirectoryPath(String path) {
        String normalized = path != null ? path.trim() : "";
        if (!config.getSkillsDirectoryPath().equals(normalized)) {
            config.setSkillsDirectoryPath(normalized);
            invalidateAgentScopeRuntime();
        }
    }

    /**
     * 启用/禁用 AgentScope Plan Mode。
     * 启用后 Agent 可自主进入计划阶段（只读调查 → plan_write 写计划 → plan_exit 请求批准），
     * 批准后进入执行阶段。切换会重建 AgentScope 运行时（下次请求生效）。
     */
    public void setEnablePlanMode(boolean enablePlanMode) {
        if (config.isEnablePlanMode() != enablePlanMode) {
            config.setEnablePlanMode(enablePlanMode);
            invalidateAgentScopeRuntime();
            logInfo("Plan Mode 已" + (enablePlanMode ? "启用" : "禁用"));
        }
    }

    public boolean isEnablePlanMode() {
        return config.isEnablePlanMode();
    }

    /**
     * 设置用户确认回调（HITL）。agent 请求批准（如 plan_exit）时触发；
     * 未设置时自动拒绝（安全默认）。由 UI 在流式会话期间设置、结束后清除。
     */
    public void setRequireConfirmHandler(com.ai.analyzer.agent.runtime.RequireConfirmHandler handler) {
        AgentScopeAgentRuntime runtime = agentScopeRuntime;
        if (runtime != null) {
            runtime.setRequireConfirmHandler(handler);
        }
        this.confirmHandler = handler;
    }

    public String getSkillsDirectoryPath() {
        return config.getSkillsDirectoryPath();
    }

    public void setEnablePythonScript(boolean enablePythonScript) {
        if (config.isEnablePythonScript() != enablePythonScript) {
            config.setEnablePythonScript(enablePythonScript);
            invalidateAgentScopeRuntime();
            logInfo("Python 脚本执行已" + (enablePythonScript ? "启用" : "禁用"));
        }
    }

    public void setEnableCliTool(boolean enableCliTool) {
        if (config.isEnableCliTool() != enableCliTool) {
            config.setEnableCliTool(enableCliTool);
            invalidateAgentScopeRuntime();
            logInfo("CLI 工具已" + (enableCliTool ? "启用" : "禁用"));
        }
    }

    public void setEnableUnrestrictedCliTool(boolean enableUnrestrictedCliTool) {
        if (config.isEnableUnrestrictedCliTool() != enableUnrestrictedCliTool) {
            config.setEnableUnrestrictedCliTool(enableUnrestrictedCliTool);
            invalidateAgentScopeRuntime();
            logInfo("CLI 无限制模式已" + (enableUnrestrictedCliTool ? "启用" : "禁用"));
        }
    }

    public void setCliWhitelist(String v) {
        if (v == null) v = "";
        if (!v.equals(config.getCliWhitelist())) {
            config.setCliWhitelist(v);
            invalidateAgentScopeRuntime();
        }
    }

    public void setCliToolPrompt(String v) {
        if (v == null) v = "";
        if (!v.equals(config.getCliToolPrompt())) {
            config.setCliToolPrompt(v);
            invalidateAgentScopeRuntime();
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
        normalized = normalizePath(normalized);
        com.ai.analyzer.util.HttpFormatter.setWorkplaceDirectory(normalized);
        if (!java.util.Objects.equals(config.getWorkplaceDirectoryPath(), normalized)) {
            config.setWorkplaceDirectoryPath(normalized);
            if (!normalized.isEmpty()) {
                config.setRagMcpDocumentsPath(new File(normalized, "rag").getAbsolutePath());
            }
            invalidateAgentScopeRuntime();
            logInfo("Workplace 目录已更新: " + normalized);
        }
    }

    /**
     * 设置前置扫描过滤器管理器
     */
    public void setPreScanFilterManager(PreScanFilterManager preScanFilterManager) {
        this.preScanFilterManager = preScanFilterManager;
    }

    private void invalidateAgentScopeRuntime() {
        if (agentScopeRuntime != null) {
            try { agentScopeRuntime.shutdown(); } catch (Exception ignored) {}
            agentScopeRuntime = null;
        }
        asToolkit = null;
    }

    // ========== AgentScope 运行时初始化 ==========

    private void ensureAgentScopeInitialized() {
        if (agentScopeRuntime != null) return;

        try {
            // 1. 创建 AgentScope Model
            io.agentscope.core.model.Model asModel = AgentScopeModelFactory.create(
                    config.getApiProvider(),
                    config.getApiKey(),
                    config.getApiUrl(),
                    config.getModel(),
                    config.isModelSearchEnabled(),
                    config.getCustomParameters());
            logInfo("AgentScope Model 已创建: " + AgentScopeModelFactory.describeConfig(
                    config.getApiProvider(), config.getApiUrl(), config.getModel(),
                    config.isModelSearchEnabled()));

            // 2. 创建 Toolkit 并注册所有工具
            asToolkit = new Toolkit();

            // 注：文件系统工具（read_file/write_file/edit_file/grep/glob）与 Shell 工具
            // （execute_shell_command）由 Harness 原生提供（workspace 根约束、默认开启），
            // 开关通过 AgentScopeAgentRuntime.disableFilesystemTools()/disableShellTool() 映射；
            // 超长内容统一由原生 ToolResultEviction 落盘 + read_file 读回。

            // 注册 WebSearch 工具
            if (config.isTavilySearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.tavily(config.getTavilyApiKey(), config.getTavilyBaseUrl()));
                logInfo("AgentScope WebSearchTools (Tavily) 已注册");
            } else if (config.isGoogleSearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.google(config.getGoogleSearchApiKey(), config.getGoogleSearchCsi()));
                logInfo("AgentScope WebSearchTools (Google) 已注册");
            } else if (config.isDuckDuckGoSearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.duckDuckGo());
                logInfo("AgentScope WebSearchTools (DuckDuckGo) 已注册");
            }

            // 注册浏览器渲染工具
            asToolkit.registerTool(new com.ai.analyzer.tools.BrowserRenderTool());

            // 注册 Burp 扩展工具（Intruder 发送等基础功能）
            if (api != null) {
                asToolkit.registerTool(new BatchFuzzTool(api));
                asToolkit.registerTool(new CurlTools(api));
                logInfo("AgentScope BatchFuzzTool + CurlTools 已注册");
            }

            // 3. 注册 MCP 客户端
            if (config.hasAnyMcpEnabled()) {
                // Burp MCP
                if (config.isEnableMcp() && config.getBurpMcpUrl() != null && !config.getBurpMcpUrl().trim().isEmpty()) {
                    AgentScopeMcpManager.registerBurpMcp(asToolkit, config.getBurpMcpUrl(), config.getBurpMcpAuthorization());
                }
                // RAG MCP
                if (config.isEnableRagMcp()) {
                    AgentScopeMcpManager.registerRagMcp(asToolkit, config.getRagMcpUrl());
                }
                // Chrome MCP
                if (config.isEnableChromeMcp()) {
                    AgentScopeMcpManager.registerChromeMcp(asToolkit, config.getChromeMcpUrl());
                }
                // 自定义 MCP 服务器
                for (var customConfig : config.getCustomMcpConfigs()) {
                    if (!customConfig.isEnabled() || !customConfig.isValid()) continue;
                    String transportType = customConfig.getRawType();
                    String url = customConfig.getUrl();
                    List<String> command = customConfig.getCommand();
                    String urlOrCommand = url != null && !url.isEmpty() ? url : (!command.isEmpty() ? command.get(0) : "");
                    List<String> args = command.size() > 1 ? command.subList(1, command.size()) : List.of();
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    if (customConfig.getAuthorization() != null && !customConfig.getAuthorization().isEmpty()) {
                        headers.put("Authorization",
                                com.ai.analyzer.agent.mcpclient.AgentScopeMcpManager
                                        .normalizeBearer(customConfig.getAuthorization()));
                    }
                    AgentScopeMcpManager.registerCustomMcp(asToolkit,
                            customConfig.getName(),
                            transportType,
                            urlOrCommand,
                            args,
                            headers);
                }
            }

            // 4. 构建系统提示词
            String systemPrompt = buildSystemPrompt();

            // 5. 创建工作区路径
            java.nio.file.Path workspacePath = null;
            if (config.getWorkplaceDirectoryPath() != null && !config.getWorkplaceDirectoryPath().trim().isEmpty()) {
                workspacePath = java.nio.file.Path.of(config.getWorkplaceDirectoryPath().trim());
            }

            // 6. 创建 AgentScopeAgentRuntime
            var runtimeBuilder = AgentScopeAgentRuntime.builder()
                    .mode(AgentScopeAgentRuntime.Mode.ACTIVE)
                    .model(asModel)
                    .systemPrompt(systemPrompt)
                    .workspacePath(workspacePath)
                    .toolkit(asToolkit);
            if (!config.isEnableFileSystemAccess()) {
                runtimeBuilder.disableFilesystemTools();
            }
            if (!config.isEnableCliTool()) {
                runtimeBuilder.disableShellTool();
            } else {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) {
                    // Windows：禁用 AgentScope 内置 shell，注册自定义 PowerShell 工具
                    // 自定义工具处理 GBK 编码、路径空格、输出解码
                    runtimeBuilder.disableShellTool();
                    asToolkit.registerTool(new com.ai.analyzer.tools.ShellExecTool(
                            config.getWorkplaceDirectoryPath(),
                            config.getWorkplaceDirectoryPath(),
                            true,
                            config.isEnableUnrestrictedCliTool()));
                    logInfo("Windows 平台：已注册自定义 PowerShell 执行工具");
                } else {
                    // Linux/macOS：使用 AgentScope 内置 execute_shell_command
                    // 内置工具支持工作区隔离、ToolResultEviction、沙箱
                    logInfo("Unix 平台：使用 AgentScope 内置 shell 工具");
                }
            }
            // 上下文 Token 预算：通过 maxTokens 配置传给 HarnessAgent 和 CompactionConfig
            String maxTokens = config.getMaxTokens();
            if (maxTokens != null && !maxTokens.isBlank()) {
                try {
                    int tokens = Integer.parseInt(maxTokens);
                    if (tokens > 0) {
                        runtimeBuilder.maxContextTokens(tokens);
                    }
                } catch (NumberFormatException ignored) { }
            }
            // Plan Mode：允许 Agent 先计划（只读调查 → 写 PLAN.md → 请求批准）再执行
            runtimeBuilder.enablePlanMode(config.isEnablePlanMode());
            if (config.isEnablePlanMode()) {
                // 计划阶段写的 todos 在每次推理前展示，配合 todo_write 保持执行聚焦
                runtimeBuilder.enableTaskList(true);
            }
            // Skills：启用时注入技能仓库，Harness 自动将 SKILL.md 提示注入上下文
            String skillsDir = config.getSkillsDirectoryPath();
            if (config.isEnableSkills() && skillsDir != null && !skillsDir.trim().isEmpty()) {
                try {
                    var skillRepository = new io.agentscope.core.skill.repository.FileSystemSkillRepository(
                            java.nio.file.Path.of(skillsDir.trim()), false);
                    runtimeBuilder.skillRepositories(java.util.List.of(skillRepository));
                    logInfo("Skills 技能仓库已注入: " + skillsDir);
                } catch (Exception e) {
                    logError("Skills 技能仓库注入失败: " + e.getMessage());
                }
            }
            agentScopeRuntime = runtimeBuilder.build();
            // runtime 重建后恢复确认回调（HITL）
            if (confirmHandler != null) {
                agentScopeRuntime.setRequireConfirmHandler(confirmHandler);
            }
            logInfo("AgentScopeAgentRuntime 已创建" + (config.isEnablePlanMode() ? "（Plan Mode 已启用）" : ""));

        } catch (Exception e) {
            logError("AgentScope 运行时初始化失败: " + e.getMessage());
            agentScopeRuntime = null;
            asToolkit = null;
            throw new RuntimeException("AgentScope 运行时初始化失败", e);
        }
    }

    // ========== 流式输出控制 ==========

    public void cancelStreaming() {
        streamGeneration.incrementAndGet();
        com.ai.analyzer.agent.runtime.StreamSession sess = currentSession;
        if (sess != null) {
            sess.cancel();
        }
        logInfo("流式输出已取消");
    }

    public void clearContext() {
        cancelStreaming();
        logInfo("聊天上下文已清空");
    }

    /**
     * 新开一个会话：销毁当前 Agent（会话状态/记忆随之丢弃），下次请求重新初始化。
     * 用于 UI 的「+ 新会话」按钮。
     */
    public void startNewSession() {
        cancelStreaming();
        if (agentScopeRuntime != null) {
            agentScopeRuntime.resetSession();
        }
        generateNewSessionId();
        logInfo("已新开会话（旧 Agent 会话已销毁）");
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
        config.setEnableSearch(settings.isEnableSearch());
        config.setSearchMode(settings.getSearchMode());
        config.setTavilyApiKey(settings.getTavilyApiKey());
        config.setTavilyBaseUrl(settings.getTavilyBaseUrl());
        config.setGoogleSearchApiKey(settings.getGoogleSearchApiKey());
        config.setGoogleSearchCsi(settings.getGoogleSearchCsi());
        config.setEnableCliTool(settings.isEnableCliTool());
        config.setEnableUnrestrictedCliTool(settings.isEnableUnrestrictedCliTool());
        config.setBurpMcpAuthorization(settings.getBurpMcpAuthorization());
        config.setCliWhitelist(settings.getCliWhitelist());
        config.setCliToolPrompt(settings.getCliToolPrompt());
        setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        if (settings.getRagMcpDocumentsPath() != null && !settings.getRagMcpDocumentsPath().isEmpty()) {
            config.setRagMcpDocumentsPath(settings.getRagMcpDocumentsPath());
        }
        config.setEnableSkills(settings.isEnableSkills());
        config.setSkillsDirectoryPath(settings.getSkillsDirectoryPath());
        config.setEnablePythonScript(settings.isEnablePythonScript());
        config.setEnableFileSystemAccess(settings.isEnableFileSystemAccess());
        config.setEnableRagMcp(settings.isEnableRagMcp());
        config.setEnableChromeMcp(settings.isEnableChromeMcp());
        config.setChromeMcpUrl(settings.getChromeMcpUrl());
        config.setBurpMcpUrl(settings.getMcpUrl());
        config.setRagMcpUrl(settings.getRagMcpUrl());
        config.setCustomMcpConfigJson(settings.getCustomMcpConfigJson());
    }

    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try {
            return PluginSettings.loadCompat(settingsFile);
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
            ? com.ai.analyzer.util.HttpFormatter.formatHttpRequestResponse(requestResponse)
            : "";

        // ========== 前置扫描器集成 ==========
        String preScanHint = "";
        if (preScanFilterManager != null && preScanFilterManager.isEnabled() && requestResponse != null) {
            try {
                PreScanFilter filter = preScanFilterManager.getFilter();
                if (filter != null) {
                    List<ScanMatch> matches = filter.scan(requestResponse,
                        preScanFilterManager.getDefaultScanTimeout());

                    if (!matches.isEmpty()) {
                        String uiMessage = PreScanFilter.buildUiMessage(matches);
                        if (onChunk != null) {
                            onChunk.accept("\n" + uiMessage + "\n");
                        }
                        preScanHint = PreScanFilter.buildPromptHint(matches);
                        logInfo("[PreScan] 检测到 " + matches.size() + " 个疑似漏洞特征");
                    }
                }
            } catch (Exception e) {
                logError("[PreScan] 扫描失败: " + e.getMessage());
            }
        }

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

        if (com.ai.analyzer.util.TokenUsageTracker.sharedOverBudget()) {
            String msg = "⛔ Token 预算已用尽，本次分析已跳过。请重置统计或提高预算。";
            logInfo(msg);
            if (onChunk != null) {
                onChunk.accept(msg);
            }
            return;
        }

        String userContent = buildAnalysisContent(httpRequest, userPrompt, sourceInfo);
        userContent = compressContent(userContent, 90000);

        logInfo("使用 AgentScope 发送流式请求");
        logInfo("模型: " + (config.getModel() != null ? config.getModel() : "qwen-max"));

        ensureAgentScopeInitialized();
        if (agentScopeRuntime == null) {
            throw new Exception("AgentScope 运行时初始化失败，请检查API Key和URL配置");
        }

        final boolean[] thinkingNoticeShown = {false};
        final int[] contentChunkCount = {0};
        final long streamId = streamGeneration.incrementAndGet();

        com.ai.analyzer.agent.runtime.StreamSession session = agentScopeRuntime.chat(
                userContent,
                currentSessionId,
                new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                        if (!isCurrentStream(streamId)) return;
                        switch (event.type()) {
                            case TEXT_DELTA -> {
                                String text = event.text();
                                if (text != null && !text.isEmpty()) {
                                    if (thinkingNoticeShown[0]) {
                                        thinkingNoticeShown[0] = false;
                                    }
                                    onChunk.accept(text);
                                    contentChunkCount[0]++;
                                }
                            }
                            case THINKING -> {
                                String thinkingDelta = event.text();
                                if (!thinkingNoticeShown[0]) {
                                    emitSystemNotice("思考中...");
                                    thinkingNoticeShown[0] = true;
                                }
                                emitModelBehavior("THINKING", thinkingDelta != null ? thinkingDelta : "");
                                DebugContext.log("AgentApiClient", "thinking",
                                        java.util.Map.of("len", String.valueOf(thinkingDelta != null ? thinkingDelta.length() : 0)));
                            }
                            case TOOL_START -> {
                                AppLogBuffer.tool("AgentApiClient", event.toolName() != null ? event.toolName() : "");
                                String toolName = event.toolName();
                                if (toolName != null && !toolName.isEmpty()) {
                                    onChunk.accept("\n[TOOL_BLOCK]" + escapeHtml(toolName) + "[/TOOL_BLOCK]\n");
                                    emitModelBehavior("TOOL_START", toolName);
                                }
                                DebugContext.log("AgentApiClient", "tool_start",
                                        java.util.Map.of("tool", toolName != null ? toolName : "null"));
                            }
                            case TOOL_END -> {
                                String toolName = event.toolName();
                                boolean toolFailed = event.toolFailed();
                                AppLogBuffer.tool("AgentApiClient", "executed: " + (toolName != null ? toolName : "unknown")
                                        + (toolFailed ? " (failed)" : " (ok)"));
                                if (toolFailed) {
                                    onChunk.accept("\n⚠️ 工具执行失败: " + escapeHtml(toolName != null ? toolName : "unknown") + "\n");
                                }
                                emitModelBehavior("TOOL_END", (toolName != null ? toolName : "unknown")
                                        + (toolFailed ? "|failed" : "|ok"));
                                DebugContext.log("AgentApiClient", "tool_end",
                                        java.util.Map.of("tool", toolName != null ? toolName : "null", "failed", String.valueOf(toolFailed)));
                            }
                            case REPLY_END -> {
                                logInfo("AgentScope 流式输出完成，共收到 " + contentChunkCount[0] + " 个chunk");
                                DebugContext.log("AgentApiClient", "reply_end",
                                        java.util.Map.of("chunks", String.valueOf(contentChunkCount[0])));
                            }
                            case SUBAGENT -> {
                                logInfo("Sub-agent 已创建: " + (event.subagentName() != null ? event.subagentName() : ""));
                            }
                            case USAGE -> {
                                com.ai.analyzer.util.TokenUsageTracker.instance().record(
                                        event.inputTokens(), event.outputTokens(),
                                        event.cachedTokens(), event.totalTokens());
                                DebugContext.log("AgentApiClient", "usage",
                                        java.util.Map.of("input", String.valueOf(event.inputTokens()),
                                                "output", String.valueOf(event.outputTokens())));
                            }
                            case ERROR -> {
                                Throwable err = event.error();
                                logError("AgentScope 事件错误: " + (err != null ? err.getMessage() : "unknown"));
                            }
                            default -> {
                                // MEMORY, etc. — 静默忽略
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        logDebug("AgentScope 流式会话完成");
                    }

                    @Override
                    public void onError(Throwable error) {
                        logError("AgentScope 流式错误: " + (error != null ? error.getMessage() : "unknown"));
                    }
                });

        // 等待流式输出完成
        currentSession = session;
        try {
            session.awaitCompletion();
        } finally {
            currentSession = null;
        }
    }

    private boolean isCurrentStream(long streamId) {
        return streamGeneration.get() == streamId;
    }

    private String compressContent(String content, int maxChars) {
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

    private void emitSystemNotice(String message) {
        Consumer<String> consumer = this.systemNoticeConsumer;
        if (consumer == null || message == null || message.trim().isEmpty()) return;
        try {
            consumer.accept(message.trim());
        } catch (Exception e) {
            logDebug("系统提示回调失败: " + e.getMessage());
        }
    }

    private String buildAnalysisContent(String httpContent, String userPrompt,
            RequestSourceDetector.RequestSourceInfo sourceInfo) {
        StringBuilder content = new StringBuilder();

        if (sourceInfo != null) {
            content.append(sourceInfo.format()).append("\n\n");
        }

        if (httpContent != null && !httpContent.trim().isEmpty()) {
            com.ai.analyzer.util.HttpFormatter.PromptPrepareResult prepared =
                    com.ai.analyzer.util.HttpFormatter.prepareForPrompt(httpContent, "http-content");
            if (prepared.cached) {
                logInfo("HTTP内容过长已缓存: " + prepared.originalLength + " 字符，提示词仅含预览与 fileId");
            }
            String finalHttp = prepared.promptText;

            if (finalHttp.contains("=== HTTP请求 ===") && finalHttp.contains("=== HTTP响应 ===")) {
                content.append("以下是HTTP请求和响应信息");
                content.append(prepared.cached ? "（完整报文已缓存，可按 fileId 分段读取）：\n\n" : "：\n\n");
            } else {
                content.append("以下是HTTP请求内容");
                content.append(prepared.cached ? "（完整报文已缓存，可按 fileId 分段读取）：\n\n" : "：\n\n");
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
                config.getRagMcpDocumentsPath(),
                config.getCustomSystemPrompt());
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
                .customBasePrompt(config.getCustomSystemPrompt())
                .build();
        cachedSystemPrompt = cached;
        cachedPromptConfigHash = hash;
        return cached;
    }

    // ========== 日志方法 ==========

    private void logInfo(String message) {
        AppLogBuffer.info("AgentApiClient", message);
        if (api != null) {
            api.logging().logToOutput("[AgentApiClient] " + message);
        }
    }

    private void logError(String message) {
        AppLogBuffer.error("AgentApiClient", message);
        if (api != null) {
            api.logging().logToError("[AgentApiClient] " + message);
        }
    }

    private void logDebug(String message) {
        AppLogBuffer.debug("AgentApiClient", message);
        if (api != null) {
            api.logging().logToOutput("[AgentApiClient] " + message);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Windows 路径含空格时转换为短路径（8.3 格式），避免 ProcessBuilder/HarnessAgent 传参问题。
     * 非 Windows 或路径无空格时原样返回。
     */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return path;
        if (!path.contains(" ")) return path;
        try {
            // 确保目录存在，否则 %~sI 无法解析短路径
            new File(path).mkdirs();
            Process p = new ProcessBuilder("cmd.exe", "/c", "for %I in (\"" + path + "\") do @echo %~sI")
                    .redirectErrorStream(true)
                    .start();
            String shortPath = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!shortPath.isEmpty() && !shortPath.contains(" ")) return shortPath;
        } catch (Exception ignored) { }
        return path;
    }
}