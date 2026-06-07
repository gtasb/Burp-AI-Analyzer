package com.ai.analyzer.Client;

import java.io.File;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 配置数据类
 * 集中管理 AgentApiClient 的所有配置项
 */
@Getter
@Setter
public class AgentConfig {
    
    // ========== API 基础配置 ==========
    private String apiKey = "";
    private String apiUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String model = "qwen-max";
    private ApiProvider apiProvider = ApiProvider.DASHSCOPE;
    
    // ========== 模型功能配置 ==========
    private boolean enableThinking = false;
    private boolean enableSearch = false;
    private String customParameters = "";
    
    // ========== MCP 配置 ==========
    private boolean enableMcp = false;
    private String burpMcpUrl = "http://127.0.0.1:9876/";
    
    private boolean enableRagMcp = false;
    private String ragMcpUrl = "";
    private String ragMcpDocumentsPath = "";
    
    private boolean enableChromeMcp = false;
    private String chromeMcpUrl = "";
    
    // ========== 联网搜索配置 ==========
    private String searchMode = "enableSearch"; // "enableSearch" | "tavily" | "google" | "duckduckgo" | "off"
    private String tavilyApiKey = "";
    private String tavilyBaseUrl = "";
    private String googleSearchApiKey = "";
    private String googleSearchCsi = "";
    
    // ========== 扩展功能配置 ==========
    private boolean enableFileSystemAccess = false;
    private boolean enableSkills = false;
    private boolean enablePythonScript = false;
    private boolean enableNotebook = false;
    private boolean enableCliTool = false;
    private String cliWhitelist = "";
    private String cliToolPrompt = "";
    private String workplaceDirectoryPath = "";
    private String customSystemPrompt = "";
    
