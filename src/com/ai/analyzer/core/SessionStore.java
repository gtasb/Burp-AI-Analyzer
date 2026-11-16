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
 * 主动分析的会话（session）存储：按 session 划分对话历史，供会话下拉切换。
 *
 * <p>文件在 {@code <workplace>/analysis-runs/sessions.json}。每个会话含若干轮
 * （turn：用户提示 + 主动 agent 产出）。切换会话时 UI 重放该会话的全部轮次，
 * AgentApiClient.switchSession 切回同一 sessionId 即续聊（上下文按 session
 * 保存在 AgentScope 状态里——SessionSwitchContextTest 验证了来回切换不丢）。
 *
 * <p>旧 analysis-runs.json（按单轮 Run 持久化）在首次加载时按「60 分钟无新轮
 * 归属同一会话」的策略迁移为会话结构，然后重命名备份为 .bak，不再读它。
 */
public final class SessionStore {

    public static final int MAX_SESSIONS = 30;
    public static final int MAX_TURNS_PER_SESSION = 200;
    public static final int MAX_RESULT_CHARS = 200_000;
    /** 相邻两轮间隔超过该值视为新会话（迁移旧 run 历史时的分组规则） */
    private static final long LEGACY_GROUP_GAP_MS = 60 * 60 * 1000L;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SessionStore INSTANCE = new SessionStore();

    private final Object lock = new Object();
    private Path baseDir;
    /** 落盘结构：session 元数据 + 轮次（按创建顺序；快照反转成最新在前） */
    private final List<SessionWithTurns> data = new ArrayList<>();
    /** 不可变快照（最新在前）：EDT 读它不等磁盘写 */
    private volatile List<Session> snapshot = List.of();

    private SessionStore() {
    }

    public static SessionStore getInstance() {
        return INSTANCE;
    }

    /** 一轮对话：用户提示 + 主动 agent 产出（不含工具轨迹，回放用）。 */
    public record Turn(long time, String method, String url, String prompt, String result) {
    }

    /** 一个会话：稳定 id（= AgentApiClient 的 sessionId）+ 标题 + 轮次列表。 */
    public record Session(String id, String title, String createdTime, String lastTime, int turnCount) {
        @Override
        public String toString() {
            return title + "（" + turnCount + " 轮 · " + lastTime + "）";
        }
    }

    /** 会话的完整可回放数据（轮次列表按发生顺序）。 */
    public record SessionDetail(Session session, List<Turn> turns) {
    }

    public void setWorkplaceDirectory(String dir) {
        Path next = dir == null || dir.isBlank() ? null : Path.of(dir.trim()).toAbsolutePath().normalize();
        synchronized (lock) {
            if (java.util.Objects.equals(baseDir, next)) return;
            baseDir = next;
            data.clear();
            data.addAll(load());
            snapshot = snapshotLocked();
        }
    }

    /** 新建会话（新会话按钮）：生成 id，持久化空会话，返回 session。 */
    public Session createSession(String sessionId, String firstTitle) {
        String title = firstTitle == null || firstTitle.isBlank() ? "新会话" : firstTitle;
        Session s = new Session(sessionId, truncate(title, 40), now(), now(), 0);
        synchronized (lock) {
            data.add(new SessionWithTurns(s, new ArrayList<>()));
            trimLocked();
            snapshot = snapshotLocked();
            save();
            return s;
        }
    }

    /** 会话结束/标题变化时更新元数据（不存在则忽略）。 */
    public Session touchSession(String sessionId, String title) {
        synchronized (lock) {
            for (SessionWithTurns swt : data) {
                if (swt.session.id().equals(sessionId)) {
                    String newTitle = title == null || title.isBlank()
                            ? swt.session.title() : truncate(title, 40);
                    swt.session = new Session(swt.session.id(), newTitle,
                            swt.session.createdTime(), now(), swt.turns.size());
                    List<Session> latest = snapshotLocked();
                    snapshot = latest;
                    save();
                    return swt.session;
                }
            }
            return null;
        }
    }

    /** 追加一轮（在后台线程调用）。会话不存在时自动创建。返回最新列表。 */
    public List<Session> addTurn(String sessionId, String title,
                                 String method, String url, String prompt, String result) {
        Turn turn = new Turn(System.currentTimeMillis(), method, url,
                truncate(prompt, 8_000), truncate(result, MAX_RESULT_CHARS));
        synchronized (lock) {
            SessionWithTurns target = null;
            for (SessionWithTurns swt : data) {
                if (swt.session.id().equals(sessionId)) {
                    target = swt;
                    break;
                }
            }
            if (target == null) {
                target = new SessionWithTurns(new Session(sessionId,
                        truncate(title == null || title.isBlank() ? "新会话" : title, 40),
                        now(), now(), 0), new ArrayList<>());
                data.add(target);
            }
            target.turns.add(turn);
            while (target.turns.size() > MAX_TURNS_PER_SESSION) {
                target.turns.remove(0);
            }
            String newTitle = title == null || title.isBlank()
                    ? target.session.title() : truncate(title, 40);
            target.session = new Session(target.session.id(), newTitle,
                    target.session.createdTime(), now(), target.turns.size());
            trimLocked();
            List<Session> latest = snapshotLocked();
            snapshot = latest;
            save();
            return latest;
        }
    }

