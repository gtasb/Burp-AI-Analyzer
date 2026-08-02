package com.ai.analyzer.ui.active;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动分析历史存储（纯逻辑，无 Swing 依赖，可测试）。
 * 记录每次分析的 时间 / 目标 / 提示词 / 完整结果，最多保留 {@value #MAX_ENTRIES} 条。
 */
public final class AnalysisHistoryStore {

    public static final int MAX_ENTRIES = 200;

    public record AnalysisHistoryEntry(String timestamp, String targetDesc, String prompt, String result) {}

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<AnalysisHistoryEntry> entries = new ArrayList<>();

    public synchronized void add(String targetDesc, String prompt, String result) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        entries.add(new AnalysisHistoryEntry(time, targetDesc, prompt, result));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public synchronized AnalysisHistoryEntry get(int index) {
        return entries.get(index);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** 返回不可变快照，供 UI 列表渲染 */
    public synchronized List<AnalysisHistoryEntry> snapshot() {
        return List.copyOf(entries);
    }
}
