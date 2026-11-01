package com.ai.analyzer.core;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import java.util.List;

/**
 * DeepSeek thinking 模式的 formatter。
 *
 * <p>官方 API 规则（2026-11 实测）：任何带 {@code tool_calls} 的 assistant 消息都必须携带
 * {@code reasoning_content}，包括**历史轮次**；历史轮次带 reasoning 是合法的，不带则返回 400
 * “The reasoning_content in the thinking mode must be passed back to the API”。
 *
 * <p>AgentScope 2.0.4 自带的 {@code compat.deepseek.DeepSeekFormatter} 会按“最后一条 user 之后 /
 * 当前工具段”主动剥离旧轮次的 reasoning，长会话（多轮工具调用）必然触发 400。这里保留 OpenAI
 * 默认转换带来的真实 reasoning，并为确实缺失的 assistant 消息补空串（官方 API 接受空串），
 * 同时关闭 DeepSeek 不支持的 strict 工具 schema。该行为与 AgentScope 2.1.0-BETA1 的修复一致。
 */
public final class DeepSeekReasoningFormatter extends OpenAIChatFormatter {

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        return padReasoning(super.doFormat(msgs));
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs,
            io.agentscope.core.model.GenerateOptions options) {
        return padReasoning(super.doFormat(msgs, options));
    }

    private static List<OpenAIMessage> padReasoning(List<OpenAIMessage> messages) {
        if (messages == null) return null;
        for (OpenAIMessage m : messages) {
            if (m == null) continue;
            if ("assistant".equals(m.getRole()) && m.getReasoningContent() == null) {
                m.setReasoningContent("");
            }
        }
        return messages;
    }
}
