package com.ai.analyzer.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每请求轻量 {@link ReActAgent} 运行时：被动扫描专用。
 *
 * <p>与 {@link AgentScopeAgentRuntime}（单例 {@code HarnessAgent} + workspace/memory/compaction）
 * 不同，本实现<b>每次 {@link #chat} 都新建一个裸 {@link ReActAgent}</b>（reason→tool→reply 循环），
 * 用后即关（{@link AutoCloseable}）。因此：
 * <ul>
 *   <li>无共享可变状态，天然隔离 —— 一个请求的工具调用/取消不会污染另一个请求；</li>
 *   <li>可被 {@code Semaphore} 安全地限定并发，实现「有界的模型并发预算」；</li>
 *   <li>无 workspace / session 持久化，跨请求的长期记忆交给共享 Notebook（黑板）承载。</li>
 * </ul>
 *
 * <p>只在 {@code Model} 与 {@code Toolkit} 两处共享（两者被设计为可跨 agent 复用），
 * 其余全部请求内局部。
 */
public class ReActAgentRuntime implements AgentRuntime {

    private final String agentName;
    private final Model model;
    private final String systemPrompt;
    private final Toolkit toolkit;
    private final long chatTimeoutMs;
    private final int maxIters;
    private final List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories;

    private ReActAgentRuntime(Builder builder) {
        this.agentName = builder.agentName;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.toolkit = builder.toolkit;
        this.chatTimeoutMs = builder.chatTimeoutMs;
        this.maxIters = builder.maxIters;
        this.skillRepositories = builder.skillRepositories;
    }

    @Override
    public StreamSession chat(String userMessage, String sessionId, RuntimeEventListener listener) {
        return chat(List.of(ChatMessage.user(userMessage)), sessionId, listener);
    }

    @Override
    public StreamSession chat(List<ChatMessage> messages, String sessionId, RuntimeEventListener listener) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String userId = System.getProperty("user.name", "burp-user");
        List<Msg> asMessages = AgentScopeAgentRuntime.toAgentScopeMessages(messages);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

        AtomicReference<Subscription> subRef = new AtomicReference<>();
        StreamSession session = new ReactiveStreamSession(future, () -> {
            Subscription s = subRef.get();
            if (s != null) {
                s.cancel();
            }
            future.cancel(true);
        });

        try {
            var builder = ReActAgent.builder()
                    .name(agentName)
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(maxIters)
                    .permissionContext(PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .build())
                    // 2.0.3：会话状态并发写入冲突策略（每请求 agent 并发场景下允许覆盖而不是抛错）
                    .conflictPolicy(io.agentscope.core.state.ConflictPolicy.OVERWRITE)
                    .toolExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(120))
                            .maxAttempts(2)
                            .build());
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                builder.sysPrompt(systemPrompt);
            }
            if (skillRepositories != null && !skillRepositories.isEmpty()) {
                builder.skillRepositories(skillRepositories);
            }

            // 每请求一个 agent，用后即关（AutoCloseable）
            try (ReActAgent agent = builder.build()) {
                agent.streamEvents(asMessages, ctx)
                        .doOnSubscribe(subRef::set)
                        .doOnNext(event -> translate(event, listener))
                        .doOnComplete(() -> {
                            subRef.set(null);
                            listener.onComplete();
                        })
                        .doOnError(error -> {
                            subRef.set(null);
                            listener.onEvent(RuntimeEvent.error(error));
                            listener.onError(error);
                            future.completeExceptionally(error);
                        })
                        .blockLast(Duration.ofMillis(chatTimeoutMs));
            }
            future.complete(null);
        } catch (Exception e) {
            listener.onEvent(RuntimeEvent.error(e));
            listener.onError(e);
            future.completeExceptionally(e);
        }

        return session;
    }

    @Override
    public void shutdown() {
        // 无持久资源；ReActAgent 在每次 chat 结束即关闭，这里无需额外清理
    }

    /** 将 AgentScope 类型化事件翻译为 UI/扫描管理器关注的 {@link RuntimeEvent}。 */
    private static void translate(AgentEvent event, RuntimeEventListener listener) {
        if (event == null) {
            return;
        }
        if (event instanceof TextBlockDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                listener.onEvent(RuntimeEvent.textDelta(delta));
            }
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                listener.onEvent(RuntimeEvent.thinking(delta));
            }
        } else if (event instanceof ToolCallStartEvent e) {
            com.ai.analyzer.util.AppLogBuffer.tool("ReActAgentRuntime", "调用工具: " + e.getToolCallName());
            listener.onEvent(RuntimeEvent.toolStart(e.getToolCallName(), null));
        } else if (event instanceof ToolResultEndEvent e) {
            boolean failed = e.getState() != ToolResultState.SUCCESS;
            com.ai.analyzer.util.AppLogBuffer.tool("ReActAgentRuntime",
                    e.getToolCallName() + " → " + (failed ? "失败" : "成功") + " (" + e.getState() + ")");
            listener.onEvent(RuntimeEvent.toolEnd(e.getToolCallName(), null, failed, -1));
        } else if (event instanceof io.agentscope.core.event.ModelCallEndEvent e) {
            io.agentscope.core.model.ChatUsage usage = e.getUsage();
            if (usage != null) {
                com.ai.analyzer.util.AppLogBuffer.debug("ReActAgentRuntime",
                        "model_call usage in=" + usage.getInputTokens()
                                + " out=" + usage.getOutputTokens()
                                + " cached=" + usage.getCachedTokens()
                                + " total=" + usage.getTotalTokens());
                listener.onEvent(RuntimeEvent.usage(
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getCachedTokens(),
                        usage.getTotalTokens()));
            }
        } else if (event instanceof AgentResultEvent e) {
            Msg result = e.getResult();
            if (result != null) {
                String text = result.getTextContent();
                if (text != null && !text.isEmpty()) {
                    listener.onEvent(RuntimeEvent.replyEnd(text));
                }
            }
        }
        // 其余（HINT_BLOCK / MODEL_CALL_START 等）静默忽略
    }

    private static final class ReactiveStreamSession implements StreamSession {
        private final CompletableFuture<String> future;
        private final Runnable canceller;
        private volatile boolean cancelled;

        ReactiveStreamSession(CompletableFuture<String> future, Runnable canceller) {
            this.future = future;
            this.canceller = canceller;
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            canceller.run();
            future.cancel(true);
        }

        @Override
        public String awaitCompletion() throws InterruptedException {
            try {
                return future.get(10, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                cancel();
                throw new RuntimeException("流式输出超时（10分钟）", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw new RuntimeException(cause != null ? cause : e);
            } catch (java.util.concurrent.CancellationException e) {
                return null;
            }
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String agentName = "burp-passive-analyzer";
        private Model model;
        private String systemPrompt;
        private Toolkit toolkit;
        private long chatTimeoutMs = TimeUnit.MINUTES.toMillis(10);
        private int maxIters = 15;
        private List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories;

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder chatTimeoutMs(long chatTimeoutMs) {
            this.chatTimeoutMs = chatTimeoutMs;
            return this;
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        public Builder skillRepositories(List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories) {
            this.skillRepositories = skillRepositories;
            return this;
        }

        public ReActAgentRuntime build() {
            if (model == null) {
                throw new IllegalStateException("model is required");
            }
            return new ReActAgentRuntime(this);
        }
    }
}