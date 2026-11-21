package com.ai.analyzer.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整理 agent 的 root 白名单（跑偏隔离）：
 * 图谱里有多个资产时，scoped GraphTool 拒绝越界读写，防止整理 agent 跑去打别的站。
 */
@DisplayName("GraphTool - root 白名单隔离")
class GraphToolScopeTest {

    @Test
    @DisplayName("unscoped_tool_allows_any_root")
    void unscopedAllowsAll() throws Exception {
        GraphTool tool = new GraphTool(freshStore(), "test-agent");
        // 不限制：任意 root 的读调用不会被拒（返回内容或空皆可，关键是不带「拒绝」）
        String r = tool.overview("https://anything.example:443");
        assertThat(r).doesNotContain("不在本次会话的范围内");
    }

    @Test
    @DisplayName("scoped_tool_denies_other_root_reads")
    void scopedDeniesReads() throws Exception {
        GraphTool tool = new GraphTool(freshStore(), "test-agent",
                List.of("https://allowed.example:443"));
        assertThat(tool.overview("https://other.example:443"))
                .contains("不在本次会话的范围内");
        assertThat(tool.getRoot("https://other.example:80"))
                .contains("不在本次会话的范围内");
        assertThat(tool.findInterfaces("https://other.example:443", null, null, null))
                .contains("不在本次会话的范围内");
        // 允许的 root 不拒
        assertThat(tool.overview("https://allowed.example:443"))
                .doesNotContain("不在本次会话的范围内");
    }

    @Test
    @DisplayName("scoped_tool_denies_other_root_writes")
    void scopedDeniesWrites() throws Exception {
        GraphTool tool = new GraphTool(freshStore(), "test-agent",
                List.of("https://allowed.example:443"));
        String deny = tool.upsertObservation("INTERFACE", "https://other.example/api/x",
                "https://other.example:443", null);
        assertThat(deny).contains("不在本次会话的范围内");

        String denyNote = tool.addNote("x", "https://other.example:443", null, "note");
        assertThat(denyNote).contains("不在本次会话的范围内");

        String denyFp = tool.upsertFingerprint("Nginx", "https://other.example:443",
                null, "server", null, "e", 0.8);
        assertThat(denyFp).contains("不在本次会话的范围内");

        // 事实锚定也按 URL 限制
        String denyFact = tool.recordAnalysisFact("GET", "https://other.example/api/y", "s");
        assertThat(denyFact).contains("不在本次会话的范围内");
    }

    @Test
    @DisplayName("scoped_tool_accepts_url_form_of_allowed_root")
    void urlFormMatches() throws Exception {
        GraphTool tool = new GraphTool(freshStore(), "test-agent",
                List.of("https://allowed.example:443"));
        // 传完整 URL（不是 origin 形态）也应放行：inScope 会归一化再比
        assertThat(tool.overview("https://allowed.example:443/api"))
                .doesNotContain("不在本次会话的范围内");
    }

    @Test
    @DisplayName("setScopeRoots_rebinds_at_runtime")
    void rebindWorks() throws Exception {
        GraphTool tool = new GraphTool(freshStore(), "test-agent");
        tool.setScopeRoots(List.of("https://a.example:443"));
        assertThat(tool.overview("https://b.example:443")).contains("不在本次会话的范围内");
        // 换绑到 b 后 a 被拒、b 放行
        tool.setScopeRoots(List.of("https://b.example:443"));
        assertThat(tool.overview("https://a.example:443")).contains("不在本次会话的范围内");
        assertThat(tool.overview("https://b.example:443")).doesNotContain("不在本次会话的范围内");
        // null = 解除限制
        tool.setScopeRoots(null);
        assertThat(tool.overview("https://a.example:443")).doesNotContain("不在本次会话的范围内");
    }

    private GraphStore freshStore() throws Exception {
        GraphStore store = GraphStore.getInstance();
        store.resetForTest();
        Path d = Files.createTempDirectory("graph-tool-scope-test");
        store.setWorkplaceDirectory(d.toString());
        return store;
    }
}
