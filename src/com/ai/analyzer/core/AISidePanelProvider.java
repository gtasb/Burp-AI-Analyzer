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

/**
 * 侧栏 AI 面板提供者（Request/Response 编辑器）。
 *
 * <p>共享同一个 AI 助手实例是既定设计：每个 {@link ChatPanel} 都从同一个共享
 * {@code AgentApiClient} 的聊天历史渲染并订阅变更，因此同一工具的所有编辑器页签
 * 看到的是同一份连续对话。编辑器组件本身每次调用都新建——Swing 组件不能同时挂载
 * 在多个容器中，缓存复用会导致标签页互相抢面板。
 */
public class AISidePanelProvider implements HttpRequestEditorProvider, HttpResponseEditorProvider {
    private final MontoyaApi api;
    private volatile AIAnalyzerTab analyzerTab;

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
        // 每次调用都必须新建：Swing 组件只能挂在一个容器里，缓存复用会让
        // 多个 Repeater 标签互相抢同一个面板，且 currentRequestResponse 后写覆盖前写。
        String key = cacheKey(creationContext);
        AISidePanelRequestEditor editor = new AISidePanelRequestEditor(api, newChatPanel());
        api.logging().logToOutput("[AISidePanel] 新建 Request 侧栏编辑器: " + key);
        return editor;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        String key = cacheKey(creationContext);
        AISidePanelResponseEditor editor = new AISidePanelResponseEditor(api, newChatPanel());
        api.logging().logToOutput("[AISidePanel] 新建 Response 侧栏编辑器: " + key);
        return editor;
    }

    /**
     * 清理缓存（调试 / 重置用）。编辑器改为每次新建后已无缓存；
     * 面板在从窗口移除时会自行注销监听。
     */
    public void clearCache() {
        api.logging().logToOutput("[AISidePanel] 无缓存需要清理");
    }
}
