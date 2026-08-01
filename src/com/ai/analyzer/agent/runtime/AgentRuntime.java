package com.ai.analyzer.agent.runtime;

import java.util.List;

/**
 * 框架无关的 agent 运行时抽象。
 *
 * <p>这是 Burp AI Analyzer 与底层 agent 框架之间的唯一契约。
 * 当前有两个计划实现：
 * <ul>
 *   <li>{@code LangChain4jAgentRuntime} — 适配现有 LangChain4j 代码，作为过渡</li>
 *   <li>{@code AgentScopeAgentRuntime} — AgentScope Java 2.0 原生实现，迁移目标</li>
 * </ul>
 *
 * <p>实现负责：
 * <ul>
 *   <li>模型创建与管理</li>
 *   <li>工具注册（MCP + 本地 @Tool）</li>
 *   <li>系统提示词注入</li>
 *   <li>上下文管理 / 压缩</li>
 *   <li>事件翻译（框架事件 → {@link RuntimeEvent}）</li>
 *   <li>线程调度（框架线程 → UI 线程）</li>
 * </ul>
 *
 * <p>实例通常由 {@code AgentRuntimeFactory} 根据用户配置创建。
 */
public interface AgentRuntime {

    /**
     * 发送单条用户消息并流式返回事件。
     *
     * @param userMessage 用户输入文本
     * @param sessionId   会话标识（用于多轮对话和状态持久化）
     * @param listener    事件回调，实现者保证在合适的线程上调用
     * @return 可用于取消和等待的会话句柄
     */
    StreamSession chat(String userMessage, String sessionId, RuntimeEventListener listener);

    /**
     * 发送多条消息（多轮对话）并流式返回事件。
     *
     * @param messages  消息列表，通常包含系统提示词、历史消息和当前用户消息
     * @param sessionId 会话标识
     * @param listener  事件回调
     * @return 可用于取消和等待的会话句柄
     */
    StreamSession chat(List<ChatMessage> messages, String sessionId, RuntimeEventListener listener);

    /**
     * 关闭此运行时，释放底层资源（HTTP 客户端、线程池等）。
     *
     * <p>关闭后不应再调用 {@link #chat}。
     */
    void shutdown();
}