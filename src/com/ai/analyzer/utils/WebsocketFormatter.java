package com.ai.analyzer.utils;

import burp.api.montoya.proxy.ProxyWebSocketMessage;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.websocket.Direction;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 消息格式化工具类
 * 统一处理 WebSocket 消息的格式化，避免代码重复
 */
public class WebsocketFormatter {
    
    /**
     * 格式化 ProxyWebSocketMessage 为字符串
     * 格式：=== WebSocket消息 ===\n方向: [CLIENT_TO_SERVER/SERVER_TO_CLIENT]\n[消息内容]
     * 
     * @param webSocketMessage Burp 的 ProxyWebSocketMessage 对象
     * @return 格式化后的字符串，如果消息为 null 则返回空字符串
     */
    public static String formatProxyWebSocketMessage(ProxyWebSocketMessage webSocketMessage) {
        if (webSocketMessage == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== WebSocket消息 ===\n");
        
        // 添加方向信息
        Direction direction = webSocketMessage.direction();
        String directionStr = direction == Direction.CLIENT_TO_SERVER ? "客户端 → 服务器" : "服务器 → 客户端";
        sb.append("方向: ").append(directionStr).append("\n");
        
        // 添加时间信息
        if (webSocketMessage.time() != null) {
            sb.append("时间: ").append(webSocketMessage.time().toString()).append("\n");
        }
        
        // 添加 WebSocket ID
        sb.append("WebSocket ID: ").append(webSocketMessage.webSocketId()).append("\n");
        
        // 添加消息 ID
        sb.append("消息 ID: ").append(webSocketMessage.id()).append("\n");
        
        // 添加升级请求信息（如果有）
        if (webSocketMessage.upgradeRequest() != null) {
            sb.append("升级请求: ").append(webSocketMessage.upgradeRequest().url()).append("\n");
        }
        
        sb.append("\n");
        
        // 获取 payload
        burp.api.montoya.core.ByteArray payload = webSocketMessage.payload();
        if (payload != null && payload.length() > 0) {
            // 尝试作为文本解析
            try {
                String textPayload = payload.toString();
                // 检查是否包含可打印字符（简单判断是否为文本）
                boolean isText = true;
                for (int i = 0; i < Math.min(textPayload.length(), 100); i++) {
                    char c = textPayload.charAt(i);
                    if (c < 32 && c != 9 && c != 10 && c != 13) { // 排除制表符、换行符、回车符
                        isText = false;
                        break;
                    }
                }
                
                if (isText) {
                    sb.append("消息内容（文本）:\n");
                    sb.append(textPayload);
                } else {
                    sb.append("消息内容（二进制，长度: ").append(payload.length()).append(" 字节）");
                }
            } catch (Exception e) {
                // 如果解析失败，作为二进制处理
                sb.append("消息内容（二进制，长度: ").append(payload.length()).append(" 字节）");
            }
        } else {
            sb.append("消息内容: （空）");
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化 WebSocketMessage 为字符串
     * 格式：=== WebSocket消息 ===\n方向: [CLIENT_TO_SERVER/SERVER_TO_CLIENT]\n[消息内容]
     * 
     * @param webSocketMessage WebSocket 消息对象
     * @return 格式化后的字符串，如果消息为 null 则返回空字符串
     */
    public static String formatWebSocketMessage(WebSocketMessage webSocketMessage) {
        if (webSocketMessage == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== WebSocket消息 ===\n");
        
        // 添加方向信息
        Direction direction = webSocketMessage.direction();
        String directionStr = direction == Direction.CLIENT_TO_SERVER ? "客户端 → 服务器" : "服务器 → 客户端";
        sb.append("方向: ").append(directionStr).append("\n");
        
        // 添加升级请求信息（如果有）
        if (webSocketMessage.upgradeRequest() != null) {
            sb.append("升级请求: ").append(webSocketMessage.upgradeRequest().url()).append("\n");
        }
        
        sb.append("\n");
        
        // 获取 payload
        burp.api.montoya.core.ByteArray payload = webSocketMessage.payload();
        if (payload != null && payload.length() > 0) {
            // 尝试作为文本解析
            try {
                String textPayload = payload.toString();
                // 检查是否包含可打印字符（简单判断是否为文本）
                boolean isText = true;
                for (int i = 0; i < Math.min(textPayload.length(), 100); i++) {
                    char c = textPayload.charAt(i);
                    if (c < 32 && c != 9 && c != 10 && c != 13) { // 排除制表符、换行符、回车符
                        isText = false;
                        break;
                    }
                }
                
                if (isText) {
                    sb.append("消息内容（文本）:\n");
                    sb.append(textPayload);
                } else {
                    sb.append("消息内容（二进制，长度: ").append(payload.length()).append(" 字节）");
                }
            } catch (Exception e) {
                // 如果解析失败，作为二进制处理
                sb.append("消息内容（二进制，长度: ").append(payload.length()).append(" 字节）");
            }
        } else {
            sb.append("消息内容: （空）");
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化字符串格式的 WebSocket 消息
     * 格式：=== WebSocket消息 ===\n方向: [CLIENT_TO_SERVER/SERVER_TO_CLIENT]\n[消息内容]
     * 
     * @param direction 方向字符串（"CLIENT_TO_SERVER" 或 "SERVER_TO_CLIENT"）
     * @param payload 消息负载（字符串）
     * @param isText 是否为文本消息
     * @return 格式化后的字符串
     */
    public static String formatWebSocketMessage(String direction, String payload, boolean isText) {
        if (payload == null || payload.trim().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== WebSocket消息 ===\n");
        sb.append("方向: ").append(direction).append("\n");
        sb.append("\n");
        
        if (isText) {
            sb.append("消息内容（文本）:\n");
            sb.append(payload);
        } else {
            sb.append("消息内容（二进制）:\n");
            sb.append(payload);
        }
        
        return sb.toString();
    }
}

