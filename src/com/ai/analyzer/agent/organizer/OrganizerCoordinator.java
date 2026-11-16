package com.ai.analyzer.agent.organizer;

import com.ai.analyzer.core.AgentApiClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;

/**
 * 主动分析轮末的整理循环：
 * 主动 agent 完成一轮 → organizer 整理入图并判断是否继续 → 继续则把指令交回主动 agent，
 * 否则保存/展示报告。循环轮次可配置（0 = 不限），也可以关闭自动继续。
 */
public final class OrganizerCoordinator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_HANDOFF_CHARS = 20_000;

    private final AgentApiClient organizerClient;
    private final Consumer<String> continueActive;
    private final Consumer<Report> onReport;
    private final Consumer<String> phaseSink;
    private final Consumer<String> eventSink;
    private final BooleanSupplier autoContinueEnabled;
    private final IntSupplier maxRounds;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile int continuationCount = 0;
    private volatile String lastInstruction = "";
    private volatile String lastGaps = "";
    /** 整理进行中又完成了一轮主动分析：保留最新一轮，整理结束后接着处理 */
    private volatile AgentApiClient.CompletedRun pendingRun;
    /** 整理 agent 的上下文每 N 轮重置一次，避免无限循环下会话无限增长 */
    private static final int ORGANIZER_SESSION_RESET_ROUNDS = 10;

    public record Report(String markdown, Path file) {
    }

    public OrganizerCoordinator(AgentApiClient organizerClient,
                                Consumer<String> continueActive,
                                Consumer<Report> onReport,
                                Consumer<String> phaseSink,
                                Consumer<String> eventSink,
                                BooleanSupplier autoContinueEnabled,
                                IntSupplier maxRounds) {
        this.organizerClient = organizerClient;
        this.continueActive = continueActive;
        this.onReport = onReport;
        this.phaseSink = phaseSink != null ? phaseSink : s -> { };
        this.eventSink = eventSink != null ? eventSink : s -> { };
        this.autoContinueEnabled = autoContinueEnabled;
        this.maxRounds = maxRounds;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 用户是否已显式停止循环（continue 回调据此放弃已排队的下一轮）。 */
    public boolean isStopped() {
        return stopped.get();
    }

    /** 新一轮主动分析开始/结束时重置循环计数。 */
    public void resetLoop() {
        continuationCount = 0;
        stopped.set(false);
        lastInstruction = "";
        // 不清 lastGaps 会让新会话第 1 轮就被“未覆盖点与上一轮相同”误杀
        lastGaps = "";
        phaseSink.accept("主动分析中（第 1 轮）");
    }

    public void stop() {
        stopped.set(true);
        pendingRun = null;
        // 真正打断正在运行的整理轮：cancelStreaming 现在会取消 runtime 当前活跃轮
        // （历史 bug：旧的取消只对已结束的轮次生效，用户按停止后整理轮照样跑完）
        try {
            organizerClient.cancelStreaming();
        } catch (Exception ignored) {
        }
    }

    /** 主动分析正常完成后调用；异步执行，不阻塞 EDT。 */
    public void onActiveRunCompleted(AgentApiClient.CompletedRun run) {
        if (run == null || organizerClient == null) return;
        pendingRun = run;
        if (!running.compareAndSet(false, true)) {
            // 已有整理在跑：保留最新一轮，结束后继续处理，避免重复触发
            return;
        }
        Thread t = new Thread(() -> {
            try {
                AgentApiClient.CompletedRun next;
                while (!stopped.get() && (next = pendingRun) != null) {
                    pendingRun = null;
                    try {
                        organize(next);
                    } catch (Exception e) {
                        onReport.accept(new Report("整理 agent 执行失败: " + e.getMessage(), null));
                    }
                }
            } finally {
                running.set(false);
            }
        }, "organizer-round");
        t.setDaemon(true);
        t.start();
    }

    private void organize(AgentApiClient.CompletedRun run) {
        if (stopped.get()) return;
        AgentApiClient organizer = organizerClient;
        OrganizerTool tool = organizer.getOrganizerTool();
        if (tool != null) {
            tool.reset();
        }
        // 整理轮运行中发生设置变更（invalidate → 重建 runtime → 新 OrganizerTool 实例）时，
        // 决策可能落在重建后的新实例上：结束时重新读取，而不是沿用轮前的旧引用
        OrganizerTool toolAtEnd = tool;

        // 预算保护：无限循环下必须先看全局 token 预算，避免把配额跑空
        if (com.ai.analyzer.util.TokenUsageTracker.sharedOverBudget()) {
            onReport.accept(new Report("Token 预算已用尽，自动分析循环已停止。请重置统计或提高预算后继续。", null));
            phaseSink.accept("已停止：Token 预算用尽");
            return;
        }

        // 上下文保护：整理 agent 的会话每 N 轮重置一次
        if (continuationCount > 0 && continuationCount % ORGANIZER_SESSION_RESET_ROUNDS == 0) {
            try {
                organizer.startNewSession();
                eventSink.accept("整理 agent 会话已重置（每 " + ORGANIZER_SESSION_RESET_ROUNDS + " 轮）");
            } catch (Exception ignored) {
            }
        }

        phaseSink.accept("整理中（第 " + (continuationCount + 1) + " 轮）");
        String prompt = buildPrompt(run);
        StringBuilder organizerText = new StringBuilder();
        java.util.function.Consumer<String> behavior = msg -> {
            if (msg == null) return;
            int sep = msg.indexOf('|');
            String type = sep >= 0 ? msg.substring(0, sep) : "OTHER";
            String detail = sep >= 0 ? msg.substring(sep + 1) : msg;
            switch (type) {
                case "TOOL_START" -> eventSink.accept("调用工具: " + detail);
                case "TOOL_END" -> eventSink.accept(
                        (detail.endsWith("|failed") ? "工具失败: " : "工具完成: ")
                                + detail.replace("|failed", ""));
                default -> { }
            }
        };
        organizer.addModelBehaviorConsumer(behavior);
        // 整理 agent 没有设置过确认回调：chatLocked 里 confirmHandler==null 会把
        // plan_exit 一律拒绝（approved=false），设置里存了 Plan Mode 时整理轮可能
        // 被拦在确认上、永远到不了 organizer_finish。整理 agent 无交互界面，自动批准。
        organizer.setRequireConfirmHandler((toolName, summary) -> true);
        try {
            organizer.runTurn(prompt, chunk -> {
                if (chunk == null || chunk.isEmpty()) return;
                if (organizerText.length() < 20_000) {
                    organizerText.append(chunk);
                }
            });
        } catch (Exception e) {
            onReport.accept(new Report("整理 agent 调用失败: " + e.getMessage(), null));
            phaseSink.accept("整理失败");
            return;
        } finally {
            organizer.removeModelBehaviorConsumer(behavior);
        }
        if (!organizerText.isEmpty()) {
            eventSink.accept("整理输出: " + truncate(organizerText.toString().replaceAll("\\s+", " "), 600));
        }
        if (stopped.get()) {
            phaseSink.accept("已停止");
            return;
        }

        // 运行期间 runtime 可能被设置变更重建：先读当前实例（决策可能落在它上面），
        // 旧引用作为兜底
        OrganizerTool freshTool = organizer.getOrganizerTool();
        OrganizerTool.Decision decision =
                freshTool != null && freshTool.getDecision() != null ? freshTool.getDecision()
                        : (toolAtEnd != null ? toolAtEnd.getDecision() : null);
        if (decision == null) {
            onReport.accept(new Report(
                    "整理 agent 未给出 organizer_finish 决策，已停止自动循环。", null));
            phaseSink.accept("已停止：整理未给出决策");
            return;
        }

        int max = Math.max(0, maxRounds.getAsInt());
        String instruction = decision.instruction() == null ? "" : decision.instruction().trim();
        String normalizedInstruction = normalize(instruction);
        String gaps = normalize(decision.gaps());
        boolean repeated = !normalizedInstruction.isEmpty()
                && normalizedInstruction.equals(lastInstruction);
        boolean sameGaps = !gaps.isEmpty() && gaps.equals(lastGaps);
        // 连续给出同一条指令 / 同一批未覆盖点，说明没有进展，继续只会重复消耗 token
        boolean noProgress = repeated || (sameGaps && decision.continueAnalysis());
        boolean canContinue = autoContinueEnabled.getAsBoolean()
                && decision.continueAnalysis()
                && !stopped.get()
                && !instruction.isBlank()
                && !noProgress
                && (max == 0 || continuationCount < max);

        if (decision.continueAnalysis() && noProgress) {
            eventSink.accept(repeated
                    ? "整理 agent 给出与上一轮相同的指令，判定为无进展，停止循环"
                    : "整理 agent 的未覆盖点与上一轮相同，判定为无进展，停止循环");
        }
        if (!gaps.isEmpty()) {
            lastGaps = gaps;
        }

        if (canContinue) {
            continuationCount++;
            lastInstruction = normalizedInstruction;
            eventSink.accept("整理完成：继续分析");
            phaseSink.accept("继续分析（第 " + (continuationCount + 1) + " 轮）");
            eventSink.accept("下一步指令: " + truncate(instruction, 300));
            continueActive.accept(instruction);
            return;
        }

        String report = decision.report();
        if (report == null || report.isBlank()) {
            StringBuilder sb = new StringBuilder();
            sb.append("## 自动分析循环已停止\n\n");
            if (decision.gaps() != null && !decision.gaps().isBlank()) {
                sb.append("未覆盖/待人工处理：\n").append(decision.gaps()).append("\n\n");
            }
            if (decision.continueAnalysis() && !instruction.isBlank()) {
                sb.append(repeated ? "整理 agent 的指令与上一轮重复，判定为无进展；" : "")
                        .append("整理 agent 建议继续，但自动继续已关闭或达到轮次上限：\n")
                        .append(instruction).append('\n');
            }
            report = sb.toString();
        }

        Path file = saveReport(report);
        eventSink.accept("整理完成：生成渗透测试报告" + (file != null ? "（" + file.getFileName() + "）" : ""));
        phaseSink.accept("已完成：报告已生成");
        onReport.accept(new Report(report, file));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String buildPrompt(AgentApiClient.CompletedRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("【主动扫描 Agent 本轮交接】\n");
        sb.append("- 时间: ").append(LocalDateTime.now()).append('\n');
        sb.append("- 自动循环轮次: ").append(continuationCount + 1)
                .append("（0 表示不限）\n");
        if (run.hasRequest()) {
            sb.append("- 请求: ").append(run.method()).append(' ').append(run.url()).append('\n');
        } else {
            sb.append("- 本轮没有绑定请求（自由对话/批量上下文），如无可入图接口可跳过图谱写入。\n");
        }
        sb.append("- 分析提示词: ").append(truncate(run.userPrompt(), 2_000)).append('\n');
        sb.append("\n【主动 Agent 产出】\n").append(truncate(run.response(), MAX_HANDOFF_CHARS)).append('\n');
        sb.append("""

                【你的任务】
                先读取知识图谱与笔记，把本轮产出和自上次以来其他 agent 的新贡献整理入图，
                然后检查是否还有主动 agent 未覆盖的地方或新的攻击向量。
                最后调用 organizer_finish（恰好一次）：
                - 需要继续：continue_analysis=true，并给主动 agent 具体的下一步指令；
                - 否则：continue_analysis=false，输出完整渗透测试报告。
                可复现的 POC 用 organizer_send_to_repeater 送进 Repeater；需要反复执行的脚本用
                organizer_save_script 保存，不要自动执行。
                """);
        return sb.toString();
    }

    private Path saveReport(String markdown) {
        try {
            String base = organizerClient.getConfig().getWorkplaceDirectoryPath();
            Path dir = base == null || base.isBlank()
                    ? Path.of(System.getProperty("user.home"), "ai-analyzer-workplace", "reports")
                    : Path.of(base.trim()).resolve("reports");
            Files.createDirectories(dir);
            Path file = dir.resolve("pentest-report-" + LocalDateTime.now().format(TS) + ".md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[已截断]";
    }
}
