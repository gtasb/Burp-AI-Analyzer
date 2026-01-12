package com.ai.analyzer.Tools;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.intruder.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * AI 只需指定要注入的参数名，工具会自动在请求中找到并标记插入点。
     * 
     * Payloads 会自动配置到全局 Provider，用户只需：
     * 1. 在 Intruder 中选择 Payload type 为 "Extension-generated"
     * 2. 选择 "AI Analyzer Payloads" 作为 Payload source
     * 3. 点击 "Start attack" 开始攻击
     */
    @Tool(name = "BurpExtTools_send_to_intruder", value = {
        "发送请求到 Burp Intruder 进行批量 payload 测试。",
        "【重要】AI 只需指定要注入的参数名（targetParameters），工具会自动在请求中找到并标记插入点。",
        "支持的参数位置：URL 查询参数、POST 表单参数、JSON 字段值、Cookie 值",
        "适用场景：SQL注入、XSS、命令注入、目录遍历等需要批量测试的场景"
    })
    public String sendToIntruder(
            @P("原始 HTTP 请求内容（不需要添加任何标记）") String requestContent,
            @P("目标主机名，例如：example.com") String targetHostname,
            @P("目标端口，例如：443 或 80") int targetPort,
            @P("是否使用HTTPS") boolean usesHttps,
            @P("Intruder 标签页名称") String tabName,
            @P("要注入的参数名列表，工具会自动找到这些参数并标记为插入点。例如：[\"id\", \"name\", \"search\"]") List<String> targetParameters,
            @P("AI 生成的 payload 列表，例如：[\"' OR '1'='1\", \"<script>alert(1)</script>\", \"../../../etc/passwd\"]") List<String> payloads
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
            if (targetParameters == null || targetParameters.isEmpty()) {
                return "错误：targetParameters 不能为空，请指定要注入的参数名列表";
            }
            
            // 2. 自动在请求中找到目标参数并标记插入点
            String markedRequest = requestContent;
            List<String> foundParams = new ArrayList<>();
            List<String> notFoundParams = new ArrayList<>();
            
            for (String paramName : targetParameters) {
                String originalRequest = markedRequest;
                markedRequest = markParameterValue(markedRequest, paramName);
                
                if (markedRequest.equals(originalRequest)) {
                    notFoundParams.add(paramName);
                } else {
                    foundParams.add(paramName);
                }
            }
            
            // 3. 解析插入点位置
            List<Range> insertionPointOffsets = parseInsertionPoints(markedRequest);
            
            if (insertionPointOffsets.isEmpty()) {
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("错误：未能在请求中找到指定的参数。\n\n");
                errorMsg.append("请求的参数：").append(targetParameters).append("\n");
                errorMsg.append("未找到的参数：").append(notFoundParams).append("\n\n");
                errorMsg.append("请检查参数名是否正确，或者参数是否存在于请求中。\n");
                errorMsg.append("支持的参数位置：\n");
                errorMsg.append("- URL 查询参数: ?name=value\n");
                errorMsg.append("- POST 表单: name=value\n");
                errorMsg.append("- JSON 字段: \"name\": \"value\" 或 \"name\": 123\n");
                errorMsg.append("- Cookie: name=value\n");
                return errorMsg.toString();
            }
            
            // 4. 更新全局 payloads 列表
            synchronized (currentPayloads) {
                currentPayloads.clear();
                currentPayloads.addAll(payloads);
            }
            
            // 5. 移除 § 标记，获取干净的请求内容
            String cleanRequest = markedRequest.replace("§", "");
            
            // 6. 创建 HttpService 和 HttpRequest
            HttpService httpService = HttpService.httpService(targetHostname, targetPort, usesHttps);
            HttpRequest httpRequest = HttpRequest.httpRequest(httpService, cleanRequest);
            
            // 7. 创建 HttpRequestTemplate（带插入点偏移）
            List<Range> adjustedOffsets = adjustOffsetsAfterMarkerRemoval(markedRequest, insertionPointOffsets);
            HttpRequestTemplate requestTemplate = HttpRequestTemplate.httpRequestTemplate(httpRequest, adjustedOffsets);
            
            // 8. 发送到 Intruder
            String intruderTabName = tabName != null && !tabName.isEmpty() ? tabName : "AI-Attack";
            api.intruder().sendToIntruder(httpService, requestTemplate, intruderTabName);
            
            // 9. 构建返回信息
            StringBuilder result = new StringBuilder();
            result.append("✅ 请求已发送到 Intruder\n\n");
            result.append("📋 攻击配置：\n");
            result.append("- 标签页: ").append(intruderTabName).append("\n");
            result.append("- 目标: ").append(usesHttps ? "https://" : "http://")
                  .append(targetHostname).append(":").append(targetPort).append("\n");
            result.append("- 已标记的参数: ").append(foundParams).append("\n");
            if (!notFoundParams.isEmpty()) {
                result.append("- ⚠️ 未找到的参数: ").append(notFoundParams).append("\n");
            }
            result.append("- 插入点: ").append(adjustedOffsets.size()).append(" 个\n");
            result.append("- Payloads: ").append(payloads.size()).append(" 个\n\n");
            
            result.append("🚀 用户操作步骤：\n");
            result.append("1. 切换到 Intruder → \"").append(intruderTabName).append("\" 标签页\n");
            result.append("2. 点击 Payloads 选项卡\n");
            result.append("3. Payload type 选择 \"Extension-generated\"\n");
            result.append("4. Payload source 选择 \"").append(PROVIDER_NAME).append("\"\n");
            result.append("5. 点击 \"Start attack\" 开始攻击 🎯\n\n");
            
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
                    ", 参数: " + foundParams + ", payloads: " + payloads.size() + " 个");
            
            return result.toString();
            
        } catch (Exception e) {
            String errorMsg = "发送到 Intruder 失败: " + e.getMessage();
            api.logging().logToError("[BurpExtTools] " + errorMsg);
            e.printStackTrace();
            return "❌ " + errorMsg;
        }
    }
    
    /**
     * 在请求中找到指定参数并用 § 标记其值
     * 支持多种格式：
     * - URL 查询参数: ?name=value 或 &name=value
     * - POST 表单: name=value
     * - JSON: "name": "value" 或 "name": 123
     * - Cookie: name=value
     */
    private String markParameterValue(String request, String paramName) {
        String result = request;
        
        // 1. URL 查询参数 / POST 表单参数: name=value
        // 匹配 ?name=value 或 &name=value 或行首的 name=value
        Pattern urlPattern = Pattern.compile(
            "([?&]|^|\\n)" + Pattern.quote(paramName) + "=([^&\\s\\n\\r]*)",
            Pattern.MULTILINE
        );
        Matcher urlMatcher = urlPattern.matcher(result);
        if (urlMatcher.find()) {
            String prefix = urlMatcher.group(1);
            String value = urlMatcher.group(2);
            result = result.substring(0, urlMatcher.start()) +
                     prefix + paramName + "=§" + value + "§" +
                     result.substring(urlMatcher.end());
            return result;
        }
        
        // 2. JSON 字符串值: "name": "value"
        Pattern jsonStringPattern = Pattern.compile(
            "\"" + Pattern.quote(paramName) + "\"\\s*:\\s*\"([^\"]*)\""
        );
        Matcher jsonStringMatcher = jsonStringPattern.matcher(result);
        if (jsonStringMatcher.find()) {
            String value = jsonStringMatcher.group(1);
            result = result.substring(0, jsonStringMatcher.start()) +
                     "\"" + paramName + "\": \"§" + value + "§\"" +
                     result.substring(jsonStringMatcher.end());
            return result;
        }
        
        // 3. JSON 数字/布尔值: "name": 123 或 "name": true
        Pattern jsonValuePattern = Pattern.compile(
            "\"" + Pattern.quote(paramName) + "\"\\s*:\\s*([^,}\\]\\s]+)"
        );
        Matcher jsonValueMatcher = jsonValuePattern.matcher(result);
        if (jsonValueMatcher.find()) {
            String value = jsonValueMatcher.group(1);
            result = result.substring(0, jsonValueMatcher.start()) +
                     "\"" + paramName + "\": §" + value + "§" +
                     result.substring(jsonValueMatcher.end());
            return result;
        }
        
        // 4. Cookie: name=value（在 Cookie 头中）
        Pattern cookiePattern = Pattern.compile(
            "(Cookie:\\s*[^\\r\\n]*)" + Pattern.quote(paramName) + "=([^;\\r\\n]*)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher cookieMatcher = cookiePattern.matcher(result);
        if (cookieMatcher.find()) {
            int valueStart = cookieMatcher.start(2);
            int valueEnd = cookieMatcher.end(2);
            String value = cookieMatcher.group(2);
            result = result.substring(0, valueStart) +
                     "§" + value + "§" +
                     result.substring(valueEnd);
            return result;
        }
        
        return result; // 未找到参数，返回原请求
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
