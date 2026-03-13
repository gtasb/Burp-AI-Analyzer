package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    
    // ========== 可配置的过滤规则（静态字段，供全局共享） ==========
    
    private static final String[] DEFAULT_STATIC_EXTENSIONS = {
        ".js", ".css", ".map",
        ".png", ".jpg", ".jpeg", ".gif", ".ico", ".bmp", ".webp",
        ".svg", ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".mp3", ".mp4", ".webm", ".wav", ".avi", ".flv",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".rar", ".7z", ".tar", ".gz",
        ".xml", ".rss", ".atom", ".sitemap"
    };
    
    private static final String[] DEFAULT_STATIC_PATHS = {
        "/images/", "/img/", "/css/", "/js/", "/fonts/", "/static/", "/assets/"
    };
    
    private static volatile Set<String> customExtensions = null;
    private static volatile String[] customStaticPaths = null;
    private static final CopyOnWriteArrayList<Pattern> domainBlacklist = new CopyOnWriteArrayList<>();
    
    /**
     * 获取默认的跳过扩展名列表（供 UI 显示）
     */
    public static String getDefaultSkipExtensionsText() {
        return String.join(", ", DEFAULT_STATIC_EXTENSIONS);
    }
    
    /**
     * 设置自定义的跳过扩展名（逗号或换行分隔，如 ".js, .css, .png"）。
     * 传入 null 或空字符串则恢复默认。
     */
    public static void setCustomSkipExtensions(String text) {
        if (text == null || text.isBlank()) {
            customExtensions = null;
            customStaticPaths = null;
            return;
        }
        Set<String> exts = Arrays.stream(text.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.toLowerCase() : ("." + s.toLowerCase()))
                .collect(Collectors.toSet());
        
        List<String> paths = exts.stream()
                .filter(s -> s.startsWith("/") && s.endsWith("/"))
                .collect(Collectors.toList());
        exts.removeAll(paths);
        
        customExtensions = exts.isEmpty() ? null : exts;
        customStaticPaths = paths.isEmpty() ? null : paths.toArray(new String[0]);
    }
    
    /**
     * 设置域名黑名单（每行一个模式，支持通配符如 *.google.com）。
     * 传入 null 或空字符串则清空。
     */
    public static void setDomainBlacklist(String text) {
        domainBlacklist.clear();
        if (text == null || text.isBlank()) return;
        
        for (String line : text.split("[\\r\\n]+")) {
            String pattern = line.trim();
            if (pattern.isEmpty() || pattern.startsWith("#")) continue;
            try {
                String regex = globToRegex(pattern);
                domainBlacklist.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * 检查域名是否在黑名单中
     */
    public static boolean isDomainBlacklisted(String host) {
        if (host == null || host.isEmpty() || domainBlacklist.isEmpty()) return false;
        for (Pattern p : domainBlacklist) {
            if (p.matcher(host).matches()) return true;
        }
        return false;
    }
    
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': sb.append("\\."); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }
    
    /**
     * 从 URL 中提取主机名
     */
    public static String extractHost(String url) {
        if (url == null) return null;
        try {
            String s = url;
            int protoEnd = s.indexOf("://");
            if (protoEnd > 0) s = s.substring(protoEnd + 3);
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            int colon = s.lastIndexOf(':');
            if (colon > 0) s = s.substring(0, colon);
            return s.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 静态方法：检查请求是否应该跳过（供外部调用）
     */
    public static boolean shouldSkipRequest(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return true;
        }
        
        String url = requestResponse.request().url();
        if (url == null) return true;
        
        // 域名黑名单检查
        String host = extractHost(url);
        if (isDomainBlacklisted(host)) return true;
        
        String lowerUrl = url.toLowerCase();
        if (isStaticResourceStatic(lowerUrl)) return true;
        
        // 仅对 GET 请求跳过二进制响应（POST/PUT 等可能涉及文件上传漏洞，必须保留）
        String method = requestResponse.request().method().toUpperCase();
        if ("GET".equals(method) && requestResponse.response() != null) {
            try {
                String respStr = requestResponse.response().toString();
                if (respStr != null) {
                    String headerPart = respStr;
                    int sep = respStr.indexOf("\r\n\r\n");
                    if (sep < 0) sep = respStr.indexOf("\n\n");
                    if (sep > 0) headerPart = respStr.substring(0, sep);
                    String lowerHeaders = headerPart.toLowerCase();
                    
                    if (lowerHeaders.contains("content-type:")) {
                        if (lowerHeaders.contains("image/") ||
                            lowerHeaders.contains("font/") ||
                            lowerHeaders.contains("audio/") ||
                            lowerHeaders.contains("video/") ||
                            lowerHeaders.contains("application/octet-stream") ||
                            lowerHeaders.contains("application/zip") ||
                            lowerHeaders.contains("application/gzip") ||
                            lowerHeaders.contains("application/pdf") ||
                            lowerHeaders.contains("application/wasm")) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        
        return false;
    }
    
    private static boolean isStaticResourceStatic(String url) {
        String path = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) path = url.substring(0, queryIndex);
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex > 0) path = path.substring(0, fragmentIndex);
        
        Set<String> exts = customExtensions;
        if (exts != null) {
            for (String ext : exts) {
                if (path.endsWith(ext)) return true;
            }
        } else {
            for (String ext : DEFAULT_STATIC_EXTENSIONS) {
                if (path.endsWith(ext)) return true;
            }
        }
        
        String[] paths = customStaticPaths;
        if (paths != null) {
            for (String sp : paths) {
                if (url.contains(sp)) return true;
            }
        } else {
            for (String sp : DEFAULT_STATIC_PATHS) {
                if (url.contains(sp)) return true;
            }
        }
        
        return false;
    }
    
    private boolean shouldSkipScan(HttpRequestResponse requestResponse) {
        return shouldSkipRequest(requestResponse);
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
