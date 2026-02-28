package com.ai.analyzer.api;

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
    private String burpMcpUrl = "http://127.0.0.1:9876";
    
    private boolean enableRagMcp = false;
    private String ragMcpUrl = "";
    private String ragMcpDocumentsPath = "";
    
    private boolean enableChromeMcp = false;
    private String chromeMcpUrl = "";
    
    // ========== 扩展功能配置 ==========
    private boolean enableFileSystemAccess = false;
    private boolean enableSkills = false;
    private boolean enablePythonScript = false;
    
    /**
     * API 提供者类型枚举
     */
    public enum ApiProvider {
        DASHSCOPE("DashScope"),
        OPENAI_COMPATIBLE("OpenAI兼容");
        
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
        this.enableFileSystemAccess = other.enableFileSystemAccess;
        this.enableSkills = other.enableSkills;
        this.enablePythonScript = other.enablePythonScript;
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
            : "http://127.0.0.1:9876/sse";
    }
    
    /**
     * 检查 RAG 文档路径是否配置
     */
    public boolean hasRagDocumentsPath() {
        return ragMcpDocumentsPath != null && !ragMcpDocumentsPath.trim().isEmpty();
    }
    
    /**
     * 检查 Chrome MCP URL 是否配置
     */
    public boolean hasChromeMcpUrl() {
        return chromeMcpUrl != null && !chromeMcpUrl.trim().isEmpty();
    }
    
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
                '}';
    }
}
