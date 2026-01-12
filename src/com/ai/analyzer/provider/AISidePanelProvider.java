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
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;

/**
 * Side Panel 提供者（回退版本）
 */
public class AISidePanelProvider implements HttpRequestEditorProvider, HttpResponseEditorProvider {
    private final MontoyaApi api;
    private AIAnalyzerTab analyzerTab;

    public AISidePanelProvider(MontoyaApi api) {
        this.api = api;
    }
    
    public void setAnalyzerTab(AIAnalyzerTab analyzerTab) {
        this.analyzerTab = analyzerTab;
    }
    
    private AgentApiClient getApiClient() {
        if (analyzerTab != null) {
            return analyzerTab.getApiClient();
        }
        
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1";
        String apiKey = "";
        String model = "qwen-max";
        
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
            // 忽略
        }
        
        AgentApiClient apiClient = new AgentApiClient(api, apiUrl, apiKey);
        apiClient.setModel(model);
        return apiClient;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        AgentApiClient sharedApiClient = getApiClient();
        ChatPanel chatPanel = new ChatPanel(api, sharedApiClient);
        
        if (analyzerTab != null) {
            chatPanel.setAnalyzerTab(analyzerTab);
        }
        
        return new AISidePanelRequestEditor(api, chatPanel);
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext creationContext) {
        AgentApiClient sharedApiClient = getApiClient();
        ChatPanel chatPanel = new ChatPanel(api, sharedApiClient);
        
        if (analyzerTab != null) {
            chatPanel.setAnalyzerTab(analyzerTab);
        }
        
        return new AISidePanelResponseEditor(api, chatPanel);
    }
}
