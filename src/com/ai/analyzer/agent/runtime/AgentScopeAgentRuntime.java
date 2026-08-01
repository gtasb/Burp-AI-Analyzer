package com.ai.analyzer.agent.runtime;

import com.ai.analyzer.util.DebugContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import org.reactivestreams.Subscription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AgentRuntime} 的 AgentScope Java 2.0 原生实现。
 *
 * <p>使用 AgentScope 的 {@link HarnessAgent} 作为底层执行引擎，
 * 通过 {@code streamEvents()} 获取类型化 {@link AgentEvent} 流并翻译为 {@link RuntimeEvent}。
 *
 * <h3>架构</h3>
 * <ul>
 *   <li>Active 模式 → {@link HarnessAgent}：交互式渗透测试，持久会话</li>
 *   <li>Passive 模式 → {@link HarnessAgent}：批量被动扫描，持久记忆 + 自动技能注入</li>
 * </ul>
 *
 * <h3>事件映射</h3>
 * AgentScope {@code AgentEvent} 子类 → {@link RuntimeEventType}：
 * <pre>
 *   TextBlockDeltaEvent      → TEXT_DELTA
 *   ThinkingBlockDeltaEvent  → THINKING
 *   ToolCallStartEvent       → TOOL_START
 *   ToolResultEndEvent       → TOOL_END（含成功/失败状态）
 *   AgentResultEvent         → REPLY_END
 *   SubagentExposedEvent     → SUBAGENT
 * </pre>
 */
public class AgentScopeAgentRuntime implements AgentRuntime {

    private final Mode mode;
    private final Model model;
    private final String systemPrompt;
    private final Path workspacePath;
    private final Toolkit toolkit;
    private final long chatTimeoutMs;
    private final boolean disableFilesystemTools;
    private final boolean disableShellTool;
    private final boolean enablePlanMode;
    private final int maxContextTokens;
    private final List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories;

    private volatile HarnessAgent harnessAgent;
    private final AtomicReference<Subscription> currentSubscription = new AtomicReference<>();

    private AgentScopeAgentRuntime(Builder builder) {
        this.mode = builder.mode;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.workspacePath = builder.workspacePath;
        this.toolkit = builder.toolkit;
        this.chatTimeoutMs = builder.chatTimeoutMs;
        this.disableFilesystemTools = builder.disableFilesystemTools;
        this.disableShellTool = builder.disableShellTool;
        this.enablePlanMode = builder.enablePlanMode;
        this.maxContextTokens = builder.maxContextTokens;
        this.skillRepositories = builder.skillRepositories;
    }

    // ---- AgentRuntime implementation ----

    @Override
    public StreamSession chat(String userMessage, String sessionId, RuntimeEventListener listener) {
        return chat(List.of(com.ai.analyzer.agent.runtime.ChatMessage.user(userMessage)), sessionId, listener);
    }

    @Override
    public StreamSession chat(List<com.ai.analyzer.agent.runtime.ChatMessage> messages,
                              String sessionId,
                              RuntimeEventListener listener) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String userId = System.getProperty("user.name", "burp-user");

        List<Msg> asMessages = toAgentScopeMessages(messages);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

        AgentScopeStreamSession session = new AgentScopeStreamSession(future, () -> {
            Subscription sub = currentSubscription.get();
            if (sub != null) {
                sub.cancel();
            }
            future.cancel(true);
        });

        try {
            buildEventStream(asMessages, ctx, listener, future)
                    .blockLast(java.time.Duration.ofMillis(chatTimeoutMs));
        } catch (Exception e) {
            listener.onEvent(RuntimeEvent.error(e));
            listener.onError(e);
            future.completeExceptionally(e);
        }

