package com.ai.analyzer.provider;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.ui.AIAnalyzerTab;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

public class AIContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final AIAnalyzerTab analyzerTab;

    public AIContextMenuProvider(MontoyaApi api, AIAnalyzerTab analyzerTab) {
        this.api = api;
        this.analyzerTab = analyzerTab;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();

        // 检查是否有选中的HTTP请求响应
        if (event.messageEditorRequestResponse().isPresent()) {
            MessageEditorHttpRequestResponse messageEditor = event.messageEditorRequestResponse().get();
            
            JMenuItem sendToAnalyzerItem = new JMenuItem("发送到AI分析");
            sendToAnalyzerItem.addActionListener(e -> {
                try {
                    // 获取请求信息
                    String requestContent = messageEditor.requestResponse().request().toString();
                    
                    // 解析请求行获取方法和URL
                    String[] lines = requestContent.split("\n");
                    if (lines.length > 0) {
                        String requestLine = lines[0];
                        String[] parts = requestLine.split(" ");
                        if (parts.length >= 2) {
                            String method = parts[0];
                            String url = parts[1];
                            
                            // 构建完整的HttpRequestResponse对象
                            var requestResponse = messageEditor.requestResponse();
                            
                            // 添加到分析器
                            analyzerTab.addRequestFromHttpRequestResponse(method, url, requestResponse);
                            
                            api.logging().logToOutput("请求已发送到AI分析器: " + method + " " + url);
                        }
                    }
                } catch (Exception ex) {
                    api.logging().logToError("发送请求到AI分析器失败: " + ex.getMessage());
                }
            });
            
            menuItems.add(sendToAnalyzerItem);
        }

        return menuItems;
    }
}