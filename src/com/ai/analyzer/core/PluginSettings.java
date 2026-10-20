package com.ai.analyzer.core;

import com.ai.analyzer.agent.mcpclient.CustomMcpConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PluginSettings implements Serializable {

    // IMPORTANT: 旧版配置使用 Java 序列化落盘，保留该 UID 仅用于读取历史文件；
    // 新配置一律以 JSON 保存（见 saveTo/loadCompat），新增字段不再影响旧配置加载。
    private static final long serialVersionUID = 9L;

    /** JSON 配置格式（自描述：新增/删除字段都能安全读取）。 */
    private static final Gson SETTINGS_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    /** Java 序列化流魔数 0xACED。 */
    private static final int JAVA_STREAM_MAGIC = 0xACED;
    private String apiUrl;
    private String apiKey;
    private String model;
    private String apiProvider = "DashScope"; // API 提供者：DashScope 或 OpenAI兼容
    private String customParameters = ""; // 用户自定义参数（JSON 格式）
    private String maxTokens = ""; // 用户显式指定上下文预算
    private long tokenBudgetTokens = 0; // 扫描周期 Token 预算（input+cached 口径），0=不限制
    private List<ApiProfile> apiProfiles = new ArrayList<>(); // 常用 API 配置档案
    
    private String userPrompt;
    private boolean enableSearch = true; // 默认启用搜索
    private boolean enableMcp = false; // 默认禁用 Burp MCP 工具调用
    private String BurpMcpUrl = "http://127.0.0.1:9876/"; // Burp MCP 服务器地址
    private String burpMcpAuthorization = "";
    private boolean enableRagMcp = false; // 默认禁用 RAG MCP 工具调用
    private String ragMcpUrl = ""; // RAG MCP 服务器地址
    private String ragMcpDocumentsPath = ""; // RAG MCP 知识库文档路径
    private boolean enableFileSystemAccess = false; // 默认禁用直接查找知识库
    private boolean enableChromeMcp = false; // 默认禁用 Chrome MCP 工具调用
    private String chromeMcpUrl = ""; // Chrome MCP 服务器地址
    private boolean enableRag = false; // 默认禁用 RAG
    private String ragDocumentsPath = ""; // RAG 文档路径
    private String workplaceDirectoryPath = ""; // 统一工作目录（skills/rag/python 均基于此目录）
    
    // 前置扫描器配置
    private boolean enablePreScanFilter = false; // 默认禁用前置扫描器（快速规则匹配）
    
    // Python 脚本执行配置
    private boolean enablePythonScript = false;

    // Skills 配置
    private boolean enableSkills = false; // 默认禁用 Skills
    private String skillsDirectoryPath = ""; // Skills 目录路径
    private List<String> enabledSkillNames = new ArrayList<>(); // 已启用的 skill 名称列表

    // CLI 工具配置
    private boolean enableCliTool = false;
    /** 文件沙箱：true=限制在工作区/project 根内；false=文件工具可读写任意路径（默认关闭） */
    private boolean enableFileSystemSandbox = false;
    /**
     * 主动分析 Agent 的 ReAct 循环单轮上限。
     * AgentScope 默认仅 10，用满后会向模型注入「停下来总结」的指令导致半途收尾。
     * 必须 &gt; 0（传 0 会被 ReActAgent 拒绝）。
     */
    private int activeMaxIters = com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime.DEFAULT_MAX_ITERS;
    /** 被动扫描 Agent 的 ReAct 循环单轮上限，必须 &gt; 0 */
    private int passiveMaxIters = 15;
    private String cliWhitelist = "";
    private String cliToolPrompt = "";
    
    // 自定义系统提示词（初始值留空，getter 中兜底返回默认提示词）
    private String customActiveSystemPrompt = null;
    private String customPassiveSystemPrompt = null;

    // Plan Mode（AgentScope 计划模式）：Agent 先写计划并获得批准后再执行
    private boolean enablePlanMode = false;
    
    // 联网搜索配置: "enableSearch" = 模型内置参数, "tavily" = Tavily搜索引擎, "off" = 关闭
    private String searchMode = "enableSearch";
    private String tavilyApiKey = "";
    private String tavilyBaseUrl = "";
    private String googleSearchApiKey = "";
    private String googleSearchCsi = "";
    
    // 被动扫描过滤配置
    private String passiveScanSkipExtensions = "";
    private String passiveScanDomainBlacklist = "";
    /** 域名白名单：非空时只扫描匹配的主机（优先于黑名单） */
    private String passiveScanDomainWhitelist = "";

    // 自定义 MCP 服务器配置（JSON 字符串，简版数组格式）
    private String customMcpConfigJson = "";
    private boolean enableCustomMcp = false;

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        normalize();
    }

    /**
     * 补齐缺省值并修正非法值。Java 反序列化不会执行构造器，新增字段会得到 0/null；
     * JSON 读取虽然会走无参构造器，但为了防御手工编辑的配置文件，同样统一归一化。
     */
    private void normalize() {
        if (apiUrl == null) apiUrl = "https://dashscope.aliyuncs.com/api/v1";
        if (apiKey == null) apiKey = "";
        if (model == null) model = "qwen3-max";
        if (apiProvider == null) apiProvider = "DashScope";
        if (customParameters == null) customParameters = "";
        if (maxTokens == null) maxTokens = "";
        if (apiProfiles == null) apiProfiles = new ArrayList<>();
        if (userPrompt == null) userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";


        if (BurpMcpUrl == null) BurpMcpUrl = "http://127.0.0.1:9876/";
        if (burpMcpAuthorization == null) burpMcpAuthorization = "";
        if (ragMcpUrl == null) ragMcpUrl = "";
        if (ragMcpDocumentsPath == null) ragMcpDocumentsPath = "";
        if (chromeMcpUrl == null) chromeMcpUrl = "";
        if (ragDocumentsPath == null) ragDocumentsPath = "";
        if (workplaceDirectoryPath == null) workplaceDirectoryPath = "";

        if (skillsDirectoryPath == null) skillsDirectoryPath = "";
        if (enabledSkillNames == null) enabledSkillNames = new ArrayList<>();
        if (cliWhitelist == null) cliWhitelist = "";
        if (cliToolPrompt == null) cliToolPrompt = "";
        // 旧配置文件反序列化后为 false，等价于「关闭沙箱」，与历史行为一致
        // 旧配置没有 ReAct 单轮上限字段（反序列化为 0），必须回落到合法默认值
        if (activeMaxIters <= 0) activeMaxIters = com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime.DEFAULT_MAX_ITERS;
        if (passiveMaxIters <= 0) passiveMaxIters = 15;
        if (tokenBudgetTokens < 0) tokenBudgetTokens = 0;

        if (searchMode == null) searchMode = "enableSearch";
        if (tavilyApiKey == null) tavilyApiKey = "";
        if (tavilyBaseUrl == null) tavilyBaseUrl = "";
        if (googleSearchApiKey == null) googleSearchApiKey = "";
        if (googleSearchCsi == null) googleSearchCsi = "";

        if (passiveScanSkipExtensions == null) passiveScanSkipExtensions = "";
        if (passiveScanDomainBlacklist == null) passiveScanDomainBlacklist = "";
        if (passiveScanDomainWhitelist == null) passiveScanDomainWhitelist = "";
        if (customMcpConfigJson == null) customMcpConfigJson = "";
        // 注意：不再根据 customMcpConfigJson 非空强制 enableCustomMcp=true。
        // 该兜底会导致用户显式关闭自定义 MCP 后重启回弹为启用（“关掉不保存” bug）。
        // 启用状态只认显式保存的 enableCustomMcp 标志。
    }

    /**
     * 以 JSON 保存配置。相比 Java 序列化，新增/删除字段都能向后兼容，
     * 不会再因为类结构变化导致旧配置文件整体失效。
     */
    public void saveTo(File settingsFile) throws IOException {
        Path target = settingsFile.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, SETTINGS_GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 兼容加载：JSON 配置直接解析；历史 Java 序列化文件先按原始描述符读取，
     * 仅在 serialVersionUID 不匹配时退回本地描述符（老版本迁移路径）。
     */
    public static PluginSettings loadCompat(File settingsFile) throws IOException, ClassNotFoundException {
        if (!hasJavaStreamMagic(settingsFile)) {
            String json = Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8);
            PluginSettings settings = SETTINGS_GSON.fromJson(json, PluginSettings.class);
            if (settings == null) {
                throw new IOException("配置文件为空: " + settingsFile);
            }
            settings.normalize();
            return settings;
        }
        try {
            return readLegacy(settingsFile, false);
        } catch (java.io.InvalidClassException e) {
            return readLegacy(settingsFile, true);
        }
    }

    private static boolean hasJavaStreamMagic(File file) throws IOException {
        try (java.io.InputStream in = new FileInputStream(file)) {
            int hi = in.read();
            int lo = in.read();
            return ((hi << 8) | lo) == JAVA_STREAM_MAGIC;
        }
    }

    private static PluginSettings readLegacy(File settingsFile, boolean forceLocalDescriptor)
            throws IOException, ClassNotFoundException {
        try (FileInputStream fis = new FileInputStream(settingsFile);
             ObjectInputStream ois = new ObjectInputStream(fis) {
                 @Override
                 protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                     ObjectStreamClass desc = super.readClassDescriptor();
                     if (forceLocalDescriptor && "com.ai.analyzer.core.PluginSettings".equals(desc.getName())) {
                         ObjectStreamClass local = ObjectStreamClass.lookup(PluginSettings.class);
                         if (local != null) return local;
                     }
                     return desc;
                 }
             }) {
            Object obj = ois.readObject();
            return (PluginSettings) obj;
        }
    }
    
    public PluginSettings() {
        // 默认设置
        this.apiUrl = "https://dashscope.aliyuncs.com/api/v1";
        this.apiKey = "";
        this.model = "qwen3-max";
        this.userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
        this.enableSearch = true;
        this.enableMcp = false;
        this.BurpMcpUrl = "http://127.0.0.1:9876/";
        this.burpMcpAuthorization = "";
        this.enableRagMcp = false;
        this.ragMcpUrl = "";
        this.ragMcpDocumentsPath = "";
        this.enableChromeMcp = false;
        this.chromeMcpUrl = "";
        this.enableRag = false;
        this.ragDocumentsPath = "";
        this.apiProfiles = new ArrayList<>();
    }

    public static class ApiProfile implements Serializable {
        private static final long serialVersionUID = 1L;
        private String name;
        private String apiProvider;
        private String apiUrl;
        private String apiKey;
        private String model;
        private String customParameters;

        public ApiProfile() {}

        public ApiProfile(String name, String apiProvider, String apiUrl, String apiKey, String model, String customParameters) {
            this.name = name;
            this.apiProvider = apiProvider;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.customParameters = customParameters;
        }

        public String getName() { return name != null ? name : ""; }
        public void setName(String name) { this.name = name; }
        public String getApiProvider() { return apiProvider != null ? apiProvider : "DashScope"; }
        public void setApiProvider(String apiProvider) { this.apiProvider = apiProvider; }
        public String getApiUrl() { return apiUrl != null ? apiUrl : ""; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey != null ? apiKey : ""; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model != null ? model : ""; }
        public void setModel(String model) { this.model = model; }
        public String getCustomParameters() { return customParameters != null ? customParameters : ""; }
        public void setCustomParameters(String customParameters) { this.customParameters = customParameters; }

        @Override
        public String toString() {
            return getName();
        }
    }
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = true;
    }
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableSearch) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = enableSearch;
        this.enableMcp = false;
        this.BurpMcpUrl = "http://127.0.0.1:9876/";
        this.enableRag = false;
        this.ragDocumentsPath = "";
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableSearch, boolean enableMcp, String mcpUrl) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRag = false;
        this.ragDocumentsPath = "";
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableSearch, boolean enableMcp, String mcpUrl, boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRagMcp = false;
        this.ragMcpUrl = "";
        this.enableChromeMcp = false;
        this.chromeMcpUrl = "";
        this.enableRag = enableRag;
        this.ragDocumentsPath = ragDocumentsPath;
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableSearch, 
            boolean enableMcp, String mcpUrl, boolean enableRagMcp, String ragMcpUrl, boolean enableChromeMcp, String chromeMcpUrl, 
            boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRagMcp = enableRagMcp;
        this.ragMcpUrl = ragMcpUrl;
        this.ragMcpDocumentsPath = "";
        this.enableChromeMcp = enableChromeMcp;
        this.chromeMcpUrl = chromeMcpUrl;
        this.enableRag = enableRag;
        this.ragDocumentsPath = ragDocumentsPath;
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableSearch, 
            boolean enableMcp, String mcpUrl, boolean enableRagMcp, String ragMcpUrl, String ragMcpDocumentsPath, 
            boolean enableChromeMcp, String chromeMcpUrl, boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRagMcp = enableRagMcp;
        this.ragMcpUrl = ragMcpUrl;
        this.ragMcpDocumentsPath = ragMcpDocumentsPath;
        this.enableChromeMcp = enableChromeMcp;
        this.chromeMcpUrl = chromeMcpUrl;
        this.enableRag = enableRag;
        this.ragDocumentsPath = ragDocumentsPath;
    }
    
    // Getters and Setters
    public String getApiUrl() {
        return apiUrl;
    }
    
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public String getModel() {
        return model;
    }
    
    public void setModel(String model) {
        this.model = model;
    }
    
    public String getApiProvider() {
        return apiProvider != null ? apiProvider : "DashScope";
    }
    
    public void setApiProvider(String apiProvider) {
        this.apiProvider = apiProvider != null ? apiProvider : "DashScope";
    }
    
    public String getCustomParameters() {
        return customParameters != null ? customParameters : "";
    }
    
    public void setCustomParameters(String customParameters) {
        this.customParameters = customParameters != null ? customParameters : "";
    }

    public String getMaxTokens() {
        return maxTokens != null ? maxTokens : "";
    }

    public void setMaxTokens(String maxTokens) {
        this.maxTokens = maxTokens != null ? maxTokens : "";
    }

    /** Token 预算（input+cached 口径），<=0 表示不限制 */
    public long getTokenBudgetTokens() {
        return tokenBudgetTokens;
    }

    public void setTokenBudgetTokens(long tokenBudgetTokens) {
        this.tokenBudgetTokens = Math.max(0, tokenBudgetTokens);
    }

    public List<ApiProfile> getApiProfiles() {
        return apiProfiles != null ? apiProfiles : new ArrayList<>();
    }

    public void setApiProfiles(List<ApiProfile> apiProfiles) {
        this.apiProfiles = apiProfiles != null ? new ArrayList<>(apiProfiles) : new ArrayList<>();
    }
    
    public String getUserPrompt() {
        return userPrompt;
    }
    
    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }
    
    public boolean isEnableSearch() {
        return enableSearch;
    }
    
    public void setEnableSearch(boolean enableSearch) {
        this.enableSearch = enableSearch;
    }
    
    public boolean isEnableMcp() {
        return enableMcp;
    }
    
    public void setEnableMcp(boolean enableMcp) {
        this.enableMcp = enableMcp;
    }
    
    public String getMcpUrl() {
        return BurpMcpUrl;
    }
    
    public void setMcpUrl(String mcpUrl) {
        this.BurpMcpUrl = mcpUrl;
    }

    public String getBurpMcpAuthorization() {
        return burpMcpAuthorization != null ? burpMcpAuthorization : "";
    }

    public void setBurpMcpAuthorization(String burpMcpAuthorization) {
        this.burpMcpAuthorization = burpMcpAuthorization != null ? burpMcpAuthorization : "";
    }
    
    public boolean isEnableRagMcp() {
        return enableRagMcp;
    }
    
    public void setEnableRagMcp(boolean enableRagMcp) {
        this.enableRagMcp = enableRagMcp;
    }
    
    public String getRagMcpUrl() {
        return ragMcpUrl;
    }
    
    public void setRagMcpUrl(String ragMcpUrl) {
        this.ragMcpUrl = ragMcpUrl;
    }
    
    public String getRagMcpDocumentsPath() {
        String effective = resolveRagDocumentsPath();
        return !effective.isEmpty() ? effective : ragMcpDocumentsPath;
    }
    
    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) {
        this.ragMcpDocumentsPath = ragMcpDocumentsPath;
    }
    
    public boolean isEnableChromeMcp() {
        return enableChromeMcp;
    }
    
    public void setEnableChromeMcp(boolean enableChromeMcp) {
        this.enableChromeMcp = enableChromeMcp;
    }
    
    public String getChromeMcpUrl() {
        return chromeMcpUrl;
    }
    
    public void setChromeMcpUrl(String chromeMcpUrl) {
        this.chromeMcpUrl = chromeMcpUrl;
    }
    
    public boolean isEnableRag() {
        return enableRag;
    }
    
    public void setEnableRag(boolean enableRag) {
        this.enableRag = enableRag;
    }
    
    public String getRagDocumentsPath() {
        String effective = resolveRagDocumentsPath();
        return !effective.isEmpty() ? effective : ragDocumentsPath;
    }
    
    public void setRagDocumentsPath(String ragDocumentsPath) {
        this.ragDocumentsPath = ragDocumentsPath;
    }
    
    public boolean isEnableFileSystemAccess() {
        return enableFileSystemAccess;
    }
    
    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) {
        this.enableFileSystemAccess = enableFileSystemAccess;
    }
    
    // Skills 配置
    public boolean isEnableSkills() {
        return enableSkills;
    }
    
    public void setEnableSkills(boolean enableSkills) {
        this.enableSkills = enableSkills;
    }
    
    public String getSkillsDirectoryPath() {
        String effective = resolveSkillsDirectoryPath();
        return !effective.isEmpty() ? effective : skillsDirectoryPath;
    }
    
    public void setSkillsDirectoryPath(String skillsDirectoryPath) {
        this.skillsDirectoryPath = skillsDirectoryPath;
    }

    public String getWorkplaceDirectoryPath() {
        return workplaceDirectoryPath != null ? workplaceDirectoryPath : "";
    }

    public void setWorkplaceDirectoryPath(String workplaceDirectoryPath) {
        this.workplaceDirectoryPath = workplaceDirectoryPath != null ? workplaceDirectoryPath.trim() : "";
    }

    // Plan Mode 配置
    public boolean isEnablePlanMode() {
        return enablePlanMode;
    }

    public void setEnablePlanMode(boolean enablePlanMode) {
        this.enablePlanMode = enablePlanMode;
    }

    public boolean hasWorkplaceDirectory() {
        return workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty();
    }

    public String resolveSkillsDirectoryPath() {
        return resolveUnderWorkplace("skills");
    }

    public String resolveRagDocumentsPath() {
        return resolveUnderWorkplace("rag");
    }

    public String resolvePythonWorkingDirectoryPath() {
        return resolveUnderWorkplace("python-workdir");
    }

    private String resolveUnderWorkplace(String subDirName) {
        if (!hasWorkplaceDirectory()) return "";
        return new File(workplaceDirectoryPath.trim(), subDirName).getAbsolutePath();
    }
    
    public List<String> getEnabledSkillNames() {
        return enabledSkillNames != null ? enabledSkillNames : new ArrayList<>();
    }
    
    public void setEnabledSkillNames(List<String> enabledSkillNames) {
        this.enabledSkillNames = enabledSkillNames != null ? enabledSkillNames : new ArrayList<>();
    }
    
    // Python 脚本执行配置
    public boolean isEnablePythonScript() {
        return enablePythonScript;
    }
    
    public void setEnablePythonScript(boolean enablePythonScript) {
        this.enablePythonScript = enablePythonScript;
    }

    // CLI 工具配置
    public boolean isEnableCliTool() {
        return enableCliTool;
    }

    public void setEnableCliTool(boolean enableCliTool) {
        this.enableCliTool = enableCliTool;
    }

    public boolean isEnableFileSystemSandbox() {
        return enableFileSystemSandbox;
    }

    public void setEnableFileSystemSandbox(boolean enableFileSystemSandbox) {
        this.enableFileSystemSandbox = enableFileSystemSandbox;
    }

    public int getActiveMaxIters() {
        // 旧配置反序列化后为 0；ReActAgent 要求 > 0，这里兜底到默认值
        return activeMaxIters > 0 ? activeMaxIters
                : com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime.DEFAULT_MAX_ITERS;
    }

    public void setActiveMaxIters(int activeMaxIters) {
        this.activeMaxIters = activeMaxIters;
    }

    public int getPassiveMaxIters() {
        return passiveMaxIters > 0 ? passiveMaxIters : 15;
    }

    public void setPassiveMaxIters(int passiveMaxIters) {
        this.passiveMaxIters = passiveMaxIters;
    }

    public String getCliWhitelist() {
        return cliWhitelist != null ? cliWhitelist : "";
    }

    public void setCliWhitelist(String v) {
        this.cliWhitelist = v != null ? v : "";
    }

    public String getCliToolPrompt() {
        return cliToolPrompt != null ? cliToolPrompt : "";
    }

    public void setCliToolPrompt(String v) {
        this.cliToolPrompt = v != null ? v : "";
    }
    
    // 前置扫描器配置
    public boolean isEnablePreScanFilter() {
        return enablePreScanFilter;
    }
    
    public void setEnablePreScanFilter(boolean enablePreScanFilter) {
        this.enablePreScanFilter = enablePreScanFilter;
    }
    
    public String getCustomActiveSystemPrompt() {
        if (customActiveSystemPrompt == null || customActiveSystemPrompt.isBlank()) {
            return com.ai.analyzer.core.SystemPromptBuilder.getDefaultBasePrompt();
        }
        return customActiveSystemPrompt;
    }
    
    public void setCustomActiveSystemPrompt(String v) {
        this.customActiveSystemPrompt = v;
    }
    
    public String getCustomPassiveSystemPrompt() {
        if (customPassiveSystemPrompt == null || customPassiveSystemPrompt.isBlank()) {
            return com.ai.analyzer.scan.pscan.SystemPromptBuilder.getDefaultBasePrompt();
        }
        return customPassiveSystemPrompt;
    }
    
    public void setCustomPassiveSystemPrompt(String v) {
        this.customPassiveSystemPrompt = v;
    }
    
    public String getSearchMode() {
        return searchMode != null ? searchMode : "enableSearch";
    }
    
    public void setSearchMode(String v) {
        this.searchMode = v != null ? v : "enableSearch";
    }
    
    public String getTavilyApiKey() {
        return tavilyApiKey != null ? tavilyApiKey : "";
    }
    
    public void setTavilyApiKey(String v) {
        this.tavilyApiKey = v != null ? v : "";
    }
    
    public String getTavilyBaseUrl() {
        return (tavilyBaseUrl != null && !tavilyBaseUrl.isBlank()) ? tavilyBaseUrl : "https://api.tavily.com/search";
    }
    
    public void setTavilyBaseUrl(String v) {
        this.tavilyBaseUrl = (v == null || v.isBlank()) ? "https://api.tavily.com/search" : v;
    }
    
    public String getGoogleSearchApiKey() {
        return googleSearchApiKey != null ? googleSearchApiKey : "";
    }
    
    public void setGoogleSearchApiKey(String v) {
        this.googleSearchApiKey = v != null ? v : "";
    }
    
    public String getGoogleSearchCsi() {
        return googleSearchCsi != null ? googleSearchCsi : "";
    }
    
    public void setGoogleSearchCsi(String v) {
        this.googleSearchCsi = v != null ? v : "";
    }
    
    public String getPassiveScanSkipExtensions() {
        return passiveScanSkipExtensions != null ? passiveScanSkipExtensions : "";
    }
    
    public void setPassiveScanSkipExtensions(String v) {
        this.passiveScanSkipExtensions = v != null ? v : "";
    }
    
    public String getPassiveScanDomainBlacklist() {
        return passiveScanDomainBlacklist != null ? passiveScanDomainBlacklist : "";
    }
    
    public void setPassiveScanDomainBlacklist(String v) {
        this.passiveScanDomainBlacklist = v != null ? v : "";
    }

    public String getPassiveScanDomainWhitelist() {
        return passiveScanDomainWhitelist != null ? passiveScanDomainWhitelist : "";
    }

    public void setPassiveScanDomainWhitelist(String v) {
        this.passiveScanDomainWhitelist = v != null ? v : "";
    }

    public String getCustomMcpConfigJson() {
        return customMcpConfigJson != null ? customMcpConfigJson : "";
    }

    public void setCustomMcpConfigJson(String v) {
        this.customMcpConfigJson = v != null ? v : "";
    }

    public boolean isEnableCustomMcp() {
        return enableCustomMcp;
    }

    public void setEnableCustomMcp(boolean enableCustomMcp) {
        this.enableCustomMcp = enableCustomMcp;
    }

    public List<CustomMcpConfig> getCustomMcpConfigs() {
        String json = getCustomMcpConfigJson();
        if (json.trim().isEmpty()) return Collections.emptyList();
        try {
            return com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser.parse(json);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
