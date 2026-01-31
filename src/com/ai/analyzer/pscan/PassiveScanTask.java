package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 被动扫描任务
 * 
 * 每个任务负责扫描一个HTTP请求/响应对
 * 实现Callable接口，支持返回结果和异常处理
 */
public class PassiveScanTask implements Callable<ScanResult> {
    
    private final ScanResult scanResult;
    private final PassiveScanApiClient apiClient;
    private final MontoyaApi api;
    private final AtomicBoolean cancelFlag;
    
    // 任务执行统计
    private long startTime;
    private long endTime;
    
    /**
     * 构造函数
     * 
     * @param scanResult 扫描结果对象（预先创建，用于状态更新）
     * @param apiClient 被动扫描API客户端
     * @param api Burp API引用
     * @param cancelFlag 取消标志
     */
    public PassiveScanTask(ScanResult scanResult, PassiveScanApiClient apiClient, 
                          MontoyaApi api, AtomicBoolean cancelFlag) {
        this.scanResult = scanResult;
        this.apiClient = apiClient;
        this.api = api;
        this.cancelFlag = cancelFlag;
    }
    
    @Override
    public ScanResult call() {
        startTime = System.currentTimeMillis();
        
        try {
            // 检查取消标志
            if (cancelFlag != null && cancelFlag.get()) {
                scanResult.markCancelled();
                return scanResult;
            }
            
            // 标记开始扫描
            scanResult.markScanning();
            logInfo("开始扫描: " + scanResult.getMethod() + " " + scanResult.getShortUrl());
            
            // 获取HTTP请求响应
            HttpRequestResponse requestResponse = scanResult.getRequestResponse();
            if (requestResponse == null) {
                scanResult.markError("请求响应为空");
                return scanResult;
            }
            
            // 检查是否需要跳过扫描
            if (shouldSkipScan(requestResponse)) {
                scanResult.markCompleted("## 风险等级: 无\n跳过扫描：静态资源或无参数请求");
                logInfo("跳过扫描: " + scanResult.getShortUrl());
                return scanResult;
            }
            
            // 再次检查取消标志
            if (cancelFlag != null && cancelFlag.get()) {
                scanResult.markCancelled();
                return scanResult;
            }
            
            // 执行AI分析（支持取消，不需要流式回调）
            String result = apiClient.analyzeRequest(requestResponse, cancelFlag, null);
            
            // 检查取消标志
            if (cancelFlag != null && cancelFlag.get()) {
                scanResult.markCancelled();
                return scanResult;
            }
            
            // 标记完成
            scanResult.markCompleted(result);
            endTime = System.currentTimeMillis();
            logInfo("扫描完成: " + scanResult.getShortUrl() + 
                   " [" + scanResult.getRiskLevel().getDisplayName() + "] " +
                   "耗时: " + (endTime - startTime) + "ms");
            
        } catch (Exception e) {
            endTime = System.currentTimeMillis();
            String errorMsg = e.getMessage();
            if (errorMsg == null) {
                errorMsg = e.getClass().getSimpleName();
            }
            scanResult.markError(errorMsg);
            logError("扫描失败: " + scanResult.getShortUrl() + " - " + errorMsg);
        }
        
        return scanResult;
    }
    
    /**
     * 静态方法：检查请求是否应该跳过（供外部调用）
     * 
     * 跳过以下类型的请求：
     * 1. 静态资源（css、图片、字体等）
     * 2. 无参数的简单GET请求（非API路径）
     */
    public static boolean shouldSkipRequest(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return true;
        }
        
        String url = requestResponse.request().url();
        String method = requestResponse.request().method();
        
