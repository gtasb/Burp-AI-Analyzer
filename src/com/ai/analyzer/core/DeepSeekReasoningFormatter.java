package com.ai.analyzer.core;

import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

/**
 * DeepSeek thinking 模式的 formatter。
 *
 * <p>官方 API 规则（2026-11 实测）：任何带 {@code tool_calls} 的 assistant 消息都必须携带
 * {@code reasoning_content}，包括**历史轮次**；历史轮次带 reasoning 是合法的，不带则返回 400
 * “The reasoning_content in the thinking mode must be passed back to the API”。
 *
 * <p>AgentScope 自带的 {@code compat.deepseek.DeepSeekFormatter} 会按“最后一条 user 之后 /
 * 当前工具段”主动剥离旧轮次的 reasoning，长会话（多轮工具调用）必然触发 400。这里直接复用
 * OpenAI 默认转换（ThinkingBlock → reasoning_content，完整保留），只关闭 DeepSeek 不支持的
 * strict 工具 schema。
 */
public final class DeepSeekReasoningFormatter extends OpenAIChatFormatter {

    @Override
    protected boolean supportsStrict() {
        return false;
    }
}