    /**
     * API 提供者类型枚举
     */
    public enum ApiProvider {
        DASHSCOPE("DashScope"),
        OPENAI_COMPATIBLE("OpenAI兼容"),
        ANTHROPIC("Anthropic兼容");
        
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
            return DASHSCOPE; // 默认
        }
    }
    
    /**
     * 默认构造函数
     */
    public AgentConfig() {
    }
    
    /**
     * 复制构造函数
     */
    public AgentConfig(AgentConfig other) {
        this.apiKey = other.apiKey;
        this.apiUrl = other.apiUrl;
        this.model = other.model;
        this.apiProvider = other.apiProvider;
        this.enableThinking = other.enableThinking;
        this.enableSearch = other.enableSearch;
        this.customParameters = other.customParameters;
        this.enableMcp = other.enableMcp;
        this.burpMcpUrl = other.burpMcpUrl;
        this.enableRagMcp = other.enableRagMcp;
        this.ragMcpUrl = other.ragMcpUrl;
        this.ragMcpDocumentsPath = other.ragMcpDocumentsPath;
        this.enableChromeMcp = other.enableChromeMcp;
        this.chromeMcpUrl = other.chromeMcpUrl;
        this.searchMode = other.searchMode;
        this.tavilyApiKey = other.tavilyApiKey;
        this.tavilyBaseUrl = other.tavilyBaseUrl;
        this.googleSearchApiKey = other.googleSearchApiKey;
        this.googleSearchCsi = other.googleSearchCsi;
        this.enableFileSystemAccess = other.enableFileSystemAccess;
        this.enableSkills = other.enableSkills;
        this.enablePythonScript = other.enablePythonScript;
        this.enableNotebook = other.enableNotebook;
        this.enableCliTool = other.enableCliTool;
        this.cliWhitelist = other.cliWhitelist;
        this.cliToolPrompt = other.cliToolPrompt;
        this.workplaceDirectoryPath = other.workplaceDirectoryPath;
        this.customSystemPrompt = other.customSystemPrompt;
    }
    
    /**
     * 检查配置是否有效（至少需要 API Key）
     */
    public boolean isValid() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
    
    /**
     * 检查是否启用了任何 MCP
     */
    public boolean hasAnyMcpEnabled() {
        return enableMcp || enableRagMcp || enableChromeMcp;
    }
    
    /**
     * 获取有效的 Burp MCP URL
     */
    public String getEffectiveBurpMcpUrl() {
        return (burpMcpUrl != null && !burpMcpUrl.trim().isEmpty()) 
            ? burpMcpUrl.trim() 
            : "http://127.0.0.1:9876/";
    }
    
    /**
     * 检查 RAG 文档路径是否配置
     */
    public boolean hasRagDocumentsPath() {
        String path = getEffectiveRagDocumentsPath();
        return path != null && !path.trim().isEmpty();
    }

    public String getEffectiveRagDocumentsPath() {
        if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
            return new File(workplaceDirectoryPath.trim(), "rag").getAbsolutePath();
        }
        return ragMcpDocumentsPath != null ? ragMcpDocumentsPath.trim() : "";
    }

    public String getEffectivePythonWorkingDirectoryPath() {
        if (workplaceDirectoryPath != null && !workplaceDirectoryPath.trim().isEmpty()) {
            return new File(workplaceDirectoryPath.trim(), "python-workdir").getAbsolutePath();
        }
        return "";
    }
    
    /**
     * 检查 Chrome MCP URL 是否配置
     */
    public boolean hasChromeMcpUrl() {
        return chromeMcpUrl != null && !chromeMcpUrl.trim().isEmpty();
    }
    
    /**
     * 是否通过模型参数启用搜索（仅DashScope有效）
     */
    public boolean isModelSearchEnabled() {
        return enableSearch && "enableSearch".equals(searchMode);
    }

    /**
     * 是否通过Tavily工具启用搜索
     */
    public boolean isTavilySearchEnabled() {
        return enableSearch && "tavily".equals(searchMode)
                && tavilyApiKey != null && !tavilyApiKey.trim().isEmpty();
    }

    public boolean isGoogleSearchEnabled() {
        return enableSearch && "google".equals(searchMode)
                && googleSearchApiKey != null && !googleSearchApiKey.trim().isEmpty()
                && googleSearchCsi != null && !googleSearchCsi.trim().isEmpty();
    }

    public boolean isDuckDuckGoSearchEnabled() {
        return enableSearch && "duckduckgo".equals(searchMode);
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey != null ? apiKey : ""; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl != null ? apiUrl : ""; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model != null ? model : ""; }
    public ApiProvider getApiProvider() { return apiProvider; }
    public void setApiProvider(ApiProvider apiProvider) { this.apiProvider = apiProvider != null ? apiProvider : ApiProvider.DASHSCOPE; }
    public boolean isEnableThinking() { return enableThinking; }
    public void setEnableThinking(boolean enableThinking) { this.enableThinking = enableThinking; }
    public boolean isEnableSearch() { return enableSearch; }
    public void setEnableSearch(boolean enableSearch) { this.enableSearch = enableSearch; }
    public String getCustomParameters() { return customParameters; }
    public void setCustomParameters(String customParameters) { this.customParameters = customParameters != null ? customParameters : ""; }
    public boolean isEnableMcp() { return enableMcp; }
    public void setEnableMcp(boolean enableMcp) { this.enableMcp = enableMcp; }
    public String getBurpMcpUrl() { return burpMcpUrl; }
    public void setBurpMcpUrl(String burpMcpUrl) { this.burpMcpUrl = burpMcpUrl != null ? burpMcpUrl : ""; }
    public boolean isEnableRagMcp() { return enableRagMcp; }
    public void setEnableRagMcp(boolean enableRagMcp) { this.enableRagMcp = enableRagMcp; }
    public String getRagMcpUrl() { return ragMcpUrl; }
    public void setRagMcpUrl(String ragMcpUrl) { this.ragMcpUrl = ragMcpUrl != null ? ragMcpUrl : ""; }
    public String getRagMcpDocumentsPath() { return ragMcpDocumentsPath; }
    public void setRagMcpDocumentsPath(String ragMcpDocumentsPath) { this.ragMcpDocumentsPath = ragMcpDocumentsPath != null ? ragMcpDocumentsPath : ""; }
    public boolean isEnableChromeMcp() { return enableChromeMcp; }
    public void setEnableChromeMcp(boolean enableChromeMcp) { this.enableChromeMcp = enableChromeMcp; }
    public String getChromeMcpUrl() { return chromeMcpUrl; }
    public void setChromeMcpUrl(String chromeMcpUrl) { this.chromeMcpUrl = chromeMcpUrl != null ? chromeMcpUrl : ""; }
    public String getSearchMode() { return searchMode; }
    public void setSearchMode(String searchMode) { this.searchMode = searchMode != null ? searchMode : "enableSearch"; }
    public String getTavilyApiKey() { return tavilyApiKey; }
    public void setTavilyApiKey(String tavilyApiKey) { this.tavilyApiKey = tavilyApiKey != null ? tavilyApiKey : ""; }
    public String getTavilyBaseUrl() { return tavilyBaseUrl; }
    public void setTavilyBaseUrl(String tavilyBaseUrl) { this.tavilyBaseUrl = tavilyBaseUrl != null ? tavilyBaseUrl : ""; }
    public String getGoogleSearchApiKey() { return googleSearchApiKey; }
    public void setGoogleSearchApiKey(String googleSearchApiKey) { this.googleSearchApiKey = googleSearchApiKey != null ? googleSearchApiKey : ""; }
    public String getGoogleSearchCsi() { return googleSearchCsi; }
    public void setGoogleSearchCsi(String googleSearchCsi) { this.googleSearchCsi = googleSearchCsi != null ? googleSearchCsi : ""; }
    public boolean isEnableFileSystemAccess() { return enableFileSystemAccess; }
    public void setEnableFileSystemAccess(boolean enableFileSystemAccess) { this.enableFileSystemAccess = enableFileSystemAccess; }
    public boolean isEnableSkills() { return enableSkills; }
    public void setEnableSkills(boolean enableSkills) { this.enableSkills = enableSkills; }
    public boolean isEnablePythonScript() { return enablePythonScript; }
    public void setEnablePythonScript(boolean enablePythonScript) { this.enablePythonScript = enablePythonScript; }
    public boolean isEnableNotebook() { return enableNotebook; }
    public void setEnableNotebook(boolean enableNotebook) { this.enableNotebook = enableNotebook; }
    public boolean isEnableCliTool() { return enableCliTool; }
    public void setEnableCliTool(boolean enableCliTool) { this.enableCliTool = enableCliTool; }
    public String getCliWhitelist() { return cliWhitelist; }
    public void setCliWhitelist(String cliWhitelist) { this.cliWhitelist = cliWhitelist != null ? cliWhitelist : ""; }
    public String getCliToolPrompt() { return cliToolPrompt; }
    public void setCliToolPrompt(String cliToolPrompt) { this.cliToolPrompt = cliToolPrompt != null ? cliToolPrompt : ""; }
    public String getWorkplaceDirectoryPath() { return workplaceDirectoryPath; }
    public void setWorkplaceDirectoryPath(String workplaceDirectoryPath) { this.workplaceDirectoryPath = workplaceDirectoryPath != null ? workplaceDirectoryPath : ""; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public void setCustomSystemPrompt(String customSystemPrompt) { this.customSystemPrompt = customSystemPrompt != null ? customSystemPrompt : ""; }

    @Override
    public String toString() {
        return "AgentConfig{" +
                "apiProvider=" + apiProvider +
                ", model='" + model + '\'' +
                ", enableThinking=" + enableThinking +
                ", enableSearch=" + enableSearch +
                ", enableMcp=" + enableMcp +
                ", enableRagMcp=" + enableRagMcp +
                ", enableChromeMcp=" + enableChromeMcp +
                ", enableFileSystemAccess=" + enableFileSystemAccess +
                ", enableSkills=" + enableSkills +
                ", enablePythonScript=" + enablePythonScript +
                ", enableNotebook=" + enableNotebook +
                '}';
    }
}
