package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.api.QianwenApiClient;
import com.ai.analyzer.utils.MarkdownRenderer;
import com.ai.analyzer.utils.HttpFormatter;
// import com.example.ai.analyzer.Tools.ToolDefinitions;
// import com.example.ai.analyzer.Tools.ToolExecutor;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;
import burp.api.montoya.http.message.HttpRequestResponse;

public class ChatPanel extends JPanel {
    private final MontoyaApi api;
    private final QianwenApiClient apiClient;
    private AIAnalyzerTab analyzerTab; // 保存analyzerTab引用，用于获取API配置
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton clearContextButton;
    private JButton stopButton;
    private JCheckBox enableThinkingCheckBox;
    private JCheckBox enableSearchCheckBox;
    private List<ChatMessage> chatHistory;
    private HttpRequestResponse currentRequest;
    private boolean isStreaming = false;
    private SwingWorker<Void, String> currentWorker;
    // private ToolExecutor toolExecutor;
    // private List<QianwenApiClient.ToolCall> pendingToolCalls;
    private boolean debugEnabled = false;
    private JTextArea debugLogArea;
    private JScrollPane debugLogScrollPane;

    public ChatPanel(MontoyaApi api, QianwenApiClient apiClient) {
        this.api = api;
        this.apiClient = apiClient;
        this.chatHistory = new ArrayList<>();
        // this.toolExecutor = new ToolExecutor(api);
        // this.pendingToolCalls = new ArrayList<>();
        
        /* Tools call 相关代码已注释
        // 设置工具定义
        apiClient.setTools(ToolDefinitions.getBurpTools());
        
        // 设置工具调用处理器
        apiClient.setToolCallHandler(toolCall -> {
            SwingUtilities.invokeLater(() -> {
                handleToolCall(toolCall);
            });
        });
        */
        
        initializeUI();
    }
    
    /**
     * 设置analyzerTab引用，用于动态更新API配置
     */
    public void setAnalyzerTab(AIAnalyzerTab analyzerTab) {
        this.analyzerTab = analyzerTab;
        // 当设置analyzerTab时，同步复选框状态
        if (analyzerTab != null && enableThinkingCheckBox != null && enableSearchCheckBox != null) {
            enableThinkingCheckBox.setSelected(analyzerTab.isEnableThinking());
            enableSearchCheckBox.setSelected(analyzerTab.isEnableSearch());
        }
    }
    
