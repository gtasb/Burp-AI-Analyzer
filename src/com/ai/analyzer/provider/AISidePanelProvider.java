package com.ai.analyzer.provider;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.api.QianwenApiClient;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;

public class AISidePanelProvider implements HttpRequestEditorProvider, HttpResponseEditorProvider {
    private final MontoyaApi api;
    private AIAnalyzerTab analyzerTab; // 保存analyzerTab引用

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
    private QianwenApiClient getApiClient() {
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
        
        QianwenApiClient apiClient = new QianwenApiClient(api, apiUrl, apiKey);
        apiClient.setModel(model);
        return apiClient;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        // 使用共享的 apiClient，避免重复初始化
        QianwenApiClient sharedApiClient = getApiClient();
        ChatPanel newChatPanel = new ChatPanel(api, sharedApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelRequestEditor editor = new AISidePanelRequestEditor(api, newChatPanel);
        return editor;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        // 使用共享的 apiClient，避免重复初始化
        QianwenApiClient sharedApiClient = getApiClient();
        ChatPanel newChatPanel = new ChatPanel(api, sharedApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelResponseEditor editor = new AISidePanelResponseEditor(api, newChatPanel);
        return editor;
    }
}