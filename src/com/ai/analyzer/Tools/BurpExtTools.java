package com.ai.analyzer.Tools;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.intruder.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Burp 扩展工具类 - 为 AI 提供 Burp 原生功能的访问
 * 解决 MCP 工具不支持的功能，如批量 payloads 传入 Intruder
 * 
 * 参考: https://github.com/238469/burp-ai-fuzzer
 */
public class BurpExtTools {
    
    private final MontoyaApi api;
    
    // 全局唯一的 PayloadGeneratorProvider，固定名称
    private static final String PROVIDER_NAME = "AI Analyzer Payloads";
    private static AIPayloadGeneratorProvider globalProvider = null;
    private static Registration providerRegistration = null;
    
    // 当前活跃的 payloads 列表（线程安全）
    private static final List<String> currentPayloads = Collections.synchronizedList(new ArrayList<>());
    
    public BurpExtTools(MontoyaApi api) {
        this.api = api;
        // 确保 PayloadGeneratorProvider 已注册
        ensureProviderRegistered();
    }
    
    /**
     * 确保全局 PayloadGeneratorProvider 已注册（只注册一次）
     */
    private synchronized void ensureProviderRegistered() {
        if (globalProvider == null) {
            globalProvider = new AIPayloadGeneratorProvider();
            providerRegistration = api.intruder().registerPayloadGeneratorProvider(globalProvider);
            api.logging().logToOutput("[BurpExtTools] 已注册 PayloadGeneratorProvider: " + PROVIDER_NAME);
        }
    }
    
    /**
     * 发送请求到 Intruder，并使用 AI 生成的 payloads
     * 
     * Payloads 会自动配置到全局 Provider，用户只需：
     * 1. 在 Intruder 中选择 Payload type 为 "Extension-generated"
     * 2. 选择 "AI Analyzer Payloads" 作为 Payload source
     * 3. 点击 "Start attack" 开始攻击
     * 
     * 注意：首次使用需要设置一次，后续 AI 生成的 payloads 会自动更新
     */
    @Tool(name = "BurpExtTools_send_to_intruder", value = {
        "发送请求到 Burp Intruder 并配置 AI 生成的 payloads。",
        "此工具用于批量测试，AI 可以生成针对性的 payload 列表。",
        "请求中使用 § 符号标记插入点（成对使用），例如：id=§1§",
        "适用场景：SQL注入、XSS、命令注入、目录遍历等需要批量 payload 测试的场景"
    })
    public String sendToIntruder(
            @P("HTTP请求内容，使用§符号标记插入点位置，例如：GET /api?id=§1§ HTTP/1.1") String requestContent,
            @P("目标主机名，例如：example.com") String targetHostname,
            @P("目标端口，例如：443 或 80") int targetPort,
            @P("是否使用HTTPS") boolean usesHttps,
            @P("Intruder标签页名称，用于标识本次攻击") String tabName,
            @P("AI生成的payload列表，例如：[\"' OR '1'='1\", \"admin'--\", \"1; DROP TABLE users\"]") List<String> payloads
    ) {
        try {
            // 1. 验证参数
            if (requestContent == null || requestContent.isEmpty()) {
                return "错误：请求内容不能为空";
            }
            if (targetHostname == null || targetHostname.isEmpty()) {
                return "错误：目标主机名不能为空";
            }
            if (payloads == null || payloads.isEmpty()) {
                return "错误：payloads 列表不能为空";
            }
            
            // 2. 解析插入点位置（找到所有 § 标记对）
            List<Range> insertionPointOffsets = parseInsertionPoints(requestContent);
            
            if (insertionPointOffsets.isEmpty()) {
                return "错误：请求中未找到插入点标记（§...§）。请使用成对的 § 符号标记要注入的位置，例如：id=§1§";
            }
            
            // 3. 更新全局 payloads 列表
            synchronized (currentPayloads) {
                currentPayloads.clear();
                currentPayloads.addAll(payloads);
            }
            
            // 4. 移除 § 标记，获取干净的请求内容
            String cleanRequest = requestContent.replace("§", "");
            
            // 5. 创建 HttpService 和 HttpRequest
            HttpService httpService = HttpService.httpService(targetHostname, targetPort, usesHttps);
            HttpRequest httpRequest = HttpRequest.httpRequest(httpService, cleanRequest);
            
            // 6. 创建 HttpRequestTemplate（带插入点偏移）
            List<Range> adjustedOffsets = adjustOffsetsAfterMarkerRemoval(requestContent, insertionPointOffsets);
            HttpRequestTemplate requestTemplate = HttpRequestTemplate.httpRequestTemplate(httpRequest, adjustedOffsets);
            
            // 7. 发送到 Intruder
            String intruderTabName = tabName != null && !tabName.isEmpty() ? tabName : "AI-Attack";
            api.intruder().sendToIntruder(httpService, requestTemplate, intruderTabName);
            
            // 8. 同时复制 payloads 到剪贴板（备用方案）
/*             String payloadsText = String.join("\n", payloads);
            copyToClipboard(payloadsText); */
            
            // 9. 构建返回信息
            StringBuilder result = new StringBuilder();
            result.append("✅ 请求已发送到 Intruder\n\n");
            result.append("📋 攻击配置：\n");
            result.append("- 标签页: ").append(intruderTabName).append("\n");
            result.append("- 目标: ").append(usesHttps ? "https://" : "http://")
                  .append(targetHostname).append(":").append(targetPort).append("\n");
            result.append("- 插入点: ").append(adjustedOffsets.size()).append(" 个\n");
            result.append("- Payloads: ").append(payloads.size()).append(" 个\n\n");
            
            result.append("🚀 操作步骤：\n");
            result.append("1. 切换到 Intruder → \"").append(intruderTabName).append("\" 标签页\n");
            result.append("2. 点击 Payloads 选项卡\n");
            result.append("3. Payload type 选择 \"Extension-generated\"\n");
            result.append("4. Payload source 选择 \"").append(PROVIDER_NAME).append("\"\n");
            result.append("5. 点击 \"Start attack\" 开始攻击 🎯\n\n");
            
            result.append("💡 提示：首次设置后，后续只需点击 Start attack！\n");
            
            result.append("📝 Payloads 预览:\n```\n");
            for (int i = 0; i < Math.min(10, payloads.size()); i++) {
                String payload = payloads.get(i);
                if (payload.length() > 60) {
                    payload = payload.substring(0, 57) + "...";
                }
                result.append(payload).append("\n");
            }
            if (payloads.size() > 10) {
                result.append("... 还有 ").append(payloads.size() - 10).append(" 个\n");
            }
            result.append("```");
            
            api.logging().logToOutput("[BurpExtTools] 已发送到 Intruder: " + intruderTabName + 
                    ", payloads: " + payloads.size() + " 个");
            
            return result.toString();
            
        } catch (Exception e) {
            String errorMsg = "发送到 Intruder 失败: " + e.getMessage();
            api.logging().logToError("[BurpExtTools] " + errorMsg);
            e.printStackTrace();
            return "❌ " + errorMsg;
        }
    }
    
