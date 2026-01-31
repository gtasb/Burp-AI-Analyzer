package com.ai.analyzer.Tools;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.BurpSuiteEdition;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.HttpMode;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import burp.api.montoya.intruder.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.swing.JTextArea;

/**
 * Burp Suite 原生工具类 - 直接使用 Montoya API 提供 Burp 功能
 * 重构说明：移除了 MCP 依赖，直接调用 Burp Suite API
 * 合并了 BurpExtTools 的功能，包括 Intruder 批量测试工具
 * 
 * @author AI Analyzer Team
 */
public class BurpTools {
    
    private final MontoyaApi api;
    
    // ========================================
    // Intruder 批量测试相关（来自 BurpExtTools）
    // ========================================
    
    // 全局唯一的 PayloadGeneratorProvider，固定名称
    private static final String PROVIDER_NAME = "AI Analyzer Payloads";
    private static AIPayloadGeneratorProvider globalProvider = null;
    private static Registration providerRegistration = null;
    
    // 当前活跃的 payloads 列表（线程安全）
    private static final List<String> currentPayloads = Collections.synchronizedList(new ArrayList<>());
    
    public BurpTools(MontoyaApi api) {
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
            api.logging().logToOutput("[BurpTools] 已注册 PayloadGeneratorProvider: " + PROVIDER_NAME);
        }
    }
    
    // ========================================
    // HTTP 请求工具
    // ========================================
    
    /**
     * 发送 HTTP/1.1 请求并返回响应
     */
    @Tool(name = "send_http1_request", value = {
        "发送 HTTP/1.1 请求到指定目标并返回响应。",
        "【⚠️ HTTP 请求格式要求】：",
        "1. **请求行格式**：`METHOD /path?query HTTP/1.1`（协议版本必须在URL之后，用空格分隔）",
        "2. **请求头格式**：每个请求头一行，必须包含 `Host` 头",
        "3. **空行要求**：HTTP 请求头块末尾**必须**有一个空行（\\r\\n\\r\\n）",
        "4. **完整示例**：",
        "   GET /app/weborders.do?param=' OR '1'='1 HTTP/1.1\\r\\n",
        "   Host: 222.73.207.85:8080\\r\\n",
        "   Cookie: JSESSIONID=xxx\\r\\n",
        "   \\r\\n",
        "【使用场景】：SQL注入、XSS、命令注入等漏洞验证"
    })
    public String sendHttp1Request(
            @P("完整的 HTTP 请求内容（包括请求行、请求头和请求体，格式必须正确）") String content,
            @P("目标主机名，例如：example.com") String targetHostname,
            @P("目标端口，例如：443 或 80") int targetPort,
            @P("是否使用HTTPS") boolean usesHttps
    ) {
        api.logging().logToOutput("[BurpTools] send_http1_request 被调用: " + targetHostname + ":" + targetPort);
        try {
            // 修复换行符（将 \n 转换为 \r\n）
            String fixedContent = content.replace("\r", "").replace("\n", "\r\n");
            
            // 创建 HTTP 服务
            HttpService service = HttpService.httpService(targetHostname, targetPort, usesHttps);
            
            // 创建 HTTP 请求
            HttpRequest request = HttpRequest.httpRequest(service, fixedContent);
            
            // 发送请求
            burp.api.montoya.http.message.HttpRequestResponse response = api.http().sendRequest(request);
            
            if (response == null || !response.hasResponse()) {
                api.logging().logToOutput("[BurpTools] send_http1_request 返回: <no response>");
                return "<no response>";
            }
            
            // 构建完整的 HTTP 响应，确保正确处理字符编码
            StringBuilder result = new StringBuilder();
            
            // 1. 添加状态行
            burp.api.montoya.http.message.responses.HttpResponse httpResponse = response.response();
            result.append("HTTP/1.1 ").append(httpResponse.statusCode()).append(" ")
                  .append(httpResponse.reasonPhrase()).append("\r\n");
            
            // 2. 添加响应头
            for (burp.api.montoya.http.message.HttpHeader header : httpResponse.headers()) {
                result.append(header.name()).append(": ").append(header.value()).append("\r\n");
            }
            result.append("\r\n");
            
            // 3. 添加响应体（使用 bodyToString() 自动处理字符编码）
            String responseBody = httpResponse.bodyToString();
            if (responseBody != null && !responseBody.isEmpty()) {
                result.append(responseBody);
            }
            
            String finalResult = result.toString();
            
            // 确保不返回空字符串，防止 LangChain4j content 字段为空
            if (finalResult.trim().isEmpty()) {
                api.logging().logToOutput("[BurpTools] send_http1_request 返回: <empty response>");
                return "<empty response>";
            }
            
            api.logging().logToOutput("[BurpTools] send_http1_request 成功，响应长度: " + finalResult.length());
            return finalResult;
            
        } catch (Exception e) {
            String errorMsg = "错误：发送请求失败 - " + e.getMessage();
            api.logging().logToError("[BurpTools] send_http1_request 失败: " + e.getMessage());
            return errorMsg;
        }
    }
    
    /**
     * 发送 HTTP/2 请求并返回响应
     */
    @Tool(name = "send_http2_request", value = {
        "发送 HTTP/2 请求到指定目标并返回响应。",
        "【参数说明】：",
        "- pseudoHeaders: HTTP/2 伪头部字段（Map），如 :method, :path, :scheme, :authority",
        "- headers: 普通 HTTP 头部字段（Map）",
        "- requestBody: 请求体内容",
        "【使用场景】：测试 HTTP/2 特定的功能或漏洞"
    })
    public String sendHttp2Request(
            @P("HTTP/2 伪头部字段，例如：{\":method\":\"GET\", \":path\":\"/api\", \":scheme\":\"https\", \":authority\":\"example.com\"}") Map<String, String> pseudoHeaders,
            @P("普通 HTTP 头部字段，例如：{\"Cookie\":\"session=abc\", \"Content-Type\":\"application/json\"}") Map<String, String> headers,
            @P("请求体内容（可选）") String requestBody,
            @P("目标主机名") String targetHostname,
            @P("目标端口") int targetPort,
            @P("是否使用HTTPS") boolean usesHttps
    ) {
        try {
            // 创建 HTTP 服务
            HttpService service = HttpService.httpService(targetHostname, targetPort, usesHttps);
            
            // 构建有序的伪头部（按照 HTTP/2 规范的顺序）
            List<String> orderedPseudoHeaderNames = List.of(":scheme", ":method", ":path", ":authority");
            Map<String, String> orderedPseudoHeaders = new LinkedHashMap<>();
            
            // 先添加有序的伪头部
            for (String name : orderedPseudoHeaderNames) {
                String value = pseudoHeaders.get(name.substring(1)); // 移除前缀 ":"
                if (value == null) {
                    value = pseudoHeaders.get(name); // 尝试带 ":" 的键
                }
                if (value != null) {
                    orderedPseudoHeaders.put(name, value);
                }
            }
            
            // 添加其他伪头部
            for (Map.Entry<String, String> entry : pseudoHeaders.entrySet()) {
                String key = entry.getKey().startsWith(":") ? entry.getKey() : ":" + entry.getKey();
                if (!orderedPseudoHeaders.containsKey(key)) {
                    orderedPseudoHeaders.put(key, entry.getValue());
                }
            }
            
            // 合并伪头部和普通头部
            List<HttpHeader> allHeaders = new ArrayList<>();
            for (Map.Entry<String, String> entry : orderedPseudoHeaders.entrySet()) {
                allHeaders.add(HttpHeader.httpHeader(entry.getKey().toLowerCase(), entry.getValue()));
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    allHeaders.add(HttpHeader.httpHeader(entry.getKey().toLowerCase(), entry.getValue()));
                }
            }
            
            // 创建 HTTP/2 请求
            String body = requestBody != null ? requestBody : "";
            HttpRequest request = HttpRequest.http2Request(service, allHeaders, body);
            
            // 发送请求
            burp.api.montoya.http.message.HttpRequestResponse response = api.http().sendRequest(request, HttpMode.HTTP_2);
            
            if (response == null || !response.hasResponse()) {
                return "<no response>";
            }
            
            // 构建完整的 HTTP 响应，确保正确处理字符编码
            StringBuilder result = new StringBuilder();
            
            // 1. 添加状态行
            burp.api.montoya.http.message.responses.HttpResponse httpResponse = response.response();
            result.append("HTTP/2 ").append(httpResponse.statusCode()).append(" ")
                  .append(httpResponse.reasonPhrase()).append("\r\n");
            
            // 2. 添加响应头
            for (burp.api.montoya.http.message.HttpHeader header : httpResponse.headers()) {
                result.append(header.name()).append(": ").append(header.value()).append("\r\n");
            }
            result.append("\r\n");
            
            // 3. 添加响应体（使用 bodyToString() 自动处理字符编码）
            String responseBody = httpResponse.bodyToString();
            if (responseBody != null && !responseBody.isEmpty()) {
                result.append(responseBody);
            }
            
            String finalResult = result.toString();
            
            // 确保不返回空字符串，防止 LangChain4j content 字段为空
            return finalResult.trim().isEmpty() ? "<empty response>" : finalResult;
            
        } catch (Exception e) {
            api.logging().logToError("发送 HTTP/2 请求失败: " + e.getMessage());
            return "错误：发送请求失败 - " + e.getMessage();
        }
    }
    
    /**
     * 创建 Repeater 标签页
     */
    @Tool(name = "create_repeater_tab", value = {
        "创建新的 Repeater 标签页，用于手动测试和验证漏洞。",
        "【使用场景】：",
        "- 发现漏洞后，需要人工确认",
        "- 测试成功后，需要进一步分析响应",
        "【决策规则】：只有需要人类确认的请求才发送到 Repeater"
    })
    public String createRepeaterTab(
            @P("完整的 HTTP 请求内容") String content,
            @P("目标主机名") String targetHostname,
            @P("目标端口") int targetPort,
            @P("是否使用HTTPS") boolean usesHttps,
            @P("Repeater 标签页名称（可选）") String tabName
    ) {
        try {
            // 创建 HTTP 服务
            HttpService service = HttpService.httpService(targetHostname, targetPort, usesHttps);
            
            // 创建 HTTP 请求
            HttpRequest request = HttpRequest.httpRequest(service, content);
            
            // 发送到 Repeater
            if (tabName != null && !tabName.isEmpty()) {
                api.repeater().sendToRepeater(request, tabName);
            } else {
                api.repeater().sendToRepeater(request);
            }
            
            api.logging().logToOutput("已创建 Repeater 标签页: " + (tabName != null ? tabName : "默认"));
            return "成功创建 Repeater 标签页" + (tabName != null ? ": " + tabName : "");
            
        } catch (Exception e) {
            api.logging().logToError("创建 Repeater 标签页失败: " + e.getMessage());
            return "错误：创建 Repeater 标签页失败 - " + e.getMessage();
        }
    }
    
    /**
     * 发送请求到 Intruder，并使用 AI 生成的 payloads（高级版本）
     * 
     * AI 只需指定要注入的参数名，工具会自动在请求中找到并标记插入点。
     * 
     * Payloads 会自动配置到全局 Provider，用户只需：
     * 1. 在 Intruder 中选择 Payload type 为 "Extension-generated"
     * 2. 选择 "AI Analyzer Payloads" 作为 Payload source
     * 3. 点击 "Start attack" 开始攻击
     */
    @Tool(name = "send_to_intruder", value = {
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
            
            api.logging().logToOutput("[BurpTools] 已发送到 Intruder: " + intruderTabName + 
                    ", 参数: " + foundParams + ", payloads: " + payloads.size() + " 个");
            
            return result.toString();
            
        } catch (Exception e) {
            String errorMsg = "发送到 Intruder 失败: " + e.getMessage();
            api.logging().logToError("[BurpTools] " + errorMsg);
            e.printStackTrace();
            return "❌ " + errorMsg;
        }
    }
    
    // ========================================
    // 编码/解码工具
    // ========================================
    
    @Tool(name = "url_encode", value = "URL 编码字符串")
    public String urlEncode(@P("要编码的字符串") String content) {
        try {
            if (content == null || content.isEmpty()) {
                return "<Empty input>";
            }
            String result = api.utilities().urlUtils().encode(content);
            return result != null && !result.isEmpty() ? result : "<Empty result>";
        } catch (Exception e) {
            return "错误：URL 编码失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "url_decode", value = "URL 解码字符串")
    public String urlDecode(@P("要解码的字符串") String content) {
        try {
            if (content == null || content.isEmpty()) {
                return "<Empty input>";
            }
            String result = api.utilities().urlUtils().decode(content);
            return result != null && !result.isEmpty() ? result : "<Empty result>";
        } catch (Exception e) {
            return "错误：URL 解码失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "base64_encode", value = "Base64 编码字符串")
    public String base64Encode(@P("要编码的字符串") String content) {
        try {
            if (content == null || content.isEmpty()) {
                return "<Empty input>";
            }
            String result = api.utilities().base64Utils().encodeToString(content);
            return result != null && !result.isEmpty() ? result : "<Empty result>";
        } catch (Exception e) {
            return "错误：Base64 编码失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "base64_decode", value = "Base64 解码字符串")
    public String base64Decode(@P("要解码的字符串") String content) {
        try {
            if (content == null || content.isEmpty()) {
                return "<Empty input>";
            }
            String result = api.utilities().base64Utils().decode(content).toString();
            return result != null && !result.isEmpty() ? result : "<Empty result>";
        } catch (Exception e) {
            return "错误：Base64 解码失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "generate_random_string", value = {
        "生成指定长度和字符集的随机字符串",
        "【字符集选项】：",
        "- ALPHANUMERIC: 字母和数字",
        "- ALPHA: 只包含字母",
        "- NUMERIC: 只包含数字",
        "- HEX: 十六进制字符"
    })
    public String generateRandomString(
            @P("字符串长度") int length,
            @P("字符集，可选：ALPHANUMERIC, ALPHA, NUMERIC, HEX") String characterSet
    ) {
        try {
            if (length <= 0) {
                return "错误：长度必须大于 0";
            }
            String result = api.utilities().randomUtils().randomString(length, characterSet);
            return result != null && !result.isEmpty() ? result : "<Empty result>";
        } catch (Exception e) {
            return "错误：生成随机字符串失败 - " + e.getMessage();
        }
    }
    
    // ========================================
    // 配置管理工具
    // ========================================
    
    @Tool(name = "output_project_options", value = {
        "导出当前项目级别的配置（JSON 格式）",
        "【用途】：查看项目配置结构，为 set_project_options 提供参考"
    })
    public String outputProjectOptions() {
        try {
            String result = api.burpSuite().exportProjectOptionsAsJson();
            return result != null && !result.trim().isEmpty() ? result : "<No project options available>";
        } catch (Exception e) {
            return "错误：导出项目配置失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "output_user_options", value = {
        "导出当前用户级别的配置（JSON 格式）",
        "【用途】：查看用户配置结构，为 set_user_options 提供参考"
    })
    public String outputUserOptions() {
        try {
            String result = api.burpSuite().exportUserOptionsAsJson();
            return result != null && !result.trim().isEmpty() ? result : "<No user options available>";
        } catch (Exception e) {
            return "错误：导出用户配置失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "set_project_options", value = {
        "设置项目级别的配置（JSON 格式）",
        "【警告】：此操作会修改 Burp Suite 配置，请谨慎使用",
        "【要求】：JSON 必须有顶层 'project_options' 对象"
    })
    public String setProjectOptions(@P("JSON 格式的项目配置") String json) {
        try {
            api.logging().logToOutput("设置项目配置: " + json);
            api.burpSuite().importProjectOptionsFromJson(json);
            return "项目配置已应用";
        } catch (Exception e) {
            api.logging().logToError("设置项目配置失败: " + e.getMessage());
            return "错误：设置项目配置失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "set_user_options", value = {
        "设置用户级别的配置（JSON 格式）",
        "【警告】：此操作会修改 Burp Suite 配置，请谨慎使用",
        "【要求】：JSON 必须有顶层 'user_options' 对象"
    })
    public String setUserOptions(@P("JSON 格式的用户配置") String json) {
        try {
            api.logging().logToOutput("设置用户配置: " + json);
            api.burpSuite().importUserOptionsFromJson(json);
            return "用户配置已应用";
        } catch (Exception e) {
            api.logging().logToError("设置用户配置失败: " + e.getMessage());
            return "错误：设置用户配置失败 - " + e.getMessage();
        }
    }
    
    // ========================================
    // 历史记录查询工具
    // ========================================
    
    @Tool(name = "get_proxy_http_history", value = {
        "获取代理 HTTP 历史记录",
        "【参数说明】：",
        "- count: 返回的记录数量（默认 10）",
        "- offset: 偏移量（默认 0）",
        "【用途】：查看最近的 HTTP 请求历史"
    })
    public String getProxyHttpHistory(
            @P("返回的记录数量") int count,
            @P("偏移量") int offset
    ) {
        try {
            List<String> history = api.proxy().history().stream()
                    .skip(offset)
                    .limit(count)
                    .map(item -> {
                        String url = item.url();
                        int statusCode = item.response() != null ? item.response().statusCode() : 0;
                        String method = item.request().method();
                        return String.format("Method: %s, URL: %s, Status: %d", method, url, statusCode);
                    })
                    .collect(Collectors.toList());
            
            if (history.isEmpty()) {
                return "没有找到历史记录";
            }
            
            return String.join("\n", history);
            
        } catch (Exception e) {
            api.logging().logToError("获取代理历史失败: " + e.getMessage());
            return "错误：获取代理历史失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "get_proxy_http_history_regex", value = {
        "按正则表达式搜索代理 HTTP 历史记录",
        "【参数说明】：",
        "- regex: 正则表达式（匹配 URL、请求或响应内容）",
        "- count: 返回的记录数量（默认 10）",
        "- offset: 偏移量（默认 0）",
        "【用途】：搜索包含特定关键词的请求"
    })
    public String getProxyHttpHistoryRegex(
            @P("正则表达式，例如：.*(login|api|upload).*") String regex,
            @P("返回的记录数量") int count,
            @P("偏移量") int offset
    ) {
        try {
            Pattern pattern = Pattern.compile(regex);
            
            List<String> matchedHistory = api.proxy().history(item -> item.contains(pattern)).stream()
                    .skip(offset)
                    .limit(count)
                    .map(item -> {
                        String url = item.url();
                        int statusCode = item.response() != null ? item.response().statusCode() : 0;
                        String method = item.request().method();
                        return String.format("Method: %s, URL: %s, Status: %d", method, url, statusCode);
                    })
                    .collect(Collectors.toList());
            
            if (matchedHistory.isEmpty()) {
                return "没有找到匹配的历史记录";
            }
            
            return String.join("\n", matchedHistory);
            
        } catch (Exception e) {
            api.logging().logToError("搜索代理历史失败: " + e.getMessage());
            return "错误：搜索代理历史失败 - " + e.getMessage();
        }
    }
    
    // ========================================
    // 扫描器工具
    // ========================================
    
    @Tool(name = "get_scanner_issues", value = {
        "获取 Burp Scanner 发现的安全问题",
        "【注意】：此功能仅在 Burp Suite Professional 版本中可用",
        "【参数说明】：",
        "- count: 返回的问题数量（默认 10）",
        "- offset: 偏移量（默认 0）"
    })
    public String getScannerIssues(
            @P("返回的问题数量") int count,
            @P("偏移量") int offset
    ) {
        try {
            // 检查是否为 Professional 版本
            if (api.burpSuite().version().edition() != BurpSuiteEdition.PROFESSIONAL) {
                return "错误：此功能仅在 Burp Suite Professional 版本中可用";
            }
            
            List<String> issues = api.siteMap().issues().stream()
                    .skip(offset)
                    .limit(count)
                    .map(issue -> {
                        String name = issue.name();
                        String severity = issue.severity().toString();
                        String confidence = issue.confidence().toString();
                        String url = issue.baseUrl();
                        return String.format("Name: %s, Severity: %s, Confidence: %s, URL: %s", 
                                name, severity, confidence, url);
                    })
                    .collect(Collectors.toList());
            
            if (issues.isEmpty()) {
                return "没有找到扫描器问题";
            }
            
            return String.join("\n", issues);
            
        } catch (Exception e) {
            api.logging().logToError("获取扫描器问题失败: " + e.getMessage());
            return "错误：获取扫描器问题失败 - " + e.getMessage();
        }
    }
    
    // ========================================
    // 其他工具
    // ========================================
    
    @Tool(name = "set_task_execution_engine_state", value = {
        "设置 Burp 任务执行引擎的状态（运行或暂停）",
        "【用途】：控制 Burp Scanner 等后台任务的执行",
        "【注意】：当前版本暂不支持此功能（Montoya API 限制）"
    })
    public String setTaskExecutionEngineState(@P("是否运行（true=运行，false=暂停）") boolean running) {
        // 注意：Montoya API 的 TaskExecutionEngine 接口在 Java 中没有 pause/resume 方法
        // Kotlin 代码使用 state 属性赋值，但 Java 中无法直接使用
        // 暂时返回提示信息
        api.logging().logToOutput("set_task_execution_engine_state 功能当前不可用（Montoya API 限制）");
        return "当前版本不支持设置任务执行引擎状态（Montoya API 限制）";
    }
    
    @Tool(name = "set_proxy_intercept_state", value = {
        "启用或禁用 Burp Proxy 拦截功能",
        "【用途】：控制是否拦截 HTTP 请求/响应"
    })
    public String setProxyInterceptState(@P("是否启用拦截（true=启用，false=禁用）") boolean intercepting) {
        try {
            if (intercepting) {
                api.proxy().enableIntercept();
            } else {
                api.proxy().disableIntercept();
            }
            
            return "代理拦截已" + (intercepting ? "启用" : "禁用");
            
        } catch (Exception e) {
            api.logging().logToError("设置代理拦截状态失败: " + e.getMessage());
            return "错误：设置代理拦截状态失败 - " + e.getMessage();
        }
    }
    
    @Tool(name = "get_active_editor_contents", value = {
        "获取当前活动的消息编辑器的内容",
        "【用途】：读取用户当前正在编辑的 HTTP 请求/响应"
    })
    public String getActiveEditorContents() {
        api.logging().logToOutput("[BurpTools] get_active_editor_contents 被调用");
        try {
            JTextArea editor = getActiveEditor();
            if (editor == null) {
                api.logging().logToOutput("[BurpTools] get_active_editor_contents 返回: <No active editor>");
                return "<No active editor>";
            }
            
            String text = editor.getText();
            // 防止返回空字符串导致 LangChain4j 的 content 字段为空
            if (text == null || text.trim().isEmpty()) {
                api.logging().logToOutput("[BurpTools] get_active_editor_contents 返回: <Editor is empty>");
                return "<Editor is empty>";
            }
            
            api.logging().logToOutput("[BurpTools] get_active_editor_contents 成功，内容长度: " + text.length());
            return text;
            
        } catch (Exception e) {
            String errorMsg = "错误：获取活动编辑器内容失败 - " + e.getMessage();
            api.logging().logToError("[BurpTools] " + errorMsg);
            return errorMsg;
        }
    }
    
    @Tool(name = "set_active_editor_contents", value = {
        "设置当前活动的消息编辑器的内容",
        "【用途】：修改用户当前正在编辑的 HTTP 请求/响应",
        "【注意】：只能修改可编辑的编辑器"
    })
    public String setActiveEditorContents(@P("要设置的文本内容") String text) {
        try {
            JTextArea editor = getActiveEditor();
            if (editor == null) {
                return "<No active editor>";
            }
            
            if (!editor.isEditable()) {
                return "<Current editor is not editable>";
            }
            
            editor.setText(text);
            return "编辑器内容已设置";
            
        } catch (Exception e) {
            api.logging().logToError("设置活动编辑器内容失败: " + e.getMessage());
            return "错误：设置活动编辑器内容失败 - " + e.getMessage();
        }
    }
    
    // ========================================
    // 辅助方法
    // ========================================
    
    /**
     * 获取当前活动的编辑器
     */
    private JTextArea getActiveEditor() {
        try {
            java.awt.Window frame = api.userInterface().swingUtils().suiteFrame();
            KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            java.awt.Component permanentFocusOwner = focusManager.getPermanentFocusOwner();
            
            // 检查焦点组件是否在 Burp 窗口中
            java.awt.Component current = permanentFocusOwner;
            boolean isInBurpWindow = false;
            while (current != null) {
                if (current == frame) {
                    isInBurpWindow = true;
                    break;
                }
                current = current.getParent();
            }
            
            if (isInBurpWindow && permanentFocusOwner instanceof JTextArea) {
                return (JTextArea) permanentFocusOwner;
            }
            
            return null;
            
        } catch (Exception e) {
            api.logging().logToError("获取活动编辑器失败: " + e.getMessage());
            return null;
        }
    }
    
    // ========================================
    // Intruder 辅助方法（来自 BurpExtTools）
    // ========================================
    
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
     * 获取当前 payloads 数量（用于调试）
     */
    public static int getCurrentPayloadsCount() {
        return currentPayloads.size();
    }
    
    // ========================================
    // 内部类：PayloadGeneratorProvider 和 PayloadGenerator
    // ========================================
    
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
}
