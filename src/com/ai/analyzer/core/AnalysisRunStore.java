package com.ai.analyzer.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 主动分析的 run 历史：按 run 持久化，供「请求分析」页回看。
 *
 * <p>文件在 {@code <workplace>/analysis-runs/analysis-runs.json}，最多保留 {@value #MAX_RUNS} 条；
 * 单条结果截断到 {@value #MAX_RESULT_CHARS} 字符，避免历史文件无限膨胀。
 */
public final class AnalysisRunStore {

    public static final int MAX_RUNS = 50;
    public static final int MAX_RESULT_CHARS = 200_000;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AnalysisRunStore INSTANCE = new AnalysisRunStore();

    private final Object lock = new Object();
    private Path baseDir;
    private List<Run> runs = new ArrayList<>();
    /** 不可变快照（最新在前）：EDT 的 list() 读它，不等 save() 的磁盘写 */
    private volatile List<Run> snapshot = List.of();

    private AnalysisRunStore() {
    }

    public static AnalysisRunStore getInstance() {
        return INSTANCE;
    }

    public record Run(long id, String time, String method, String url, String prompt, String result) {
        @Override
        public String toString() {
            String target = url == null ? "" : url;
            if (target.length() > 70) {
                target = target.substring(0, 69) + "…";
            }
            return "[" + time + "] " + (method == null ? "" : method + " ") + target;
        }
    }

    public void setWorkplaceDirectory(String dir) {
        Path next = dir == null || dir.isBlank() ? null : Path.of(dir.trim()).toAbsolutePath().normalize();
        synchronized (lock) {
            if (java.util.Objects.equals(baseDir, next)) return;
            baseDir = next;
            runs = load();
            snapshot = snapshotLocked();
        }
    }

    /** 新增一条 run（在后台线程调用）。返回最新列表（最新在前）。 */
    public List<Run> add(String method, String url, String prompt, String result) {
        Run run = new Run(System.currentTimeMillis(), LocalDateTime.now().format(TS),
                method, url, truncate(prompt, 8_000), truncate(result, MAX_RESULT_CHARS));
        synchronized (lock) {
            runs.add(run);
            while (runs.size() > MAX_RUNS) {
                runs.remove(0);
            }
            List<Run> latest = snapshotLocked();
            snapshot = latest;
            save();
            return latest;
        }
    }

    /** EDT 安全：读不可变快照，绝不等磁盘写（save 可达数 MB，会卡住 UI）。 */
    public List<Run> list() {
        return snapshot;
    }

    public void clear() {
        synchronized (lock) {
            runs.clear();
            snapshot = List.of();
            save();
        }
    }

    private List<Run> snapshotLocked() {
        List<Run> copy = new ArrayList<>(runs);
        java.util.Collections.reverse(copy);
        return java.util.Collections.unmodifiableList(copy);
    }

    private Path file() {
        Path base = baseDir != null ? baseDir
                : Path.of(System.getProperty("user.home"), "ai-analyzer-workplace");
        return base.resolve("analysis-runs").resolve("analysis-runs.json");
    }

    private List<Run> load() {
        Path f = file();
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            List<Run> loaded = GSON.fromJson(json, new TypeToken<List<Run>>() {}.getType());
            return loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(runs), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[已截断]";
    }
}