    /**
     * 复制文本到系统剪贴板
     */
/*     private void copyToClipboard(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
        } catch (Exception e) {
            api.logging().logToError("[BurpExtTools] 复制到剪贴板失败: " + e.getMessage());
        }
    } */
    
    /**
     * 解析请求中的插入点标记（§...§）
     */
    private List<Range> parseInsertionPoints(String content) {
        List<Range> ranges = new ArrayList<>();
        int index = 0;
        
        while (index < content.length()) {
            int start = content.indexOf('§', index);
            if (start == -1) break;
            
            int end = content.indexOf('§', start + 1);
            if (end == -1) break;
            
            ranges.add(Range.range(start, end + 1));
            index = end + 1;
        }
        
        return ranges;
    }
    
    /**
     * 调整插入点偏移量（移除 § 标记后）
     */
    private List<Range> adjustOffsetsAfterMarkerRemoval(String originalContent, List<Range> originalRanges) {
        List<Range> adjustedRanges = new ArrayList<>();
        int removedChars = 0;
        
        for (Range range : originalRanges) {
            int originalStart = range.startIndexInclusive();
            int originalEnd = range.endIndexExclusive();
            
            int adjustedStart = originalStart - removedChars;
            int adjustedEnd = originalEnd - removedChars - 2;
            
            adjustedRanges.add(Range.range(adjustedStart, adjustedEnd));
            removedChars += 2;
        }
        
        return adjustedRanges;
    }
    
    /**
     * 全局 PayloadGeneratorProvider - 固定名称，payloads 可动态更新
     * 参考: https://github.com/238469/burp-ai-fuzzer
     */
    private static class AIPayloadGeneratorProvider implements PayloadGeneratorProvider {
        
        @Override
        public String displayName() {
            return PROVIDER_NAME;
        }
        
        @Override
        public PayloadGenerator providePayloadGenerator(AttackConfiguration attackConfiguration) {
            // 每次攻击开始时创建新的 Generator，使用当前的 payloads
            List<String> payloadsCopy;
            synchronized (currentPayloads) {
                payloadsCopy = new ArrayList<>(currentPayloads);
            }
            return new AIPayloadGenerator(payloadsCopy);
        }
    }
    
    /**
     * PayloadGenerator - 逐个返回 payloads
     */
    private static class AIPayloadGenerator implements PayloadGenerator {
        private final List<String> payloads;
        private int currentIndex = 0;
        
        public AIPayloadGenerator(List<String> payloads) {
            this.payloads = payloads;
        }
        
        @Override
        public GeneratedPayload generatePayloadFor(IntruderInsertionPoint insertionPoint) {
            if (currentIndex >= payloads.size()) {
                return GeneratedPayload.end();
            }
            return GeneratedPayload.payload(payloads.get(currentIndex++));
        }
    }
    
    /**
     * 获取当前 payloads 数量（用于调试）
     */
    public static int getCurrentPayloadsCount() {
        return currentPayloads.size();
    }
}
