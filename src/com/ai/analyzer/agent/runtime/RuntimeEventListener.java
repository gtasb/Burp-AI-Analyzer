package com.ai.analyzer.agent.runtime;

/**
 * 运行时事件监听器。
 *
 * <p>UI 层实现此接口以接收来自 {@link AgentRuntime#chat} 的流式事件。
 * 所有回调在实现者控制的线程上调用（例如 Swing EDT），由 {@link AgentRuntime}
 * 实现负责线程调度。
 */
@FunctionalInterface
public interface RuntimeEventListener {

    /**
     * 收到一个运行时事件。
     *
     * @param event 框架无关的运行时事件，永远不会为 null
     */
    void onEvent(RuntimeEvent event);

    /**
     * 流正常结束。默认空实现。
     */
    default void onComplete() {}

    /**
     * 流出错。默认空实现。
     *
     * @param error 导致流终止的异常
     */
    default void onError(Throwable error) {}
}