        if (url == null || method == null) {
            return true;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // 跳过静态资源
        if (isStaticResourceStatic(lowerUrl)) {
            return true;
        }
        
        // 跳过无参数的GET请求（除非URL看起来像API）
        if ("GET".equalsIgnoreCase(method)) {
            boolean hasQueryParams = url.contains("?") && url.indexOf("?") < url.length() - 1;
            boolean looksLikeApi = lowerUrl.contains("/api/") || 
                                   lowerUrl.contains("/v1/") || 
                                   lowerUrl.contains("/v2/") ||
                                   lowerUrl.contains("/rest/") ||
                                   lowerUrl.contains("/graphql");
            
            if (!hasQueryParams && !looksLikeApi) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 静态方法：检查是否为静态资源
     */
    private static boolean isStaticResourceStatic(String url) {
        String[] staticExtensions = {
            ".css", ".png", ".jpg", ".jpeg", ".gif", ".ico", 
            ".svg", ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".mp3", ".mp4", ".webm", ".wav", ".avi",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".zip", ".rar", ".7z", ".tar", ".gz"
        };
        
        String path = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            path = url.substring(0, queryIndex);
        }
        
        for (String ext : staticExtensions) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        
        String[] staticPaths = { "/images/", "/img/", "/css/" };
        for (String staticPath : staticPaths) {
            if (url.contains(staticPath)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 实例方法：检查是否应该跳过扫描（内部使用）
     * 
     * 跳过以下类型的请求：
     * 1. 静态资源（css、图片、字体等）
     * 2. 无参数的简单GET请求
     * 3. WebSocket请求
     */
    private boolean shouldSkipScan(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return true;
        }
        
        String url = requestResponse.request().url();
        String method = requestResponse.request().method();
        
        if (url == null || method == null) {
            return true;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // 跳过静态资源
        if (isStaticResource(lowerUrl)) {
            return true;
        }
        
        // 跳过无参数的GET请求（除非URL看起来像API）
        if ("GET".equalsIgnoreCase(method)) {
            boolean hasQueryParams = url.contains("?") && url.indexOf("?") < url.length() - 1;
            boolean looksLikeApi = lowerUrl.contains("/api/") || 
                                   lowerUrl.contains("/v1/") || 
                                   lowerUrl.contains("/v2/") ||
                                   lowerUrl.contains("/rest/") ||
                                   lowerUrl.contains("/graphql");
            
            if (!hasQueryParams && !looksLikeApi) {
                // 检查响应是否包含敏感信息
                if (requestResponse.response() != null) {
                    String responseBody = requestResponse.response().bodyToString();
                    if (responseBody != null && !containsSensitivePatterns(responseBody)) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否为静态资源
     */
    private boolean isStaticResource(String url) {
        // 静态文件扩展名
        String[] staticExtensions = {
            ".css", ".png", ".jpg", ".jpeg", ".gif", ".ico", 
            ".svg", ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".mp3", ".mp4", ".webm", ".wav", ".avi",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".zip", ".rar", ".7z", ".tar", ".gz"
        };
        
        // 去除query string后检查
        String path = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            path = url.substring(0, queryIndex);
        }
        
        for (String ext : staticExtensions) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        
        // 静态资源路径
        String[] staticPaths = {
            "/images/", "/img/", "/css/"
        };
        
        for (String staticPath : staticPaths) {
            if (url.contains(staticPath)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查响应是否包含敏感模式（用于决定是否扫描无参数GET请求）
     */
    private boolean containsSensitivePatterns(String content) {
        if (content == null || content.length() < 50) {
            return false;
        }
        
        String lower = content.toLowerCase();
        
        // 敏感关键词
        String[] sensitivePatterns = {
            "password", "passwd", "secret", "token", "api_key", "apikey",
            "private_key", "privatekey", "access_token", "refresh_token",
            "session", "auth", "credential", "admin", "root",
            "database", "mysql", "postgresql", "mongodb",
            "aws_", "azure_", "gcp_", "s3://", "jdbc:",
            "internal", "debug", "trace", "stack", "exception"
        };
        
        for (String pattern : sensitivePatterns) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取执行时间（毫秒）
     */
    public long getExecutionTime() {
        if (endTime > 0 && startTime > 0) {
            return endTime - startTime;
        }
        return 0;
    }
    
    /**
     * 获取扫描结果
     */
    public ScanResult getScanResult() {
        return scanResult;
    }
    
    // ========== 日志方法 ==========
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[PassiveScanTask] " + message);
        }
    }
    
    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[PassiveScanTask] " + message);
        }
    }
}
