package com.ai.analyzer.tools;

import com.ai.analyzer.tools.NotebookUtils.NotebookEntry;
import com.ai.analyzer.tools.NotebookUtils.NotebookStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotebookStore - 共享知识黑板引擎")
class NotebookStoreTest {

    private NotebookStore store;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        store = NotebookStore.getInstance();
        dir = Files.createTempDirectory("notebook-test");
        store.setWorkplaceDirectory(dir.toString());
    }

    @Test
    @DisplayName("写入递增版本号，域名大小写归一，读取回内容")
    void write_increments_version_and_reads_back() throws Exception {
        var r1 = store.write("Example.COM", "attack_surface", "登录页位于 /login", "active-agent");
        var r2 = store.write("example.com", "parameter", "id 参数存在反射", "passive-agent");

        assertThat(r1.version()).isEqualTo(1);
        assertThat(r2.version()).isEqualTo(2);
        assertThat(store.latestVersion("example.com")).isEqualTo(2);
        assertThat(NotebookStore.normalizeDomain("EXAMPLE.com")).isEqualTo("example.com");
        assertThat(store.read("example.com")).contains("登录页位于 /login");
    }

    @Test
    @DisplayName("相同内容去重：未新增版本、未重复入库")
    void dedup_skips_identical_content() throws Exception {
        store.write("example.com", "vuln_clue", "可能的 SQL 注入", "a");
        var dup = store.write("example.com", "vuln_clue", "可能的 SQL 注入", "b");

        assertThat(dup.duplicate()).isTrue();
        assertThat(dup.version()).isEqualTo(1);
        assertThat(store.getUpdates("example.com", 0)).hasSize(1);
    }

    @Test
    @DisplayName("get_updates 只返回大于 since_version 的增量")
    void get_updates_returns_only_newer_versions() throws Exception {
        store.write("example.com", "note", "第一条", "a");
        store.write("example.com", "note", "第二条", "a");
        store.write("example.com", "note", "第三条", "a");

        List<NotebookEntry> updates = store.getUpdates("example.com", 1);

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).version()).isEqualTo(2);
        assertThat(updates.get(1).version()).isEqualTo(3);
        assertThat(updates.get(1).content()).isEqualTo("第三条");
    }

    @Test
    @DisplayName("listDomains 返回各域名摘要（版本 + 条目数）")
    void list_domains_reports_summary() throws Exception {
        store.write("a.com", "note", "x1", "a");
        store.write("b.com", "note", "x2", "a");
        store.write("b.com", "note", "x3", "a");

        var domains = store.listDomains();
        assertThat(domains).extracting(NotebookStore.DomainSummary::domain).containsExactlyInAnyOrder("a.com", "b.com");
        assertThat(domains.stream()
                .filter(d -> d.domain().equals("b.com"))
                .findFirst().orElseThrow()
                .version()).isEqualTo(2);
    }

    @Test
    @DisplayName("域名归一：去协议/端口/路径，非法字符替换")
    void normalize_domain_strips_protocol_path_port() {
        assertThat(NotebookStore.normalizeDomain("https://www.Example.com:8443/login?x=1"))
                .isEqualTo("www.example.com");
        assertThat(NotebookStore.normalizeDomain("")).isEqualTo("default");
    }

    @Test
    @DisplayName("并发写入：无版本丢失、无重复版本号")
    void concurrent_writes_no_lost_versions() throws Exception {
        int threads = 8;
        int perThread = 10;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    try {
                        store.write("example.com", "note", "t" + id + "-" + j, "a" + id);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();

        int expected = threads * perThread;
        assertThat(store.latestVersion("example.com")).isEqualTo(expected);
        assertThat(store.getUpdates("example.com", 0)).hasSize(expected);

        long distinctVersions = store.getUpdates("example.com", 0).stream()
                .map(NotebookEntry::version)
                .distinct()
                .count();
        assertThat(distinctVersions).isEqualTo(expected);
    }

    @Test
    @DisplayName("stripEntryMarkers 隐藏元数据标记，保留可读正文与标题")
    void strip_markers_hides_metadata() throws Exception {
        store.write("example.com", "note", "hello world", "a");
        String stripped = NotebookStore.stripEntryMarkers(store.read("example.com"));

        assertThat(stripped).doesNotContain("nb:entry");
        assertThat(stripped).contains("hello world");
        assertThat(stripped).contains("[v1]");
    }
}