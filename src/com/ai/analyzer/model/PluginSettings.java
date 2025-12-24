package com.ai.analyzer.model;

import java.io.Serializable;
public class PluginSettings implements Serializable {
    
    private static final long serialVersionUID = 1L;
    private String apiUrl;
    private String apiKey;
    private String model;
    
    private String userPrompt;
    private boolean enableThinking = true; // 默认启用思考过程
    private boolean enableSearch = true; // 默认启用搜索
    private boolean enableMcp = false; // 默认禁用 Burp MCP 工具调用
    private String BurpMcpUrl = "http://127.0.0.1:9876/sse"; // Burp MCP 服务器地址
    private boolean enableRagMcp = false; // 默认禁用 RAG MCP 工具调用
    private String ragMcpUrl = " "; // RAG MCP 服务器地址
    private String ragMcpDocumentsPath = ""; // RAG MCP 知识库文档路径
    private boolean enableChromeMcp = false; // 默认禁用 Chrome MCP 工具调用
    private String chromeMcpUrl = " "; // Chrome MCP 服务器地址
    private boolean enableRag = false; // 默认禁用 RAG
    private String ragDocumentsPath = ""; // RAG 文档路径
    
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
}
