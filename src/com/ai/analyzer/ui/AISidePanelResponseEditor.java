package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.utils.HttpSyntaxHighlighter;

import javax.swing.*;
import java.awt.*;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;

public class AISidePanelResponseEditor implements ExtensionProvidedHttpResponseEditor {
    private final MontoyaApi api;
    private final ChatPanel chatPanel;
    private JPanel mainPanel;
    private HttpResponse currentResponse;

    public AISidePanelResponseEditor(MontoyaApi api, ChatPanel chatPanel) {
        this.api = api;
        this.chatPanel = chatPanel;
        initializeUI();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());
        
        // 创建左右分割面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        // 左侧：响应显示
        JPanel responsePanel = new JPanel(new BorderLayout());
        responsePanel.setBorder(BorderFactory.createTitledBorder("HTTP响应"));
        
        // 这里我们创建一个简单的文本显示区域
        JTextPane responseTextPane = new JTextPane();
        responseTextPane.setEditable(false);
        responseTextPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        responseTextPane.setContentType("text/plain");
        responseTextPane.setBackground(Color.WHITE);
        responseTextPane.setForeground(Color.BLACK);
        JScrollPane responseScrollPane = new JScrollPane(responseTextPane);
        responsePanel.add(responseScrollPane, BorderLayout.CENTER);

        // 右侧：AI聊天
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
            // 更新聊天面板的当前请求（这里我们需要获取对应的请求）
            // 在实际使用中，这应该从上下文获取
            // 使用UTF-8编码正确处理中文字符
            byte[] responseBytes = response.toByteArray().getBytes();
            String responseStr = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
            updateResponseDisplay(responseStr);
        }
    }

    private void updateResponseDisplay(String responseContent) {
        // 找到响应文本面板并更新内容
        Component leftComponent = ((JSplitPane) mainPanel.getComponent(0)).getLeftComponent();
        if (leftComponent instanceof JPanel) {
            JPanel responsePanel = (JPanel) leftComponent;
            Component scrollPane = responsePanel.getComponent(0);
            if (scrollPane instanceof JScrollPane) {
                JScrollPane jsp = (JScrollPane) scrollPane;
                Component textPane = jsp.getViewport().getView();
                if (textPane instanceof JTextPane) {
                    JTextPane tp = (JTextPane) textPane;
                    HttpSyntaxHighlighter.highlightHttp(tp, responseContent);
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
