package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentApiClient - path normalization")
class AgentApiClientTest {

    @Test
    @DisplayName("active agent no longer writes fact anchors; organizer tool does")
    void activeAnalysisLeavesFactAnchorToOrganizer(@TempDir Path tempDir) throws Exception {
        com.ai.analyzer.graph.GraphStore store = com.ai.analyzer.graph.GraphStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            burp.api.montoya.http.message.HttpRequestResponse rr =
                    org.mockito.Mockito.mock(burp.api.montoya.http.message.HttpRequestResponse.class);
            burp.api.montoya.http.message.requests.HttpRequest req =
                    org.mockito.Mockito.mock(burp.api.montoya.http.message.requests.HttpRequest.class);
            org.mockito.Mockito.when(rr.request()).thenReturn(req);
            org.mockito.Mockito.when(req.method()).thenReturn("GET");
            org.mockito.Mockito.when(req.url()).thenReturn("https://fact.example/api/x");
            org.mockito.Mockito.when(req.bodyToString()).thenReturn("");
            store.observe(rr);

            AgentApiClient client = new AgentApiClient("http://localhost", "k");
            assertThat(client.getRole()).isEqualTo(AgentRole.ACTIVE);

            String root = "https://fact.example:443";
            assertThat(store.listNodes(root, 100))
                    .noneMatch(n -> n.type() == com.ai.analyzer.graph.GraphTypes.NodeType.ANALYSIS_FACT);

            // 整理 agent 通过图谱工具落事实锚点
            new com.ai.analyzer.graph.GraphTool(store, "organizer")
                    .recordAnalysisFact("GET", "https://fact.example/api/x", "organizer summary");

            assertThat(store.listNodes(root, 100))
                    .anyMatch(n -> n.type() == com.ai.analyzer.graph.GraphTypes.NodeType.ANALYSIS_FACT);
            assertThat(store.listEdges(root, 100))
                    .anyMatch(e -> e.relationType() == com.ai.analyzer.graph.GraphTypes.EdgeType.COVERS);
        } finally {
            store.setWorkplaceDirectory("");
        }
    }

    @Test
    @DisplayName("role-specific clients have distinct agent names")
    void roleSpecificAgentNames() {
        burp.api.montoya.MontoyaApi api = org.mockito.Mockito.mock(burp.api.montoya.MontoyaApi.class);
        AgentApiClient active = new AgentApiClient(api, "http://localhost", "k", AgentRole.ACTIVE);
        AgentApiClient sidebar = new AgentApiClient(api, "http://localhost", "k", AgentRole.SIDEBAR);
        AgentApiClient organizer = new AgentApiClient(api, "http://localhost", "k", AgentRole.ORGANIZER);

        assertThat(active.getAgentName()).isEqualTo("burp-ai-active");
        assertThat(sidebar.getAgentName()).isEqualTo("burp-ai-sidebar");
        assertThat(organizer.getAgentName()).isEqualTo("burp-ai-organizer");
        assertThat(active.getRole()).isNotEqualTo(sidebar.getRole());
    }

    @Test
    @DisplayName("normalizePath should return empty for null input")
    void normalizePath_null() {
        assertThat(AgentApiClient.normalizePath(null)).isNull();
    }

    @Test
    @DisplayName("normalizePath should return empty for empty input")
    void normalizePath_empty() {
        assertThat(AgentApiClient.normalizePath("")).isEmpty();
    }

    @Test
    @DisplayName("normalizePath should return unchanged for path without spaces")
    void normalizePath_noSpaces() {
        String result = AgentApiClient.normalizePath("C:\\Tools\\test");
        assertThat(result).isEqualTo("C:\\Tools\\test");
    }

    @Test
    @DisplayName("normalizePath should resolve short path for real directory with spaces")
    void normalizePath_realDirWithSpaces(@TempDir Path tempDir) throws IOException {
        // 创建一个实际存在的子目录，路径包含空格
        File spaceDir = new File(tempDir.toFile(), "my tools");
        assertThat(spaceDir.mkdirs()).isTrue();

        String result = AgentApiClient.normalizePath(spaceDir.getAbsolutePath());
        assertThat(result).isNotNull().isNotEmpty();

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            // Windows 上应转为短路径（不含空格）
            assertThat(result).doesNotContain(" ");
        } else {
            // 非 Windows 原样返回
            assertThat(result).isEqualTo(spaceDir.getAbsolutePath());
        }
    }
}
