package com.ai.analyzer.util;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.proxy.ProxyWebSocketMessage;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.websocket.Direction;
import java.util.List;

/**
 * 可能的请求来源检测工具类
 * 用于检测 HTTP 请求的可能来源（proxy history, proxy intercept, repeater 等）
 */
public class RequestSourceDetector {
    
    /**
     * 可能的请求来源信息
     */
    public static class RequestSourceInfo {
        private final String source;
        private final Integer index; // 在 history 中的索引（offset）
        private final boolean inActiveEditor;
        
        public RequestSourceInfo(String source, Integer index, boolean inActiveEditor) {
            this.source = source;
            this.index = index;
            this.inActiveEditor = inActiveEditor;
        }
        
        public String getSource() {
            return source;
        }
        
        public Integer getIndex() {
            return index;
        }
        
        public boolean isInActiveEditor() {
            return inActiveEditor;
        }
        
        /**
         * 格式化为字符串，用于添加到 user prompt
         */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("可能的请求来源：").append(source);
            
            if (index != null) {
                sb.append("（history id：").append(index).append("）");
            }
            
            if (inActiveEditor) {
                sb.append("，当前在活动消息编辑器中");
            }
            
            return sb.toString();
        }
    }
    
    /**
     * 检测可能的请求来源
     * 
     * @param api MontoyaApi 实例
     * @param requestResponse HTTP 请求响应对象（可为 null）
     * @return 可能的请求来源信息
     */
    public static RequestSourceInfo detectSource(MontoyaApi api, HttpRequestResponse requestResponse) {
        if (api == null || requestResponse == null) {
            return new RequestSourceInfo("未知", null, false);
        }

        String className = requestResponse.getClass().getName();

        // 1. 拦截器 / Repeater：类名判断，零成本
        if (className.contains("InterceptedRequest") || className.contains("InterceptedResponse")) {
            return new RequestSourceInfo("proxy-intercept", null, checkActiveEditor(api));
        }
        if (className.contains("RepeaterRequestResponse") || className.contains("Repeater")) {
            return new RequestSourceInfo("repeater", null, checkActiveEditor(api));
        }

        // 2. Proxy history：ProxyHttpRequestResponse 自带稳定 id，**不要**再调用
        //    api.proxy().history()。旧实现每次都把整个历史列表取出来，甚至逐条
        //    toByteArray() 比对，历史几万条时每次分析都会卡住并产生大量临时对象。
        if (requestResponse instanceof ProxyHttpRequestResponse proxyItem) {
            return new RequestSourceInfo("proxy-http-history", proxyItem.id(), checkActiveEditor(api));
        }

        return new RequestSourceInfo("未知", null, checkActiveEditor(api));
    }
    
    /**
     * 检测可能的 WebSocket 消息来源
     * 
     * @param api MontoyaApi 实例
     * @param webSocketMessage WebSocket 消息对象（可为 null）
     * @return 可能的请求来源信息
     */
    public static RequestSourceInfo detectWebSocketSource(MontoyaApi api, WebSocketMessage webSocketMessage) {
        if (api == null || webSocketMessage == null) {
            return new RequestSourceInfo("未知", null, false);
        }
        
        // 1. 检查是否在 proxy WebSocket history 中
        Integer wsHistoryIndex = findInProxyWebSocketHistory(api, webSocketMessage);
        if (wsHistoryIndex != null) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("proxy-websocket-history", wsHistoryIndex, inEditor);
        }
        
        // 2. 检查是否在 proxy intercept 中（WebSocket 拦截）
        if (isProxyWebSocketIntercept(api, webSocketMessage)) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("proxy-websocket-intercept", null, inEditor);
        }
        
        // 3. 默认：未知来源，但检查是否在活动编辑器中
        boolean inEditor = checkActiveEditor(api);
        return new RequestSourceInfo("未知", null, inEditor);
    }
    
    /**
     * 在 proxy WebSocket history 中查找 WebSocket 消息，返回索引（offset）
     * 注意：ID 和索引（offset）是不同的概念
     * - ID 是消息的唯一标识符，不会因为 history 中其他消息的删除而改变
     * - 索引（offset）是消息在 history 列表中的位置（从 0 开始），会随着列表变化而变化
     */
    private static Integer findInProxyWebSocketHistory(MontoyaApi api, WebSocketMessage webSocketMessage) {
        try {
            List<ProxyWebSocketMessage> history = api.proxy().webSocketHistory();
            
            // 如果 webSocketMessage 本身就是 ProxyWebSocketMessage，直接使用 indexOf 获取索引
            if (webSocketMessage instanceof ProxyWebSocketMessage) {
                ProxyWebSocketMessage proxyMessage = (ProxyWebSocketMessage) webSocketMessage;
                int index = history.indexOf(proxyMessage);
                if (index >= 0) {
                    return index; // 返回索引（offset）
                }
                // 如果 indexOf 返回 -1（未找到），返回 null，不携带索引
                return null;
            } else {
                // 如果不是 ProxyWebSocketMessage，通过内容和方向比较来查找
                burp.api.montoya.core.ByteArray targetPayload = webSocketMessage.payload();
                Direction targetDirection = webSocketMessage.direction();
                
                for (int i = 0; i < history.size(); i++) {
                    ProxyWebSocketMessage item = history.get(i);
                    // 比较方向和 payload
                    if (item.direction() == targetDirection && 
                        item.payload() != null && targetPayload != null &&
                        item.payload().equals(targetPayload)) {
                        return i; // 返回索引（offset）
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误，返回 null
        }
        return null;
    }
    
    /**
     * 检查是否在 proxy WebSocket intercept 中
     */
    private static boolean isProxyWebSocketIntercept(MontoyaApi api, WebSocketMessage webSocketMessage) {
        try {
            // 检查 webSocketMessage 是否是 InterceptedTextMessage/InterceptedBinaryMessage 类型
            // 通过检查类名来判断（因为接口可能不直接继承）
            String className = webSocketMessage.getClass().getName();
            if (className.contains("InterceptedTextMessage") || 
                className.contains("InterceptedBinaryMessage") ||
                className.contains("Intercepted")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检查当前是否在活动消息编辑器中
     * 通过尝试获取活动编辑器内容来判断
     */
    private static boolean checkActiveEditor(MontoyaApi api) {
        try {
            // 这里可以通过 MCP 工具来检查，但为了简化，暂时返回 false
            // 实际实现可以通过检查焦点组件来判断
            // 或者通过 MCP 的 get_active_editor_contents 工具
            return false; // 暂时返回 false，需要更复杂的实现
        } catch (Exception e) {
            return false;
        }
    }
}

