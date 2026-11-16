package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionStore - 会话存储")
class SessionStoreTest {

    @Test
    @DisplayName("create_addTurn_and_list_newest_first")
    void lifecycle(@TempDir Path tempDir) {
        SessionStore store = SessionStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            store.clear();
            store.createSession("sess-1", "目标 A");
            store.addTurn("sess-1", "目标 A", "GET", "https://a.example/x", "p1", "r1");
            store.addTurn("sess-1", "目标 A", "GET", "https://a.example/x", "p2", "r2");
            store.createSession("sess-2", "目标 B");
            store.addTurn("sess-2", "目标 B", "POST", "https://b.example/y", "p3", "r3");

            List<SessionStore.Session> list = store.list();
            assertThat(list).hasSize(2);
            // 最新在前
            assertThat(list.get(0).id()).isEqualTo("sess-2");
            assertThat(list.get(0).turnCount()).isEqualTo(1);
            assertThat(list.get(1).id()).isEqualTo("sess-1");
            assertThat(list.get(1).turnCount()).isEqualTo(2);
            assertThat(list.get(1).title()).contains("目标 A");

            SessionStore.SessionDetail detail = store.detail("sess-1");
            assertThat(detail).isNotNull();
            assertThat(detail.turns()).hasSize(2);
            // 轮次按发生顺序
            assertThat(detail.turns().get(0).prompt()).isEqualTo("p1");
            assertThat(detail.turns().get(1).result()).isEqualTo("r2");
        } finally {
            store.clear();
        }
    }

    @Test
    @DisplayName("addTurn_creates_missing_session_and_updates_title")
    void addTurnAutoCreates(@TempDir Path tempDir) {
        SessionStore store = SessionStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            store.clear();
            store.addTurn("auto-1", "POST /api/login", "POST", "https://c.example/login", "prompt", "result");
            List<SessionStore.Session> list = store.list();
            assertThat(list).hasSize(1);
            assertThat(list.get(0).id()).isEqualTo("auto-1");
            assertThat(list.get(0).title()).contains("/api/login");
            assertThat(list.get(0).turnCount()).isEqualTo(1);
        } finally {
            store.clear();
        }
    }

    @Test
    @DisplayName("delete_and_persist_across_reload")
    void deleteAndReload(@TempDir Path tempDir) {
        SessionStore store = SessionStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            store.clear();
            store.createSession("s-keep", "保留");
            store.addTurn("s-keep", "保留", "GET", "https://k.example", "p", "r");
            store.createSession("s-del", "删除");
            store.deleteSession("s-del");
            assertThat(store.list()).hasSize(1);
            assertThat(store.list().get(0).id()).isEqualTo("s-keep");

            // 换目录再换回：数据从磁盘重新加载
            store.setWorkplaceDirectory(tempDir.resolve("other").toString());
            assertThat(store.list()).isEmpty();
            store.setWorkplaceDirectory(tempDir.toString());
            assertThat(store.list()).hasSize(1);
            assertThat(store.detail("s-keep").turns()).hasSize(1);
            assertThat(Files.exists(tempDir.resolve("analysis-runs").resolve("sessions.json"))).isTrue();
        } finally {
            store.clear();
        }
    }

    @Test
    @DisplayName("migrates_legacy_runs_into_sessions")
    void legacyMigration(@TempDir Path tempDir) {
        // 准备旧格式 analysis-runs.json（两条同间隔 + 一条远间隔）
        Path dir = tempDir.resolve("analysis-runs");
        try {
            Files.createDirectories(dir);
            String json = """
                    [
                      {"id":1000,"time":"2026-11-01 10:00:00","method":"GET","url":"https://old.example/a","prompt":"q1","result":"r1"},
                      {"id":120000,"time":"2026-11-01 10:02:00","method":"GET","url":"https://old.example/b","prompt":"q2","result":"r2"},
                      {"id":9000000000,"time":"2026-11-03 18:00:00","method":"POST","url":"https://other.example/c","prompt":"q3","result":"r3"}
                    ]
                    """;
            Files.writeString(dir.resolve("analysis-runs.json"), json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SessionStore store = SessionStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            List<SessionStore.Session> list = store.list();
            // 10:00 和 10:02 间隔 2 分钟 → 同一会话；18:00 远超 60 分钟 → 另一会话
            assertThat(list).hasSize(2);
            SessionStore.SessionDetail first = store.detail(list.get(1).id());
            assertThat(first.turns()).hasSize(2);
            assertThat(first.turns().get(0).prompt()).isEqualTo("q1");
            SessionStore.SessionDetail second = store.detail(list.get(0).id());
            assertThat(second.turns()).hasSize(1);
            assertThat(second.turns().get(0).prompt()).isEqualTo("q3");
            // 旧文件已改名备份
            assertThat(Files.exists(dir.resolve("analysis-runs.json.bak"))).isTrue();
            assertThat(Files.exists(dir.resolve("analysis-runs.json"))).isFalse();
        } finally {
            store.clear();
        }
    }
}
