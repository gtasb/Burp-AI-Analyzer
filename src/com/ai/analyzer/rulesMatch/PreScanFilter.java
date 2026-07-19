package com.ai.analyzer.rulesMatch;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;

/**
 * 前置扫描过滤器
 * 在AI分析之前快速匹配已知漏洞特征，提高检测效率
 * 
 * 设计原则：
 * 1. 多线程并发扫描
 * 2. 轻量级匹配（正则表达式）
 * 3. 快速返回结果
 * 4. 不阻塞主流程
 */
public class PreScanFilter {
    
    private final MontoyaApi api;
    private final List<VulnerabilityRule> rules;
    private final ExecutorService executorService;
    private final int threadPoolSize;
    private volatile boolean isEnabled;
    
    /**
     * 构造函数
     * 
     * @param api Burp API
     * @param rules 漏洞匹配规则列表
     * @param threadPoolSize 线程池大小（建议 2-4）
     */
    public PreScanFilter(MontoyaApi api, List<VulnerabilityRule> rules, int threadPoolSize) {
        this.api = api;
        this.rules = rules;
        this.threadPoolSize = threadPoolSize;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
        this.isEnabled = false;
        
        api.logging().logToOutput("[PreScanFilter] 初始化完成，规则数：" + rules.size() + 
            "，线程池大小：" + threadPoolSize);
    }
    
    /**
     * 启用过滤器
     */
    public void enable() {
        this.isEnabled = true;
        api.logging().logToOutput("[PreScanFilter] 已启用");
    }
    
    /**
     * 禁用过滤器
     */
    public void disable() {
        this.isEnabled = false;
        api.logging().logToOutput("[PreScanFilter] 已禁用");
    }
    
    /**
     * 检查是否启用
     */
    public boolean isEnabled() {
        return isEnabled;
    }
    
    /**
     * 扫描HTTP请求/响应，返回匹配结果
     * 
     * @param requestResponse HTTP请求响应对
     * @param timeoutMs 超时时间（毫秒）
     * @return 匹配结果列表
     */
    public List<ScanMatch> scan(HttpRequestResponse requestResponse, long timeoutMs) {
        if (!isEnabled) {
            return new ArrayList<>();
        }
        
        try {
            // 获取请求和响应内容
            String requestStr = requestResponse.request().toString();
            String responseStr = requestResponse.response() != null 
                ? requestResponse.response().toString() 
                : "";
            
            // 合并请求和响应用于扫描
            String combinedContent = requestStr + "\n" + responseStr;
            
            // 分批扫描（每个线程处理一部分规则）
            List<Future<List<ScanMatch>>> futures = new ArrayList<>();
            int rulesPerThread = Math.max(1, rules.size() / threadPoolSize);
            
            for (int i = 0; i < rules.size(); i += rulesPerThread) {
                int start = i;
                int end = Math.min(i + rulesPerThread, rules.size());
                List<VulnerabilityRule> batchRules = rules.subList(start, end);
                
                Future<List<ScanMatch>> future = executorService.submit(() -> 
                    scanWithRules(combinedContent, batchRules));
                futures.add(future);
            }
            
            // 收集结果（带超时）
            List<ScanMatch> allMatches = new ArrayList<>();
            for (Future<List<ScanMatch>> future : futures) {
                try {
                    List<ScanMatch> matches = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    allMatches.addAll(matches);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    api.logging().logToOutput("[PreScanFilter] 扫描超时");
                } catch (Exception e) {
                    api.logging().logToError("[PreScanFilter] 扫描出错: " + e.getMessage());
                }
            }
            
            return allMatches;
            
        } catch (Exception e) {
            api.logging().logToError("[PreScanFilter] 扫描失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 使用给定的规则扫描内容
     */
    private List<ScanMatch> scanWithRules(String content, List<VulnerabilityRule> rulesToScan) {
        List<ScanMatch> matches = new ArrayList<>();
        
        for (VulnerabilityRule rule : rulesToScan) {
            for (VulnerabilityRule.CompiledPattern compiledPattern : rule.getPatterns()) {
                if (compiledPattern.getPattern() == null) {
                    continue;
                }
                
                try {
                    Matcher matcher = compiledPattern.getPattern().matcher(content);
                    if (matcher.find()) {
                        // 提取匹配的字符串
                        String matchedStr = matcher.group();
                        
                        // 创建匹配结果
                        ScanMatch match = new ScanMatch(
                            rule.getName(),
                            matchedStr,
                            rule.getSeverity(),
                            compiledPattern.getSubType()
                        );
                        
                        matches.add(match);
                        
                        // 每个规则只报告一次匹配（避免重复）
                        break;
                    }
                } catch (Exception e) {
                    // 忽略单个规则的匹配错误
                }
            }
        }
        
        return matches;
    }
    
    /**
     * 构建用于追加到UserPrompt的提示文本
     * 
     * @param matches 匹配结果列表
     * @return 格式化的提示文本
     */
    public static String buildPromptHint(List<ScanMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        
        StringBuilder hint = new StringBuilder();
        hint.append("\n\n【前置扫描器结果】\n");
        hint.append("疑似检测到以下漏洞特征，请重点关注并验证真实性：\n");
        
        for (int i = 0; i < matches.size(); i++) {
            ScanMatch match = matches.get(i);
            hint.append((i + 1)).append(". ").append(match.toPromptHint()).append("\n");
        }
        
        hint.append("\n请使用工具主动测试验证以上漏洞，不要仅凭特征就下结论。");
        
        return hint.toString();
    }
    
    /**
     * 构建用于UI显示的消息
     * 
     * @param matches 匹配结果列表
     * @return 格式化的UI消息
     */
    public static String buildUiMessage(List<ScanMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        
        StringBuilder message = new StringBuilder();
        message.append("前置扫描器匹配到 ").append(matches.size()).append(" 个疑似漏洞特征\n");
        
        // 暂时不输出UI消息
/*         for (ScanMatch match : matches) {
            message.append("• ").append(match.toUiMessage()).append("\n");
        } */
        
        return message.toString();
    }
    
    /**
     * 关闭过滤器并释放资源
     */
    public void shutdown() {
        api.logging().logToOutput("[PreScanFilter] 正在关闭...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}
