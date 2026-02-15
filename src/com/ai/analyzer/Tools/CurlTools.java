package com.ai.analyzer.Tools;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Curl 风格的 HTTP 请求工具类 - 替代 MCP 中的 send_http1_request 和 send_http2_request
 * 
 * 设计目标：
 * - 更稳定：避免 MCP 工具的超时和格式错误问题
 * - 更简单：使用完整 URL，自动解析主机/端口/协议
 * - 更灵活：支持自定义请求头、请求体、超时时间
 * 
 * 优势对比：
 * - send_http1_request：需要手动拼接 HTTP 请求格式，容易出现格式错误（如缺少空行）
 * - CurlTools：使用 URL + headers + body 的方式，自动构造标准 HTTP 请求
 */
public class CurlTools {
    
    private final MontoyaApi api;
    
    // 默认超时时间（秒）
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    
    // 默认请求头
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    
    public CurlTools(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 发送 HTTP 请求（核心方法）- 类似 curl 命令
     * 
     * 使用示例：
     * curl_send_request(
     *   "http://example.com/api/test?param=value",
     *   "GET",
     *   ["Cookie: session=xxx", "Authorization: Bearer token"],
     *   null,
     *   30
     * )
     */
    @Tool(name = "curl_send_request", value = {
        "【参数说明】：",
        "- url: 完整的请求 URL（必须包含协议，例如：http://example.com/path?param=value）",
        "- method: HTTP 方法（GET, POST, PUT, DELETE, PATCH 等，默认 GET）",
        "- headers: 请求头列表，每个元素格式为 \"Header-Name: value\"（可选）",
        "- body: 请求体内容（可选，仅 POST/PUT/PATCH 等方法需要）",
        "- timeoutSeconds: 请求超时时间（秒），默认 30 秒",
        "【返回格式】：",
        "- 成功：HTTP 状态码 + 响应头 + 响应体",
        "- 失败：错误信息",
        "【示例】：",
        "curl_send_request(",
        "  \"http://example.com/api/login\",",
        "  \"POST\",",
        "  [\"Content-Type: application/json\", \"Cookie: session=abc123\"],",
        "  \"{\\\"username\\\":\\\"admin\\\",\\\"password\\\":\\\"test123\\\"}\",",
        "  30",
        ")"
    })
    public String sendRequest(
            @P("完整的请求 URL（必须包含协议，例如：http://example.com/path?param=value）") String url,
            @P("HTTP 方法（GET, POST, PUT, DELETE 等，默认 GET）") String method,
            @P("请求头列表，格式：[\"Header-Name: value\", ...]，可选") List<String> headers,
            @P("请求体内容（可选，仅 POST/PUT/PATCH 等方法需要）") String body,
            @P("请求超时时间（秒），默认 30 秒") Integer timeoutSeconds
    ) {
        try {
            // 1. 参数验证
            if (url == null || url.isEmpty()) {
                return "❌ 错误：URL 不能为空";
            }
            
            // 2. 解析 URL
            URL parsedUrl;
            try {
                parsedUrl = new URL(url);
            } catch (Exception e) {
                return "❌ 错误：URL 格式无效: " + url + "\n" +
                       "请确保 URL 包含协议（如 http:// 或 https://）";
            }
            
            String hostname = parsedUrl.getHost();
            int port = parsedUrl.getPort();
            boolean usesHttps = "https".equalsIgnoreCase(parsedUrl.getProtocol());
            
            // 如果端口未指定，使用默认端口
            if (port == -1) {
                port = usesHttps ? 443 : 80;
            }
            
            String path = parsedUrl.getPath();
            if (path.isEmpty()) {
                path = "/";
            }
            if (parsedUrl.getQuery() != null && !parsedUrl.getQuery().isEmpty()) {
                path = path + "?" + parsedUrl.getQuery();
            }
            
            // 3. 构造 HTTP 请求
            String httpMethod = (method != null && !method.isEmpty()) ? method.toUpperCase() : "GET";
            
            // 构造请求头
            List<HttpHeader> httpHeaders = new ArrayList<>();
            httpHeaders.add(HttpHeader.httpHeader("Host", hostname + (port == 80 || port == 443 ? "" : ":" + port)));
            httpHeaders.add(HttpHeader.httpHeader("User-Agent", DEFAULT_USER_AGENT));
            httpHeaders.add(HttpHeader.httpHeader("Accept", "*/*"));
            httpHeaders.add(HttpHeader.httpHeader("Connection", "close"));
            
            // 添加自定义请求头
            if (headers != null && !headers.isEmpty()) {
                for (String header : headers) {
                    if (header == null || header.isEmpty()) {
                        continue;
                    }
                    
                    int colonIndex = header.indexOf(':');
                    if (colonIndex > 0) {
                        String headerName = header.substring(0, colonIndex).trim();
                        String headerValue = header.substring(colonIndex + 1).trim();
                        
                        // 避免重复的 Host 头
                        if ("Host".equalsIgnoreCase(headerName)) {
                            // 移除之前添加的 Host 头
                            httpHeaders.removeIf(h -> "Host".equalsIgnoreCase(h.name()));
                        }
                        
                        httpHeaders.add(HttpHeader.httpHeader(headerName, headerValue));
                    }
                }
            }
            
            // 如果有请求体，添加 Content-Length 头（如果没有手动指定）
            if (body != null && !body.isEmpty()) {
                boolean hasContentLength = httpHeaders.stream()
                        .anyMatch(h -> "Content-Length".equalsIgnoreCase(h.name()));
                if (!hasContentLength) {
                    httpHeaders.add(HttpHeader.httpHeader("Content-Length", String.valueOf(body.getBytes().length)));
                }
                
                // 如果是 POST/PUT/PATCH 且没有 Content-Type，添加默认 Content-Type
                if (("POST".equals(httpMethod) || "PUT".equals(httpMethod) || "PATCH".equals(httpMethod))) {
                    boolean hasContentType = httpHeaders.stream()
                            .anyMatch(h -> "Content-Type".equalsIgnoreCase(h.name()));
                    if (!hasContentType) {
                        // 自动检测 Content-Type
                        String contentType = detectContentType(body);
                        httpHeaders.add(HttpHeader.httpHeader("Content-Type", contentType));
                    }
                }
            }
            
            // 4. 构造原始 HTTP 请求字符串
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append(httpMethod).append(" ").append(path).append(" HTTP/1.1\r\n");
            for (HttpHeader h : httpHeaders) {
                requestBuilder.append(h.name()).append(": ").append(h.value()).append("\r\n");
            }
            requestBuilder.append("\r\n");
            if (body != null && !body.isEmpty()) {
                requestBuilder.append(body);
            }
            String rawRequest = requestBuilder.toString();
            
            // 5. 创建 HttpService 和 HttpRequest
            HttpService httpService = HttpService.httpService(hostname, port, usesHttps);
            HttpRequest httpRequest = HttpRequest.httpRequest(httpService, rawRequest);
            
            // 6. 发送请求（api.http().sendRequest 返回 HttpRequestResponse）
            long startTime = System.currentTimeMillis();
            HttpRequestResponse requestResponse;
            
            try {
                requestResponse = api.http().sendRequest(httpRequest);
            } catch (Exception e) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                return "❌ 请求失败: " + e.getMessage() + "\n" +
                       "耗时: " + elapsedMs + " ms\n" +
                       "URL: " + url;
            }
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            
            // 7. 从 HttpRequestResponse 中提取 HttpResponse
            HttpResponse httpResponse = requestResponse.response();
            if (httpResponse == null) {
                return "❌ 未收到响应\n" +
                       "耗时: " + elapsedMs + " ms\n" +
                       "URL: " + url;
            }
            
            // 8. 格式化响应
            return formatResponse(httpRequest, httpResponse, elapsedMs);
            
        } catch (Exception e) {
            String errorMsg = "发送请求时发生异常: " + e.getMessage();
            api.logging().logToError("[CurlTools] " + errorMsg);
            e.printStackTrace();
            return "❌ " + errorMsg;
        }
    }
    
