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
import io.agentscope.core.message.MsgRole;
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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

    private static final String AGENT_NAME = "burp-ai-analyzer";

    private final Mode mode;
    private final Model model;
    private final String systemPrompt;
    private final Path workspacePath;
    private final Toolkit toolkit;
    private final long chatTimeoutMs;
    private final boolean disableFilesystemTools;
    private final boolean disableShellTool;
    private final boolean enablePlanMode;
    private final boolean enableTaskList;
    private final int maxContextTokens;
    private final List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepositories;

    private volatile HarnessAgent harnessAgent;
    private final AtomicReference<Subscription> currentSubscription = new AtomicReference<>();
    private volatile RequireConfirmHandler confirmHandler;
    /** 累积 ToolCallDeltaEvent 的部分参数字符串，key=toolCallId，在 ToolCallEndEvent 或 ToolResultStartEvent 时取用 */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> toolCallArgBuffers = new java.util.concurrent.ConcurrentHashMap<>();

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
        this.enableTaskList = builder.enableTaskList;
        this.maxContextTokens = builder.maxContextTokens;
        this.skillRepositories = builder.skillRepositories;
    }

    /**
     * 设置用户确认回调（HITL）。ASK 决策（如 plan_exit 请求批准）会调用它，
     * 未设置时自动拒绝（安全默认）。
     */
    public void setRequireConfirmHandler(RequireConfirmHandler handler) {
        this.confirmHandler = handler;
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
            // 清理该会话残留的挂起确认状态：上一次运行若在 ASKING 状态中途结束，
            // 状态会持久化到 (agentId, sessionId)，导致新请求启动即报
            // "Agent is paused for human-in-the-loop confirmation"。
            clearPendingConfirmationState(sessionId);

            // HITL 循环：agent 遇到 ASK 决策（如 plan_exit）时会暂停并发起确认，
            // 我们在收集到待确认工具调用后询问用户，用 ConfirmResult 恢复 agent，
            // 直到一次完整执行流程结束（或用户取消/出错）。
            List<Msg> currentMessages = asMessages;
            while (!future.isCancelled()) {
                List<io.agentscope.core.message.ToolUseBlock> pendingConfirm = new java.util.ArrayList<>();
                buildEventStream(currentMessages, ctx, listener, future, pendingConfirm)
                        .blockLast(java.time.Duration.ofMillis(chatTimeoutMs));

                if (future.isCancelled() || pendingConfirm.isEmpty()) {
                    break;
                }

                // agent 暂停等待确认：询问用户（无回调时自动拒绝，安全默认）
                String summary = buildConfirmSummary(pendingConfirm);
                boolean approved = confirmHandler != null
                        && confirmHandler.confirm("permission_ask", summary);

                List<io.agentscope.core.event.ConfirmResult> results = pendingConfirm.stream()
                        .map(t -> new io.agentscope.core.event.ConfirmResult(approved, t))
                        .toList();
                java.util.Map<String, Object> meta = new java.util.HashMap<>();
                meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
                currentMessages = List.of(Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .textContent(approved ? "approved" : "denied")
                        .metadata(meta)
                        .build());
            }
            future.complete(null);
        } catch (Exception e) {
            listener.onEvent(RuntimeEvent.error(e));
            listener.onError(e);
            future.completeExceptionally(e);
        }

        return session;
    }

    /**
     * 清理指定会话残留的挂起确认状态（ASKING）。
     *
     * <p>AgentScope 会把 agent 的会话状态（含待确认的工具调用）持久化到 state store，
     * 若上一次运行在 ASKING 状态中途结束（取消/出错），新请求启动时 agent 会直接继承
     * 该挂起状态并立即报 "paused for human-in-the-loop confirmation"，导致无法继续。
     * 这里在新会话开始前清掉 (agentId, sessionId) 的残留状态。
     */
    private void clearPendingConfirmationState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            HarnessAgent agent = getOrCreateHarnessAgent();
            io.agentscope.core.state.AgentStateStore store = agent.getStateStore();
            if (store != null && store.exists(agent.getAgentId(), sessionId)) {
                store.delete(agent.getAgentId(), sessionId);
                DebugContext.log("AgentScopeAgentRuntime", "cleared_stale_confirm_state",
                        java.util.Map.of("session", sessionId));
            }
        } catch (Exception e) {
            DebugContext.log("AgentScopeAgentRuntime", "clear_stale_confirm_state_failed",
                    java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : ""));
        }
    }

    /**
     * 生成待确认工具调用的可读摘要（工具名 + 参数摘要，限长）。
     */
    private String buildConfirmSummary(List<io.agentscope.core.message.ToolUseBlock> pending) {
        StringBuilder sb = new StringBuilder();
        for (io.agentscope.core.message.ToolUseBlock block : pending) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("工具: ").append(block.getName());
            try {
                String input = String.valueOf(block.getInput());
                if (input.length() > 200) input = input.substring(0, 200) + "…";
                if (!"null".equals(input) && !input.isEmpty()) {
                    sb.append("\n  参数: ").append(input);
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    @Override
    public void shutdown() {
        Subscription sub = currentSubscription.getAndSet(null);
        if (sub != null) {
            sub.cancel();
        }
        harnessAgent = null;
    }

    /**
     * 重置当前 Agent 会话上下文：释放并重建 agent（下一次请求会全新初始化），
     * 从而清空对话历史、长期记忆与任何挂起状态。
     */
    public void resetSession() {
        shutdown();
        purgePersistentSessionData(workspacePath);
        DebugContext.log("AgentScopeAgentRuntime", "session_reset",
                java.util.Map.of("mode", mode != null ? mode.name() : ""));
    }

    /**
     * 清理 workspace 中由 HarnessAgent 写入的持久会话/记忆状态。
     *
     * <p>之前的「新会话」只重建了 Java 对象，但 workspace 下的
     * {@code <user>/MEMORY.md}、{@code <user>/memory/}、{@code agents/burp-ai-analyzer/}
     * 仍然保留，新的 agent 一初始化就把这些长期记忆重新加载回来，导致
     * 用户点击「新会话」后依然带着旧上下文继续跑。
     */
    public static void purgePersistentSessionData(Path workspacePath) {
        if (workspacePath == null) return;
        String userId = System.getProperty("user.name", "burp-user");
        deleteRecursivelyQuietly(workspacePath.resolve(userId));
        deleteRecursivelyQuietly(workspacePath.resolve("agents").resolve(AGENT_NAME));
    }

    private static void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // 新会话应尽力清理，不因单个文件删除失败中断主流程
        }
    }

    // ---- Event stream ----

    private reactor.core.publisher.Flux<AgentEvent> buildEventStream(
            List<Msg> messages,
            RuntimeContext ctx,
            RuntimeEventListener listener,
            CompletableFuture<String> future,
            List<io.agentscope.core.message.ToolUseBlock> pendingConfirm) {

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
                .doOnNext(event -> dispatchAgentScopeEvent(event, listener, pendingConfirm))
                .doOnComplete(() -> {
                    currentSubscription.set(null);
                    listener.onComplete();
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
    private void dispatchAgentScopeEvent(AgentEvent event, RuntimeEventListener listener,
            List<io.agentscope.core.message.ToolUseBlock> pendingConfirm) {
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
        } else if (event instanceof io.agentscope.core.event.ToolCallDeltaEvent e) {
            // 累积流式的工具调用参数
            toolCallArgBuffers.computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                    .append(e.getDelta() != null ? e.getDelta() : "");
        } else if (event instanceof io.agentscope.core.event.ToolCallEndEvent e) {
            // 工具调用参数完成：从 buffer 取出并重置，以便 ToolResultStartEvent 取用
            StringBuilder buf = toolCallArgBuffers.remove(e.getToolCallId());
            String args = buf != null ? buf.toString() : "";
            if (!args.isEmpty() && !"{}".equals(args)) {
                String summary = args.length() > 200 ? args.substring(0, 200) + "…" : args;
                listener.onEvent(RuntimeEvent.textDelta("\n📥 参数: " + summary + "\n"));
            }
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
        } else if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent e) {
            // ASK 决策：agent 暂停等待确认（如 plan_exit 或敏感工具调用）。
            // 无论是否计划模式都要收集并回复 ConfirmResult，否则 agent 会卡在 ASKING 状态；
            // 批准与否由 confirmHandler 决定（普通模式自动批准，计划模式也已改为自动批准）。
            List<io.agentscope.core.message.ToolUseBlock> pending = e.getToolCalls();
            if (pending != null && !pending.isEmpty()) {
                pendingConfirm.addAll(pending);
            }
            DebugContext.log("AgentScopeAgentRuntime", "require_user_confirm",
                    Map.of("count", String.valueOf(pendingConfirm.size())));
        }
        // 其他事件类型（MODEL_CALL_START/END, AGENT_START/END, HINT_BLOCK 等）静默忽略
    }

    // ---- Agent lifecycle ----

    private HarnessAgent getOrCreateHarnessAgent() {
        if (harnessAgent == null) {
            synchronized (this) {
                if (harnessAgent == null) {
                    var builder = HarnessAgent.builder()
                            .name(AGENT_NAME)
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
                    var compactionCfg = CompactionConfig.builder()
                            .triggerMessages(30)
                            .keepMessages(10)
                            .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                                    .maxArgLength(2000)
                                    .truncationText("... [truncated] ...")
                                    .build());
                    if (maxContextTokens > 0) {
                        // 按 token 预算的 70% 触发压缩，保留 30%
                        int triggerTokens = (int) (maxContextTokens * 0.7);
                        int keepTokens = (int) (maxContextTokens * 0.3);
                        compactionCfg.triggerTokens(triggerTokens)
                                .keepTokens(keepTokens);
                    }
                    builder.compaction(compactionCfg.build());
                    // 配置长期记忆（跨会话持久化）
                    builder.memory(MemoryConfig.defaults());
                    // 权限模式设为 BYPASS：敏感工具（extension_info 等）直接执行，
                    // 不再进入 ASKING 等待人工确认
                    builder.permissionContext(io.agentscope.core.permission.PermissionContextState.builder()
                            .mode(io.agentscope.core.permission.PermissionMode.BYPASS)
                            .build());
                    // 禁用会话持久化：每次请求都是新 sessionId，不残留上一轮的挂起确认状态，
                    // 避免 "Agent is paused for human-in-the-loop confirmation" 跨请求复现
                    builder.disableSessionPersistence();
                    // 启用挂起工具恢复：模型返回一批并发的工具调用时，若部分结果因超时/异常
                    // 未收集到，允许 agent 自动跳过挂起工具而非直接崩溃报错
                    // "Pending tool calls exist without results"
                    builder.enablePendingToolRecovery(true);
                    // 异步工具调用超时：防止单个工具挂死导致整个 Agent 阻塞
                    builder.asyncToolTimeout(java.time.Duration.ofMinutes(3));
                    // 单工具执行超时，避免 MCP 或外部工具长时间无响应
                    builder.toolExecutionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                            .timeout(java.time.Duration.ofSeconds(120))
                            .maxAttempts(2)
                            .build());
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
                    if (enableTaskList) {
                        // 任务清单：plan 阶段写的 todos 会在每次推理前以提示形式展示
                        builder.enableTaskList();
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
        private boolean enableTaskList;
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

        /** 启用 Harness 任务清单（todo_write + 每轮推理前展示 todos），默认关闭 */
        public Builder enableTaskList(boolean enableTaskList) {
            this.enableTaskList = enableTaskList;
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
