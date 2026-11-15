package com.ai.analyzer.agent.organizer;

import burp.api.montoya.MontoyaApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("OrganizerTool - 整理 agent 工具")
class OrganizerToolTest {

    @Test
    @DisplayName("finish_records_decision")
    void finishRecordsDecision(@TempDir Path tempDir) {
        OrganizerTool tool = new OrganizerTool(mock(MontoyaApi.class), tempDir);
        tool.finish(true, "test /api/order with id=2", "orders endpoint untested", "");

        OrganizerTool.Decision d = tool.getDecision();
        assertThat(d).isNotNull();
        assertThat(d.continueAnalysis()).isTrue();
        assertThat(d.instruction()).contains("/api/order");
        assertThat(d.gaps()).contains("orders");
    }

    @Test
    @DisplayName("save_script_sanitizes_path_and_writes_file")
    void savesScriptWithoutTraversal(@TempDir Path tempDir) throws Exception {
        OrganizerTool tool = new OrganizerTool(mock(MontoyaApi.class), tempDir);
        String result = tool.saveScript("../evil.py", "print('poc')");

        Path expected = tempDir.resolve("pocs").resolve("evil.py");
        assertThat(result).contains("evil.py");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.readString(expected)).contains("print('poc')");
    }

    @Test
    @DisplayName("invalid_url_is_rejected_before_touching_repeater")
    void rejectsInvalidUrl(@TempDir Path tempDir) {
        OrganizerTool tool = new OrganizerTool(mock(MontoyaApi.class), tempDir);
        assertThat(tool.sendToRepeater("GET", "not-a-url", null, null, "poc"))
                .contains("URL 不合法");
    }
}
