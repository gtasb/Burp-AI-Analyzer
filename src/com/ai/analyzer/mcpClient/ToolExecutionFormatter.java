package com.ai.analyzer.mcpClient;

import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具执行信息格式化器
 * 用于将工具执行信息格式化为Markdown格式的灰体小字，显示在UI中
 */
public class ToolExecutionFormatter {
    
    /**
     * 格式化工具执行信息为Markdown格式的灰体小字
     * @param beforeToolExecution 工具执行前的上下文
     * @return 格式化后的Markdown字符串，如果格式化失败则返回null
     */
    public static String formatToolExecutionInfo(BeforeToolExecution beforeToolExecution) {
        if (beforeToolExecution == null) {
            return null;
        }
        
        try {
            ToolExecutionRequest request = beforeToolExecution.request();
            if (request == null) {
                return createToolInfoMarkdown("正在执行工具...");
            }
            
            String toolName = extractToolName(request);
            
            if (toolName == null || toolName.isEmpty()) {
                return createToolInfoMarkdown("正在执行工具...");
            }
            
            return createToolInfoMarkdown(toolName);
        } catch (Exception e) {
            // 格式化失败，返回默认信息
            return createToolInfoMarkdown("正在执行工具...");
        }
    }
    
    /**
     * 从 ToolExecutionRequest 中提取工具名称
     */
    private static String extractToolName(ToolExecutionRequest request) {
        try {
            // ToolExecutionRequest 应该有 name() 方法
            return request.name();
        } catch (Exception e) {
            // 如果失败，尝试从 toString() 中提取
            return extractToolNameFromString(request.toString());
        }
    }
    
    
    /**
     * 从字符串中提取工具名称（fallback方法）
     */
    private static String extractToolNameFromString(String str) {
        if (str == null) {
            return null;
        }
        
        // 尝试从 "toolName=xxx" 格式中提取
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
     * 创建工具信息的Markdown格式
     * 使用特殊标记 [TOOL] 让 MarkdownRenderer 识别并应用醒目样式
     * 只显示工具名称，不显示参数（保持简洁）
     */
    private static String createToolInfoMarkdown(String toolName) {
        // 转义Markdown特殊字符
        String escapedName = escapeMarkdown(toolName);
        // 使用特殊的标记格式，让 MarkdownRenderer 可以识别并应用特殊样式
        // 格式: [TOOL]工具名称[/TOOL]
        return "[TOOL]⚡ 正在执行工具: " + escapedName + "[/TOOL]\n\n";
    }
    
    /**
     * Markdown转义，防止特殊字符被解析
     * 注意：工具名称在代码块（反引号）中，所以不需要转义下划线
     */
    private static String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        // 转义Markdown特殊字符
        // 注意：下划线 _ 在代码块中不需要转义，所以不转义它
        // 反引号 ` 需要转义，因为它是代码块的边界
        return text.replace("\\", "\\\\")
                   .replace("`", "\\`");
    }
    
}

