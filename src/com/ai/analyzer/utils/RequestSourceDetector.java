package com.ai.analyzer.utils;

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
                sb.append("（索引（offset）：").append(index).append("）");
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
        
        // 1. 检查是否在 proxy HTTP history 中
        Integer httpHistoryIndex = findInProxyHttpHistory(api, requestResponse);
        if (httpHistoryIndex != null) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("proxy-http-history", httpHistoryIndex, inEditor);
        }
        
        // 2. 检查是否在 proxy WebSocket history 中
        Integer wsHistoryIndex = findInProxyWebSocketHistory(api, requestResponse);
        if (wsHistoryIndex != null) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("proxy-websocket-history", wsHistoryIndex, inEditor);
        }
        
        // 3. 检查是否在 proxy intercept 中
        if (isProxyIntercept(api, requestResponse)) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("proxy-intercept", null, inEditor);
        }
        
        // 4. 检查是否在 repeater 中
        if (isRepeater(api, requestResponse)) {
            boolean inEditor = checkActiveEditor(api);
            return new RequestSourceInfo("repeater", null, inEditor);
        }
        
        // 5. 默认：未知来源，但检查是否在活动编辑器中
        boolean inEditor = checkActiveEditor(api);
        return new RequestSourceInfo("未知", null, inEditor);
    }
    
    /**
     * 在 proxy HTTP history 中查找请求，返回索引（offset）
     * 注意：ID 和索引（offset）是不同的概念
     * - ID 是请求的唯一标识符，不会因为 history 中其他请求的删除而改变
     * - 索引（offset）是请求在 history 列表中的位置（从 0 开始），会随着列表变化而变化
     */
    private static Integer findInProxyHttpHistory(MontoyaApi api, HttpRequestResponse requestResponse) {
        try {
            List<ProxyHttpRequestResponse> history = api.proxy().history();
            
            // 如果 requestResponse 本身就是 ProxyHttpRequestResponse，直接使用 indexOf 获取索引
            if (requestResponse instanceof ProxyHttpRequestResponse) {
                ProxyHttpRequestResponse proxyItem = (ProxyHttpRequestResponse) requestResponse;
                int index = history.indexOf(proxyItem);
                if (index >= 0) {
                    return index; // 返回索引（offset）
                }
                // 如果 indexOf 返回 -1（未找到），返回 null，不携带索引
                return null;
            } else {
                // 如果不是 ProxyHttpRequestResponse，通过内容比较来查找
                byte[] targetRequestBytes = requestResponse.request().toByteArray().getBytes();
                
                for (int i = 0; i < history.size(); i++) {
                    ProxyHttpRequestResponse item = history.get(i);
                    if (item.request() != null) {
                        byte[] itemRequestBytes = item.request().toByteArray().getBytes();
                        // 简单比较：如果请求内容相同，认为是同一个请求
                        if (java.util.Arrays.equals(targetRequestBytes, itemRequestBytes)) {
                            return i; // 返回索引（offset）
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误，返回 null
        }
        return null;
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
     * 在 proxy WebSocket history 中查找 HTTP 请求（通过升级请求匹配），返回索引
     * 注意：这是为了兼容 HTTP 请求响应对象，通过升级请求来查找对应的 WebSocket 消息
     */
    private static Integer findInProxyWebSocketHistory(MontoyaApi api, HttpRequestResponse requestResponse) {
        try {
            // 如果 requestResponse 有对应的 WebSocket 升级请求，可以通过升级请求来查找
            // 但这个方法主要用于 HTTP 请求响应，WebSocket 消息应该使用 detectWebSocketSource
            // 暂时返回 null，因为 WebSocket history 通常不直接包含 HTTP 请求响应
            return null;
        } catch (Exception e) {
            // 忽略错误
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
     * 检查是否在 proxy intercept 中
     */
    private static boolean isProxyIntercept(MontoyaApi api, HttpRequestResponse requestResponse) {
        try {
            // 检查 requestResponse 是否是 InterceptedRequest/InterceptedResponse 类型
            // 通过检查类名来判断（因为接口可能不直接继承）
            String className = requestResponse.getClass().getName();
            if (className.contains("InterceptedRequest") || className.contains("InterceptedResponse")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检查是否在 repeater 中
     */
    private static boolean isRepeater(MontoyaApi api, HttpRequestResponse requestResponse) {
        try {
            // 通过类名检查（RepeaterRequestResponse 类不存在，只能通过类名判断）
            String className = requestResponse.getClass().getName();
            if (className.contains("RepeaterRequestResponse") || className.contains("Repeater")) {
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

