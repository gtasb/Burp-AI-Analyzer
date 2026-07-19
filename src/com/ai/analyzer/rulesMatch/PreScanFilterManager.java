package com.ai.analyzer.rulesMatch;

import burp.api.montoya.MontoyaApi;

import java.util.List;

/**
 * 前置扫描过滤器管理器
 * 负责初始化和管理前置扫描过滤器的生命周期
 */
public class PreScanFilterManager {
    
    private final MontoyaApi api;
    private PreScanFilter preScanFilter;
    private boolean initialized = false;
    
    // 默认配置（规则已硬编码在 HardcodedRules 中，不再从文件加载）
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    private static final long DEFAULT_SCAN_TIMEOUT_MS = 500; // 500ms超时
    
    public PreScanFilterManager(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 初始化前置扫描过滤器
     * 规则全部来自 HardcodedRules（硬编码），不依赖外部 JSON 文件。
     *
     * @param rulesPath 已忽略，保留参数兼容
     * @param threadPoolSize 线程池大小（如果<=0则使用默认值）
     * @return 是否初始化成功
     */
    public boolean initialize(String rulesPath, int threadPoolSize) {
        if (initialized) {
            api.logging().logToOutput("[PreScanFilterManager] 已经初始化，跳过");
            return true;
        }
        
        try {
            int actualThreadPoolSize = (threadPoolSize > 0) 
                ? threadPoolSize : DEFAULT_THREAD_POOL_SIZE;
            
            api.logging().logToOutput("[PreScanFilterManager] 开始初始化（规则：硬编码）");
            
            // 从硬编码规则加载，不依赖 JSON 文件
            List<VulnerabilityRule> rules = HardcodedRules.getAllRules();
            
            if (rules.isEmpty()) {
                api.logging().logToError("[PreScanFilterManager] 未加载到任何规则，初始化失败");
                return false;
            }
            
            preScanFilter = new PreScanFilter(api, rules, actualThreadPoolSize);
            initialized = true;
            
            api.logging().logToOutput("[PreScanFilterManager] 初始化成功，规则数：" + rules.size());
            return true;
            
        } catch (Exception e) {
            api.logging().logToError("[PreScanFilterManager] 初始化失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 初始化（使用默认配置）
     */
    public boolean initialize() {
        return initialize(null, 0);
    }
    
    /**
     * 获取前置扫描过滤器实例
     * 
     * @return 过滤器实例，如果未初始化则返回null
     */
    public PreScanFilter getFilter() {
        return preScanFilter;
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 启用前置扫描过滤器
     */
    public void enable() {
        if (preScanFilter != null) {
            preScanFilter.enable();
        } else {
            api.logging().logToError("[PreScanFilterManager] 过滤器未初始化，无法启用");
        }
    }
    
    /**
     * 禁用前置扫描过滤器
     */
    public void disable() {
        if (preScanFilter != null) {
            preScanFilter.disable();
        }
    }
    
    /**
     * 检查是否启用
     */
    public boolean isEnabled() {
        return preScanFilter != null && preScanFilter.isEnabled();
    }
    
    /**
     * 获取默认扫描超时时间
     */
    public long getDefaultScanTimeout() {
        return DEFAULT_SCAN_TIMEOUT_MS;
    }
    
    /**
     * 关闭管理器并释放资源
     */
    public void shutdown() {
        if (preScanFilter != null) {
            preScanFilter.shutdown();
            preScanFilter = null;
        }
        initialized = false;
        api.logging().logToOutput("[PreScanFilterManager] 已关闭");
    }
}
