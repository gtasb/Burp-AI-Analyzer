package com.ai.analyzer.scan.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.ai.analyzer.tools.BatchFuzzTool;
import com.ai.analyzer.tools.CurlTools;
import com.ai.analyzer.tools.WebSearchTools;
import com.ai.analyzer.agent.mcpclient.CustomMcpConfig;
import com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser;
import com.ai.analyzer.core.PluginSettings;
import com.ai.analyzer.util.AppLogBuffer;
import com.ai.analyzer.util.DebugContext;
import com.ai.analyzer.util.HttpFormatter;
import com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime;
import com.ai.analyzer.agent.runtime.RuntimeEvent;
import com.ai.analyzer.agent.runtime.RuntimeEventListener;
import com.ai.analyzer.core.AgentScopeModelFactory;
import com.ai.analyzer.agent.mcpclient.AgentScopeMcpManager;
import io.agentscope.core.tool.Toolkit;
import com.ai.analyzer.scan.rulesmatch.PreScanFilterManager;
import com.ai.analyzer.scan.rulesmatch.PreScanFilter;
import com.ai.analyzer.scan.rulesmatch.ScanMatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 被动扫描专用的AI API客户端 (AgentScope)
 *
 * 设计原则（DAST风格）：
 * 1. 完整功能 - 支持MCP工具调用、联网搜索等
 * 2. 松耦合 - 与主AgentApiClient分离，有独立的状态管理
 * 3. 线程安全 - 使用同步机制保护共享资源
 */
public class PassiveScanApiClient {

    // API配置
    @Getter
    private String apiKey;
    @Getter
    private String apiUrl;
    @Getter
    private String model;
    private String maxTokens = "";
    @Getter
    private ApiProvider apiProvider = ApiProvider.DASHSCOPE;

    // Burp API引用
    private MontoyaApi api;

    // 前置扫描过滤器管理器
    private PreScanFilterManager preScanFilterManager;

    // 功能开关
    @Getter
    private boolean enableSearch = false;
    @Getter
    private String searchMode = "enableSearch";
    @Getter
    private String tavilyApiKey = "";
    @Getter
    private String tavilyBaseUrl = "";
    @Getter
    private String googleSearchApiKey = "";
    @Getter
    private String googleSearchCsi = "";
    @Getter
    private boolean enableMcp = false;
    @Getter
    private String BurpMcpUrl = "http://127.0.0.1:9876/";
    @Getter
    private String burpMcpAuthorization = "";
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
    private boolean enableCliTool = false;
    @Getter
    private boolean enableUnrestrictedCliTool = false;
    @Getter
    private String cliWhitelist = "";
    @Getter
    private String cliToolPrompt = "";
    @Getter
    private boolean enableSkills = false;
    @Getter
    private String skillsDirectoryPath = "";
    @Getter
    private String workplaceDirectoryPath = "";
    @Getter
    private String customSystemPrompt = "";

    // 自定义 MCP 服务器配置（JSON 字符串，简版数组格式）
    @Getter
    private String customMcpConfigJson = "";
    private List<CustomMcpConfig> customMcpConfigs = Collections.emptyList();

    // ========== AgentScope 运行时 ==========
    private AgentScopeAgentRuntime agentScopeRuntime;
    private Toolkit asToolkit;

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
     * 无参构造函数
     */
    public PassiveScanApiClient(MontoyaApi api, String apiUrl, String apiKey) {
        this.api = api;
        if (apiUrl != null && !apiUrl.trim().isEmpty() && apiKey != null && !apiKey.trim().isEmpty()) {
            this.apiUrl = apiUrl.trim();
            this.apiKey = apiKey.trim();
        } else {
            loadSettingsFromFile();
        }
    }

    private void invalidateAgentScopeRuntime() {
        if (agentScopeRuntime != null) {
            try { agentScopeRuntime.shutdown(); } catch (Exception ignored) {}
            agentScopeRuntime = null;
        }
        asToolkit = null;
    }

