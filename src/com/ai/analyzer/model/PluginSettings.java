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
    
    public PluginSettings() {
        // 默认设置
        this.apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        this.apiKey = "";
        this.model = "qwen3-max";
        this.userPrompt = "请分析这个请求中可能存在的安全漏洞，并给出渗透测试建议";
        this.enableThinking = true;
        this.enableSearch = true;
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
}