        return session;
    }

    @Override
    public void shutdown() {
        Subscription sub = currentSubscription.getAndSet(null);
        if (sub != null) {
            sub.cancel();
        }
        harnessAgent = null;
    }

    // ---- Event stream ----

    private reactor.core.publisher.Flux<AgentEvent> buildEventStream(
            List<Msg> messages,
            RuntimeContext ctx,
            RuntimeEventListener listener,
            CompletableFuture<String> future) {

        reactor.core.publisher.Flux<AgentEvent> flux;
        switch (mode) {
            case ACTIVE, PASSIVE -> {
                HarnessAgent agent = getOrCreateHarnessAgent();
                flux = agent.streamEvents(messages, ctx);
            }
            default -> throw new IllegalStateException("Unknown mode: " + mode);
        }

        return flux
                .doOnSubscribe(sub -> currentSubscription.set(sub))
                .doOnNext(event -> dispatchAgentScopeEvent(event, listener))
                .doOnComplete(() -> {
                    currentSubscription.set(null);
                    listener.onComplete();
                    future.complete(null);
                })
                .doOnError(error -> {
                    currentSubscription.set(null);
                    listener.onEvent(RuntimeEvent.error(error));
                    listener.onError(error);
                    future.completeExceptionally(error);
                });
    }

    /**
     * 将 AgentScope {@link AgentEvent} 翻译为 {@link RuntimeEvent} 并投递给 listener。
     *
     * <p>使用 instanceof 模式匹配（Java 21）分派具体事件类型。
     */
    private void dispatchAgentScopeEvent(AgentEvent event, RuntimeEventListener listener) {
        if (event == null) return;

        if (event instanceof TextBlockDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                listener.onEvent(RuntimeEvent.textDelta(delta));
            }
            DebugContext.log("AgentScopeAgentRuntime", "text_delta", Map.of("len", String.valueOf(delta != null ? delta.length() : 0)));
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                listener.onEvent(RuntimeEvent.thinking(delta));
            }
            DebugContext.log("AgentScopeAgentRuntime", "thinking_delta", Map.of("len", String.valueOf(delta != null ? delta.length() : 0)));
        } else if (event instanceof ToolCallStartEvent e) {
            listener.onEvent(RuntimeEvent.toolStart(e.getToolCallName(), null));
            DebugContext.log("AgentScopeAgentRuntime", "tool_start", Map.of("tool", e.getToolCallName()));
        } else if (event instanceof ToolResultEndEvent e) {
            boolean failed = e.getState() != ToolResultState.SUCCESS;
            listener.onEvent(RuntimeEvent.toolEnd(e.getToolCallName(), null, failed, -1));
            DebugContext.log("AgentScopeAgentRuntime", "tool_end", Map.of("tool", e.getToolCallName(), "state", String.valueOf(e.getState())));
        } else if (event instanceof io.agentscope.core.event.ModelCallEndEvent e) {
            io.agentscope.core.model.ChatUsage usage = e.getUsage();
            if (usage != null) {
                listener.onEvent(RuntimeEvent.usage(
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getCachedTokens(),
                        usage.getTotalTokens()));
                DebugContext.log("AgentScopeAgentRuntime", "usage",
                        Map.of("input", String.valueOf(usage.getInputTokens()),
                                "output", String.valueOf(usage.getOutputTokens()),
                                "total", String.valueOf(usage.getTotalTokens())));
            }
        } else if (event instanceof AgentResultEvent e) {
            Msg result = e.getResult();
            if (result != null) {
                String text = result.getTextContent();
                if (text != null && !text.isEmpty()) {
                    listener.onEvent(RuntimeEvent.replyEnd(text));
                }
            }
            DebugContext.log("AgentScopeAgentRuntime", "agent_result", Map.of("hasResult", String.valueOf(result != null)));
        } else if (event instanceof SubagentExposedEvent e) {
            listener.onEvent(RuntimeEvent.subagent(
                    e.getLabel() != null ? e.getLabel() : e.getSubagentId(), null));
            DebugContext.log("AgentScopeAgentRuntime", "subagent_exposed", Map.of("label", e.getLabel() != null ? e.getLabel() : "null"));
        }
        // 其他事件类型（MODEL_CALL_START/END, AGENT_START/END, HINT_BLOCK 等）静默忽略
    }

    // ---- Agent lifecycle ----

    private HarnessAgent getOrCreateHarnessAgent() {
        if (harnessAgent == null) {
            synchronized (this) {
                if (harnessAgent == null) {
                    var builder = HarnessAgent.builder()
                            .name("burp-ai-analyzer")
                            .model(model);
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        builder.sysPrompt(systemPrompt);
                    }
                    if (workspacePath != null) {
                        builder.workspace(workspacePath);
                    }
                    if (toolkit != null) {
                        builder.toolkit(toolkit);
                    }
                    // 配置上下文压缩（防止 token 超限）
                    builder.compaction(CompactionConfig.builder()
                            .triggerMessages(30)
                            .keepMessages(10)
                            .build());
                    // 配置长期记忆（跨会话持久化）
                    builder.memory(MemoryConfig.defaults());
                    // 配置大工具结果驱逐（pentest 场景常见）
                    builder.toolResultEviction(
                            io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.builder()
                                    .maxResultChars(16_000)
                                    .build());
                    // 原生子代理能力（agent_spawn/agent_send/agent_list + task_*）默认启用，
                    // 子代理自动继承主代理的模型与工具
                    // 原生开关：按插件配置映射 Harness 内置工具
                    if (disableFilesystemTools) {
                        builder.disableFilesystemTools();
                    }
                    if (disableShellTool) {
                        builder.disableShellTool();
                    }
                    if (enablePlanMode) {
                        builder.enablePlanMode();
                    }
                    if (maxContextTokens > 0) {
                        builder.maxContextTokens(maxContextTokens);
                    }
                    if (skillRepositories != null && !skillRepositories.isEmpty()) {
                        builder.skillRepositories(skillRepositories);
                    }
                    harnessAgent = builder.build();
                }
            }
        }
        return harnessAgent;
    }

    // ---- Message conversion ----

    static List<Msg> toAgentScopeMessages(List<com.ai.analyzer.agent.runtime.ChatMessage> messages) {
        List<Msg> result = new ArrayList<>(messages.size());
        for (com.ai.analyzer.agent.runtime.ChatMessage msg : messages) {
            result.add(switch (msg.role()) {
                case SYSTEM -> new SystemMessage(msg.content());
                case USER -> new UserMessage(msg.content());
                case ASSISTANT -> new AssistantMessage(msg.content());
                case TOOL_RESULT -> new ToolResultMessage(
                        null, null, msg.content());
            });
        }
        return result;
    }

    // ---- StreamSession ----

    private static final class AgentScopeStreamSession implements StreamSession {
        private final CompletableFuture<String> future;
        private final Runnable canceller;
        private volatile boolean cancelled;

        AgentScopeStreamSession(CompletableFuture<String> future, Runnable canceller) {
            this.future = future;
            this.canceller = canceller;
        }

        @Override
        public void cancel() {
            if (cancelled) return;
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
            } catch (java.util.concurrent.ExecutionException e) {
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

    // ---- Builder ----

    public enum Mode { ACTIVE, PASSIVE }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Mode mode = Mode.ACTIVE;
        private Model model;
        private String systemPrompt;
        private Path workspacePath;
        private Toolkit toolkit;
        private long chatTimeoutMs = TimeUnit.MINUTES.toMillis(10);
        private boolean disableFilesystemTools;
        private boolean disableShellTool;
        private boolean enablePlanMode;
        private int maxContextTokens;
        private List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories;

        /** Active 和 Passive 模式均使用 HarnessAgent（持久会话 + workspace + skills） */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** AgentScope Model 实例（通过 ModelRegistry 或直接构造） */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /** 通过字符串协议创建模型，如 "dashscope:qwen-max" */
        public Builder model(String modelProtocol) {
            this.model = ModelRegistry.resolve(modelProtocol);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** AgentScope workspace 目录（含 AGENTS.md, MEMORY.md, skills 等） */
        public Builder workspacePath(Path workspacePath) {
            this.workspacePath = workspacePath;
            return this;
        }

        /** AgentScope Toolkit（包含 MCP 客户端、工具等） */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder chatTimeoutMs(long chatTimeoutMs) {
            this.chatTimeoutMs = chatTimeoutMs;
            return this;
        }

        /** 禁用 Harness 原生文件系统工具（read_file/write_file/edit_file/grep/glob），默认开启 */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /** 禁用 Harness 原生 Shell 工具（execute_shell_command），默认开启 */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /** 启用 Harness Plan Mode（PlanEnterTool/PlanWriteTool/PlanExitTool），默认关闭 */
        public Builder enablePlanMode(boolean enablePlanMode) {
            this.enablePlanMode = enablePlanMode;
            return this;
        }

        /** 上下文 token 预算上限，<=0 表示使用 Harness 默认值 */
        public Builder maxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
            return this;
        }

        /** 注入外部技能仓库（如 FileSystemSkillRepository），Harness 会自动将技能提示注入上下文 */
        public Builder skillRepositories(List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories) {
            this.skillRepositories = skillRepositories;
            return this;
        }

        public AgentScopeAgentRuntime build() {
            if (model == null) {
                throw new IllegalStateException("model is required");
            }
            return new AgentScopeAgentRuntime(this);
        }
    }
}