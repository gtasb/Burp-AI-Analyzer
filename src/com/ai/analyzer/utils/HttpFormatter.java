package com.ai.analyzer.utils;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求/响应格式化工具类
 * 统一处理 HTTP 内容的格式化，避免代码重复
 */
public class HttpFormatter {
    
    /**
     * 格式化 HttpRequestResponse 为字符串
     * 格式：=== HTTP请求 ===\n[请求内容]\n\n=== HTTP响应 ===\n[响应内容]
     * 
     * @param requestResponse Burp 的 HttpRequestResponse 对象
     * @return 格式化后的字符串，如果没有请求则返回空字符串
     */
    public static String formatHttpRequestResponse(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== HTTP请求 ===\n");
        
        // 使用 UTF-8 编码正确解析中文字符
        byte[] requestBytes = requestResponse.request().toByteArray().getBytes();
        String requestStr = new String(requestBytes, StandardCharsets.UTF_8);
        sb.append(requestStr);
        
        if (requestResponse.response() != null) {
            sb.append("\n\n=== HTTP响应 ===\n");
            byte[] responseBytes = requestResponse.response().toByteArray().getBytes();
            String responseStr = new String(responseBytes, StandardCharsets.UTF_8);
            sb.append(responseStr);
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化字符串格式的请求和响应
     * 格式：=== HTTP请求 ===\n[请求内容]\n\n=== HTTP响应 ===\n[响应内容]
     * 
     * @param request 请求字符串
     * @param response 响应字符串（可为 null）
     * @return 格式化后的字符串
     */
    public static String formatHttpRequestResponse(String request, String response) {
        if (request == null || request.trim().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== HTTP请求 ===\n");
        sb.append(request);
        
        if (response != null && !response.trim().isEmpty()) {
            sb.append("\n\n=== HTTP响应 ===\n");
            sb.append(response);
        }
        
        return sb.toString();
    }
}

