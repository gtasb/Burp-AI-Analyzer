package com.ai.analyzer.utils;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpSyntaxHighlighter {
    // 定义颜色 - 按Burp风格
    private static final Color HEADER_COLOR = new Color(0, 102, 204);     // 蓝色 - HTTP头
    private static final Color PARAM_NAME_COLOR = new Color(0, 102, 204);  // 蓝色 - 参数名/JSON键
    private static final Color PARAM_VALUE_COLOR = new Color(220, 50, 47); // 红色 - 参数值/JSON值
    private static final Color TEXT_COLOR = Color.BLACK;                   // 黑色 - 普通文本
    private static final Color BACKGROUND_COLOR = Color.WHITE;             // 白色 - 背景

    public static void highlightHttp(JTextPane textPane, String httpContent) {
        StyledDocument doc = textPane.getStyledDocument();
        
        // 设置白色背景
        textPane.setBackground(BACKGROUND_COLOR);
        textPane.setForeground(TEXT_COLOR);
        
        // 清空现有内容
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }

        // 设置默认样式
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style regular = doc.addStyle("regular", defaultStyle);
        StyleConstants.setFontFamily(regular, "Consolas");
        StyleConstants.setFontSize(regular, 12);
        StyleConstants.setForeground(regular, TEXT_COLOR);
        StyleConstants.setBackground(regular, BACKGROUND_COLOR);

        // 创建样式
        Style headerStyle = doc.addStyle("header", regular);
        StyleConstants.setForeground(headerStyle, HEADER_COLOR);
        
        Style paramNameStyle = doc.addStyle("paramName", regular);
        StyleConstants.setForeground(paramNameStyle, PARAM_NAME_COLOR);
        
        Style paramValueStyle = doc.addStyle("paramValue", regular);
        StyleConstants.setForeground(paramValueStyle, PARAM_VALUE_COLOR);

        // 按行处理HTTP内容
        String[] lines = httpContent.split("\n");
        boolean inBody = false;
        StringBuilder bodyContent = new StringBuilder();
        int firstBodyLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            try {
                if (i == 0) {
                    // 第一行
                    doc.insertString(doc.getLength(), line + "\n", regular);
                } else if (line.trim().isEmpty()) {
                    // 空行，表示头部结束，开始body
                    inBody = true;
                    doc.insertString(doc.getLength(), line + "\n", regular);
                } else if (!inBody) {
                    // HTTP头部
                    highlightHeaderLine(doc, line, headerStyle, regular);
                } else {
                    // 收集body内容
                    if (firstBodyLine == -1) {
                        firstBodyLine = i;
                    }
                    bodyContent.append(line).append("\n");
                }
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }
        
        // 处理完整的body内容
        if (bodyContent.length() > 0) {
            String body = bodyContent.toString().trim();
            if (!body.isEmpty()) {
                try {
                    highlightBodyContent(doc, body, paramNameStyle, paramValueStyle, regular);
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void highlightHeaderLine(StyledDocument doc, String line, 
            Style headerStyle, Style regular) throws BadLocationException {
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
            String headerName = line.substring(0, colonIndex).trim();
            String headerValue = line.substring(colonIndex);
            // Header名称用蓝色
            doc.insertString(doc.getLength(), headerName, headerStyle);
            // Header值用黑色
            doc.insertString(doc.getLength(), headerValue + "\n", regular);
        } else {
            doc.insertString(doc.getLength(), line + "\n", regular);
        }
    }

    private static void highlightBodyContent(StyledDocument doc, String content, 
            Style paramNameStyle, Style paramValueStyle, Style regular) throws BadLocationException {
        
        // 检查是否是JSON格式
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            highlightJsonContent(doc, trimmed, paramNameStyle, paramValueStyle, regular);
        } 
        // 检查是否是XML格式
        else if (trimmed.startsWith("<")) {
            highlightXmlContent(doc, trimmed, paramNameStyle, paramValueStyle, regular);
        }
        // 检查是否是URL参数格式
        else if (content.contains("&") && content.contains("=")) {
            highlightUrlParams(doc, content, paramNameStyle, paramValueStyle, regular);
        }
        // 普通文本
        else {
            doc.insertString(doc.getLength(), content + "\n", regular);
        }
    }
    
    private static void highlightJsonContent(StyledDocument doc, String json,
            Style paramNameStyle, Style paramValueStyle, Style regular) throws BadLocationException {
        
        String formatted = formatJson(json);
        if (formatted != null) {
            // 使用格式化后的JSON进行高亮
            String[] lines = formatted.split("\n");
            for (String line : lines) {
                highlightJsonLine(doc, line, paramNameStyle, paramValueStyle, regular);
            }
        } else {
            // 如果格式化失败，逐行高亮
            String[] lines = json.split("\n");
            for (String line : lines) {
                highlightJsonLine(doc, line, paramNameStyle, paramValueStyle, regular);
            }
        }
    }
    
    private static void highlightJsonLine(StyledDocument doc, String line,
            Style paramNameStyle, Style paramValueStyle, Style regular) throws BadLocationException {
        
        Pattern keyValuePattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[-]?\\d+(?:\\.\\d+)?|true|false|null|\\{|\\[)");
        Matcher matcher = keyValuePattern.matcher(line);
        int lastEnd = 0;
        
        while (matcher.find()) {
            // 添加匹配前的文本
            doc.insertString(doc.getLength(), line.substring(lastEnd, matcher.start()), regular);
            
            // 添加引号和键名（蓝色）
            doc.insertString(doc.getLength(), "\"", regular);
            doc.insertString(doc.getLength(), matcher.group(1), paramNameStyle);
            doc.insertString(doc.getLength(), "\"", regular);
            
            // 添加冒号和空格
            int colonIndex = line.indexOf(':', matcher.end(1) + 2);
            if (colonIndex > 0) {
                doc.insertString(doc.getLength(), line.substring(matcher.end(1) + 2, colonIndex + 1), regular);
            }
            
            // 添加值（红色）
            String value = matcher.group(2);
            doc.insertString(doc.getLength(), " ", regular);
            doc.insertString(doc.getLength(), value, paramValueStyle);
            
            lastEnd = matcher.end();
        }
        
        // 添加剩余的文本
        if (lastEnd < line.length()) {
            doc.insertString(doc.getLength(), line.substring(lastEnd), regular);
        }
        
        doc.insertString(doc.getLength(), "\n", regular);
    }
    
    private static String formatJson(String jsonString) {
        try {
            StringBuilder formatted = new StringBuilder();
            int indentLevel = 0;
            boolean inString = false;
            boolean escapeNext = false;
            
            for (int i = 0; i < jsonString.length(); i++) {
                char c = jsonString.charAt(i);
                
                if (escapeNext) {
                    formatted.append(c);
                    escapeNext = false;
                    continue;
                }
                
                if (c == '\\') {
                    escapeNext = true;
                    formatted.append(c);
                    continue;
                }
                
                if (c == '"') {
                    inString = !inString;
                    formatted.append(c);
                } else if (!inString) {
                    if (c == '{' || c == '[') {
                        formatted.append(c);
                        indentLevel++;
                        formatted.append("\n").append(getIndent(indentLevel));
                    } else if (c == '}' || c == ']') {
                        indentLevel--;
                        formatted.append("\n").append(getIndent(indentLevel)).append(c);
                    } else if (c == ',') {
                        formatted.append(c);
                        formatted.append("\n").append(getIndent(indentLevel));
                    } else if (c == ':') {
                        formatted.append(c).append(" ");
                    } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                        formatted.append(c);
                    }
                } else {
                    formatted.append(c);
                }
            }
            
            return formatted.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String getIndent(int level) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) {
            indent.append("  ");
        }
        return indent.toString();
    }
    
    private static void highlightXmlContent(StyledDocument doc, String xml,
            Style paramNameStyle, Style paramValueStyle, Style regular) throws BadLocationException {
        
        // 简单的XML高亮
        Pattern tagPattern = Pattern.compile("</?([^\\s>]+)[^>]*>");
        Matcher matcher = tagPattern.matcher(xml);
        int lastEnd = 0;
        
        while (matcher.find()) {
            // 添加匹配前的文本
            doc.insertString(doc.getLength(), xml.substring(lastEnd, matcher.start()), regular);
            
            // 添加标签（蓝色）
            String tagName = matcher.group(1);
            doc.insertString(doc.getLength(), matcher.group(0), paramNameStyle);
            
            lastEnd = matcher.end();
        }
        
        // 添加剩余的文本
        if (lastEnd < xml.length()) {
            doc.insertString(doc.getLength(), xml.substring(lastEnd), regular);
        }
    }
    
    private static void highlightUrlParams(StyledDocument doc, String line,
            Style paramNameStyle, Style paramValueStyle, Style regular) throws BadLocationException {
        // URL参数格式: param1=value1&param2=value2
        String[] params = line.split("&");
        for (int i = 0; i < params.length; i++) {
            String param = params[i];
            if (param.contains("=")) {
                int eqIndex = param.indexOf('=');
                String paramName = param.substring(0, eqIndex);
                String paramValue = param.substring(eqIndex + 1);
                // 参数名蓝色
                doc.insertString(doc.getLength(), paramName + "=", paramNameStyle);
                // 参数值红色
                doc.insertString(doc.getLength(), paramValue, paramValueStyle);
            } else {
                doc.insertString(doc.getLength(), param, regular);
            }
            if (i < params.length - 1) {
                doc.insertString(doc.getLength(), "&", regular);
            }
        }
        doc.insertString(doc.getLength(), "\n", regular);
    }
}
