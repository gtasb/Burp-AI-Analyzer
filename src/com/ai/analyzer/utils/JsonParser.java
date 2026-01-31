package com.ai.analyzer.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 JSON 解析器
 * 用于解析用户自定义参数到 API（如 Ollama 的 format, options, keep_alive, think 等）
 * 
 * 支持的格式示例：
 * - {"format": "json", "keep_alive": "30m", "think": true}
 * - {"options": {"num_ctx": 8192, "top_k": 50, "min_p": 0.05}}
 */
public class JsonParser {
    
    /**
     * 将 JSON 字符串解析为 Map（支持嵌套对象和数组）
     */
    public static Map<String, Object> parseJsonToMap(String jsonStr) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        if (jsonStr == null) return result;
        jsonStr = jsonStr.trim();
        if (jsonStr.isEmpty()) return result;
        
        // 移除外层花括号
        if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
            jsonStr = jsonStr.substring(1, jsonStr.length() - 1).trim();
        }
        
        if (jsonStr.isEmpty()) return result;
        
        // 状态机解析：支持嵌套对象和数组
        int i = 0;
        while (i < jsonStr.length()) {
            // 跳过空白
            while (i < jsonStr.length() && Character.isWhitespace(jsonStr.charAt(i))) i++;
            if (i >= jsonStr.length()) break;
            
            // 解析 key
            String key = parseJsonKey(jsonStr, i);
            if (key == null) break;
            i += countKeyLength(jsonStr, i);
            
            // 跳过空白和冒号
            while (i < jsonStr.length() && (Character.isWhitespace(jsonStr.charAt(i)) || jsonStr.charAt(i) == ':')) i++;
            if (i >= jsonStr.length()) break;
            
            // 解析 value
            ParseResult pr = parseJsonValue(jsonStr, i);
            if (pr != null) {
                result.put(key, pr.value);
                i = pr.endIndex;
            } else {
                break;
            }
            
            // 跳过逗号和空白
            while (i < jsonStr.length() && (Character.isWhitespace(jsonStr.charAt(i)) || jsonStr.charAt(i) == ',')) i++;
        }
        
        return result;
    }
    
    /**
     * 解析结果类，包含值和结束位置
     */
    private static class ParseResult {
        Object value;
        int endIndex;
        ParseResult(Object value, int endIndex) {
            this.value = value;
            this.endIndex = endIndex;
        }
    }
    
    /**
     * 解析 JSON key（去除引号）
     */
    private static String parseJsonKey(String json, int start) {
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        
        // 带引号的 key
        if (c == '"' || c == '\'') {
            char quote = c;
            int end = start + 1;
            while (end < json.length() && json.charAt(end) != quote) {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) end++;
                end++;
            }
            return json.substring(start + 1, end);
        }
        
        // 不带引号的 key
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ':' || Character.isWhitespace(ch)) break;
            end++;
        }
        return json.substring(start, end);
    }
    
    /**
     * 计算 key 在 JSON 中的长度（包含引号）
     */
    private static int countKeyLength(String json, int start) {
        if (start >= json.length()) return 0;
        char c = json.charAt(start);
        
        if (c == '"' || c == '\'') {
            char quote = c;
            int end = start + 1;
            while (end < json.length() && json.charAt(end) != quote) {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) end++;
                end++;
            }
            return end - start + 1; // +1 for closing quote
        }
        
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ':' || Character.isWhitespace(ch)) break;
            end++;
        }
        return end - start;
    }
    
    /**
     * 解析 JSON 值（支持字符串、数字、布尔、null、对象、数组）
     */
    private static ParseResult parseJsonValue(String json, int start) {
        if (start >= json.length()) return null;
        
        char c = json.charAt(start);
        
        // 字符串
        if (c == '"' || c == '\'') {
            char quote = c;
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (ch == '\\' && end + 1 < json.length()) {
                    sb.append(json.charAt(end + 1));
                    end += 2;
                } else if (ch == quote) {
                    end++;
                    break;
                } else {
                    sb.append(ch);
                    end++;
                }
            }
            return new ParseResult(sb.toString(), end);
        }
        
        // 对象
        if (c == '{') {
            int braceCount = 1;
            int end = start + 1;
            while (end < json.length() && braceCount > 0) {
                char ch = json.charAt(end);
                if (ch == '{') braceCount++;
                else if (ch == '}') braceCount--;
                else if (ch == '"' || ch == '\'') {
                    // 跳过字符串内容
                    char quote = ch;
                    end++;
                    while (end < json.length() && json.charAt(end) != quote) {
                        if (json.charAt(end) == '\\' && end + 1 < json.length()) end++;
                        end++;
                    }
                }
                end++;
            }
            String objStr = json.substring(start, end);
            Map<String, Object> nestedMap = parseJsonToMap(objStr);
            return new ParseResult(nestedMap, end);
        }
        
        // 数组
        if (c == '[') {
            int bracketCount = 1;
            int end = start + 1;
            while (end < json.length() && bracketCount > 0) {
                char ch = json.charAt(end);
                if (ch == '[') bracketCount++;
                else if (ch == ']') bracketCount--;
                else if (ch == '"' || ch == '\'') {
                    char quote = ch;
                    end++;
                    while (end < json.length() && json.charAt(end) != quote) {
                        if (json.charAt(end) == '\\' && end + 1 < json.length()) end++;
                        end++;
                    }
                }
                end++;
            }
            String arrContent = json.substring(start + 1, end - 1).trim();
            List<Object> list = parseJsonArray(arrContent);
            return new ParseResult(list, end);
        }
        
        // 数字、布尔、null
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
            end++;
        }
        String valueStr = json.substring(start, end).trim();
        
        // 布尔值
        if ("true".equalsIgnoreCase(valueStr)) {
            return new ParseResult(true, end);
        }
        if ("false".equalsIgnoreCase(valueStr)) {
            return new ParseResult(false, end);
        }
        
        // null
        if ("null".equalsIgnoreCase(valueStr)) {
            return new ParseResult(null, end);
        }
        
        // 数字
        try {
            if (valueStr.contains(".")) {
                return new ParseResult(Double.parseDouble(valueStr), end);
            } else {
                // 尝试 Long，如果溢出则用 Double
                try {
                    return new ParseResult(Long.parseLong(valueStr), end);
                } catch (NumberFormatException e) {
                    return new ParseResult(Double.parseDouble(valueStr), end);
                }
            }
        } catch (NumberFormatException e) {
            // 作为字符串返回
            return new ParseResult(valueStr, end);
        }
    }
    
    /**
     * 解析 JSON 数组
     */
    private static List<Object> parseJsonArray(String arrContent) {
        List<Object> list = new ArrayList<>();
        if (arrContent.isEmpty()) return list;
        
        int i = 0;
        while (i < arrContent.length()) {
            // 跳过空白
            while (i < arrContent.length() && Character.isWhitespace(arrContent.charAt(i))) i++;
            if (i >= arrContent.length()) break;
            
            ParseResult pr = parseJsonValue(arrContent, i);
            if (pr != null) {
                list.add(pr.value);
                i = pr.endIndex;
            } else {
                break;
            }
            
            // 跳过逗号和空白
            while (i < arrContent.length() && (Character.isWhitespace(arrContent.charAt(i)) || arrContent.charAt(i) == ',')) i++;
        }
        
        return list;
    }
}
