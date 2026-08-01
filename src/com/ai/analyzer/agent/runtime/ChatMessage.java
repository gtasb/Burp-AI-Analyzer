package com.ai.analyzer.agent.runtime;

/**
 * 框架无关的聊天消息。
 *
 * <p>用于 {@link AgentRuntime#chat} 的多轮对话参数。
 * 适配器负责在 LangChain4j {@code dev.langchain4j.data.message.ChatMessage}
 * 或 AgentScope {@code io.agentscope.core.message.Message} 之间转换。
 */
public record ChatMessage(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL_RESULT
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage toolResult(String content) {
        return new ChatMessage(Role.TOOL_RESULT, content);
    }
}