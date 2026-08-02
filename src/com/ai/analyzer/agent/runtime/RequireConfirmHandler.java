package com.ai.analyzer.agent.runtime;

/**
 * 用户确认回调（HITL）。
 *
 * <p>当 AgentScope 权限系统返回 ASK 决策（例如 Plan Mode 中 {@code plan_exit}
 * 请求批准执行计划）时，agent 会暂停并等待用户确认。
 * 实现方应把待确认操作展示给用户并返回批准/拒绝结果。
 *
 * <p>回调在 {@link AgentScopeAgentRuntime#chat} 的调用线程上执行
 * （通常是 SwingWorker 后台线程），若需要弹窗请在内部自行切换到 EDT。
 *
 * @param toolName 触发确认的工具名（如 plan_exit）
 * @param summary  待确认操作的可读摘要
 * @return true 批准（允许执行），false 拒绝（agent 留在当前阶段）
 */
@FunctionalInterface
public interface RequireConfirmHandler {

    boolean confirm(String toolName, String summary);
}
