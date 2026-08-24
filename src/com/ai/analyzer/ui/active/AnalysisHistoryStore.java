package com.ai.analyzer.ui.active;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动分析历史存储（纯逻辑，无 Swing 依赖，可测试）。
 * 记录每次分析的 时间 / 目标 / 提示词 / 完整结果，最多保留 {@value #MAX_ENTRIES} 条。
 * 自动持久化到磁盘，插件重载不丢失。
 */
public final class AnalysisHistoryStore {

    public static final int MAX_ENTRIES = 200;

    public record AnalysisHistoryEntry(String timestamp, String targetDesc, String prompt, String result) {}

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<AnalysisHistoryEntry> entries = new ArrayList<>();
    private final File storageFile;

    /** 使用默认路径（用户主目录） */
    public AnalysisHistoryStore() {
        this(new File(System.getProperty("user.home"), ".burp_ai_analysis_history.dat"));
    }

    /** 指定存储路径（可用于工作区隔离） */
    public AnalysisHistoryStore(File storageFile) {
        this.storageFile = storageFile;
        loadFromDisk();
    }

    public synchronized void add(String targetDesc, String prompt, String result) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        entries.add(new AnalysisHistoryEntry(time, targetDesc, prompt, result));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        saveToDisk();
    }

    public synchronized AnalysisHistoryEntry get(int index) {
        return entries.get(index);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
        saveToDisk();
    }

    /** 返回不可变快照，供 UI 列表渲染 */
    public synchronized List<AnalysisHistoryEntry> snapshot() {
        return List.copyOf(entries);
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(storageFile))) {
            List<SerializableEntry> loaded = (List<SerializableEntry>) ois.readObject();
            for (SerializableEntry se : loaded) {
                entries.add(new AnalysisHistoryEntry(se.timestamp, se.targetDesc, se.prompt, se.result));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveToDisk() {
        try {
            storageFile.getParentFile().mkdirs();
            List<SerializableEntry> toSave = new ArrayList<>(entries.size());
            for (AnalysisHistoryEntry e : entries) {
                toSave.add(new SerializableEntry(e.timestamp(), e.targetDesc(), e.prompt(), e.result()));
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(storageFile))) {
                oos.writeObject(toSave);
            }
        } catch (Exception ignored) {
        }
    }

    private record SerializableEntry(String timestamp, String targetDesc, String prompt, String result) implements Serializable {}
}
