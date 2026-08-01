package com.ai.analyzer.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug-mode backdoor diagnostics singleton.
 * 调试模式后门诊断单例
 *
 * <p>When enabled via the Debug tab, captures detailed structured events
 * across all subsystems. The ring buffer is bounded to prevent memory
 * exhaustion. Events can be exported to a file for offline analysis
 * when human testers encounter bugs during GUI testing.
 * 启用后捕获所有子系统的详细结构化事件，可导出文件供离线分析
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   DebugContext.log("AgentApiClient", "streaming_start", Map.of("runtime", "AgentScope"));
 *   // ... later, when a bug is reported:
 *   DebugContext.dumpToFile(Path.of("/tmp/burp-debug.log"));
 * }</pre>
 */
public final class DebugContext {

    private static volatile boolean enabled = false;
    private static final int MAX_EVENTS = 500;
    private static final ConcurrentLinkedDeque<DebugEvent> EVENTS = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger totalEventCount = new AtomicInteger(0);

    private DebugContext() {}

    // ---- Toggle ----

    /** Enable debug-mode event capture. */
    public static void enable() {
        enabled = true;
        log("DebugContext", "lifecycle", Map.of("action", "enabled"));
    }

    /** Disable debug-mode event capture. */
    public static void disable() {
        log("DebugContext", "lifecycle", Map.of("action", "disabled"));
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ---- Event Logging ----

    /**
     * Log a structured debug event. Thread-safe, non-blocking.
     * Events are silently discarded when debug mode is disabled.
     *
     * @param category  subsystem name (e.g. "AgentApiClient", "AgentScopeAgentRuntime")
     * @param summary   short description of what happened
     * @param details   key-value pairs with contextual data (may be null)
     */
    public static void log(String category, String summary, Map<String, String> details) {
        if (!enabled) return;

        DebugEvent event = new DebugEvent(
                Instant.now().toString(),
                category,
                summary,
                details != null ? new LinkedHashMap<>(details) : Collections.emptyMap()
        );

        EVENTS.addLast(event);
        totalEventCount.incrementAndGet();

        // Trim to max size
        while (EVENTS.size() > MAX_EVENTS) {
            EVENTS.pollFirst();
        }
    }

    /**
     * Convenience overload: log with just a summary, no details.
     */
    public static void log(String category, String summary) {
        log(category, summary, null);
    }

    // ---- Snapshot & Export ----

    /**
     * Return a snapshot of recent events, newest first.
     */
    public static List<DebugEvent> snapshot() {
        List<DebugEvent> list = new ArrayList<>(EVENTS);
        Collections.reverse(list);
        return list;
    }

    /**
     * Return a human-readable text snapshot suitable for the Debug tab.
     */
    public static String snapshotText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Debug Context Snapshot ===\n");
        sb.append("Total events captured: ").append(totalEventCount.get()).append("\n");
        sb.append("In buffer: ").append(EVENTS.size()).append("\n");
        sb.append("Enabled: ").append(enabled).append("\n\n");

        for (DebugEvent e : snapshot()) {
            sb.append(e.timestamp).append(" [").append(e.category).append("] ").append(e.summary).append("\n");
            if (!e.details.isEmpty()) {
                for (var entry : e.details.entrySet()) {
                    sb.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Dump all captured events to a file. Useful for sharing debug logs
     * after reproducing a bug through the GUI.
     *
     * @param path target file path
     * @throws IOException if writing fails
     */
    public static void dumpToFile(Path path) throws IOException {
        Files.writeString(path, snapshotText());
        System.out.println("[DebugContext] Dumped " + EVENTS.size() + " events to " + path.toAbsolutePath());
    }

    /**
     * Dump to a file in the workplace directory.
     */
    public static void dumpToFile(File file) throws IOException {
        dumpToFile(file.toPath());
    }

    /**
     * Get the total number of events captured since last enable.
     */
    public static int totalEventCount() {
        return totalEventCount.get();
    }

    /**
     * Clear all captured events.
     */
    public static void clear() {
        EVENTS.clear();
        totalEventCount.set(0);
    }

    // ---- Event Record ----

    public record DebugEvent(
            String timestamp,
            String category,
            String summary,
            Map<String, String> details
    ) {}
}