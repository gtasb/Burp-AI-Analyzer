package com.ai.analyzer.pscan;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 被动扫描结果数据模型
 * 用于存储单个请求的AI扫描结果
 */
public class ScanResult {
    
    /**
     * 风险等级枚举
     */
    public enum RiskLevel {
        CRITICAL("严重", 4),
        HIGH("高", 3),
        MEDIUM("中", 2),
        LOW("低", 1),
        INFO("信息", 0),
        NONE("无", -1);
        
        private final String displayName;
        private final int priority;
        
        RiskLevel(String displayName, int priority) {
            this.displayName = displayName;
            this.priority = priority;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public int getPriority() {
            return priority;
        }
        
        /**
         * 从AI响应中解析风险等级
         */
        public static RiskLevel fromString(String text) {
            if (text == null) return NONE;
            String lower = text.toLowerCase();
            if (lower.contains("严重") || lower.contains("critical")) return CRITICAL;
            if (lower.contains("高") || lower.contains("high")) return HIGH;
            if (lower.contains("中") || lower.contains("medium")) return MEDIUM;
            if (lower.contains("低") || lower.contains("low")) return LOW;
            if (lower.contains("信息") || lower.contains("info")) return INFO;
            return NONE;
        }
    }
    
    /**
     * 扫描状态枚举
     */
    public enum ScanStatus {
        PENDING("等待中"),
        SCANNING("扫描中"),
        COMPLETED("已完成"),
        ERROR("错误"),
        CANCELLED("已取消");
        
        private final String displayName;
        
