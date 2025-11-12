package com.ai.analyzer.Tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.io.StringReader;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.scanner.audit.issues.AuditIssue;

/**
 * 工具执行器
 * 直接使用Burp Montoya API实现所有工具功能
 */
public class ToolExecutor {
    private final MontoyaApi api;
    
    public ToolExecutor(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 执行工具调用
     * @param toolName 工具名称
     * @param arguments 工具参数（JSON字符串）
     * @return 工具执行结果
     */
    public String executeTool(String toolName, String arguments) {
        api.logging().logToOutput("[ToolExecutor] ========== 开始执行工具 ==========");
        api.logging().logToOutput("[ToolExecutor] 工具名称: " + toolName);
        api.logging().logToOutput("[ToolExecutor] 原始参数 (类型: " + (arguments != null ? arguments.getClass().getSimpleName() : "null") + ", 长度: " + (arguments != null ? arguments.length() : 0) + "): " + arguments);
        try {
            JsonObject args;
            try {
                if (arguments == null || arguments.trim().isEmpty()) {
                    api.logging().logToOutput("[ToolExecutor] 警告: 参数为空或只包含空白字符");
                    args = new JsonObject();
                } else {
                    String trimmedArgs = arguments.trim();
                    
                    // 尝试多种方式解析JSON
                    try {
                        // 方法1: 使用宽松模式解析JSON
                        JsonReader reader = new JsonReader(new StringReader(trimmedArgs));
                        reader.setLenient(true); // 允许宽松的JSON格式
                        JsonElement element = JsonParser.parseReader(reader);
                        
                        if (element.isJsonObject()) {
                            args = element.getAsJsonObject();
                            api.logging().logToOutput("[ToolExecutor] 方法1成功: 使用宽松模式解析JSON对象");
                        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                            api.logging().logToOutput("[ToolExecutor] 方法1: 参数是JSON字符串，尝试二次解析");
                            // 如果参数本身是一个JSON字符串（转义的），再次解析
                            String innerJson = element.getAsString();
                            JsonReader innerReader = new JsonReader(new StringReader(innerJson));
                            innerReader.setLenient(true);
                            JsonElement innerElement = JsonParser.parseReader(innerReader);
                            if (innerElement.isJsonObject()) {
                                args = innerElement.getAsJsonObject();
                            } else {
                                args = new JsonObject();
                            }
                        } else {
                            args = new JsonObject();
                        }
                    } catch (Exception e1) {
                        api.logging().logToOutput("[ToolExecutor] 方法1失败: " + e1.getClass().getSimpleName() + " - " + e1.getMessage());
                        // 方法2: 如果宽松解析也失败，尝试直接解析
                        try {
                            args = JsonParser.parseString(trimmedArgs).getAsJsonObject();
                            api.logging().logToOutput("[ToolExecutor] 方法2成功: 直接解析JSON字符串");
                        } catch (Exception e2) {
                            api.logging().logToOutput("[ToolExecutor] 方法2失败: " + e2.getClass().getSimpleName() + " - " + e2.getMessage());
                            // 方法3: 尝试清理后解析
                            try {
                                // 移除可能的转义字符
                                String cleanedArgs = trimmedArgs
                                    .replace("\\\"", "\"")
                                    .replace("\\n", "")
                                    .replace("\\r", "")
                                    .replace("\\t", "");
                                
                                JsonReader reader = new JsonReader(new StringReader(cleanedArgs));
                                reader.setLenient(true);
                                JsonElement element = JsonParser.parseReader(reader);
                                
                                if (element.isJsonObject()) {
                                    args = element.getAsJsonObject();
                                    api.logging().logToOutput("[ToolExecutor] 方法3成功: 清理后解析JSON对象");
                                } else {
                                    args = new JsonObject();
                                    api.logging().logToOutput("[ToolExecutor] 方法3: 清理后解析结果不是JSON对象，使用空对象");
                                }
                            } catch (Exception e3) {
                                api.logging().logToOutput("[ToolExecutor] 方法3失败: " + e3.getClass().getSimpleName() + " - " + e3.getMessage());
                                // 所有方法都失败，返回错误信息
                                String errorMsg = e1.getMessage();
                                if (errorMsg == null || errorMsg.trim().isEmpty()) {
                                    errorMsg = e1.getClass().getSimpleName();
                                }
                                String argPreview = trimmedArgs.length() > 100 
                                    ? trimmedArgs.substring(0, 100) + "..." 
                                    : trimmedArgs;
                                return "参数解析失败: " + errorMsg + "\n参数内容: " + argPreview;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 提供更详细的错误信息
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.trim().isEmpty()) {
                    errorMsg = e.getClass().getSimpleName();
                }
                String argPreview = arguments != null && arguments.length() > 100 
                    ? arguments.substring(0, 100) + "..." 
                    : arguments;
                api.logging().logToOutput("[ToolExecutor] 参数解析完全失败: " + errorMsg);
                api.logging().logToOutput("[ToolExecutor] 参数预览: " + argPreview);
                return "参数解析失败: " + errorMsg + "\n参数内容: " + argPreview;
            }
            
            api.logging().logToOutput("[ToolExecutor] 参数解析成功，解析后的JSON对象: " + args.toString());
            api.logging().logToOutput("[ToolExecutor] 参数对象键列表: " + args.keySet());
            
            api.logging().logToOutput("[ToolExecutor] 开始执行工具: " + toolName);
            switch (toolName) {
                case "send_http_request":
                    return executeSendHttpRequest(args);
                    
/*                 case "create_repeater_tab":
                    return executeCreateRepeaterTab(args);
                    
                case "send_to_intruder":
                    return executeSendToIntruder(args); */
                    
                case "get_proxy_history":
                    return executeGetProxyHistory(args);
                    
/*                 case "set_proxy_intercept":
                    return executeSetProxyIntercept(args); */
                    
                default:
                    api.logging().logToOutput("[ToolExecutor] 错误: 未知的工具名称: " + toolName);
                    return "未知的工具: " + toolName;
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.trim().isEmpty()) {
                errorMsg = e.getClass().getSimpleName();
            }
            api.logging().logToOutput("[ToolExecutor] ========== 工具执行异常 ==========");
            api.logging().logToOutput("[ToolExecutor] 异常类型: " + e.getClass().getName());
            api.logging().logToOutput("[ToolExecutor] 异常消息: " + errorMsg);
            api.logging().logToError("工具执行异常堆栈: " + e.getClass().getName());
            for (StackTraceElement ste : e.getStackTrace()) {
                api.logging().logToError("  at " + ste.toString());
            }
            return "工具执行错误: " + errorMsg;
        } finally {
            api.logging().logToOutput("[ToolExecutor] ========== 工具执行结束 ==========");
        }
    }
    
    /**
     * 发送HTTP请求
     */
    private String executeSendHttpRequest(JsonObject args) {
        try {
            if (!args.has("targetHostname") || !args.has("targetPort") || !args.has("usesHttps") 
                || !args.has("method") || !args.has("path")) {
                return "错误: 缺少必需参数 (targetHostname, targetPort, usesHttps, method, path)";
            }
            
            String hostname = args.get("targetHostname").getAsString();
            int port = args.get("targetPort").getAsInt();
            boolean usesHttps = args.get("usesHttps").getAsBoolean();
            String method = args.get("method").getAsString();
            String path = args.get("path").getAsString();
            
            // 创建HttpService
            HttpService service = HttpService.httpService(hostname, port, usesHttps);
            
            // 构建HTTP请求
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            requestBuilder.append("Host: ").append(hostname);
            if (port != (usesHttps ? 443 : 80)) {
                requestBuilder.append(":").append(port);
            }
            requestBuilder.append("\r\n");
            
            // 添加自定义头部
            if (args.has("headers") && args.get("headers").isJsonObject()) {
                JsonObject headers = args.getAsJsonObject("headers");
                for (String key : headers.keySet()) {
                    requestBuilder.append(key).append(": ").append(headers.get(key).getAsString()).append("\r\n");
                }
            }
            
            // 添加请求体
            if (args.has("body")) {
                String body = args.get("body").getAsString();
                requestBuilder.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
                requestBuilder.append("\r\n");
                requestBuilder.append(body);
            } else {
                requestBuilder.append("\r\n");
            }
            
            HttpRequest request = HttpRequest.httpRequest(service, requestBuilder.toString());
            
            // 发送请求
            var response = api.http().sendRequest(request);
            
            // 返回响应信息
            StringBuilder result = new StringBuilder();
            result.append("状态码: ").append(response.response().statusCode()).append("\n");
            result.append("响应长度: ").append(response.response().body().length()).append(" bytes\n");
            result.append("响应头:\n");
            response.response().headers().forEach(header -> {
                result.append("  ").append(header.name()).append(": ").append(header.value()).append("\n");
            });
            
            return result.toString();
        } catch (Exception e) {
            return "发送HTTP请求失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
    
    /**
     * 创建Repeater标签页
     */
    private String executeCreateRepeaterTab(JsonObject args) {
        try {
            if (!args.has("targetHostname") || !args.has("targetPort") || !args.has("usesHttps") 
                || !args.has("requestContent")) {
                return "错误: 缺少必需参数 (targetHostname, targetPort, usesHttps, requestContent)";
            }
            
            String hostname = args.get("targetHostname").getAsString();
            int port = args.get("targetPort").getAsInt();
            boolean usesHttps = args.get("usesHttps").getAsBoolean();
            String requestContent = args.get("requestContent").getAsString();
            String tabName = args.has("tabName") ? args.get("tabName").getAsString() : null;
            
            HttpService service = HttpService.httpService(hostname, port, usesHttps);
            HttpRequest request = HttpRequest.httpRequest(service, requestContent);
            
            if (tabName != null && !tabName.trim().isEmpty()) {
                api.repeater().sendToRepeater(request, tabName);
            } else {
                api.repeater().sendToRepeater(request);
            }
            
            return "已成功创建Repeater标签页" + (tabName != null ? " (名称: " + tabName + ")" : "");
        } catch (Exception e) {
            return "创建Repeater标签页失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
    
    /**
     * 发送到Intruder
     */
    private String executeSendToIntruder(JsonObject args) {
        try {
            if (!args.has("targetHostname") || !args.has("targetPort") || !args.has("usesHttps") 
                || !args.has("requestContent")) {
                return "错误: 缺少必需参数 (targetHostname, targetPort, usesHttps, requestContent)";
            }
            
            String hostname = args.get("targetHostname").getAsString();
            int port = args.get("targetPort").getAsInt();
            boolean usesHttps = args.get("usesHttps").getAsBoolean();
            String requestContent = args.get("requestContent").getAsString();
            String tabName = args.has("tabName") ? args.get("tabName").getAsString() : null;
            
            HttpService service = HttpService.httpService(hostname, port, usesHttps);
            HttpRequest request = HttpRequest.httpRequest(service, requestContent);
            
            if (tabName != null && !tabName.trim().isEmpty()) {
                api.intruder().sendToIntruder(request, tabName);
            } else {
                api.intruder().sendToIntruder(request);
            }
            
            return "已成功发送到Intruder" + (tabName != null ? " (名称: " + tabName + ")" : "");
        } catch (Exception e) {
            return "发送到Intruder失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
    
    /**
     * 获取代理历史记录
     */
    private String executeGetProxyHistory(JsonObject args) {
        try {
            if (!args.has("count") || !args.has("offset")) {
                return "错误: 缺少必需参数 (count, offset)";
            }
            
            int count = args.get("count").getAsInt();
            int offset = args.get("offset").getAsInt();
            
            // 获取所有代理历史
            List<ProxyHttpRequestResponse> history;
            if (args.has("regex") && !args.get("regex").getAsString().trim().isEmpty()) {
                String regex = args.get("regex").getAsString();
                Pattern pattern = Pattern.compile(regex);
                history = api.proxy().history().stream()
                    .filter(item -> pattern.matcher(item.request().url()).find())
                    .toList();
            } else {
                history = api.proxy().history();
            }
            
            // 应用分页
            int total = history.size();
            int start = Math.min(offset, total);
            int end = Math.min(offset + count, total);
            
            if (start >= total) {
                return "未找到记录 (偏移量: " + offset + ", 总数: " + total + ")";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("代理历史记录 (显示 ").append(start + 1).append("-").append(end).append(" / 总计 ").append(total).append("):\n\n");
            
            for (int i = start; i < end; i++) {
                ProxyHttpRequestResponse item = history.get(i);
                result.append("[记录 ").append(i + 1).append("]\n");
                result.append("URL: ").append(item.request().url()).append("\n");
                result.append("方法: ").append(item.request().method()).append("\n");
                result.append("状态码: ").append(item.response() != null ? item.response().statusCode() : "N/A").append("\n");
                result.append("时间: ").append(item.time().toString()).append("\n");
                result.append("\n");
            }
            
            return result.toString();
        } catch (Exception e) {
            return "获取代理历史失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
    
    /**
     * 设置代理拦截状态
     */
    private String executeSetProxyIntercept(JsonObject args) {
        try {
            if (!args.has("intercepting")) {
                return "错误: 缺少必需参数 (intercepting)";
            }
            
            boolean intercepting = args.get("intercepting").getAsBoolean();
            
            if (intercepting) {
                api.proxy().enableIntercept();
                return "已启用代理拦截";
            } else {
                api.proxy().disableIntercept();
                return "已禁用代理拦截";
            }
        } catch (Exception e) {
            return "设置代理拦截状态失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