    /** 读取某个会话的完整轮次（回放用）。 */
    public SessionDetail detail(String sessionId) {
        synchronized (lock) {
            for (SessionWithTurns swt : data) {
                if (swt.session.id().equals(sessionId)) {
                    return new SessionDetail(swt.session, List.copyOf(swt.turns));
                }
            }
            return null;
        }
    }

    /** 删除会话。 */
    public void deleteSession(String sessionId) {
        synchronized (lock) {
            data.removeIf(swt -> swt.session.id().equals(sessionId));
            snapshot = snapshotLocked();
            save();
        }
    }

    /** EDT 安全：最新在前的不可变快照。 */
    public List<Session> list() {
        return snapshot;
    }

    public void clear() {
        synchronized (lock) {
            data.clear();
            snapshot = List.of();
            save();
        }
    }

    // ================= 内部 =================

    /** 落盘结构：session 元数据 + 轮次。 */
    private static final class SessionWithTurns {
        Session session;
        List<Turn> turns = new ArrayList<>();

        SessionWithTurns() {
        }

        SessionWithTurns(Session session, List<Turn> turns) {
            this.session = session;
            this.turns = turns;
        }
    }

    private void trimLocked() {
        // 超出上限时删最旧的非当前活跃会话（保序）
        while (data.size() > MAX_SESSIONS) {
            data.remove(0);
        }
    }

    private List<Session> snapshotLocked() {
        List<Session> copy = new ArrayList<>();
        for (SessionWithTurns swt : data) {
            copy.add(swt.session);
        }
        // 最新在后（自然顺序）；快照反转成最新在前
        java.util.Collections.reverse(copy);
        return java.util.Collections.unmodifiableList(copy);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private Path file() {
        Path base = baseDir != null ? baseDir
                : Path.of(System.getProperty("user.home"), "ai-analyzer-workplace");
        return base.resolve("analysis-runs").resolve("sessions.json");
    }

    private Path legacyFile() {
        Path base = baseDir != null ? baseDir
                : Path.of(System.getProperty("user.home"), "ai-analyzer-workplace");
        return base.resolve("analysis-runs").resolve("analysis-runs.json");
    }

    private List<SessionWithTurns> load() {
        Path f = file();
        try {
            if (Files.exists(f)) {
                String json = Files.readString(f, StandardCharsets.UTF_8);
                List<SessionWithTurns> loaded = GSON.fromJson(json,
                        new TypeToken<List<SessionWithTurns>>() {}.getType());
                if (loaded != null) {
                    for (SessionWithTurns swt : loaded) {
                        if (swt.turns == null) swt.turns = new ArrayList<>();
                    }
                    return new ArrayList<>(loaded);
                }
            }
        } catch (Exception ignored) {
        }
        // 首次（无 sessions.json）：迁移旧 analysis-runs.json，成功后把旧文件改名为 .bak
        List<SessionWithTurns> migrated = migrateLegacy();
        if (migrated != null) {
            try {
                Path legacy = legacyFile();
                if (Files.exists(legacy)) {
                    Files.move(legacy, legacy.resolveSibling("analysis-runs.json.bak"));
                }
            } catch (Exception ignored) {
            }
            return migrated;
        }
        return new ArrayList<>();
    }

    /** 旧 run 历史迁移：相邻间隔 < 60min 的 run 归为同一会话。无旧文件返回 null。 */
    private List<SessionWithTurns> migrateLegacy() {
        Path legacy = legacyFile();
        if (!Files.exists(legacy)) return null;
        try {
            String json = Files.readString(legacy, StandardCharsets.UTF_8);
            List<AnalysisRunStore.Run> runs = GSON.fromJson(json,
                    new TypeToken<List<AnalysisRunStore.Run>>() {}.getType());
            if (runs == null || runs.isEmpty()) return new ArrayList<>();
            // runs 存储顺序：旧在前。分组
            List<SessionWithTurns> out = new ArrayList<>();
            SessionWithTurns current = null;
            long lastTime = -1;
            int groupSeq = 0;
            for (AnalysisRunStore.Run run : runs) {
                long t = run.id();
                if (current == null || t - lastTime > LEGACY_GROUP_GAP_MS) {
                    groupSeq++;
                    String title = titleOf(run);
                    current = new SessionWithTurns(new Session(
                            "legacy-" + groupSeq + "-" + t, title,
                            run.time() != null ? run.time() : now(),
                            run.time() != null ? run.time() : now(), 0), new ArrayList<>());
                    out.add(current);
                }
                current.turns.add(new Turn(t, run.method(), run.url(), run.prompt(), run.result()));
                current.session = new Session(current.session.id(),
                        current.session.title().equals("迁移会话") ? titleOf(run) : current.session.title(),
                        current.session.createdTime(),
                        run.time() != null ? run.time() : now(), current.turns.size());
                lastTime = t;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String titleOf(AnalysisRunStore.Run run) {
        String target = run.url() == null ? "" : run.url();
        if (target.length() > 34) target = target.substring(0, 33) + "…";
        String prompt = run.prompt() == null ? "" : run.prompt().trim();
        if (!prompt.isEmpty()) {
            String first = prompt.replaceAll("\\s+", " ");
            if (first.length() > 30) first = first.substring(0, 29) + "…";
            return (run.method() == null ? "" : run.method() + " ") + target + " · " + first;
        }
        return (run.method() == null ? "" : run.method() + " ") + (target.isEmpty() ? "自由对话" : target);
    }

    private void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[已截断]";
    }
}
