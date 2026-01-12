package com.ai.analyzer.model;

/**
 * 聊天消息数据模型
 * 存储单条消息的角色和内容
 * 
 * 设计原则：
 * - 不可变对象（线程安全）
 * - 与 UI 完全解耦
 */
public class ChatMessage {
    private final String role;    // "user", "assistant", "system"
    private final String content;
    private final long timestamp;
    
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }
    
    public ChatMessage(String role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }
    
    public String getRole() {
        return role;
    }
    
    public String getContent() {
        return content;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public boolean isUser() {
        return "user".equals(role);
    }
    
    public boolean isAssistant() {
        return "assistant".equals(role);
    }
    
    public boolean isSystem() {
        return "system".equals(role);
    }
    
    @Override
    public String toString() {
        return role + ": " + content;
    }
}