    /**
     * 发送 GET 请求（简化版）
     */
    @Tool(name = "curl_get", value = {
        "【快捷工具】发送 GET 请求（简化版，只需 URL 和 headers）。",
        "适用于简单的 GET 请求场景。",
        "参数：",
        "- url: 完整的请求 URL（必须包含协议）",
        "- headers: 请求头列表（可选）"
    })
    public String get(
            @P("完整的请求 URL（必须包含协议）") String url,
            @P("请求头列表，格式：[\"Header-Name: value\", ...]，可选") List<String> headers
    ) {
        return sendRequest(url, "GET", headers, null, DEFAULT_TIMEOUT_SECONDS);
    }
    
    /**
     * 发送 POST 请求（简化版）
     */
    @Tool(name = "curl_post", value = {
        "【快捷工具】发送 POST 请求（简化版）。",
        "适用于简单的 POST 请求场景。",
        "参数：",
        "- url: 完整的请求 URL（必须包含协议）",
        "- body: 请求体内容",
        "- headers: 请求头列表（可选）"
    })
    public String post(
            @P("完整的请求 URL（必须包含协议）") String url,
            @P("请求体内容") String body,
            @P("请求头列表，格式：[\"Header-Name: value\", ...]，可选") List<String> headers
    ) {
        return sendRequest(url, "POST", headers, body, DEFAULT_TIMEOUT_SECONDS);
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 格式化响应内容
     */
    private String formatResponse(HttpRequest request, HttpResponse response, long elapsedMs) {
        StringBuilder result = new StringBuilder();
        
        // 请求信息
        result.append("📤 请求信息:\n");
        result.append(request.method()).append(" ").append(request.path()).append("\n");
        result.append("目标: ").append(request.httpService().host())
              .append(":").append(request.httpService().port()).append("\n");
        result.append("耗时: ").append(elapsedMs).append(" ms\n\n");
        
        // 响应状态
        result.append("📥 响应:\n");
        result.append("状态码: ").append(response.statusCode()).append("\n");
        
        // 响应头
        result.append("\n响应头:\n");
        for (HttpHeader header : response.headers()) {
            result.append("  ").append(header.name()).append(": ").append(header.value()).append("\n");
        }
        
        // 响应体
        String responseBody = response.bodyToString();
        result.append("\n响应体长度: ").append(responseBody.length()).append(" 字节\n");
        
        if (responseBody.length() > 0) {
            result.append("\n响应体内容:\n");
            result.append("```\n");
            
            // 如果响应体过大，截断显示
            if (responseBody.length() > 5000) {
                result.append(responseBody, 0, 5000);
                result.append("\n\n... (响应体过大，已截断，完整内容共 ")
                      .append(responseBody.length()).append(" 字节)\n");
            } else {
                result.append(responseBody);
            }
            
            result.append("\n```");
        }
        
        return result.toString();
    }
    
    /**
     * 自动检测 Content-Type
     */
    private String detectContentType(String body) {
        if (body == null || body.isEmpty()) {
            return "text/plain";
        }
        
        String trimmed = body.trim();
        
        // JSON 格式
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return "application/json";
        }
        
        // XML 格式
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return "application/xml";
        }
        
        // 表单格式（key=value&key2=value2）
        if (trimmed.matches("^[^=&]+=.+(&[^=&]+=.+)*$")) {
            return "application/x-www-form-urlencoded";
        }
        
        // 默认
        return "text/plain";
    }
}
