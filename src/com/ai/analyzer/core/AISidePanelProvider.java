package com.ai.analyzer.core;

import burp.api.montoya.MontoyaApi;

import com.ai.analyzer.ui.ChatPanel;
import com.ai.analyzer.ui.AISidePanelRequestEditor;
import com.ai.analyzer.ui.AISidePanelResponseEditor;
import com.ai.analyzer.ui.AIAnalyzerTab;
import com.ai.analyzer.core.AgentApiClient;
import com.ai.analyzer.core.PluginSettings;

import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.core.ToolType;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 侧栏 AI 面板提供者（Request/Response 编辑器）。
 *
 * <p>按工具类型（Repeater/Extensions/…）共享同一个 AI 助手实例是既定设计：
 * 每个 {@link ChatPanel} 都从同一个共享 {@code AgentApiClient} 的聊天历史渲染并订阅变更，
 * 因此同一工具的所有编辑器页签看到的是同一份连续对话。这里只做「按工具类型避免重复
 * 建编辑器实例」的缓存，并精简日志（不再逐调用刷屏）。
 */
public class AISidePanelProvider implements HttpRequestEditorProvider, HttpResponseEditorProvider {
    private final MontoyaApi api;
    private volatile AIAnalyzerTab analyzerTab;

    // Editor 实例缓存：键 = ToolType.toolName()，同一工具共享一个侧栏 AI 助手实例
    private final Map<String, ExtensionProvidedHttpRequestEditor> requestEditorCache = new ConcurrentHashMap<>();
    private final Map<String, ExtensionProvidedHttpResponseEditor> responseEditorCache = new ConcurrentHashMap<>();

    public AISidePanelProvider(MontoyaApi api) {
        this.api = api;
    }

    /**
     * 设置 analyzerTab 引用，用于获取共享的 API Client 与最新配置。
     */
    public void setAnalyzerTab(AIAnalyzerTab analyzerTab) {
        this.analyzerTab = analyzerTab;
    }

    /**
     * 获取共享的 API Client（优先用 analyzerTab 的共享实例）。
     */
    private AgentApiClient getApiClient() {
        if (analyzerTab != null) {
            return analyzerTab.getApiClient();
        }
        AgentApiClient apiClient = new AgentApiClient(api, "https://dashscope.aliyuncs.com/api/v1", "");
        try {
            File settingsFile = new File(System.getProperty("user.home"), ".burp_ai_analyzer_settings");
            if (settingsFile.exists()) {
                PluginSettings settings = PluginSettings.loadCompat(settingsFile);
                if (settings.getApiUrl() != null && !settings.getApiUrl().isEmpty()) {
                    apiClient.setApiUrl(settings.getApiUrl());
                }
                if (settings.getApiKey() != null && !settings.getApiKey().isEmpty()) {
                    apiClient.setApiKey(settings.getApiKey());
                }
                if (settings.getModel() != null && !settings.getModel().isEmpty()) {
                    apiClient.setModel(settings.getModel());
                }
            }
        } catch (Exception ignored) {
        }
        return apiClient;
    }

    /** 缓存键 = 工具类型（同一工具共享同一个侧栏 AI 助手实例） */
    private String cacheKey(EditorCreationContext context) {
        if (context != null && context.toolSource() != null) {
            ToolType tt = context.toolSource().toolType();
            if (tt != null) {
                return tt.toolName();
            }
        }
        return "default";
    }

    private AgentApiClient sharedClient() {
        return getApiClient();
    }

    private ChatPanel newChatPanel() {
        ChatPanel chatPanel = new ChatPanel(api, sharedClient());
        AIAnalyzerTab tab = analyzerTab;
        if (tab != null) {
            chatPanel.setAnalyzerTab(tab);
        }
        return chatPanel;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        String key = cacheKey(creationContext);
        ExtensionProvidedHttpRequestEditor cached = requestEditorCache.get(key);
        if (cached != null) {
            return cached;
        }
        AISidePanelRequestEditor editor = new AISidePanelRequestEditor(api, newChatPanel());
        requestEditorCache.put(key, editor);
        api.logging().logToOutput("[AISidePanel] 新建 Request 侧栏编辑器: " + key);
        return editor;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        String key = cacheKey(creationContext);
        ExtensionProvidedHttpResponseEditor cached = responseEditorCache.get(key);
        if (cached != null) {
            return cached;
        }
        AISidePanelResponseEditor editor = new AISidePanelResponseEditor(api, newChatPanel());
        responseEditorCache.put(key, editor);
        api.logging().logToOutput("[AISidePanel] 新建 Response 侧栏编辑器: " + key);
        return editor;
    }

    /**
     * 清理缓存（调试 / 重置用）。
     */
    public void clearCache() {
        requestEditorCache.clear();
        responseEditorCache.clear();
        api.logging().logToOutput("[AISidePanel] Editor 缓存已清理");
    }
}
