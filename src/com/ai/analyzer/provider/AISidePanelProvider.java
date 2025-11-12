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
     * 从保存的设置文件加载API配置
     */
    private QianwenApiClient createApiClientWithSettings() {
        String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        String apiKey = "";
        String model = "qwen-max";
        
        // 优先从analyzerTab获取配置
        if (analyzerTab != null) {
            apiUrl = analyzerTab.getApiUrl();
            apiKey = analyzerTab.getApiKey();
            model = analyzerTab.getModel();
        } else {
            // 如果analyzerTab不可用，尝试从保存的设置文件加载
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
        }
        
        QianwenApiClient apiClient = new QianwenApiClient(api, apiUrl, apiKey);
        apiClient.setModel(model);
        return apiClient;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        // Create new instances for each editor
        QianwenApiClient newApiClient = createApiClientWithSettings();
        ChatPanel newChatPanel = new ChatPanel(api, newApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelRequestEditor editor = new AISidePanelRequestEditor(api, newChatPanel);
        return editor;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        // Create new instances for each editor
        QianwenApiClient newApiClient = createApiClientWithSettings();
        ChatPanel newChatPanel = new ChatPanel(api, newApiClient);
        // 设置analyzerTab引用，使ChatPanel能够动态更新API配置
        if (analyzerTab != null) {
            newChatPanel.setAnalyzerTab(analyzerTab);
        }
        AISidePanelResponseEditor editor = new AISidePanelResponseEditor(api, newChatPanel);
        return editor;
    }
}