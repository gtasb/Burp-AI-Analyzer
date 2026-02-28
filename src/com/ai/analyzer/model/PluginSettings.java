package com.ai.analyzer.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PluginSettings implements Serializable {
    
    private static final long serialVersionUID = 6L;
    private String apiUrl;
    private String apiKey;
    private String model;
    private String apiProvider = "DashScope"; // API 提供者：DashScope 或 OpenAI兼容
    private String customParameters = ""; // 用户自定义参数（JSON 格式）
    
    private String userPrompt;
    private boolean enableThinking = true; // 默认启用思考过程
    private boolean enableSearch = true; // 默认启用搜索
    private boolean enableMcp = false; // 默认禁用 Burp MCP 工具调用
    private String BurpMcpUrl = "http://127.0.0.1:9876/sse"; // Burp MCP 服务器地址
    private boolean enableRagMcp = false; // 默认禁用 RAG MCP 工具调用
    private String ragMcpUrl = " "; // RAG MCP 服务器地址
    private String ragMcpDocumentsPath = ""; // RAG MCP 知识库文档路径
    private boolean enableFileSystemAccess = false; // 默认禁用直接查找知识库
    private boolean enableChromeMcp = false; // 默认禁用 Chrome MCP 工具调用
    private String chromeMcpUrl = " "; // Chrome MCP 服务器地址
    private boolean enableRag = false; // 默认禁用 RAG
    private String ragDocumentsPath = ""; // RAG 文档路径
    
    // 前置扫描器配置
    private boolean enablePreScanFilter = false; // 默认禁用前置扫描器（快速规则匹配）
    
    // Python 脚本执行配置
    private boolean enablePythonScript = false;
    
    // Skills 配置
    private boolean enableSkills = false; // 默认禁用 Skills
    private String skillsDirectoryPath = ""; // Skills 目录路径
    private List<String> enabledSkillNames = new ArrayList<>(); // 已启用的 skill 名称列表
    
    public PluginSettings() {
        // 默认设置
        this.apiUrl = "https://dashscope.aliyuncs.com/api/v1";
        this.apiKey = "";
        this.model = "qwen3-max";
        this.userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
        this.enableThinking = true;
        this.enableSearch = true;
        this.enableMcp = false;
        this.BurpMcpUrl = "http://127.0.0.1:9876/sse";
        this.enableRagMcp = false;
        this.ragMcpUrl = " ";
        this.ragMcpDocumentsPath = "";
        this.enableChromeMcp = false;
        this.chromeMcpUrl = " ";
        this.enableRag = false;
        this.ragDocumentsPath = "";
    }
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = true;
        this.enableSearch = true;
    }
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableThinking, boolean enableSearch) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = enableThinking;
        this.enableSearch = enableSearch;
        this.enableMcp = false;
        this.BurpMcpUrl = "http://127.0.0.1:9876/sse";
        this.enableRag = false;
        this.ragDocumentsPath = "";
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableThinking, boolean enableSearch, boolean enableMcp, String mcpUrl) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = enableThinking;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRag = false;
        this.ragDocumentsPath = "";
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableThinking, boolean enableSearch, boolean enableMcp, String mcpUrl, boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = enableThinking;
        this.enableSearch = enableSearch;
        this.enableMcp = enableMcp;
        this.BurpMcpUrl = mcpUrl;
        this.enableRagMcp = false;
        this.ragMcpUrl = " ";
        this.enableChromeMcp = false;
        this.chromeMcpUrl = " ";
        this.enableRag = enableRag;
        this.ragDocumentsPath = ragDocumentsPath;
    }
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableThinking, boolean enableSearch, 
            boolean enableMcp, String mcpUrl, boolean enableRagMcp, String ragMcpUrl, boolean enableChromeMcp, String chromeMcpUrl, 
            boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = enableThinking;
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
    
    public PluginSettings(String apiUrl, String apiKey, String model, String userPrompt, boolean enableThinking, boolean enableSearch, 
            boolean enableMcp, String mcpUrl, boolean enableRagMcp, String ragMcpUrl, String ragMcpDocumentsPath, 
            boolean enableChromeMcp, String chromeMcpUrl, boolean enableRag, String ragDocumentsPath) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.userPrompt = userPrompt;
        this.enableThinking = enableThinking;
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
    
    public String getUserPrompt() {
        return userPrompt;
    }
    
    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
    }
    
    public boolean isEnableThinking() {
        return enableThinking;
    }
    
    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
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
        return ragMcpDocumentsPath;
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
        return ragDocumentsPath;
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
        return skillsDirectoryPath;
    }
    
    public void setSkillsDirectoryPath(String skillsDirectoryPath) {
        this.skillsDirectoryPath = skillsDirectoryPath;
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
    
    // 前置扫描器配置
    public boolean isEnablePreScanFilter() {
        return enablePreScanFilter;
    }
    
    public void setEnablePreScanFilter(boolean enablePreScanFilter) {
        this.enablePreScanFilter = enablePreScanFilter;
    }
}
