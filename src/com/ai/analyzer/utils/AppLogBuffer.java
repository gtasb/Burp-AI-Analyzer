package com.ai.analyzer.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Small in-memory log buffer for the plugin UI debug tab.
 */
public final class AppLogBuffer {
    private static final int MAX_LINES = 1_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<String> MCP_TRAFFIC_LINES = new ArrayList<>();
    private static final List<McpTrafficEntry> MCP_TRAFFIC_ENTRIES = new ArrayList<>();

    private AppLogBuffer() {
    }

    public static void debug(String source, String message) {
        append("DEBUG", source, message);
    }

    public static void info(String source, String message) {
        append("INFO", source, message);
    }

    public static void error(String source, String message) {
        append("ERROR", source, message);
    }

    public static void tool(String source, String message) {
        append("TOOL", source, message);
    }

    public static void mcpTraffic(String source, String message) {
        appendTo(MCP_TRAFFIC_LINES, "MCP_TRAFFIC", source, message);
        append("MCP_TRAFFIC", source, message);
    }

    public static void mcpTraffic(String source, String toolName, String target, String request, String args) {
        recordMcpRequest(null, source, toolName, target, request, args);
    }

    public static void recordMcpRequest(String requestId, String source, String toolName, String target, String request, String args) {
        String timestamp = LocalDateTime.now().format(TS);
        McpTrafficEntry entry = new McpTrafficEntry(
                normalizeRequestId(requestId, source, toolName, args),
                timestamp,
                "",
                source,
                toolName,
                target,
                "请求中",
                "",
                request,
                "",
                args
        );
        synchronized (MCP_TRAFFIC_ENTRIES) {
            MCP_TRAFFIC_ENTRIES.add(entry);
            if (MCP_TRAFFIC_ENTRIES.size() > MAX_LINES) {
                MCP_TRAFFIC_ENTRIES.subList(0, MCP_TRAFFIC_ENTRIES.size() - MAX_LINES).clear();
            }
        }
        mcpTraffic(source, "tool=" + toolName + "\ntarget=" + target + "\nargs=" + args);
    }

    public static void recordMcpResponse(String requestId, String source, String toolName, String result,
            boolean failed, String durationText) {
        String normalizedId = normalizeRequestId(requestId, source, toolName, null);
        synchronized (MCP_TRAFFIC_ENTRIES) {
            for (int i = MCP_TRAFFIC_ENTRIES.size() - 1; i >= 0; i--) {
                McpTrafficEntry entry = MCP_TRAFFIC_ENTRIES.get(i);
                if (entry.requestId().equals(normalizedId)
                        || (requestId == null && entry.source().equals(source) && entry.toolName().equals(toolName))) {
                    String status = failed ? "失败" : "完成";
                    MCP_TRAFFIC_ENTRIES.set(i, entry.withResponse(LocalDateTime.now().format(TS),
                            status, durationText, result));
                    break;
                }
            }
        }
        mcpTraffic(source, "tool=" + toolName + "\nstatus=" + (failed ? "失败" : "完成")
                + "\nduration=" + (durationText == null ? "" : durationText)
                + "\nresult=" + abbreviate(result, 6_000));
    }

    public static String snapshot() {
        synchronized (LINES) {
            return String.join("\n", LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static String mcpTrafficSnapshot() {
        synchronized (MCP_TRAFFIC_LINES) {
            return String.join("\n\n", MCP_TRAFFIC_LINES);
        }
    }

    public static List<McpTrafficEntry> mcpTrafficEntriesSnapshot() {
        synchronized (MCP_TRAFFIC_ENTRIES) {
            return new ArrayList<>(MCP_TRAFFIC_ENTRIES);
        }
    }

    public static void clearMcpTraffic() {
        synchronized (MCP_TRAFFIC_LINES) {
            MCP_TRAFFIC_LINES.clear();
        }
        synchronized (MCP_TRAFFIC_ENTRIES) {
            MCP_TRAFFIC_ENTRIES.clear();
        }
    }

    public record McpTrafficEntry(
            String requestId,
            String timestamp,
            String finishTime,
            String source,
            String toolName,
            String target,
            String status,
            String duration,
            String request,
            String response,
            String args
    ) {
        public McpTrafficEntry withResponse(String finishTime, String status, String duration, String response) {
            return new McpTrafficEntry(requestId, timestamp, finishTime, source, toolName, target,
                    status, duration, request, response, args);
        }
    }

    private static void append(String level, String source, String message) {
        appendTo(LINES, level, source, message);
    }

    private static void appendTo(List<String> target, String level, String source, String message) {
        String line = "%s [%s] [%s] %s".formatted(
                LocalDateTime.now().format(TS),
                level,
                source == null ? "unknown" : source,
                message == null ? "" : message
        );
        synchronized (target) {
            target.add(line);
            if (target.size() > MAX_LINES) {
                target.subList(0, target.size() - MAX_LINES).clear();
            }
        }
    }

    private static String normalizeRequestId(String requestId, String source, String toolName, String args) {
        if (requestId != null && !requestId.isBlank()) return requestId;
        return (source == null ? "" : source) + "|"
                + (toolName == null ? "" : toolName) + "|"
                + (args == null ? "" : Integer.toHexString(args.hashCode()));
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n...[内容过长已截断]...";
    }
}
