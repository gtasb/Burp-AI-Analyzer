package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnalysisRunStore - 主动分析历史")
class AnalysisRunStoreTest {

    @Test
    @DisplayName("persists_runs_and_returns_newest_first")
    void persistsRuns(@TempDir Path tempDir) {
        AnalysisRunStore store = AnalysisRunStore.getInstance();
        store.setWorkplaceDirectory(tempDir.toString());
        try {
            store.clear();
            store.add("GET", "https://a.example/x", "prompt-1", "result-1");
            store.add("POST", "https://a.example/y", "prompt-2", "result-2");

            List<AnalysisRunStore.Run> runs = store.list();
            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).result()).isEqualTo("result-2");
            assertThat(runs.get(1).result()).isEqualTo("result-1");
        } finally {
            store.clear();
        }
    }

    @Test
    @DisplayName("ignores_missing_workspace_and_keeps_working")
    void missingWorkspace(@TempDir Path tempDir) {
        AnalysisRunStore store = AnalysisRunStore.getInstance();
        store.setWorkplaceDirectory(tempDir.resolve("not-created").toString());
        try {
            store.clear();
            store.add(null, "free-chat", "", "hello");
            assertThat(store.list()).hasSize(1);
        } finally {
            store.clear();
        }
    }
}
