package com.ai.analyzer.util;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把多条 HTTP 报文拼成一条批量分析提示词（纯函数，便于测试）。
 * 每条报文格式化后按上限截断，避免批量文本无限膨胀。
 */
public final class BatchPromptBuilder {

    /** 单条报文进入批量提示词的最大字符数 */
    public static final int MAX_ITEM_LENGTH = 6000;

    private BatchPromptBuilder() {
    }

    public static String buildBatchAnalysisPrompt(List<HttpRequestResponse> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(items.size() * 256);
        sb.append("请综合分析以下 ").append(items.size())
                .append(" 个 HTTP 报文，逐条评估安全风险，并指出它们之间的关联（如同参数越权、会话复用、接口权限差异等）。");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\n\n===== 报文 ").append(i + 1).append(" =====");
            String text = formatItem(items.get(i));
            if (text.length() > MAX_ITEM_LENGTH) {
                text = text.substring(0, MAX_ITEM_LENGTH) + "\n...[报文过长已截断]";
            }
            sb.append("\n").append(text);
        }
        return sb.toString();
    }

    static String formatItem(HttpRequestResponse requestResponse) {
        if (requestResponse == null) {
            return "(空报文)";
        }
        StringBuilder sb = new StringBuilder();
        if (requestResponse.request() != null) {
            sb.append("请求:\n")
                    .append(new String(requestResponse.request().toByteArray().getBytes(), StandardCharsets.UTF_8));
        } else {
            sb.append("请求: (无)");
        }
        if (requestResponse.response() != null) {
            sb.append("\n\n响应:\n")
                    .append(new String(requestResponse.response().toByteArray().getBytes(), StandardCharsets.UTF_8));
        } else {
            sb.append("\n\n响应: (无)");
        }
        return sb.toString();
    }
}
