package com.ai.analyzer.agent.runtime;

/**
 * 框架无关的 agent 运行时事件类型。
 *
 * <p>这层事件模型同时作为 LangChain4j 回调和 AgentScope {@code streamEvents()}
 * 类型化事件的统一投影，让 UI / 扫描管理器只依赖这一套语义，不直接耦合具体 agent 框架。
 *
 * <p>迁移完成后，AgentScope 的事件会被适配器翻译成这些类型。
 */
public enum RuntimeEventType {
    /** 文本增量（流式 chunk） */
    TEXT_DELTA,
    /** 模型思考增量（thinking） */
    THINKING,
    /** 工具调用开始 */
    TOOL_START,
    /** 工具调用结束（含结果） */
    TOOL_END,
    /** 回复整体完成 */
    REPLY_END,
    /** 错误 */
    ERROR,
    /** 子智能体相关事件（spawn / message / 完成） */
    SUBAGENT,
    /** 记忆 / 上下文压缩事件 */
    MEMORY,
    /** 一次模型调用的 token 用量（input/output/cached/total） */
    USAGE
}
