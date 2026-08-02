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
    private HttpRequestResponse currentRequestResponse;
    private HttpRequestEditor requestEditor;
    private MessageCollectionPanel collectionPanel;

    public AISidePanelRequestEditor(MontoyaApi api, ChatPanel chatPanel) {
        this.api = api;
        this.chatPanel = chatPanel;
        initializeUI();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());
        
        // 左右分栏：左侧 = 报文 + 收藏列表，右侧 = AI 助手
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(420);
        splitPane.setResizeWeight(0);
        splitPane.setContinuousLayout(true);

        requestEditor = api.userInterface().createHttpRequestEditor();

        collectionPanel = new MessageCollectionPanel(chatPanel,
                () -> currentRequestResponse,
                rr -> {
                    if (rr != null && rr.request() != null) {
                        requestEditor.setRequest(rr.request());
                    }
                });

        // 左侧上下分栏：上 = HTTP 请求报文，下 = 收藏报文，分隔条可自由拖动
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        leftSplit.setDividerLocation(300);
        leftSplit.setResizeWeight(0.55);
        leftSplit.setContinuousLayout(true);

        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBorder(BorderFactory.createTitledBorder("HTTP请求"));
        requestPanel.setMinimumSize(new Dimension(0, 0));
        requestPanel.add(requestEditor.uiComponent(), BorderLayout.CENTER);
        leftSplit.setTopComponent(requestPanel);
        leftSplit.setBottomComponent(collectionPanel);
        collectionPanel.setMinimumSize(new Dimension(0, 0));

        JPanel chatPanelContainer = new JPanel(new BorderLayout());
        chatPanelContainer.setBorder(BorderFactory.createTitledBorder("AI助手"));
        chatPanelContainer.add(chatPanel, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftSplit);
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
        this.currentRequestResponse = requestResponse;
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
