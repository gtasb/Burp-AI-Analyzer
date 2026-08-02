package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHistoryStoreTest {

    @Test
    void addAndRetrieve() {
        AnalysisHistoryStore store = new AnalysisHistoryStore();
        store.add("GET /api/user?id=1", "请分析", "结果1");
        store.add("自由对话模式", "请分析", "结果2");
        assertEquals(2, store.size());
        assertEquals("GET /api/user?id=1", store.get(0).targetDesc());
        assertEquals("结果1", store.get(0).result());
        assertEquals("结果2", store.get(1).result());
        assertTrue(store.snapshot().get(0).timestamp() != null && !store.snapshot().get(0).timestamp().isEmpty());
    }

    @Test
    void clear() {
        AnalysisHistoryStore store = new AnalysisHistoryStore();
        store.add("t", "p", "r");
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void capAtMaxEntries() {
        AnalysisHistoryStore store = new AnalysisHistoryStore();
        for (int i = 0; i < AnalysisHistoryStore.MAX_ENTRIES + 50; i++) {
            store.add("t" + i, "p" + i, "r" + i);
        }
        assertEquals(AnalysisHistoryStore.MAX_ENTRIES, store.size());
        // 最早被挤出的 50 条：剩下 t50..t249，最新一条保留
        assertEquals("t50", store.get(0).targetDesc());
        assertEquals("t" + (AnalysisHistoryStore.MAX_ENTRIES + 49), store.get(store.size() - 1).targetDesc());
    }
}
