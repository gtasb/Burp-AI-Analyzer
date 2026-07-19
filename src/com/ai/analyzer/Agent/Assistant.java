package com.ai.analyzer.Agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;

/**
 * AI Assistant 接口
 * 使用 LangChain4j AI Services 构建
 * 
 * 参考: https://docs.langchain4j.dev/tutorials/ai-services
 */
public interface Assistant {

    /**
     * 基础聊天方法 - 接收原始消息列表
     */
    TokenStream chat(List<ChatMessage> messages);
    
    /**
     * 安全分析方法 - 带用户消息模板
     * 使用 @UserMessage 注解定义消息模板
     * {{content}} 会被替换为实际内容
     */
    @UserMessage("{{content}}")
    TokenStream analyze(@V("content") String content);
}
