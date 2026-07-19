package com.ai.analyzer.listener;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

/**
 * ChatModelListener 实现，用于监听请求和响应，并通过 logDebug 输出详细信息
 */
public class DebugChatModelListener implements ChatModelListener {
    private final MontoyaApi api;

    public DebugChatModelListener(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        logDebug("=== 请求开始 ===");
        if (requestContext.chatRequest() != null) {
            var request = requestContext.chatRequest();
            //logDebug("请求ID: " + request.id());
            //logDebug("模型: " + request.model());
            
            if (request.messages() != null) {
                logDebug("消息数量: " + request.messages().size());
                for (int i = 0; i < request.messages().size(); i++) {
                    ChatMessage msg = request.messages().get(i);
                    String content = msg.toString();
                    // 如果内容太长，只显示前500个字符
                    if (content != null && content.length() > 500) {
                        content = content.substring(0, 500) + "... (截断)";
                    }
                    logDebug("消息[" + i + "] 角色: " + msg.type() + ", 内容: " + content);
                }
            }
            
            if (request.temperature() != null) {
                logDebug("温度: " + request.temperature());
            }

            if (request.topP() != null) {
                logDebug("TopP: " + request.topP());
            }
            if (request.topK() != null) {
                logDebug("TopK: " + request.topK());
            }
        }
        logDebug("=== 请求结束 ===");
    }
    
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        logDebug("=== 响应开始 ===");
        if (responseContext.chatResponse() != null) {
            var response = responseContext.chatResponse();
            if (response.id() != null) {
                logDebug("响应ID: " + response.id());
            }
            if (response.toString() != null) {
                String content = response.toString();
                // 如果内容太长，只显示前500个字符
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "... (截断)";
                }
                logDebug("响应内容: " + content);
            }
            if (response.tokenUsage() != null) {
                logDebug("Token使用情况: " + response.tokenUsage());
            }
            if (response.finishReason() != null) {
                logDebug("完成原因: " + response.finishReason());
            }
        }
        logDebug("=== 响应结束 ===");
    }
    
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        logDebug("=== 请求错误 ===");
        if (errorContext.chatRequest() != null) {
            logDebug("请求ID: " + errorContext.chatRequest().toString());
        }
        if (errorContext.error() != null) {
            Throwable error = errorContext.error();
            logDebug("错误类型: " + error.getClass().getName());
            logDebug("错误消息: " + error.getMessage());
            if (error.getCause() != null) {
                logDebug("错误原因: " + error.getCause().getMessage());
            }
        }
        logDebug("=== 错误结束 ===");
    }
    
    private void logDebug(String message) {
        if (api != null) {
            api.logging().logToOutput("[DebugChatModelListener] " + message);
        }
    }
}

