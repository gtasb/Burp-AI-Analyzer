package com.ai.analyzer.listener;

/**
 * 聊天更新监听器接口
 * 
 * ChatPanel 实现此接口，接收来自 ChatSessionManager 的数据更新
 * 这实现了数据层和 UI 层的解耦
 */
public interface ChatUpdateListener {
    
    /**
     * 收到流式响应的片段
     * @param chunk 响应片段
     */
    void onChunkReceived(String chunk);
    
    /**
     * AI 响应完成
     * @param fullResponse 完整响应内容
     */
    void onResponseComplete(String fullResponse);
    
    /**
     * 流式响应开始
     */
    void onStreamingStarted();
    
    /**
     * 流式响应停止（用户取消或完成）
     */
    void onStreamingStopped();
    
    /**
     * 发生错误
     * @param errorMessage 错误消息
     */
    void onError(String errorMessage);
    
    /**
     * 用户消息已添加
     * @param message 用户消息内容
     */
    void onUserMessageAdded(String message);
    
    /**
     * 会话切换
     * @param sessionId 新的会话 ID
     */
    void onSessionChanged(String sessionId);
    
    /**
     * 会话被清空
     */
    void onSessionCleared();
}
