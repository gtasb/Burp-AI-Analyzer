package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.awt.*;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.HttpResponseEditor;

public class AISidePanelResponseEditor implements ExtensionProvidedHttpResponseEditor {
    private final MontoyaApi api;
    private final ChatPanel chatPanel;
    private JPanel mainPanel;
    private HttpResponse currentResponse;
    private HttpResponseEditor responseEditor;

    public AISidePanelResponseEditor(MontoyaApi api, ChatPanel chatPanel) {
        this.api = api;
        this.chatPanel = chatPanel;
        initializeUI();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        responseEditor = api.userInterface().createHttpResponseEditor();
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("HTTP响应"));
        responsePanel.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JPanel chatPanelContainer = new JPanel(new BorderLayout());
        chatPanelContainer.setBorder(BorderFactory.createTitledBorder("AI助手"));
        chatPanelContainer.add(chatPanel, BorderLayout.CENTER);

        splitPane.setLeftComponent(responsePanel);
        splitPane.setRightComponent(chatPanelContainer);

        mainPanel.add(splitPane, BorderLayout.CENTER);
    }

    @Override
    public JComponent uiComponent() {
        return mainPanel;
    }

    @Override
    public HttpResponse getResponse() {
        return currentResponse;
    }

    public void setResponse(HttpResponse response) {
        this.currentResponse = response;
        if (response != null) {
            responseEditor.setResponse(response);
        } else {
            responseEditor.setResponse(HttpResponse.httpResponse());
        }
        chatPanel.setCurrentRequest(null);
    }

    @Override
    public Selection selectedData() {
        return null;
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        return true;
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        if (requestResponse != null) {
            setResponse(requestResponse.response());
            chatPanel.setCurrentRequest(requestResponse);
        }
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public String caption() {
        return "AI助手";
    }
}