    /**
     * 更新API配置（从analyzerTab获取最新的配置）
     */
    private void updateApiConfig() {
        if (analyzerTab != null) {
            apiClient.setApiUrl(analyzerTab.getApiUrl());
            apiClient.setApiKey(analyzerTab.getApiKey());
            apiClient.setModel(analyzerTab.getModel());
            apiClient.setEnableThinking(analyzerTab.isEnableThinking());
            apiClient.setEnableSearch(analyzerTab.isEnableSearch());
        } else {
            // 如果没有analyzerTab，使用复选框的状态
            if (enableThinkingCheckBox != null) {
                apiClient.setEnableThinking(enableThinkingCheckBox.isSelected());
            }
            if (enableSearchCheckBox != null) {
                apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
            }
        }
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 创建主分割面板（聊天区域和debug日志区域）
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplitPane.setDividerLocation(400);

        // 创建聊天区域
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        chatArea.setBackground(Color.WHITE);
        chatArea.setForeground(Color.BLACK);
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        
        // 创建debug日志区域（初始隐藏）
        debugLogArea = new JTextArea();
        debugLogArea.setEditable(false);
        debugLogArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        debugLogArea.setBackground(new Color(240, 240, 240));
        debugLogArea.setForeground(Color.BLACK);
        debugLogArea.setRows(5);
        debugLogScrollPane = new JScrollPane(debugLogArea);
        debugLogScrollPane.setBorder(BorderFactory.createTitledBorder("Debug日志"));
        debugLogScrollPane.setPreferredSize(new Dimension(0, 150));
        debugLogScrollPane.setVisible(false);
        
        mainSplitPane.setTopComponent(chatScrollPane);
        mainSplitPane.setBottomComponent(debugLogScrollPane);

        // 创建输入区域
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        
        // 功能开关面板
        JPanel featurePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        enableThinkingCheckBox = new JCheckBox("启用深度思考", true);
        enableSearchCheckBox = new JCheckBox("启用网络搜索", true);
        
        // 添加监听器，当复选框状态改变时更新API客户端配置
        enableThinkingCheckBox.addActionListener(e -> {
            if (analyzerTab == null) {
                apiClient.setEnableThinking(enableThinkingCheckBox.isSelected());
            } else {
                // 如果analyzerTab存在，同步到analyzerTab
                // 注意：这里不直接修改analyzerTab的复选框，因为ChatPanel应该从analyzerTab获取配置
                // 但我们可以更新API客户端配置
                apiClient.setEnableThinking(enableThinkingCheckBox.isSelected());
            }
        });
        enableSearchCheckBox.addActionListener(e -> {
            if (analyzerTab == null) {
                apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
            } else {
                apiClient.setEnableSearch(enableSearchCheckBox.isSelected());
            }
        });
        
        featurePanel.add(enableThinkingCheckBox);
        featurePanel.add(enableSearchCheckBox);
        
        // 顶部按钮面板
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        
        sendButton = new JButton("发送");
        sendButton.addActionListener(e -> sendMessage());
        sendButton.setPreferredSize(new Dimension(60, 25));
        sendButton.setMargin(new Insets(2, 8, 2, 8));
        
        clearContextButton = new JButton("清空");
        clearContextButton.addActionListener(e -> clearContext());
        clearContextButton.setPreferredSize(new Dimension(60, 25));
        clearContextButton.setMargin(new Insets(2, 8, 2, 8));
        
        stopButton = new JButton("停止");
        stopButton.addActionListener(e -> stopStreaming());
        stopButton.setEnabled(false); // 初始状态禁用
        stopButton.setPreferredSize(new Dimension(60, 25));
        stopButton.setMargin(new Insets(2, 8, 2, 8));
        
        topPanel.add(sendButton);
        topPanel.add(clearContextButton);
        topPanel.add(stopButton);
        
        // 将功能开关面板和按钮面板组合
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(featurePanel, BorderLayout.WEST);
        topContainer.add(topPanel, BorderLayout.EAST);
        
        inputPanel.add(topContainer, BorderLayout.NORTH);

        // 输入框
        inputField = new JTextField();
        inputField.setBackground(Color.WHITE);
        inputField.setForeground(Color.BLACK);
        inputField.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {}

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendMessage();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {}
        });
        
        inputPanel.add(inputField, BorderLayout.CENTER);

        add(mainSplitPane, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);
    }
    
    /**
     * 添加debug日志
     */
    private void debugLog(String message) {
        if (debugEnabled) {
            String timestamp = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            String logMessage = "[" + timestamp + "] " + message + "\n";
            
            SwingUtilities.invokeLater(() -> {
                debugLogArea.append(logMessage);
                debugLogArea.setCaretPosition(debugLogArea.getDocument().getLength());
            });
        }
        // 同时输出到Burp日志
        api.logging().logToOutput("[AI助手-Debug] " + message);
    }
    
    /**
     * 切换debug日志显示
     */
    private void toggleDebugLog() {
        debugEnabled = !debugEnabled;
        debugLogScrollPane.setVisible(debugEnabled);
        if (debugEnabled) {
            debugLog("Debug日志已启用");
        } else {
            api.logging().logToOutput("[AI助手] Debug日志已禁用");
        }
        revalidate();
        repaint();
    }

    private void sendMessage() {
        if (isStreaming) return;
        
        String message = inputField.getText().trim();
        
        // 如果没有用户输入，使用默认分析提示
        String finalMessage = message.isEmpty() ? "请分析当前请求的安全风险" : message;
        
        // 只有当用户确实输入了内容时才添加到聊天
        if (!message.isEmpty()) {
            appendToChat("你", message, true);
            chatHistory.add(new ChatMessage("user", message));
        }
        
        inputField.setText("");
        
        // 开始流式输出
        isStreaming = true;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        
        currentWorker = new SwingWorker<Void, String>() {
            private StringBuilder fullResponse = new StringBuilder();
            private int aiMessageStartPos = -1; // 记录AI消息开始位置

            @Override
            protected Void doInBackground() throws Exception {
                try {
                    // 构建上下文
                    StringBuilder contextBuilder = new StringBuilder();
                    //contextBuilder.append("重要提示：请先仔细分析提供的HTTP请求和响应，给出详细的安全分析报告。只有在确实需要时（如获取更多信息、构造测试payload等）才调用工具。不要随意调用编码/解码工具。\n\n");
                    
                    // 添加当前请求信息（如果有）
                    if (currentRequest != null) {
                        contextBuilder.append("当前请求信息：\n");
                        // 使用UTF-8编码正确解析中文字符
                        byte[] requestBytes = currentRequest.request().toByteArray().getBytes();
                        String requestStr = new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8);
                        contextBuilder.append("请求：\n").append(requestStr).append("\n\n");
                        
                        if (currentRequest.response() != null) {
                            byte[] responseBytes = currentRequest.response().toByteArray().getBytes();
                            String responseStr = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
                            contextBuilder.append("响应：\n").append(responseStr).append("\n\n");
                        } else {
                            contextBuilder.append("注意：当前只有请求信息，没有响应信息。\n\n");
                        }
                    } else {
                        // 没有请求时，提示可以自由对话
                        contextBuilder.append("当前没有关联的HTTP请求，你可以自由提问，我会尽力帮助你。\n");
/*                         contextBuilder.append("你可以使用提供的工具来调用Burp Suite的功能，例如：\n");
                        contextBuilder.append("- 获取代理历史记录\n");
                        contextBuilder.append("- 获取扫描器发现的问题\n");
                        contextBuilder.append("- 发送HTTP请求\n");
                        contextBuilder.append("- 执行编码/解码操作\n");
                        contextBuilder.append("- 生成随机字符串\n\n"); */
                    }
                    
                    // 注意：聊天历史由 LangChain4j 的 chatMemory 自动管理，不需要手动添加到上下文
                    contextBuilder.append("用户问题：").append(finalMessage);

                    // 在调用API前更新配置
                    updateApiConfig();
                    
                    debugLog("开始调用AI API");
                    debugLog("用户消息: " + finalMessage);
                    debugLog("API URL: " + apiClient.getApiUrl());
                    debugLog("API Key: " + (apiClient.getApiKey().isEmpty() ? "未设置" : "已设置"));
                    debugLog("上下文:\n" + contextBuilder.toString());
                    
                    // 先在UI中添加AI助手前缀（只添加一次）
                    SwingUtilities.invokeLater(() -> {
                        try {
                            StyledDocument doc = chatArea.getStyledDocument();
                            Style senderStyle = doc.addStyle("sender", null);
                            StyleConstants.setBold(senderStyle, true);
                            StyleConstants.setForeground(senderStyle, Color.GREEN);
                            
                            doc.insertString(doc.getLength(), "AI助手: \n", senderStyle);
                            aiMessageStartPos = doc.getLength(); // 记录AI消息内容开始位置
                        } catch (Exception e) {
                            api.logging().logToError("添加AI助手前缀失败: " + e.getMessage());
                        }
                    });
                    
                    // 调用API：传递 HttpRequestResponse 对象以检测请求来源
                    api.logging().logToOutput("[ChatPanel] 开始调用analyzeRequestStream");
                    apiClient.analyzeRequestStream(
                        currentRequest,
                        finalMessage,
                        chunk -> {
                            //api.logging().logToOutput("[ChatPanel] 收到内容chunk: " + (chunk.length() > 100 ? chunk.substring(0, 100) + "..." : chunk));
                            // 将chunk添加到缓冲区
                            fullResponse.append(chunk);
                            
                            // 流式输出时，实时进行Markdown解析和渲染
                            // 使用invokeAndWait确保最后一个chunk的渲染完成
                            try {
                                SwingUtilities.invokeAndWait(() -> {
                                    try {
                                        // 使用流式Markdown渲染，从AI消息开始位置重新渲染整个缓冲区
                                        if (aiMessageStartPos >= 0) {
                                            MarkdownRenderer.appendMarkdownStreaming(chatArea, fullResponse.toString(), aiMessageStartPos);
                                            chatArea.setCaretPosition(chatArea.getStyledDocument().getLength());
                                        } else {
                                            // 如果还没有记录位置，先追加纯文本
                                            StyledDocument doc = chatArea.getStyledDocument();
                                            Style messageStyle = doc.addStyle("message", null);
                                            StyleConstants.setForeground(messageStyle, Color.BLACK);
                                            doc.insertString(doc.getLength(), chunk, messageStyle);
                                            chatArea.setCaretPosition(doc.getLength());
                                        }
                                    } catch (Exception e) {
                                        api.logging().logToOutput("[ChatPanel] 流式Markdown渲染失败: " + e.getMessage());
                                        api.logging().logToError("流式Markdown渲染失败: " + e.getMessage());
                                        for (StackTraceElement ste : e.getStackTrace()) {
                                            api.logging().logToError("  at " + ste.toString());
                                        }
                                    }
                                });
                            } catch (Exception e) {
                                // 如果invokeAndWait失败，回退到invokeLater
                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        if (aiMessageStartPos >= 0) {
                                            MarkdownRenderer.appendMarkdownStreaming(chatArea, fullResponse.toString(), aiMessageStartPos);
                                            chatArea.setCaretPosition(chatArea.getStyledDocument().getLength());
                                        } else {
                                            StyledDocument doc = chatArea.getStyledDocument();
                                            Style messageStyle = doc.addStyle("message", null);
                                            StyleConstants.setForeground(messageStyle, Color.BLACK);
                                            doc.insertString(doc.getLength(), chunk, messageStyle);
                                            chatArea.setCaretPosition(doc.getLength());
                                        }
                                    } catch (Exception ex) {
                                        api.logging().logToError("流式Markdown渲染失败: " + ex.getMessage());
                                    }
                                });
                            }
                        }
                    );
                    
                    api.logging().logToOutput("[ChatPanel] analyzeRequestStream调用完成，fullResponse长度: " + fullResponse.length());
                    api.logging().logToOutput("[ChatPanel] fullResponse内容: " + (fullResponse.length() > 500 ? fullResponse.substring(0, 500) + "..." : fullResponse.toString()));
                    debugLog("AI API调用完成");
                    
                    // 流式输出完成后，使用完整的Markdown渲染替换流式渲染的内容
                    String finalContent = fullResponse.toString();
                    if (!finalContent.isEmpty() && aiMessageStartPos >= 0) {
                        try {
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    StyledDocument doc = chatArea.getStyledDocument();
                                    // 删除流式渲染的内容
                                    int currentLength = doc.getLength();
                                    if (currentLength > aiMessageStartPos) {
                                        doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                    }
                                    // 使用完整的Markdown渲染
                                    MarkdownRenderer.appendMarkdown(chatArea, finalContent);
                                    chatArea.setCaretPosition(chatArea.getStyledDocument().getLength());
                                } catch (BadLocationException e) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + e.getMessage());
                                } catch (Exception e) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + e.getMessage());
                                }
                            });
                        } catch (Exception e) {
                            // 如果invokeAndWait失败，使用invokeLater作为备选
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    StyledDocument doc = chatArea.getStyledDocument();
                                    int currentLength = doc.getLength();
                                    if (currentLength > aiMessageStartPos) {
                                        doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                                    }
                                    MarkdownRenderer.appendMarkdown(chatArea, finalContent);
                                    chatArea.setCaretPosition(chatArea.getStyledDocument().getLength());
                                } catch (BadLocationException ex) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + ex.getMessage());
                                } catch (Exception ex) {
                                    api.logging().logToError("流式输出完成后完整渲染失败: " + ex.getMessage());
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendToChat("AI助手", "抱歉，处理请求时出现错误: " + e.getMessage(), false);
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // 检查是否有异常
                    api.logging().logToOutput("[ChatPanel] SwingWorker done()被调用");
                    api.logging().logToOutput("[ChatPanel] fullResponse最终长度: " + fullResponse.length());
                    api.logging().logToOutput("[ChatPanel] fullResponse最终内容: " + (fullResponse.length() > 500 ? fullResponse.substring(0, 500) + "..." : fullResponse.toString()));
                    
                    // 添加到历史记录
                    chatHistory.add(new ChatMessage("assistant", fullResponse.toString()));
                    
                    // 流式输出已完成，在done()中已经完成了完整渲染，这里不需要再渲染
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendToChat("AI助手", "抱歉，处理请求时出现错误: " + e.getMessage(), false);
                    });
                } finally {
                    isStreaming = false;
                    aiMessageStartPos = -1; // 重置位置
                    // 恢复按钮状态
                    SwingUtilities.invokeLater(() -> {
                        sendButton.setEnabled(true);
                        stopButton.setEnabled(false);
                    });
                }
            }
        };

        currentWorker.execute();
    }

    private void stopStreaming() {
        if (currentWorker != null && !currentWorker.isDone()) {
            // 先取消流式输出连接
            if (apiClient != null) {
                apiClient.cancelStreaming();
            }
            // 然后取消 SwingWorker
            currentWorker.cancel(true);
            isStreaming = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
            appendToChat("AI助手", "[输出已中断]", false);
        }
    }

    private void appendToChat(String sender, String message, boolean isUser) {
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // 发送者样式
            Style senderStyle = doc.addStyle("sender", null);
            StyleConstants.setBold(senderStyle, true);
            StyleConstants.setForeground(senderStyle, isUser ? Color.BLUE : Color.GREEN);
            
            // 消息样式
            Style messageStyle = doc.addStyle("message", null);
            StyleConstants.setForeground(messageStyle, Color.BLACK);
            
            // 添加发送者和消息
            doc.insertString(doc.getLength(), sender + ": ", senderStyle);
            doc.insertString(doc.getLength(), message, messageStyle);
            doc.insertString(doc.getLength(), "\n\n", messageStyle);
            
            // 滚动到底部
            chatArea.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            api.logging().logToError("添加聊天消息失败: " + e.getMessage());
        }
    }

    private void clearContext() {
        // 如果正在输出，先停止
        if (currentWorker != null && !currentWorker.isDone()) {
            // apiClient.clearContext() 内部会先调用 cancelStreaming()
            currentWorker.cancel(true);
            isStreaming = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
        
        chatHistory.clear();
        chatArea.setText("");
        // 清空 Assistant 的聊天记忆（共享实例）
        // 注意：apiClient.clearContext() 内部已经会先调用 cancelStreaming()
        apiClient.clearContext();
        api.logging().logToOutput("聊天上下文已清空");
    }

    public void setCurrentRequest(HttpRequestResponse request) {
        this.currentRequest = request;
        if (request != null) {
            appendToChat("系统", "已更新当前请求信息", false);
        }
    }

    public HttpRequestResponse getCurrentRequest() {
        return currentRequest;
    }

    public void notifyRequestUpdated(HttpRequestResponse request) {
        setCurrentRequest(request);
    }
    
    /**
     * 处理工具调用
     */
    /* Tools call 相关代码已注释
    private void handleToolCall(QianwenApiClient.ToolCall toolCall) {
        api.logging().logToOutput("[ChatPanel] ========== 收到工具调用 ==========");
        api.logging().logToOutput("[ChatPanel] 工具调用ID: " + toolCall.getId());
        api.logging().logToOutput("[ChatPanel] 工具名称: " + toolCall.getName());
        api.logging().logToOutput("[ChatPanel] 工具参数原始值: " + toolCall.getArguments());
        api.logging().logToOutput("[ChatPanel] 工具参数是否为null: " + (toolCall.getArguments() == null));
        api.logging().logToOutput("[ChatPanel] 工具参数是否为空字符串: " + (toolCall.getArguments() != null && toolCall.getArguments().trim().isEmpty()));
        api.logging().logToOutput("[ChatPanel] 工具参数长度: " + (toolCall.getArguments() != null ? toolCall.getArguments().length() : 0));
        
        appendToChat("系统", "正在调用工具: " + toolCall.getName(), false);
        debugLog("工具调用: " + toolCall.getName());
        debugLog("工具参数: " + (toolCall.getArguments() != null && !toolCall.getArguments().isEmpty() 
            ? toolCall.getArguments() 
            : "(空)"));
        
        // 如果参数为空，直接返回错误
        if (toolCall.getArguments() == null || toolCall.getArguments().trim().isEmpty()) {
            api.logging().logToOutput("[ChatPanel] 错误: 工具调用参数为空，无法执行");
            SwingUtilities.invokeLater(() -> {
                appendToChat("工具执行结果", "错误: 工具调用缺少参数。AI需要提供完整的工具参数才能执行。", false);
            });
            return;
        }
        
        api.logging().logToOutput("[ChatPanel] 开始执行工具，传递参数到ToolExecutor");
        // 执行工具（在后台线程中执行，避免阻塞UI）
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            api.logging().logToOutput("[ChatPanel] 工具执行线程已启动");
            String result = toolExecutor.executeTool(toolCall.getName(), toolCall.getArguments());
            long duration = System.currentTimeMillis() - startTime;
            
            api.logging().logToOutput("[ChatPanel] 工具执行完成，耗时: " + duration + "ms");
            api.logging().logToOutput("[ChatPanel] 工具执行结果: " + (result.length() > 500 ? result.substring(0, 500) + "..." : result));
            
            debugLog("工具执行完成，耗时: " + duration + "ms");
            debugLog("工具结果: " + (result.length() > 500 ? result.substring(0, 500) + "..." : result));
            
            // 显示工具执行结果
            SwingUtilities.invokeLater(() -> {
                appendToChat("工具执行结果", result, false);
                // 将工具结果保存，等待流式输出完成后处理
                pendingToolCalls.add(toolCall);
            });
        }).start();
    }
    */
    
    /* Tools call 相关代码已注释
    // 处理待处理的工具调用
    private void processPendingToolCalls(String userMessage) {
        if (pendingToolCalls.isEmpty()) return;
        
        StringBuilder toolResults = new StringBuilder();
        toolResults.append("\n\n工具调用结果：\n");
        
        for (QianwenApiClient.ToolCall toolCall : pendingToolCalls) {
            String result = toolExecutor.executeTool(toolCall.getName(), toolCall.getArguments());
            toolResults.append("- ").append(toolCall.getName()).append(": ").append(result).append("\n");
        }
        
        // 清空待处理列表
        pendingToolCalls.clear();
        
        // 继续对话，将工具结果发送给AI
        // 这里可以触发新一轮的API调用，将工具结果包含在消息中
        appendToChat("系统", "工具执行完成，AI将基于结果继续回答", false);
    }
    
    // 处理工具结果并继续对话
    private void processToolResult(QianwenApiClient.ToolCall toolCall, String result) {
        // 这里可以将工具结果发送回AI，让AI基于结果继续回答
        // 需要修改API调用逻辑以支持多轮对话
    }
    */
 

    // 内部聊天消息类
    public static class ChatMessage {
        private String role;
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}