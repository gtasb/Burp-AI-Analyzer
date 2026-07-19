package com.ai.analyzer.mcpClient;

import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工具执行信息格式化器
 * 类似 Cursor 风格：简洁、醒目、显示关键参数
 */
public class ToolExecutionFormatter {
    
    private static final Gson gson = new GsonBuilder().create();
    
    // 参数值最大显示长度
    private static final int MAX_PARAM_VALUE_LENGTH = 60;
    // 最多显示的参数数量
    private static final int MAX_PARAMS_TO_SHOW = 4;
    
    /**
     * 格式化工具执行信息
     * @param beforeToolExecution 工具执行前的上下文
     * @return 格式化后的字符串，如果格式化失败则返回null
     */
    public static String formatToolExecutionInfo(BeforeToolExecution beforeToolExecution) {
        if (beforeToolExecution == null) {
            return null;
        }
        
        try {
            ToolExecutionRequest request = beforeToolExecution.request();
            if (request == null) {
                return createToolBlock("unknown", null);
            }
            
            String toolName = extractToolName(request);
            String arguments = extractArguments(request);
            
            if (toolName == null || toolName.isEmpty()) {
                toolName = "unknown";
            }
            
            return createToolBlock(toolName, arguments);
        } catch (Exception e) {
            return createToolBlock("error", null);
        }
    }
    
    /**
     * 从 ToolExecutionRequest 中提取工具名称
     */
    private static String extractToolName(ToolExecutionRequest request) {
        try {
            return request.name();
        } catch (Exception e) {
            return extractToolNameFromString(request.toString());
        }
    }
    
    /**
     * 从 ToolExecutionRequest 中提取参数
     */
    private static String extractArguments(ToolExecutionRequest request) {
        try {
            return request.arguments();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 从字符串中提取工具名称（fallback方法）
     */
    private static String extractToolNameFromString(String str) {
        if (str == null) {
            return null;
        }
        
        int start = str.indexOf("toolName=");
        if (start >= 0) {
            start += "toolName=".length();
            int end = str.indexOf(",", start);
            if (end < 0) {
                end = str.indexOf("}", start);
            }
            if (end > start) {
                return str.substring(start, end).trim();
            }
        }
        
        return null;
    }
    
    /**
     * 创建工具执行块
     * 格式: [TOOL_BLOCK]工具名|参数摘要[/TOOL_BLOCK]
     */
    private static String createToolBlock(String toolName, String arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TOOL_BLOCK]");
        sb.append(toolName);
        sb.append("|");
        sb.append(formatArgsSummary(arguments));
        sb.append("[/TOOL_BLOCK]\n");
        return sb.toString();
    }
    
    /**
     * 格式化参数摘要 - 提取关键参数，简洁显示
     */
    private static String formatArgsSummary(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        
        try {
            JsonObject json = gson.fromJson(arguments, JsonObject.class);
            Map<String, String> keyParams = new LinkedHashMap<>();
            
            // 优先显示的关键参数
            String[] priorityKeys = {
                "targetHostname", "host", "url", "endpoint",  // 目标相关
                "method", "path",                              // 请求相关
                "count", "offset", "limit",                    // 数量相关
                "tabName", "name",                             // 名称相关
                "content"                                      // 内容（最后，因为通常很长）
            };
            
            // 先添加优先参数
            for (String key : priorityKeys) {
                if (json.has(key) && keyParams.size() < MAX_PARAMS_TO_SHOW) {
                    String value = formatParamValue(json.get(key));
                    if (value != null && !value.isEmpty()) {
                        keyParams.put(key, value);
                    }
                }
            }
            
            // 如果还有空间，添加其他参数
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (keyParams.size() >= MAX_PARAMS_TO_SHOW) break;
                String key = entry.getKey();
                if (!keyParams.containsKey(key)) {
                    String value = formatParamValue(entry.getValue());
                    if (value != null && !value.isEmpty()) {
                        keyParams.put(key, value);
                    }
                }
            }
            
            if (keyParams.isEmpty()) {
                return "";
            }
            
            // 构建参数字符串 - 使用 ||| 作为分隔符（避免换行符破坏 Markdown 解析）
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : keyParams.entrySet()) {
                if (!first) result.append("|||");
                result.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
            
            // 如果原始 JSON 有更多参数，添加省略号
            if (json.size() > keyParams.size()) {
                result.append("|||...");
            }
            
            return result.toString();
        } catch (Exception e) {
            // JSON 解析失败，返回截断的原始字符串
            return truncateString(arguments, MAX_PARAM_VALUE_LENGTH);
        }
    }
    
    /**
     * 格式化单个参数值
     */
    private static String formatParamValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        
        String value;
        if (element.isJsonPrimitive()) {
            value = element.getAsString();
        } else {
            value = element.toString();
        }
        
        // 对于 content 类型的长值，特殊处理
        if (value.contains("HTTP/1.1") || value.contains("<?xml")) {
            // HTTP 请求内容，只提取第一行
            int newlineIdx = value.indexOf('\n');
            if (newlineIdx > 0) {
                value = value.substring(0, Math.min(newlineIdx, 50)) + "...";
            }
        }
        
        return truncateString(value, MAX_PARAM_VALUE_LENGTH);
    }
    
    /**
     * 截断字符串
     */
    private static String truncateString(String str, int maxLength) {
        if (str == null) return "";
        // 移除换行符
        str = str.replace("\n", " ").replace("\r", "");
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
}

