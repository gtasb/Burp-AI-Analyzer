package com.ai.analyzer.rulesMatch;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.List;

/**
 * 前置扫描过滤器使用示例
 * 
 * 这个类展示了如何在实际代码中使用前置扫描过滤器
 */
public class Example {
    
    /**
     * 示例1: 基本用法
     */
    public static void basicUsage(MontoyaApi api) {
        // 1. 创建管理器
        PreScanFilterManager manager = new PreScanFilterManager(api);
        
        // 2. 初始化（加载规则）
        boolean initialized = manager.initialize();
        if (!initialized) {
            api.logging().logToError("初始化失败");
            return;
        }
        
        // 3. 启用过滤器
        manager.enable();
        
        api.logging().logToOutput("前置扫描过滤器已准备就绪");
    }
    
    /**
     * 示例2: 扫描HTTP请求/响应
     */
    public static void scanExample(MontoyaApi api, 
                                   PreScanFilterManager manager,
                                   HttpRequestResponse requestResponse) {
        
        if (!manager.isEnabled()) {
            return;
        }
        
        // 获取过滤器实例
        PreScanFilter filter = manager.getFilter();
        if (filter == null) {
            return;
        }
        
        // 执行扫描（500ms超时）
        List<ScanMatch> matches = filter.scan(requestResponse, 500);
        
        // 处理结果
        if (!matches.isEmpty()) {
            api.logging().logToOutput("检测到 " + matches.size() + " 个疑似漏洞");
            
            for (ScanMatch match : matches) {
                api.logging().logToOutput("  - " + match.toUiMessage());
            }
        }
    }
    
    /**
     * 示例3: 集成到主动扫描
     */
    public static String integrateToActiveScanning(
        MontoyaApi api,
        PreScanFilterManager manager,
        HttpRequestResponse requestResponse,
        String originalUserPrompt) {
        
        // 执行前置扫描
        if (manager.isEnabled()) {
            PreScanFilter filter = manager.getFilter();
            if (filter != null) {
                List<ScanMatch> matches = filter.scan(requestResponse, 500);
                
                if (!matches.isEmpty()) {
                    // 生成提示文本并追加到UserPrompt
                    String hint = PreScanFilter.buildPromptHint(matches);
                    return originalUserPrompt + hint;
                }
            }
        }
        
        return originalUserPrompt;
    }
    
    /**
     * 示例4: 集成到被动扫描
     */
    public static class PassiveScanningExample {
        
        private final PreScanFilterManager manager;
        private final MontoyaApi api;
        
        public PassiveScanningExample(MontoyaApi api, PreScanFilterManager manager) {
            this.api = api;
            this.manager = manager;
        }
        
        public String buildPromptWithPreScan(HttpRequestResponse requestResponse) {
            StringBuilder prompt = new StringBuilder();
            
            // 基础提示词
            prompt.append("请分析以下HTTP流量的安全风险：\n\n");
            prompt.append(formatHttpContent(requestResponse));
            
            // 前置扫描
            if (manager.isEnabled()) {
                PreScanFilter filter = manager.getFilter();
                if (filter != null) {
                    List<ScanMatch> matches = filter.scan(requestResponse, 500);
                    
                    if (!matches.isEmpty()) {
                        String hint = PreScanFilter.buildPromptHint(matches);
                        prompt.append(hint);
                    }
                }
            }
            
            return prompt.toString();
        }
        
        private String formatHttpContent(HttpRequestResponse requestResponse) {
            return requestResponse.request().toString() + "\n\n" +
                   (requestResponse.response() != null 
                       ? requestResponse.response().toString() 
                       : "");
        }
    }
    
    /**
     * 示例5: 自定义配置
     */
    public static void customConfiguration(MontoyaApi api) {
        PreScanFilterManager manager = new PreScanFilterManager(api);
        
        // 使用自定义规则文件和线程池大小
        String customRulesPath = "D:/custom/rules.json";
        int threadPoolSize = 8; // 8个线程
        
        boolean initialized = manager.initialize(customRulesPath, threadPoolSize);
        if (initialized) {
            manager.enable();
            api.logging().logToOutput("使用自定义配置初始化成功");
        }
    }
    
    /**
     * 示例6: 资源清理
     */
    public static void cleanup(PreScanFilterManager manager, MontoyaApi api) {
        // 禁用过滤器
        manager.disable();
        
        // 关闭并释放资源
        manager.shutdown();
        
        api.logging().logToOutput("前置扫描过滤器已关闭");
    }
    
    /**
     * 示例7: 完整工作流程
     */
    public static class CompleteWorkflowExample {
        
        private final MontoyaApi api;
        private final PreScanFilterManager manager;
        
        public CompleteWorkflowExample(MontoyaApi api) {
            this.api = api;
            this.manager = new PreScanFilterManager(api);
        }
        
        public void initialize() {
            if (manager.initialize()) {
                api.logging().logToOutput("✓ 前置扫描器初始化成功");
            } else {
                api.logging().logToError("✗ 前置扫描器初始化失败");
            }
        }
        
        public void enable() {
            manager.enable();
            api.logging().logToOutput("✓ 前置扫描器已启用");
        }
        
        public void processRequest(HttpRequestResponse requestResponse) {
            if (!manager.isEnabled()) {
                api.logging().logToOutput("前置扫描器未启用，跳过扫描");
                return;
            }
            
            PreScanFilter filter = manager.getFilter();
            if (filter == null) {
                return;
            }
            
            // 扫描
            long startTime = System.currentTimeMillis();
            List<ScanMatch> matches = filter.scan(requestResponse, 500);
            long elapsed = System.currentTimeMillis() - startTime;
            
            // 记录结果
            api.logging().logToOutput(String.format(
                "扫描完成，耗时 %dms，发现 %d 个疑似漏洞",
                elapsed, matches.size()
            ));
            
            // 输出详细信息
            if (!matches.isEmpty()) {
                for (ScanMatch match : matches) {
                    api.logging().logToOutput("  • " + match.getVulnerabilityType() + 
                        " (" + match.getSeverity() + ")");
                }
            }
        }
        
        public void shutdown() {
            manager.disable();
            manager.shutdown();
            api.logging().logToOutput("✓ 前置扫描器已关闭");
        }
    }
    
    /**
     * 示例8: 错误处理
     */
    public static void errorHandling(MontoyaApi api, PreScanFilterManager manager,
                                    HttpRequestResponse requestResponse) {
        try {
            // 检查是否已初始化
            if (!manager.isInitialized()) {
                api.logging().logToError("管理器未初始化");
                return;
            }
            
            // 检查是否已启用
            if (!manager.isEnabled()) {
                api.logging().logToOutput("前置扫描器未启用");
                return;
            }
            
            // 获取过滤器
            PreScanFilter filter = manager.getFilter();
            if (filter == null) {
                api.logging().logToError("无法获取过滤器实例");
                return;
            }
            
            // 执行扫描
            List<ScanMatch> matches = filter.scan(requestResponse, 500);
            
            // 处理结果
            if (matches == null || matches.isEmpty()) {
                api.logging().logToOutput("未发现漏洞特征");
            } else {
                api.logging().logToOutput("发现 " + matches.size() + " 个疑似漏洞");
            }
            
        } catch (Exception e) {
            api.logging().logToError("扫描过程出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
