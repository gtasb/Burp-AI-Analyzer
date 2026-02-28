package com.ai.analyzer.utils;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求/响应格式化工具类
 * 统一处理 HTTP 内容的格式化，避免代码重复
 */
public class HttpFormatter {
    
    public static final int DEFAULT_MAX_LENGTH = 15000;
    
    /**
     * HTTP内容压缩结果
     */
    public static class CompressResult {
        public final String content;
        public final boolean wasCompressed;
        public final int originalLength;
        public final int compressedLength;
        
        public CompressResult(String content, boolean wasCompressed, int originalLength, int compressedLength) {
            this.content = content;
            this.wasCompressed = wasCompressed;
            this.originalLength = originalLength;
            this.compressedLength = compressedLength;
        }
    }
    
    /**
     * 如果HTTP内容过长则压缩，保留请求头完整，截断请求体和响应体
     */
    public static CompressResult compressIfTooLong(String httpContent) {
        return compressIfTooLong(httpContent, DEFAULT_MAX_LENGTH);
    }
    
    public static CompressResult compressIfTooLong(String httpContent, int maxTotalLength) {
        if (httpContent == null) {
            return new CompressResult("", false, 0, 0);
        }
        if (httpContent.length() <= maxTotalLength) {
            return new CompressResult(httpContent, false, httpContent.length(), httpContent.length());
        }
        
        int originalLength = httpContent.length();
        
        String requestSection = httpContent;
        String responseSection = null;
        
        int respIdx = httpContent.indexOf("=== HTTP响应 ===");
        if (respIdx > 0) {
            requestSection = httpContent.substring(0, respIdx).trim();
            responseSection = httpContent.substring(respIdx);
        }
        
        int halfLimit = maxTotalLength / 2;
        StringBuilder result = new StringBuilder();
        
        result.append(truncateSection(requestSection, responseSection != null ? halfLimit : maxTotalLength, "请求"));
        
        if (responseSection != null) {
            result.append("\n\n");
            result.append(truncateSection(responseSection, halfLimit, "响应"));
        }
        
        String compressed = result.toString();
        return new CompressResult(compressed, true, originalLength, compressed.length());
    }
    
    private static String truncateSection(String section, int maxLength, String sectionName) {
        if (section.length() <= maxLength) {
            return section;
        }
        
        int headerEnd = findHeaderBodySeparator(section);
        
        if (headerEnd > 0 && headerEnd < maxLength) {
            String headers = section.substring(0, headerEnd);
            String body = section.substring(headerEnd);
            int bodyLimit = maxLength - headers.length();
            
            if (body.length() > bodyLimit && bodyLimit > 0) {
                return headers + body.substring(0, bodyLimit)
                    + "\n\n...[" + sectionName + "体已截断，原始 " + body.length() + " 字符，保留前 " + bodyLimit + " 字符]";
            }
            return section;
        }
        
        return section.substring(0, maxLength)
            + "\n\n...[" + sectionName + "内容已截断，原始 " + section.length() + " 字符，保留前 " + maxLength + " 字符]";
    }
    
    private static int findHeaderBodySeparator(String content) {
        int firstLine = content.indexOf('\n');
        if (firstLine < 0) return -1;
        
        int dblNewline = content.indexOf("\r\n\r\n", firstLine);
        if (dblNewline > 0) return dblNewline + 4;
        
        dblNewline = content.indexOf("\n\n", firstLine);
        if (dblNewline > 0) return dblNewline + 2;
        
        return -1;
    }

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

