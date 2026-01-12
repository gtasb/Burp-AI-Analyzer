package com.ai.analyzer.manager;

import com.ai.analyzer.model.ChatSession;
import com.ai.analyzer.listener.ChatUpdateListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 聊天会话管理器 - 全局单例
 * 
 * 核心职责：
 * 1. 管理所有聊天会话的数据
 * 2. 通知所有监听器（ChatPanel）数据变化
 * 3. 与 UI 层完全解耦
 * 
 * 设计原则：
 * - 线程安全（使用 ConcurrentHashMap 和 CopyOnWriteArrayList）
 * - 观察者模式（监听器通知机制）
 * - 单例模式（全局唯一实例）
 */
public class ChatSessionManager {
    
    // 单例实例
    private static volatile ChatSessionManager instance;
    
    // 会话存储
    private final Map<String, ChatSession> sessions;
    
    // 当前活动会话 ID
    private volatile String currentSessionId;
    
    // 监听器列表（使用 CopyOnWriteArrayList 保证线程安全和迭代安全）
    private final List<ChatUpdateListener> listeners;
    
    // 默认会话 ID
    public static final String DEFAULT_SESSION_ID = "default";
    
    private ChatSessionManager() {
        this.sessions = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.currentSessionId = DEFAULT_SESSION_ID;
        
        // 创建默认会话
        sessions.put(DEFAULT_SESSION_ID, new ChatSession(DEFAULT_SESSION_ID));
    }
    
    /**
     * 获取单例实例
     */
    public static ChatSessionManager getInstance() {
        if (instance == null) {
            synchronized (ChatSessionManager.class) {
                if (instance == null) {
                    instance = new ChatSessionManager();
                }
            }
        }
        return instance;
    }
    
    // ========== 会话管理 ==========
    
    /**
     * 获取当前会话
     */
    public ChatSession getCurrentSession() {
        return getOrCreateSession(currentSessionId);
    }
    
    /**
     * 获取或创建指定会话
     */
    public ChatSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ChatSession::new);
    }
    
    /**
     * 设置当前会话
     */
    public void setCurrentSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = DEFAULT_SESSION_ID;
        }
        this.currentSessionId = sessionId;
        getOrCreateSession(sessionId); // 确保会话存在
        notifySessionChanged(sessionId);
    }
    
    /**
     * 获取当前会话 ID
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }
    
    // ========== 数据操作（会被 AgentApiClient 调用） ==========
    
    /**
     * 添加用户消息
     */
    public void addUserMessage(String message) {
        ChatSession session = getCurrentSession();
        session.addUserMessage(message);
        notifyUserMessageAdded(message);
    }
    
    /**
     * 开始 AI 响应（流式）
     */
    public void startStreaming() {
        ChatSession session = getCurrentSession();
        session.startAiResponse();
        notifyStreamingStarted();
    }
    
    /**
     * 追加流式响应片段
     */
    public void appendChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        ChatSession session = getCurrentSession();
        session.appendChunk(chunk);
        notifyChunkReceived(chunk);
    }
    
    /**
     * 完成 AI 响应
     */
    public void finalizeResponse() {
        ChatSession session = getCurrentSession();
        String fullResponse = session.getCurrentResponseContent();
        session.finalizeAiResponse();
        notifyResponseComplete(fullResponse);
    }
    
    /**
     * 取消当前响应
     */
    public void cancelResponse() {
        ChatSession session = getCurrentSession();
        session.cancelResponse();
        notifyStreamingStopped();
    }
    
    /**
     * 报告错误
     */
    public void reportError(String error) {
        ChatSession session = getCurrentSession();
        session.setError(error);
        notifyError(error);
    }
    
    /**
     * 清空当前会话
     */
    public void clearCurrentSession() {
        ChatSession session = getCurrentSession();
        session.clear();
        notifySessionCleared();
    }
    
    // ========== UI 辅助方法 ==========
    
    /**
     * 设置 AI 消息在 UI 中的起始位置
     */
    public void setAiMessageStartPos(int pos) {
        getCurrentSession().setAiMessageStartPos(pos);
    }
    
    /**
     * 获取 AI 消息在 UI 中的起始位置
     */
    public int getAiMessageStartPos() {
        return getCurrentSession().getAiMessageStartPos();
    }
    
    /**
     * 重置 AI 消息位置
     */
    public void resetAiMessageStartPos() {
        getCurrentSession().resetAiMessageStartPos();
    }
    
    /**
     * 当前是否正在流式响应
     */
    public boolean isStreaming() {
        return getCurrentSession().isStreaming();
    }
    
    // ========== 监听器管理 ==========
    
    /**
     * 添加监听器
     */
    public void addListener(ChatUpdateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    /**
     * 移除监听器
     */
    public void removeListener(ChatUpdateListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 获取监听器数量（用于调试）
     */
    public int getListenerCount() {
        return listeners.size();
    }
    
    // ========== 通知方法 ==========
    
    private void notifyChunkReceived(String chunk) {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onChunkReceived(chunk);
            } catch (Exception e) {
                // 忽略单个监听器的异常，不影响其他监听器
            }
        }
    }
    
    private void notifyResponseComplete(String fullResponse) {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onResponseComplete(fullResponse);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifyStreamingStarted() {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onStreamingStarted();
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifyStreamingStopped() {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onStreamingStopped();
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifyError(String error) {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onError(error);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifyUserMessageAdded(String message) {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onUserMessageAdded(message);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifySessionChanged(String sessionId) {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onSessionChanged(sessionId);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    private void notifySessionCleared() {
        for (ChatUpdateListener listener : listeners) {
            try {
                listener.onSessionCleared();
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
}
