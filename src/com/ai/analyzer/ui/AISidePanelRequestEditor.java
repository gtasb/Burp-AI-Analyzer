package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.awt.*;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.HttpRequestEditor;

public class AISidePanelRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final MontoyaApi api;
    private final ChatPanel chatPanel;
    private JPanel mainPanel;
    private HttpRequest currentRequest;
    private HttpRequestEditor requestEditor;

    public AISidePanelRequestEditor(MontoyaApi api, ChatPanel chatPanel) {
        this.api = api;
        this.chatPanel = chatPanel;
        initializeUI();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        requestEditor = api.userInterface().createHttpRequestEditor();
        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBorder(BorderFactory.createTitledBorder("HTTP请求"));
        requestPanel.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        JPanel chatPanelContainer = new JPanel(new BorderLayout());
        chatPanelContainer.setBorder(BorderFactory.createTitledBorder("AI助手"));
        chatPanelContainer.add(chatPanel, BorderLayout.CENTER);

        splitPane.setLeftComponent(requestPanel);
        splitPane.setRightComponent(chatPanelContainer);

        mainPanel.add(splitPane, BorderLayout.CENTER);
    }

    @Override
    public JComponent uiComponent() {
        return mainPanel;
    }

    @Override
    public HttpRequest getRequest() {
        return currentRequest;
    }

    public void setRequest(HttpRequest request) {
        this.currentRequest = request;
        if (request != null) {
            requestEditor.setRequest(request);
        } else {
            requestEditor.setRequest(HttpRequest.httpRequest());
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
            setRequest(requestResponse.request());
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
