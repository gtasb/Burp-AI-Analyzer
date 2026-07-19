package com.ai.analyzer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.ai.analyzer.ui.AIAnalyzerTab;
import com.ai.analyzer.provider.AIContextMenuProvider;
import com.ai.analyzer.provider.AISidePanelProvider;
import com.ai.analyzer.rulesMatch.PreScanFilterManager;

public class AIExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        // 设置插件名称
        api.extension().setName("Ai Analyzer");

        // 初始化前置扫描过滤器管理器
        PreScanFilterManager preScanFilterManager = new PreScanFilterManager(api);
        boolean filterInitialized = preScanFilterManager.initialize();
        if (filterInitialized) {
            api.logging().logToOutput("[AIExtension] 前置扫描过滤器初始化成功");
        } else {
            api.logging().logToError("[AIExtension] 前置扫描过滤器初始化失败，该功能将不可用");
        }

        // 创建AI分析标签页（传递前置扫描管理器）
        AIAnalyzerTab analyzerTab = new AIAnalyzerTab(api, preScanFilterManager);
        api.userInterface().registerSuiteTab("Ai Analyzer", analyzerTab.getUiComponent());

        // 注册右键菜单
        AIContextMenuProvider contextMenuProvider = new AIContextMenuProvider(api, analyzerTab);
        api.userInterface().registerContextMenuItemsProvider(contextMenuProvider);

        // 注册AI Side Panel
        AISidePanelProvider sidePanelProvider = new AISidePanelProvider(api);
        // 将analyzerTab引用传递给sidePanelProvider，使其能够获取API配置
        sidePanelProvider.setAnalyzerTab(analyzerTab);
        
        api.userInterface().registerHttpRequestEditorProvider(sidePanelProvider);
        api.userInterface().registerHttpResponseEditorProvider(sidePanelProvider);

        // 输出日志到 Burp 的 Output 标签页
        api.logging().logToOutput("AI漏洞分析助手插件已成功加载！");
        api.logging().logToOutput("使用方法：");
        api.logging().logToOutput("1. 在AI分析标签页配置通义千问API Key");
        api.logging().logToOutput("2. 在任意HTTP请求上右键选择'发送到AI分析'");
        api.logging().logToOutput("3. 填写分析提示词，点击'开始分析'按钮");
        api.logging().logToOutput("4. 在Proxy和Repeater中使用AI Side Panel进行实时对话");
        api.logging().logToOutput("5. 启用被动扫描功能自动从HTTP History获取流量进行AI安全分析");
        api.logging().logToOutput("6. 启用前置扫描器快速匹配35+种已知漏洞特征（基于规则库）");
    }
}