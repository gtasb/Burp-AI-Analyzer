package com.ai.analyzer.ui;

import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.api.AgentApiClient;
import com.ai.analyzer.listener.ChatUpdateListener;
import com.ai.analyzer.manager.ChatSessionManager;
import com.ai.analyzer.model.ChatMessage;
import com.ai.analyzer.model.ChatSession;
import com.ai.analyzer.utils.MarkdownRenderer;
import com.ai.analyzer.utils.DebugLogger;
import static com.ai.analyzer.utils.DebugLogger.*;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * 聊天面板
 * 
 * 架构说明：
 * - 实现 ChatUpdateListener 接口接收数据更新
 * - UI 与数据层完全解耦，数据由 ChatSessionManager 管理
 */
public class ChatPanel extends JPanel implements ChatUpdateListener {
    // 用于追踪实例的唯一 ID
    private static int instanceCounter = 0;
    private final int instanceId;
    
    private final MontoyaApi api;
    private final AgentApiClient apiClient;
    private AIAnalyzerTab analyzerTab;
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton clearContextButton;
    private JButton stopButton;
    private JCheckBox enableThinkingCheckBox;
    private JCheckBox enableSearchCheckBox;
    private HttpRequestResponse currentRequest;
    private boolean isStreaming = false;
    private SwingWorker<Void, String> currentWorker;
    
    // 流式渲染位置跟踪
    private volatile int aiMessageStartPos = -1;
    
    // 标志位：是否已经恢复过会话数据
    private boolean sessionRestored = false;

    // 安全日志方法 - 防止 Burp API 在组件销毁后访问崩溃
    private void safeLogOutput(String message) {
        try {
            if (api != null && api.logging() != null) {
                api.logging().logToOutput(message);
            }
        } catch (Exception e) {
            // 忽略 - Burp API 可能在组件销毁后不可用
        }
    }
    
    private void safeLogError(String message) {
        try {
            if (api != null && api.logging() != null) {
                api.logging().logToError(message);
            }
        } catch (Exception e) {
            // 忽略 - Burp API 可能在组件销毁后不可用
        }
    }
    
    // 检查组件是否仍然有效
    private boolean isComponentValid() {
        return this.isDisplayable() && chatArea != null && chatArea.isDisplayable();
    }
    
