package com.ai.analyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聊天会话数据模型
 * 存储单个会话的所有消息和当前流式响应
 * 
 * 设计原则：
 * - 与 UI 完全解耦
 * - 线程安全
 * - 支持流式响应
 */
public class ChatSession {
    private final String sessionId;
    private final List<ChatMessage> messages;  // 使用 model.ChatMessage
    private final StringBuilder currentResponse;
    private volatile boolean isStreaming;
    private volatile int aiMessageStartPos;
    private volatile String lastError;
    
    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.messages = Collections.synchronizedList(new ArrayList<>());
        this.currentResponse = new StringBuilder();
        this.isStreaming = false;
        this.aiMessageStartPos = -1;
        this.lastError = null;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    // ========== 消息管理 ==========
    
    /**
     * 添加用户消息
     */
    public synchronized void addUserMessage(String content) {
        messages.add(new ChatMessage("user", content));
    }
    
    /**
     * 开始 AI 响应（流式）
     */
    public synchronized void startAiResponse() {
        currentResponse.setLength(0);
        isStreaming = true;
        lastError = null;
    }
    
    /**
     * 追加流式响应的片段
     */
    public synchronized void appendChunk(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            currentResponse.append(chunk);
        }
    }
    
    /**
     * 完成 AI 响应
     */
    public synchronized void finalizeAiResponse() {
        if (currentResponse.length() > 0) {
            messages.add(new ChatMessage("assistant", currentResponse.toString()));
        }
        currentResponse.setLength(0);
        isStreaming = false;
    }
    
    /**
     * 取消当前响应
     */
    public synchronized void cancelResponse() {
        if (isStreaming) {
            // 保留已接收的部分响应
            if (currentResponse.length() > 0) {
                messages.add(new ChatMessage("assistant", currentResponse.toString() + "\n\n[已取消]"));
            }
            currentResponse.setLength(0);
            isStreaming = false;
        }
    }
    
    /**
     * 记录错误
     */
    public synchronized void setError(String error) {
        this.lastError = error;
        if (isStreaming) {
            if (currentResponse.length() > 0) {
                messages.add(new ChatMessage("assistant", currentResponse.toString() + "\n\n[错误: " + error + "]"));
            } else {
                messages.add(new ChatMessage("assistant", "[错误: " + error + "]"));
            }
            currentResponse.setLength(0);
            isStreaming = false;
        }
    }
    
    // ========== 获取数据 ==========
    
    /**
     * 获取所有消息（只读）
     */
    public List<ChatMessage> getMessages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }
    
    /**
     * 获取当前流式响应内容
     */
    public synchronized String getCurrentResponseContent() {
        return currentResponse.toString();
    }
    
    /**
     * 是否正在流式响应
     */
    public boolean isStreaming() {
        return isStreaming;
    }
    
    /**
     * 获取最后的错误
     */
    public String getLastError() {
        return lastError;
    }
    
    // ========== UI 辅助 ==========
    
    /**
     * 设置 AI 消息在 UI 中的起始位置（用于流式渲染）
     */
    public void setAiMessageStartPos(int pos) {
        this.aiMessageStartPos = pos;
    }
    
    /**
     * 获取 AI 消息在 UI 中的起始位置
     */
    public int getAiMessageStartPos() {
        return aiMessageStartPos;
    }
    
    /**
     * 重置 AI 消息位置
     */
    public void resetAiMessageStartPos() {
        this.aiMessageStartPos = -1;
    }
    
    /**
     * 清空会话
     */
    public synchronized void clear() {
        messages.clear();
        currentResponse.setLength(0);
        isStreaming = false;
        aiMessageStartPos = -1;
        lastError = null;
    }
    
    /**
     * 获取消息数量
     */
    public int getMessageCount() {
        return messages.size();
    }
    
    /**
     * 会话是否为空
     */
    public boolean isEmpty() {
        return messages.isEmpty() && currentResponse.length() == 0;
    }
}
