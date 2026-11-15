package com.ai.analyzer.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentDigestStore - 侧栏结论摘要单向共享")
class AgentDigestStoreTest {

    @AfterEach
    void cleanup() {
        AgentDigestStore.getInstance().clear();
    }

    @Test
    @DisplayName("records_text_and_renders_recent_entries")
    void recordsAndRenders() {
        AgentDigestStore store = AgentDigestStore.getInstance();
        store.record("first conclusion");
        store.record("second conclusion");

        String rendered = store.renderForPrompt(10_000);
        assertThat(rendered).contains("first conclusion").contains("second conclusion");
        // 最新在后
        assertThat(rendered.indexOf("first conclusion")).isLessThan(rendered.indexOf("second conclusion"));
    }

    @Test
    @DisplayName("deduplicates_consecutive_identical_entries")
    void deduplicates() {
        AgentDigestStore store = AgentDigestStore.getInstance();
        store.record("same");
        store.record("same   ");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("caps_render_length")
    void capsRenderLength() {
        AgentDigestStore store = AgentDigestStore.getInstance();
        store.record("x".repeat(5_000));
        String rendered = store.renderForPrompt(100);
        assertThat(rendered.length()).isLessThanOrEqualTo(120);
    }
}