    /**
     * 追加消息并使用 Markdown 渲染
     */
    private void appendToChatWithMarkdown(String sender, String content) {
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // 发送者样式
            Style senderStyle = doc.addStyle("sender", null);
            StyleConstants.setBold(senderStyle, true);
            StyleConstants.setForeground(senderStyle, Color.GREEN);
            
            // 添加发送者
            doc.insertString(doc.getLength(), sender + ": \n", senderStyle);
            
            // 使用 Markdown 渲染内容
            MarkdownRenderer.appendMarkdown(chatArea, content);
            
            // 添加换行
            Style messageStyle = doc.addStyle("message", null);
            StyleConstants.setForeground(messageStyle, Color.BLACK);
            doc.insertString(doc.getLength(), "\n\n", messageStyle);
            
            // 滚动到底部
            chatArea.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            safeLogError("[ChatPanel] Markdown渲染失败: " + e.getMessage());
        }
    }
    
    // ========== ChatUpdateListener 接口实现 ==========
    
    @Override
    public void onChunkReceived(String chunk) {
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            
            try {
                ChatSession session = ChatSessionManager.getInstance().getCurrentSession();
                String currentContent = session.getCurrentResponseContent();
                
                // 初始化 AI 助手前缀（如果需要）
                if (aiMessageStartPos < 0) {
                    StyledDocument doc = chatArea.getStyledDocument();
                    Style senderStyle = doc.addStyle("sender", null);
                    StyleConstants.setBold(senderStyle, true);
                    StyleConstants.setForeground(senderStyle, Color.GREEN);
                    doc.insertString(doc.getLength(), "AI助手: \n", senderStyle);
                    aiMessageStartPos = doc.getLength();
                    isStreaming = true;
                    sendButton.setEnabled(false);
                    stopButton.setEnabled(true);
                }
                
                // 渲染内容
                if (aiMessageStartPos >= 0 && !currentContent.isEmpty()) {
                    MarkdownRenderer.appendMarkdownStreaming(chatArea, currentContent, aiMessageStartPos);
                    chatArea.setCaretPosition(chatArea.getStyledDocument().getLength());
                }
            } catch (Exception e) {
                e("[ChatPanel#" + instanceId + "] onChunkReceived 失败", e);
            }
        });
    }
    
    @Override
    public void onResponseComplete(String fullResponseContent) {
        d("[ChatPanel#" + instanceId + "] onResponseComplete() 收到, isStreaming=" + isStreaming);
        
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            if (!isStreaming) return;
            
            try {
                if (aiMessageStartPos >= 0 && !fullResponseContent.isEmpty()) {
                    StyledDocument doc = chatArea.getStyledDocument();
                    int currentLength = doc.getLength();
                    if (currentLength > aiMessageStartPos) {
                        doc.remove(aiMessageStartPos, currentLength - aiMessageStartPos);
                    }
                    MarkdownRenderer.appendMarkdown(chatArea, fullResponseContent);
                    
                    Style messageStyle = doc.addStyle("message", null);
                    StyleConstants.setForeground(messageStyle, Color.BLACK);
                    doc.insertString(doc.getLength(), "\n\n", messageStyle);
                    
                    chatArea.setCaretPosition(doc.getLength());
                }
            } catch (Exception e) {
                e("[ChatPanel#" + instanceId + "] onResponseComplete 失败", e);
            } finally {
                aiMessageStartPos = -1;
                isStreaming = false;
                sendButton.setEnabled(true);
                stopButton.setEnabled(false);
            }
        });
    }
    
    @Override
    public void onStreamingStarted() {
        // 不在监听器中更新 UI，避免触发 Burp 重建 Editor
        // 按钮状态由 sendMessage() 直接管理
        d("[ChatPanel#" + instanceId + "] onStreamingStarted() 收到（忽略）");
    }
    
    @Override
    public void onStreamingStopped() {
        d("[ChatPanel#" + instanceId + "] onStreamingStopped() 收到, isStreaming=" + isStreaming);
        
        // 只有正在流式的 ChatPanel 才需要处理
        if (!isStreaming) return;
        
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            
            d("[ChatPanel#" + instanceId + "] 重置状态");
            isStreaming = false;
            aiMessageStartPos = -1;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }
    
    @Override
    public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            
            appendToChat("系统", "错误: " + errorMessage, false);
            isStreaming = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
            aiMessageStartPos = -1;
        });
    }
    
    @Override
    public void onUserMessageAdded(String message) {
        // 用户消息由 sendMessage() 直接处理，这里不需要重复添加
        // 但如果是其他地方添加的用户消息，这里可以显示
    }
    
    @Override
    public void onSessionChanged(String sessionId) {
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            
            // 会话切换，重新显示新会话的数据
            ChatSession session = ChatSessionManager.getInstance().getOrCreateSession(sessionId);
            initializeFromSession(session);
            safeLogOutput("[ChatPanel] 会话已切换: " + sessionId);
        });
    }
    
    @Override
    public void onSessionCleared() {
        SwingUtilities.invokeLater(() -> {
            if (!isComponentValid()) return;
            
            chatArea.setText("");
            aiMessageStartPos = -1;
        });
    }
    
    /**
     * 当组件从容器中移除时，取消监听器注册
     */
    @Override
    public void removeNotify() {
        d("[ChatPanel#" + instanceId + "] removeNotify() 被调用");
        ChatSessionManager.getInstance().removeListener(this);
        d("[ChatPanel#" + instanceId + "] 已取消监听器, 剩余=" + ChatSessionManager.getInstance().getListenerCount());
        super.removeNotify();
    }
    
    /**
     * 当组件添加到容器时调用
     * 关键修复：在这里恢复会话数据，而不是在构造函数中，避免UI操作触发Burp重建Editor
     */
    @Override
    public void addNotify() {
        super.addNotify();
        d("[ChatPanel#" + instanceId + "] addNotify() 被调用, isShowing=" + isShowing());
        
        // 延迟恢复会话数据，避免在构造函数中执行UI操作
        if (!sessionRestored) {
            SwingUtilities.invokeLater(() -> {
                ChatSessionManager manager = ChatSessionManager.getInstance();
                ChatSession session = manager.getCurrentSession();
                
                if (session != null && !session.isEmpty()) {
                    d("[ChatPanel#" + instanceId + "] 在 addNotify() 中恢复会话数据");
                    initializeFromSession(session);
                    sessionRestored = true;
                }
            });
        }
    }

    public ChatPanel(MontoyaApi api, AgentApiClient apiClient) {
        this.instanceId = ++instanceCounter;
        this.api = api;
        this.apiClient = apiClient;
        
        d("[ChatPanel#" + instanceId + "] 构造函数开始, 线程=" + Thread.currentThread().getName());
        
        initializeUI();
        
        // 从 ChatSessionManager 获取数据
        ChatSessionManager manager = ChatSessionManager.getInstance();
        ChatSession session = manager.getCurrentSession();
        
        // 注册为监听器
        manager.addListener(this);
        int listenerCount = manager.getListenerCount();
        
        d("[ChatPanel#" + instanceId + "] 已注册监听器, 总数=" + listenerCount);
        
        // 关键修复：不在构造函数中恢复会话数据，避免UI操作触发Burp重建Editor
        // 会话数据将在 addNotify() 中恢复，当组件真正添加到容器时
        
        d("[ChatPanel#" + instanceId + "] 构造函数完成");
    }
    
    /**
     * 从 session 同步初始化 UI 状态
     * 必须在 EDT 上执行
     */
    private void initializeFromSession(ChatSession session) {
        if (session == null) return;
        
        try {
            // 清空当前显示
            chatArea.setText("");
            aiMessageStartPos = -1;
            
            // 显示历史消息
            for (ChatMessage msg : session.getMessages()) {
                if (msg.isUser()) {
                    appendToChat("你", msg.getContent(), true);
                } else if (msg.isAssistant()) {
                    appendToChatWithMarkdown("AI助手", msg.getContent());
                }
            }
            
            // 如果正在流式响应，恢复流式状态
            // 关键修复：不在这里更新按钮状态，避免触发Burp重建Editor
            // 按钮状态将由 onChunkReceived() 在第一次收到数据时更新
            if (session.isStreaming()) {
                // 添加 AI 助手前缀
                StyledDocument doc = chatArea.getStyledDocument();
                Style senderStyle = doc.addStyle("sender", null);
                StyleConstants.setBold(senderStyle, true);
                StyleConstants.setForeground(senderStyle, Color.GREEN);
                doc.insertString(doc.getLength(), "AI助手: \n", senderStyle);
                aiMessageStartPos = doc.getLength();
                
                // 显示当前响应内容（如果有）
                String currentContent = session.getCurrentResponseContent();
                if (!currentContent.isEmpty()) {
                    MarkdownRenderer.appendMarkdownStreaming(chatArea, currentContent, aiMessageStartPos);
                }
                
                // 只设置内部状态，不更新按钮（避免触发Burp重建）
                isStreaming = true;
                // 按钮状态将在 onChunkReceived() 第一次收到数据时更新
                
                safeLogOutput("[ChatPanel] 恢复流式状态, aiMessageStartPos=" + aiMessageStartPos + 
                             ", 内容长度=" + currentContent.length());
            }
        } catch (Exception e) {
            safeLogError("[ChatPanel] initializeFromSession 失败: " + e.getMessage());
        }
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

        // 创建聊天区域
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        chatArea.setBackground(Color.WHITE);
        chatArea.setForeground(Color.BLACK);
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

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

        add(chatScrollPane, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage() {
        // 使用 ChatSessionManager 的全局状态检查，而不是本地状态
        boolean globalStreaming = ChatSessionManager.getInstance().isStreaming();
        d("[ChatPanel#" + instanceId + "] sendMessage() 开始, globalStreaming=" + globalStreaming + ", localStreaming=" + isStreaming);
        
        if (globalStreaming || isStreaming) {
            d("[ChatPanel#" + instanceId + "] 已在流式状态，忽略");
            return;
        }
        
        String message = inputField.getText().trim();
        String finalMessage = message.isEmpty() ? "请分析当前请求的安全风险" : message;
        
        d("[ChatPanel#" + instanceId + "] 用户消息: " + finalMessage);
        
        // 添加用户消息到显示
        if (!message.isEmpty()) {
            appendToChat("你", message, true);
            ChatSessionManager.getInstance().addUserMessage(message);
        }
        
        inputField.setText("");
        updateApiConfig();
        
        // 关键修复：不在 sendMessage() 中立即更新 UI 或设置状态，避免触发 Burp 重建 Editor
        // 所有状态更新和UI更新都将延迟到第一次收到 chunk 时（在 onChunkReceived() 中）
        // 这样可以避免在 sendMessage() 中执行任何可能触发 Burp 重建的操作
        
        d("[ChatPanel#" + instanceId + "] 准备调用 startStreaming()");
        
        currentWorker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    safeLogOutput("[ChatPanel] 开始调用 analyzeWithSessionManager");
                    
                    // 新架构：使用 ChatSessionManager
                    // 数据写入 ChatSessionManager，UI 通过监听器更新
                    ChatSessionManager manager = ChatSessionManager.getInstance();
                    
                    // 开始流式响应（通知其他监听器）
                    manager.startStreaming();
                    
                    try {
                        // 调用API：传递 HttpRequestResponse 对象以检测请求来源
                        apiClient.analyzeRequestStream(
                            currentRequest,
                            finalMessage,
                            chunk -> {
                                // 检查是否已取消
                                if (isCancelled() || apiClient.isStreamingCancelled()) {
                                    return;
                                }
                                
                                // 新架构：数据写入 ChatSessionManager
                                // ChatSessionManager 会通知所有监听器（包括这个 ChatPanel）
                                manager.appendChunk(chunk);
                            }
                        );
                        
                        // 完成响应
                        manager.finalizeResponse();
                        
                    } catch (Exception e) {
                        // 报告错误
                        manager.reportError(e.getMessage());
                        throw e;
                    }
                    
                    safeLogOutput("[ChatPanel] analyzeWithSessionManager 调用完成");
                    
                } catch (Exception e) {
                    safeLogError("[ChatPanel] 分析请求失败: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        appendToChat("系统", "抱歉，处理请求时出现错误: " + e.getMessage(), false);
                    });
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // 检查是否有异常
                    safeLogOutput("[ChatPanel] SwingWorker done() 被调用");
                } catch (java.util.concurrent.CancellationException e) {
                    // 用户取消，正常情况
                    safeLogOutput("[ChatPanel] 分析已被用户取消");
                } catch (Exception e) {
                    // 只有在非取消情况下才显示错误
                    if (!apiClient.isStreamingCancelled()) {
                        SwingUtilities.invokeLater(() -> {
                            appendToChat("系统", "抱歉，处理请求时出现错误: " + e.getMessage(), false);
                        });
                    }
                }
                // 注意：按钮状态由 onResponseComplete() 或 onStreamingStopped() 回调更新
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
            
            // 新架构：通知 ChatSessionManager 取消响应
            ChatSessionManager.getInstance().cancelResponse();
            
            // 添加中断提示
            appendToChat("系统", "[输出已中断]", false);
        }
        // 注意：按钮状态由 onStreamingStopped() 回调更新
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
            safeLogError("添加聊天消息失败: " + e.getMessage());
        }
    }

    private void clearContext() {
        // 如果正在输出，先停止
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }
        
        // 新架构：清空 ChatSessionManager 中的会话数据
        ChatSessionManager.getInstance().clearCurrentSession();
        
        // 清空 Assistant 的聊天记忆（共享实例）
        // 注意：apiClient.clearContext() 内部已经会先调用 cancelStreaming()
        apiClient.clearContext();
        
        // 注意：UI 清空由 onSessionCleared() 回调处理
        safeLogOutput("聊天上下文已清空");
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
}