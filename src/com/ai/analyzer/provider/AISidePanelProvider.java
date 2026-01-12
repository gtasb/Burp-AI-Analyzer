package com.ai.analyzer.provider;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.api.AgentApiClient;
import com.ai.analyzer.ui.ChatPanel;
import com.ai.analyzer.ui.AISidePanelRequestEditor;
import com.ai.analyzer.ui.AISidePanelResponseEditor;
import com.ai.analyzer.ui.AIAnalyzerTab;
import com.ai.analyzer.model.PluginSettings;

import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.core.ToolSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AISidePanelProvider implements HttpRequestEditorProvider, HttpResponseEditorProvider {
    private final MontoyaApi api;
    private AIAnalyzerTab analyzerTab; // 保存analyzerTab引用
    
    // Editor 实例缓存：使用 ToolSource 作为键，避免重复创建
    // 注意：Burp Suite 可能会为同一个工具创建多个 Editor 实例（例如不同的 Repeater tab）
    // 但我们可以通过缓存减少不必要的重建
    private final Map<String, ExtensionProvidedHttpRequestEditor> requestEditorCache = new ConcurrentHashMap<>();
    private final Map<String, ExtensionProvidedHttpResponseEditor> responseEditorCache = new ConcurrentHashMap<>();

    public AISidePanelProvider(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 设置analyzerTab引用，用于获取API配置
     */
    public void setAnalyzerTab(AIAnalyzerTab analyzerTab) {
        this.analyzerTab = analyzerTab;
    }
    
    /**
     * 获取共享的 API Client（优先从 analyzerTab 获取，避免重复初始化）
     */
    private AgentApiClient getApiClient() {
        // 优先使用 analyzerTab 中的共享 apiClient，避免重复初始化
        if (analyzerTab != null) {
            return analyzerTab.getApiClient();
        }
        
        // 如果 analyzerTab 不可用，才创建新实例（这种情况应该很少发生）
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String apiKey = "";
        String model = "qwen-max";
        
        // 尝试从保存的设置文件加载
        try {
            File settingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
            if (settingsFile.exists()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(settingsFile))) {
                    PluginSettings settings = (PluginSettings) ois.readObject();
                    if (settings.getApiUrl() != null && !settings.getApiUrl().isEmpty()) {
                        apiUrl = settings.getApiUrl();
                    }
                    if (settings.getApiKey() != null && !settings.getApiKey().isEmpty()) {
                        apiKey = settings.getApiKey();
                    }
                    if (settings.getModel() != null && !settings.getModel().isEmpty()) {
                        model = settings.getModel();
                    }
                }
            }
        } catch (Exception e) {
            // 忽略加载错误，使用默认值
        }
        
        AgentApiClient apiClient = new AgentApiClient(api, apiUrl, apiKey);
        apiClient.setModel(model);
        return apiClient;
    }

    /**
     * 生成缓存键：使用 ToolSource 和 EditorMode 组合
     * 这样可以区分不同工具和不同模式的 Editor
     */
    private String generateCacheKey(EditorCreationContext context) {
        if (context == null) {
            return "default";
        }
        ToolSource toolSource = context.toolSource();
        String toolName = "unknown";
        if (toolSource != null && toolSource.toolType() != null) {
            toolName = toolSource.toolType().toolName();
        }
        String editorMode = context.editorMode() != null ? context.editorMode().name() : "default";
        return toolName + ":" + editorMode;
    }
    
    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        String cacheKey = generateCacheKey(creationContext);
        
        // 检查缓存中是否已存在对应的 Editor
        ExtensionProvidedHttpRequestEditor cachedEditor = requestEditorCache.get(cacheKey);
        if (cachedEditor != null) {
            // 检查 Editor 是否仍然有效（没有被垃圾回收）
            // 注意：如果 Burp Suite 强制重建，旧的 Editor 可能已经失效
            // 但我们可以尝试复用，减少不必要的重建
            api.logging().logToOutput("[AISidePanelProvider] 复用缓存的 Request Editor: " + cacheKey);
            return cachedEditor;
        }
        
        // 创建新的 Editor 实例
        api.logging().logToOutput("[AISidePanelProvider] 创建新的 Request Editor: " + cacheKey);
        AgentApiClient sharedApiClient = getApiClient();
        ChatPanel newChatPanel = new ChatPanel(api, sharedApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelRequestEditor editor = new AISidePanelRequestEditor(api, newChatPanel);
        
        // 缓存新创建的 Editor
        requestEditorCache.put(cacheKey, editor);
        
        return editor;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        String cacheKey = generateCacheKey(creationContext);
        
        // 检查缓存中是否已存在对应的 Editor
        ExtensionProvidedHttpResponseEditor cachedEditor = responseEditorCache.get(cacheKey);
        if (cachedEditor != null) {
            api.logging().logToOutput("[AISidePanelProvider] 复用缓存的 Response Editor: " + cacheKey);
            return cachedEditor;
        }
        
        // 创建新的 Editor 实例
        api.logging().logToOutput("[AISidePanelProvider] 创建新的 Response Editor: " + cacheKey);
        AgentApiClient sharedApiClient = getApiClient();
        ChatPanel newChatPanel = new ChatPanel(api, sharedApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelResponseEditor editor = new AISidePanelResponseEditor(api, newChatPanel);
        
        // 缓存新创建的 Editor
        responseEditorCache.put(cacheKey, editor);
        
        return editor;
    }
    
    /**
     * 清理缓存（可选，用于调试或重置）
     */
    public void clearCache() {
        requestEditorCache.clear();
        responseEditorCache.clear();
        api.logging().logToOutput("[AISidePanelProvider] Editor 缓存已清理");
    }
}