    // ========== 配置 Setter ==========

    public void setApi(MontoyaApi api) { this.api = api; }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.equals(this.apiKey)) {
            this.apiKey = apiKey;
            invalidateAgentScopeRuntime();
        }
    }

    public void setApiUrl(String apiUrl) {
        if (apiUrl != null && !apiUrl.equals(this.apiUrl)) {
            this.apiUrl = apiUrl;
            invalidateAgentScopeRuntime();
        }
    }

    public void setModel(String model) {
        if (model != null && !model.equals(this.model)) {
            this.model = model;
            invalidateAgentScopeRuntime();
        }
    }

    public void setMaxTokens(String maxTokens) {
        if (maxTokens == null) maxTokens = "";
        this.maxTokens = maxTokens.trim();
    }

    public void setApiProvider(ApiProvider apiProvider) {
        if (apiProvider != null && apiProvider != this.apiProvider) {
            this.apiProvider = apiProvider;
            invalidateAgentScopeRuntime();
        }
    }

    public void setApiProvider(String providerName) {
        setApiProvider(ApiProvider.fromDisplayName(providerName));
    }

    public void setEnableSearch(boolean v) {
        if (v != this.enableSearch) { this.enableSearch = v; invalidateAgentScopeRuntime(); }
    }

    public void setSearchMode(String v) {
        if (v == null) v = "enableSearch";
        if (!v.equals(this.searchMode)) { this.searchMode = v; invalidateAgentScopeRuntime(); }
    }

    public void setTavilyApiKey(String v) {
        if (v == null) v = "";
        if (!v.equals(this.tavilyApiKey)) { this.tavilyApiKey = v; invalidateAgentScopeRuntime(); }
    }

    public void setTavilyBaseUrl(String v) {
        if (v == null) v = "";
        if (!v.equals(this.tavilyBaseUrl)) { this.tavilyBaseUrl = v; invalidateAgentScopeRuntime(); }
    }

    public void setGoogleSearchApiKey(String v) {
        if (v == null) v = "";
        if (!v.equals(this.googleSearchApiKey)) { this.googleSearchApiKey = v; invalidateAgentScopeRuntime(); }
    }

    public void setGoogleSearchCsi(String v) {
        if (v == null) v = "";
        if (!v.equals(this.googleSearchCsi)) { this.googleSearchCsi = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableMcp(boolean v) {
        if (v != this.enableMcp) { this.enableMcp = v; invalidateAgentScopeRuntime(); }
    }

    public void setBurpMcpUrl(String v) {
        if (v == null || v.trim().isEmpty()) v = "http://127.0.0.1:9876/";
        if (!v.equals(this.BurpMcpUrl)) { this.BurpMcpUrl = v; invalidateAgentScopeRuntime(); }
    }

    public void setBurpMcpAuthorization(String v) {
        if (v == null) v = "";
        if (!v.equals(this.burpMcpAuthorization)) { this.burpMcpAuthorization = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableRagMcp(boolean v) {
        if (v != this.enableRagMcp) { this.enableRagMcp = v; invalidateAgentScopeRuntime(); }
    }

    public void setRagMcpUrl(String v) {
        if (v == null || v.trim().isEmpty()) v = "";
        if (!v.equals(this.ragMcpUrl)) { this.ragMcpUrl = v; invalidateAgentScopeRuntime(); }
    }

    public void setRagMcpDocumentsPath(String v) {
        if (v == null) v = "";
        if (!v.equals(this.ragMcpDocumentsPath)) { this.ragMcpDocumentsPath = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableChromeMcp(boolean v) {
        if (v != this.enableChromeMcp) { this.enableChromeMcp = v; invalidateAgentScopeRuntime(); }
    }

    public void setChromeMcpUrl(String v) {
        if (v == null || v.trim().isEmpty()) v = "";
        if (!v.equals(this.chromeMcpUrl)) { this.chromeMcpUrl = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableFileSystemAccess(boolean v) {
        if (v != this.enableFileSystemAccess) { this.enableFileSystemAccess = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnablePythonScript(boolean v) {
        if (v != this.enablePythonScript) { this.enablePythonScript = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableCliTool(boolean v) {
        if (v != this.enableCliTool) { this.enableCliTool = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableUnrestrictedCliTool(boolean v) {
        if (v != this.enableUnrestrictedCliTool) { this.enableUnrestrictedCliTool = v; invalidateAgentScopeRuntime(); }
    }

    public void setCliWhitelist(String v) {
        if (v == null) v = "";
        if (!v.equals(this.cliWhitelist)) { this.cliWhitelist = v; invalidateAgentScopeRuntime(); }
    }

    public void setCliToolPrompt(String v) {
        if (v == null) v = "";
        if (!v.equals(this.cliToolPrompt)) { this.cliToolPrompt = v; invalidateAgentScopeRuntime(); }
    }

    public void setEnableSkills(boolean v) {
        if (v != this.enableSkills) { this.enableSkills = v; invalidateAgentScopeRuntime(); }
    }

    public void setSkillsDirectoryPath(String v) {
        if (v == null) v = "";
        if (!v.equals(this.skillsDirectoryPath)) { this.skillsDirectoryPath = v; invalidateAgentScopeRuntime(); }
    }

    public void setWorkplaceDirectoryPath(String workplaceDirectoryPath) {
        String normalized = workplaceDirectoryPath == null ? "" : workplaceDirectoryPath.trim();
        normalized = normalizePath(normalized);
        com.ai.analyzer.util.HttpFormatter.setWorkplaceDirectory(normalized);
        this.workplaceDirectoryPath = normalized;
    }

    public void setCustomMcpConfigJson(String v) {
        String normalized = v == null ? "" : v;
        if (!normalized.equals(this.customMcpConfigJson)) {
            this.customMcpConfigJson = normalized;
            this.customMcpConfigs = CustomMcpConfigParser.parse(normalized);
            invalidateAgentScopeRuntime();
        }
    }

    public void setCustomSystemPrompt(String v) {
        String normalized = v == null ? "" : v;
        if (!java.util.Objects.equals(this.customSystemPrompt, normalized)) {
            this.customSystemPrompt = normalized;
            cachedSystemPrompt = null;
        }
    }

    public void setPreScanFilterManager(PreScanFilterManager preScanFilterManager) {
        this.preScanFilterManager = preScanFilterManager;
    }

    @Deprecated
    public void setPreScanFilterManager(PreScanFilterManager preScanFilterManager, boolean noop) {
        this.preScanFilterManager = preScanFilterManager;
    }

    // ========== AgentScope 运行时初始化 ==========

    private void ensureAgentScopeInitialized() {
        if (agentScopeRuntime != null) return;

        try {
            // 1. 创建 AgentScope Model
            io.agentscope.core.model.Model asModel = AgentScopeModelFactory.create(
                    apiProvider,
                    apiKey,
                    apiUrl,
                    model,
                    isModelSearchEnabled(),
                    ""); // custom parameters - TODO: add field
            logInfo("AgentScope Model 已创建: " + AgentScopeModelFactory.describeConfig(
                    apiProvider, apiUrl, model, isModelSearchEnabled()));

            // 2. 创建 Toolkit 并注册所有工具
            asToolkit = new Toolkit();

            // 注：文件系统工具（read_file/write_file/edit_file/grep/glob）与 Shell 工具
            // （execute_shell_command）由 Harness 原生提供（workspace 根约束、默认开启），
            // 开关通过 AgentScopeAgentRuntime.disableFilesystemTools()/disableShellTool() 映射；
            // 超长内容统一由原生 ToolResultEviction 落盘 + read_file 读回。

            // 注册 WebSearch 工具
            if (isTavilySearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.tavily(tavilyApiKey, tavilyBaseUrl));
                logInfo("AgentScope WebSearchTools (Tavily) 已注册");
            } else if (isGoogleSearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.google(googleSearchApiKey, googleSearchCsi));
                logInfo("AgentScope WebSearchTools (Google) 已注册");
            } else if (isDuckDuckGoSearchEnabled()) {
                asToolkit.registerTool(WebSearchTools.duckDuckGo());
                logInfo("AgentScope WebSearchTools (DuckDuckGo) 已注册");
            }

            // 注册浏览器渲染工具
            asToolkit.registerTool(new com.ai.analyzer.tools.BrowserRenderTool());

            // 注册 Burp 扩展工具（Intruder 发送 + 批量爆破）
            if (api != null) {
                asToolkit.registerTool(new BatchFuzzTool(api));
                asToolkit.registerTool(new CurlTools(api));
                logInfo("AgentScope BatchFuzzTool + CurlTools 已注册");
            }

            // 3. 注册 MCP 客户端
            if (hasAnyMcpEnabled()) {
                if (enableMcp && BurpMcpUrl != null && !BurpMcpUrl.trim().isEmpty()) {
                    AgentScopeMcpManager.registerBurpMcp(asToolkit, BurpMcpUrl, burpMcpAuthorization);
                }
                if (enableRagMcp) {
                    AgentScopeMcpManager.registerRagMcp(asToolkit, ragMcpUrl);
                }
                if (enableChromeMcp) {
                    AgentScopeMcpManager.registerChromeMcp(asToolkit, chromeMcpUrl);
                }
                for (var customConfig : customMcpConfigs) {
                    if (!customConfig.isEnabled() || !customConfig.isValid()) continue;
                    String transportType = customConfig.getRawType();
                    String url = customConfig.getUrl();
                    List<String> command = customConfig.getCommand();
                    String urlOrCommand = url != null && !url.isEmpty() ? url : (!command.isEmpty() ? command.get(0) : "");
                    List<String> args = command.size() > 1 ? command.subList(1, command.size()) : List.of();
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    if (customConfig.getAuthorization() != null && !customConfig.getAuthorization().isEmpty()) {
                        headers.put("Authorization",
                                AgentScopeMcpManager.normalizeBearer(customConfig.getAuthorization()));
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
            if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
                workspacePath = java.nio.file.Path.of(workplaceDirectoryPath.trim());
            }

            // 6. 创建 AgentScopeAgentRuntime (PASSIVE mode)
            var runtimeBuilder = AgentScopeAgentRuntime.builder()
                    .mode(AgentScopeAgentRuntime.Mode.PASSIVE)
                    .model(asModel)
                    .systemPrompt(systemPrompt)
                    .workspacePath(workspacePath)
                    .toolkit(asToolkit);
            if (!enableFileSystemAccess) {
                runtimeBuilder.disableFilesystemTools();
            }
            if (!enableCliTool) {
                runtimeBuilder.disableShellTool();
            } else {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) {
                    runtimeBuilder.disableShellTool();
                    asToolkit.registerTool(new com.ai.analyzer.tools.ShellExecTool(
                            workplaceDirectoryPath, workplaceDirectoryPath, true, enableUnrestrictedCliTool));
                }
            }
            // Skills：启用时注入技能仓库，Harness 自动将 SKILL.md 提示注入上下文
            if (enableSkills && skillsDirectoryPath != null && !skillsDirectoryPath.trim().isEmpty()) {
                try {
                    var skillRepository = new io.agentscope.core.skill.repository.FileSystemSkillRepository(
                            java.nio.file.Path.of(skillsDirectoryPath.trim()), false);
                    runtimeBuilder.skillRepositories(java.util.List.of(skillRepository));
                    logInfo("Skills 技能仓库已注入: " + skillsDirectoryPath);
                } catch (Exception e) {
                    logError("Skills 技能仓库注入失败: " + e.getMessage());
                }
            }
            // 上下文 Token 预算：通过 maxTokens 配置传给 HarnessAgent 和 CompactionConfig
            if (maxTokens != null && !maxTokens.isBlank()) {
                try {
                    int tokens = Integer.parseInt(maxTokens);
                    if (tokens > 0) {
                        runtimeBuilder.maxContextTokens(tokens);
                    }
                } catch (NumberFormatException ignored) { }
            }
            agentScopeRuntime = runtimeBuilder.build();
            logInfo("AgentScopeAgentRuntime 已创建 (PASSIVE mode)");

        } catch (Exception e) {
            logError("AgentScope 运行时初始化失败: " + e.getMessage());
            agentScopeRuntime = null;
            asToolkit = null;
            throw new RuntimeException("AgentScope 运行时初始化失败", e);
        }
    }

    // ========== 功能开关查询 ==========

    public boolean isModelSearchEnabled() {
        return enableSearch && "enableSearch".equals(searchMode);
    }

    public boolean isTavilySearchEnabled() {
        return "tavily".equals(searchMode) && tavilyApiKey != null && !tavilyApiKey.isEmpty();
    }

    public boolean isGoogleSearchEnabled() {
        return "google".equals(searchMode) && googleSearchApiKey != null && !googleSearchApiKey.isEmpty();
    }

    public boolean isDuckDuckGoSearchEnabled() {
        return "duckduckgo".equals(searchMode);
    }

    public boolean hasAnyMcpEnabled() {
        return enableMcp || enableRagMcp || enableChromeMcp || !customMcpConfigs.isEmpty();
    }

    public boolean hasChromeMcpUrl() {
        return chromeMcpUrl != null && !chromeMcpUrl.trim().isEmpty();
    }

    public String getEffectiveRagDocumentsPath() {
        if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
            return new File(workplaceDirectoryPath.trim(), "rag").getAbsolutePath();
        }
        return ragMcpDocumentsPath != null ? ragMcpDocumentsPath.trim() : "";
    }
    public String getEffectiveBurpMcpUrl() { return BurpMcpUrl; }
    public String getEffectiveBurpMcpAuthorization() { return burpMcpAuthorization; }
    public List<CustomMcpConfig> getCustomMcpConfigs() { return customMcpConfigs; }

    // ========== 配置加载 ==========

    private void loadSettingsFromFile() {
        String defaultApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String defaultModel = "qwen3-max";

        File localSettingsFile = new File("ai_analyzer_settings.dat");
        if (localSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(localSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultModel);
                return;
            }
        }

        File userSettingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
        if (userSettingsFile.exists()) {
            PluginSettings settings = loadSettingsFromFile(userSettingsFile);
            if (settings != null) {
                applySettings(settings, defaultApiUrl, defaultModel);
                return;
            }
        }

        this.apiUrl = defaultApiUrl;
        this.apiKey = "";
        this.model = defaultModel;
    }

    private void applySettings(PluginSettings settings, String defaultApiUrl, String defaultModel) {
        this.apiUrl = settings.getApiUrl() != null && !settings.getApiUrl().isEmpty()
            ? settings.getApiUrl() : defaultApiUrl;
        this.apiKey = settings.getApiKey() != null ? settings.getApiKey() : "";
        this.model = settings.getModel() != null && !settings.getModel().isEmpty()
            ? settings.getModel() : defaultModel;
        this.apiProvider = ApiProvider.fromDisplayName(settings.getApiProvider());
        this.enableSearch = settings.isEnableSearch();
        this.searchMode = settings.getSearchMode();
        this.tavilyApiKey = settings.getTavilyApiKey();
        this.tavilyBaseUrl = settings.getTavilyBaseUrl();
        this.googleSearchApiKey = settings.getGoogleSearchApiKey();
        this.googleSearchCsi = settings.getGoogleSearchCsi();
        this.enableMcp = settings.isEnableMcp();
        this.BurpMcpUrl = settings.getMcpUrl();
        this.burpMcpAuthorization = settings.getBurpMcpAuthorization();
        this.enableRagMcp = settings.isEnableRagMcp();
        this.ragMcpUrl = settings.getRagMcpUrl();
        this.ragMcpDocumentsPath = settings.getRagMcpDocumentsPath();
        this.enableChromeMcp = settings.isEnableChromeMcp();
        this.chromeMcpUrl = settings.getChromeMcpUrl();
        this.enableFileSystemAccess = settings.isEnableFileSystemAccess();
        this.enablePythonScript = settings.isEnablePythonScript();
        this.enableCliTool = settings.isEnableCliTool();
        this.enableUnrestrictedCliTool = settings.isEnableUnrestrictedCliTool();
        this.cliWhitelist = settings.getCliWhitelist();
        this.cliToolPrompt = settings.getCliToolPrompt();
        this.enableSkills = settings.isEnableSkills();
        this.skillsDirectoryPath = settings.getSkillsDirectoryPath();
        setWorkplaceDirectoryPath(settings.getWorkplaceDirectoryPath());
        this.customMcpConfigJson = settings.getCustomMcpConfigJson();
        this.customMcpConfigs = CustomMcpConfigParser.parse(this.customMcpConfigJson);
        this.customSystemPrompt = settings.getCustomPassiveSystemPrompt();
        com.ai.analyzer.util.TokenUsageTracker.instance().setBudget(settings.getTokenBudgetTokens());
    }

    private PluginSettings loadSettingsFromFile(File settingsFile) {
        try {
            return PluginSettings.loadCompat(settingsFile);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 主要分析方法 ==========

    /**
     * 被动扫描分析HTTP请求/响应
     */
    public String analyzeRequest(HttpRequestResponse requestResponse, AtomicBoolean cancelFlag) throws Exception {
        return analyzeRequest(requestResponse, cancelFlag, null);
    }

    public String analyzeRequest(HttpRequestResponse requestResponse, AtomicBoolean cancelFlag,
            Consumer<String> onChunk) throws Exception {
        if (requestResponse == null) {
            return "## 风险等级: 无\n请求为空，无法分析。";
        }

        if (com.ai.analyzer.util.TokenUsageTracker.sharedOverBudget()) {
            logDebug("Token 预算已用尽，跳过本次分析");
            return "## 风险等级: 无\nToken 预算已用尽，本次分析已跳过。";
        }

        String url = "(unknown)";
        try {
            url = requestResponse.request().url();
            if (url != null && url.length() > 80) url = url.substring(0, 80) + "...";
        } catch (Exception ignored) {}

        logDebug("开始分析: " + url);

        // 格式化 + 清洗 HTTP 内容
        String httpContent = HttpFormatter.formatHttpRequestResponse(requestResponse);

        if (httpContent == null || httpContent.trim().isEmpty()) {
            logDebug("HTTP内容为空，跳过: " + url);
            return "## 风险等级: 无\n请求内容为空，无法分析。";
        }

        HttpFormatter.PromptPrepareResult prepared =
                HttpFormatter.prepareForPrompt(httpContent, "passive-http-content");
        if (prepared.cached) {
            logDebug("HTTP内容过长已缓存: " + prepared.originalLength + " 字符，提示词仅含预览与 fileId");
        }
        httpContent = prepared.promptText;

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

        return doAgentScopeAnalysis(userContent, cancelFlag, onChunk);
    }

    /**
     * 会话批次分析：将同一会话内的连续请求作为一次 LLM 调用，做序列级（流程/逻辑）分析。
     *
     * @param batchRequests 同一会话的连续请求（按时间先后，至少 2 个）
     */
    public String analyzeBatch(List<HttpRequestResponse> batchRequests, AtomicBoolean cancelFlag,
            Consumer<String> onChunk) throws Exception {
        if (batchRequests == null || batchRequests.size() < 2) {
            if (batchRequests != null && batchRequests.size() == 1) {
                return analyzeRequest(batchRequests.get(0), cancelFlag, onChunk);
            }
            return "## 风险等级: 无\n批次为空，无法分析。";
        }

        logDebug("开始会话批次分析: " + batchRequests.size() + " 个连续请求, 会话="
                + RequestFingerprint.sessionKey(batchRequests.get(0)));

        String userContent = buildBatchScanPrompt(batchRequests);
        return doAgentScopeAnalysis(userContent, cancelFlag, onChunk);
    }

    /**
     * AgentScope 流式分析公共骨架：发送 userContent，收集流式响应。
     */
    private String doAgentScopeAnalysis(String userContent, AtomicBoolean cancelFlag,
            Consumer<String> onChunk) throws Exception {
        // ========== AgentScope 路径 ==========
        ensureAgentScopeInitialized();
        if (agentScopeRuntime == null) {
            throw new Exception("AgentScope 运行时初始化失败，请检查API配置");
        }
        logDebug("使用 AgentScope 发送流式请求");

        StringBuilder resultBuilder = new StringBuilder();
        CompletableFuture<String> futureResult = new CompletableFuture<>();

        com.ai.analyzer.agent.runtime.StreamSession session = agentScopeRuntime.chat(
                userContent,
                "pscan-session-" + System.currentTimeMillis(),
                new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                        if (cancelFlag != null && cancelFlag.get()) return;
                        switch (event.type()) {
                            case TEXT_DELTA -> {
                                String text = event.text();
                                if (text != null && !text.isEmpty()) {
                                    resultBuilder.append(text);
                                    if (onChunk != null) {
                                        onChunk.accept(text);
                                    }
                                }
                            }
                            case TOOL_START -> {
                                String toolName = event.toolName();
                                if (toolName != null && !toolName.isEmpty()) {
                                    AppLogBuffer.tool("PassiveScanApiClient", toolName);
                                    if (onChunk != null) {
                                        onChunk.accept("\n[TOOL_BLOCK]" + escapeHtml(toolName) + "[/TOOL_BLOCK]\n");
                                    }
                                }
                            }
                            case TOOL_END -> {
                                String toolName = event.toolName();
                                boolean toolFailed = event.toolFailed();
                                AppLogBuffer.tool("PassiveScanApiClient", "executed: " + (toolName != null ? toolName : "unknown")
                                        + (toolFailed ? " (failed)" : " (ok)"));
                                if (toolFailed && onChunk != null) {
                                    onChunk.accept("\n⚠️ 工具执行失败: " + escapeHtml(toolName != null ? toolName : "unknown") + "\n");
                                }
                            }
                            case REPLY_END -> {
                                logDebug("AgentScope 流式输出完成");
                                futureResult.complete(resultBuilder.toString());
                            }
                            case USAGE -> {
                                com.ai.analyzer.util.TokenUsageTracker.instance().record(
                                        event.inputTokens(), event.outputTokens(),
                                        event.cachedTokens(), event.totalTokens());
                            }
                            case ERROR -> {
                                Throwable err = event.error();
                                futureResult.completeExceptionally(err != null ? err : new Exception("AgentScope unknown error"));
                            }
                            case THINKING -> {
                                // 被动扫描场景下 THINKING 无 UI 展示，仅用于日志
                            }
                            default -> {
                                // SUBAGENT, MEMORY — 静默处理
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (!futureResult.isDone()) {
                            futureResult.complete(resultBuilder.toString());
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        futureResult.completeExceptionally(error);
                    }
                });

        // 等待结果
        String result = futureResult.get(10, TimeUnit.MINUTES);
        logDebug("AgentScope 分析完成，响应长度: " + (result != null ? result.length() : 0) + " 字符");
        return result != null ? result : "## 风险等级: 无\nAgentScope 分析未返回结果。";
    }

    public void clearContext() {
        invalidateAgentScopeRuntime();
        logInfo("共享聊天上下文已清空");
    }

    /**
     * 构建被动扫描的系统提示词，委托给 {@link SystemPromptBuilder}
     */
    private String buildSystemPrompt() {
        int hash = java.util.Objects.hash(
                enableSearch, enableMcp, enableRagMcp, enableChromeMcp,
                enableFileSystemAccess, enableSkills, ragMcpDocumentsPath,
                customSystemPrompt);
        String cached = cachedSystemPrompt;
        if (cached != null && hash == cachedPromptConfigHash) return cached;

        cached = new SystemPromptBuilder()
                .enableSearch(enableSearch)
                .enableSkills(enableSkills)
                .customBasePrompt(customSystemPrompt)
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
        prompt.append(httpContent);
        return prompt.toString();
    }

    /**
     * 构建会话批次分析提示词：强调流程/逻辑漏洞的序列级分析。
     * 批次内容过长时优先保留最新的请求（逻辑异常通常出现在序列尾部）。
     */
    private String buildBatchScanPrompt(List<HttpRequestResponse> batchRequests) {
        final int maxTotalLength = 30000;

        StringBuilder body = new StringBuilder();
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < batchRequests.size(); i++) {
            HttpRequestResponse rr = batchRequests.get(i);
            String label = "#" + (i + 1) + " ";
            try {
                String url = rr.request().url();
                if (url != null) label += url;
            } catch (Exception ignored) {
            }

            String content = HttpFormatter.formatHttpRequestResponse(rr);
            if (content == null || content.isEmpty()) continue;
            HttpFormatter.PromptPrepareResult prepared =
                    HttpFormatter.prepareForPrompt(content, "passive-batch-content");
            parts.add("--- 请求 " + label + " ---\n" + prepared.promptText);
        }
        if (parts.isEmpty()) return null;

        // 从尾部向前保留，直到不超过总长度上限
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (body.length() + parts.get(i).length() + 2 <= maxTotalLength) {
                body.insert(0, parts.get(i) + "\n\n");
            } else if (body.isEmpty()) {
                body.insert(0, parts.get(i));
                break;
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下**连续用户操作流量序列**（同一会话内按时间先后排列）中的安全风险。\n\n");
        prompt.append("**核心关注（逻辑漏洞，本序列分析的重点）**：\n");
        prompt.append("- 业务流程：识别完整操作流程，发现流程跳跃、状态机绕过、验证码/确认步骤被跳过\n");
        prompt.append("- 访问控制：同一操作在不同参数/不同身份下的响应差异（越权/IDOR）\n");
        prompt.append("- 状态依赖：后续请求是否依赖前置请求的状态（如未登录直接访问受保护操作、未创建先修改）\n");
        prompt.append("- 参数变化：序列中参数值跳变（订单号、用户ID、金额枚举）\n");
        prompt.append("- 常规漏洞同样分析（注入、XSS、CSRF 等）\n\n");
        prompt.append("**输出要求**：\n");
        prompt.append("- 每个请求单独标注是否存在风险（对应请求编号 #N）\n");
        prompt.append("- 逻辑漏洞请说明完整的触发流程（序列中哪些请求的组合导致漏洞）\n");
        prompt.append("- 如果整个序列无中等以上风险，只输出 \"风险等级: 无\"\n\n");
        prompt.append(body);
        return prompt.toString();
    }

    // ========== 日志方法 ==========

    private void logInfo(String message) {
        AppLogBuffer.info("PassiveScanApiClient", message);
        if (api != null) {
            api.logging().logToOutput("[PassiveScanApiClient] " + message);
        }
    }

    private void logError(String message) {
        AppLogBuffer.error("PassiveScanApiClient", message);
        if (api != null) {
            api.logging().logToError("[PassiveScanApiClient] " + message);
        }
    }

    private void logDebug(String message) {
        AppLogBuffer.debug("PassiveScanApiClient", message);
        if (api != null) {
            api.logging().logToOutput("[PassiveScan-DEBUG] " + message);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return path;
        if (!path.contains(" ")) return path;
        try {
            new java.io.File(path).mkdirs();
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