        ScanStatus(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    // 基本信息
    private final int id;
    private final String method;
    private final String url;
    private final String host;
    private final LocalDateTime timestamp;
    private final HttpRequestResponse requestResponse;
    
    // 扫描结果
    private volatile ScanStatus status;
    private volatile RiskLevel riskLevel;
    private volatile String analysisResult;
    private volatile String errorMessage;
    private volatile LocalDateTime completedTime;
    
    /**
     * 构造函数
     */
    public ScanResult(int id, HttpRequestResponse requestResponse) {
        this.id = id;
        this.requestResponse = requestResponse;
        this.timestamp = LocalDateTime.now();
        this.status = ScanStatus.PENDING;
        this.riskLevel = RiskLevel.NONE;
        
        // 解析请求信息
        if (requestResponse != null && requestResponse.request() != null) {
            this.method = requestResponse.request().method();
            this.url = requestResponse.request().url();
            this.host = requestResponse.request().httpService() != null 
                ? requestResponse.request().httpService().host() 
                : "";
        } else {
            this.method = "";
            this.url = "";
            this.host = "";
        }
    }
    
    // ========== Getters ==========
    
    public int getId() {
        return id;
    }
    
    public String getMethod() {
        return method;
    }
    
    public String getUrl() {
        return url;
    }
    
    public String getHost() {
        return host;
    }
    
    /**
     * 获取简短URL（去除query string）
     */
    public String getShortUrl() {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            return url.substring(0, Math.min(queryIndex, 60)) + (queryIndex > 60 ? "..." : "");
        }
        return url.length() > 60 ? url.substring(0, 60) + "..." : url;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public String getFormattedTimestamp() {
        return timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
    
    public HttpRequestResponse getRequestResponse() {
        return requestResponse;
    }
    
    public boolean hasResponse() {
        return requestResponse != null && requestResponse.response() != null;
    }
    
    public ScanStatus getStatus() {
        return status;
    }
    
    public RiskLevel getRiskLevel() {
        return riskLevel;
    }
    
    public String getAnalysisResult() {
        return analysisResult;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public LocalDateTime getCompletedTime() {
        return completedTime;
    }
    
    // ========== Setters (线程安全) ==========
    
    public synchronized void setStatus(ScanStatus status) {
        this.status = status;
    }
    
    public synchronized void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }
    
    public synchronized void setAnalysisResult(String analysisResult) {
        this.analysisResult = analysisResult;
        // 从分析结果中解析风险等级
        if (analysisResult != null) {
            this.riskLevel = parseRiskLevelFromResult(analysisResult);
        }
    }
    
    public synchronized void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public synchronized void setCompletedTime(LocalDateTime completedTime) {
        this.completedTime = completedTime;
    }
    
    /**
     * 标记扫描开始
     */
    public synchronized void markScanning() {
        this.status = ScanStatus.SCANNING;
    }
    
    /**
     * 标记扫描完成
     */
    public synchronized void markCompleted(String result) {
        this.status = ScanStatus.COMPLETED;
        this.analysisResult = result;
        this.completedTime = LocalDateTime.now();
        this.riskLevel = parseRiskLevelFromResult(result);
    }
    
    /**
     * 标记扫描错误
     */
    public synchronized void markError(String error) {
        this.status = ScanStatus.ERROR;
        this.errorMessage = error;
        this.completedTime = LocalDateTime.now();
    }
    
    /**
     * 标记扫描取消
     */
    public synchronized void markCancelled() {
        this.status = ScanStatus.CANCELLED;
        this.completedTime = LocalDateTime.now();
    }
    
    /**
     * 从AI响应中解析风险等级
     * 优先级：严重 > 高 > 中 > 低 > 信息 > 无
     */
    private RiskLevel parseRiskLevelFromResult(String result) {
        if (result == null || result.isEmpty()) {
            return RiskLevel.NONE;
        }
        
        String lower = result.toLowerCase();
        
        // 检查是否明确表示没有发现漏洞
        if (lower.contains("未发现") || lower.contains("没有发现") || 
            lower.contains("no vulnerabilit") || lower.contains("无安全问题") ||
            lower.contains("安全") && lower.contains("良好")) {
            return RiskLevel.NONE;
        }
        
        // 按优先级检查风险等级关键词
        // 检查严重级别
        if (lower.contains("严重") || lower.contains("critical") || 
            lower.contains("紧急") || lower.contains("rce") || 
            lower.contains("远程代码执行")) {
            return RiskLevel.CRITICAL;
        }
        
        // 检查高级别
        if (lower.contains("高危") || lower.contains("高风险") || 
            lower.contains("严重程度: 高") || lower.contains("severity: high") ||
            lower.contains("sql注入") || lower.contains("sql injection") ||
            lower.contains("命令注入") || lower.contains("command injection") ||
            lower.contains("xxe") || lower.contains("ssrf")) {
            return RiskLevel.HIGH;
        }
        
        // 检查中级别
        if (lower.contains("中危") || lower.contains("中风险") || 
            lower.contains("严重程度: 中") || lower.contains("severity: medium") ||
            lower.contains("xss") || lower.contains("跨站脚本") ||
            lower.contains("csrf") || lower.contains("越权") ||
            lower.contains("信息泄露")) {
            return RiskLevel.MEDIUM;
        }
        
        // 检查低级别
        if (lower.contains("低危") || lower.contains("低风险") || 
            lower.contains("严重程度: 低") || lower.contains("severity: low")) {
            return RiskLevel.LOW;
        }
        
        // 检查信息级别
        if (lower.contains("信息") || lower.contains("info") || 
            lower.contains("建议") || lower.contains("注意")) {
            return RiskLevel.INFO;
        }
        
        // 如果包含漏洞/风险相关词汇但没有明确等级，默认为中
        if (lower.contains("漏洞") || lower.contains("风险") || 
            lower.contains("vulnerability") || lower.contains("risk") ||
            lower.contains("问题") || lower.contains("issue")) {
            return RiskLevel.MEDIUM;
        }
        
        return RiskLevel.NONE;
    }
    
    /**
     * 获取用于去重的唯一标识
     * 基于 method + host + path（不含query string）
     */
    public String getDeduplicationKey() {
        String path = url;
        if (path != null) {
            int queryIndex = path.indexOf('?');
            if (queryIndex > 0) {
                path = path.substring(0, queryIndex);
            }
        }
        return method + "|" + host + "|" + path;
    }
    
    @Override
    public String toString() {
        return String.format("#%d %s %s [%s] %s", 
            id, method, getShortUrl(), 
            status.getDisplayName(), 
            riskLevel.getDisplayName());
    }
}
