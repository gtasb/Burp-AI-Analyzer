package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.utils.HttpSyntaxHighlighter;

import javax.swing.*;
import java.awt.*;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;

public class AISidePanelRequestEditor implements ExtensionProvidedHttpRequestEditor {
    private final MontoyaApi api;
    private final ChatPanel chatPanel;
    private JPanel mainPanel;
    private HttpRequest currentRequest;

    public AISidePanelRequestEditor(MontoyaApi api, ChatPanel chatPanel) {
        this.api = api;
        this.chatPanel = chatPanel;
        initializeUI();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());
        
        // 创建左右分割面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        // 左侧：请求显示
        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBorder(BorderFactory.createTitledBorder("HTTP请求"));
        
        // 这里我们创建一个简单的文本显示区域，支持HTTP语法高亮
        JTextPane requestTextPane = new JTextPane();
        requestTextPane.setEditable(false);
        requestTextPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        requestTextPane.setContentType("text/plain");
        requestTextPane.setBackground(Color.WHITE);
        requestTextPane.setForeground(Color.BLACK);
        JScrollPane requestScrollPane = new JScrollPane(requestTextPane);
        requestPanel.add(requestScrollPane, BorderLayout.CENTER);

        // 右侧：AI聊天
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
            // 更新聊天面板的当前请求
            // 注意：这里简化处理，实际应该从上下文获取完整的HttpRequestResponse
            // HttpRequestResponse requestResponse = api.http().buildRequestResponse(request, null);
            // chatPanel.setCurrentRequest(requestResponse);
            
            // 更新显示区域，使用UTF-8编码正确处理中文字符
            byte[] requestBytes = request.toByteArray().getBytes();
            String requestStr = new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8);
            updateRequestDisplay(requestStr);
        }
    }

    private void updateRequestDisplay(String requestContent) {
        // 找到请求文本面板并更新内容
        Component leftComponent = ((JSplitPane) mainPanel.getComponent(0)).getLeftComponent();
        if (leftComponent instanceof JPanel) {
            JPanel requestPanel = (JPanel) leftComponent;
            Component scrollPane = requestPanel.getComponent(0);
            if (scrollPane instanceof JScrollPane) {
                JScrollPane jsp = (JScrollPane) scrollPane;
                Component textPane = jsp.getViewport().getView();
                if (textPane instanceof JTextPane) {
                    JTextPane tp = (JTextPane) textPane;
                    HttpSyntaxHighlighter.highlightHttp(tp, requestContent);
                }
            }
        }
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
