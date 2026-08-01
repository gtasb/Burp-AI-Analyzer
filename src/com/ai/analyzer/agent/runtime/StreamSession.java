package com.ai.analyzer.agent.runtime;

/**
 * 代表一个正在进行的 agent 流式会话。
 *
 * <p>由 {@link AgentRuntime#chat} 返回，用于取消和等待完成。
 * 实现必须是线程安全的。
 */
public interface StreamSession {

    /**
     * 取消当前会话。
     *
     * <p>幂等操作：多次调用效果相同。取消后 listener 的
     * {@link RuntimeEventListener#onComplete} 或
     * {@link RuntimeEventListener#onError} 会被调用一次。
     */
    void cancel();

    /**
     * 阻塞等待会话完成，返回完整响应文本。
     *
     * <p>若会话已被取消，返回 {@code null} 或部分文本。
     * 若会话出错，抛出包装的 {@link RuntimeException}。
     *
     * @return 完整响应文本，或 null（如果取消且无文本）
     * @throws RuntimeException 若会话执行出错
     * @throws InterruptedException 若等待被中断
     */
    String awaitCompletion() throws InterruptedException;

    /**
     * 会话是否已完成（正常结束、出错或取消）。
     *
     * @return true 如果会话不再产生新事件
     */
    boolean isDone();
}