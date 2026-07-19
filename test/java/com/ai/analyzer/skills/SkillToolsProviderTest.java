package com.ai.analyzer.skills;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SkillToolsProvider (二进制执行)")
class SkillToolsProviderTest {

    @TempDir
    Path tempDir;

    private SkillManager manager;
    private SkillToolsProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        manager = new SkillManager();

        // 创建带工具的 skill
        Path skillDir = tempDir.resolve("recon");
        Files.createDirectories(skillDir);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Files.writeString(skillDir.resolve("SKILL.md"), ("""
                ---
                name: recon
                description: Reconnaissance skill
                tools:
                  - name: echo_test
                    description: Echo a message
                    command: %s
                    args: "%s"
                    timeout: 10
                    parameters:
                      - name: msg
                        description: Message
                        required: true
                ---
                
                # Recon Skill
                Reconnaissance instructions.
                """).formatted(
                isWindows ? "cmd" : "/bin/echo",
                isWindows ? "/c echo {msg}" : "{msg}"
        ), StandardCharsets.UTF_8);

        // 创建无工具的 skill
        Path docsDir = tempDir.resolve("api-analysis");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("SKILL.md"), """
                ---
                name: api-analysis
                description: API security analysis guidelines
                ---
                
                When analyzing API endpoints:
                1. Check authentication
                2. Check authorization
                """, StandardCharsets.UTF_8);

        manager.setSkillsDirectoryPath(tempDir.toString());
        manager.enableSkill("recon");
        manager.enableSkill("api-analysis");

        provider = new SkillToolsProvider(manager);
    }

    // ==================== execute_skill_tool ====================

    @Nested
    @DisplayName("execute_skill_tool")
    class ExecuteSkillTool {

        @Test
        @DisplayName("should_return_error_for_unknown_tool")
        void should_return_error_for_unknown_tool() {
            String result = provider.executeSkillTool("nonexistent_tool", "{}");
            assertThat(result).contains("错误");
            assertThat(result).contains("未找到工具");
        }

        @Test
        @DisplayName("should_return_error_for_blank_tool_name")
        void should_return_error_for_blank_tool_name() {
            assertThat(provider.executeSkillTool("", "{}")).contains("错误");
            assertThat(provider.executeSkillTool(null, "{}")).contains("错误");
        }

        @Test
        @DisplayName("should_execute_valid_tool")
        void should_execute_valid_tool() {
            String result = provider.executeSkillTool("echo_test",
                    "{\"msg\": \"skill-test-output\"}");
            assertThat(result).contains("skill-test-output");
        }
    }

    // ==================== list_skill_tools ====================

    @Nested
    @DisplayName("list_skill_tools")
    class ListSkillTools {

        @Test
        @DisplayName("should_list_enabled_tools")
        void should_list_enabled_tools() {
            String result = provider.listSkillTools();
            assertThat(result).contains("skill_recon_echo_test");
            assertThat(result).contains("Echo a message");
        }

        @Test
        @DisplayName("should_return_empty_when_no_tools")
        void should_return_empty_when_no_tools() {
            manager.disableSkill("recon");
            String result = provider.listSkillTools();
            assertThat(result).contains("没有可用的 Skill 工具");
        }
    }

    // ==================== JSON Parsing ====================

    @Nested
    @DisplayName("parseJsonParameters")
    class JsonParsing {

        @Test
        @DisplayName("should_parse_simple_json")
        void should_parse_simple_json() {
            Map<String, String> params = provider.parseJsonParameters(
                    "{\"target\": \"192.168.1.1\", \"ports\": \"80,443\"}");
            assertThat(params).containsEntry("target", "192.168.1.1");
            assertThat(params).containsEntry("ports", "80,443");
        }

        @Test
        @DisplayName("should_handle_escaped_characters")
        void should_handle_escaped_characters() {
            Map<String, String> params = provider.parseJsonParameters(
                    "{\"msg\": \"hello\\nworld\"}");
            assertThat(params.get("msg")).isEqualTo("hello\nworld");
        }

        @Test
        @DisplayName("should_return_empty_map_for_null_or_blank")
        void should_return_empty_map_for_null_or_blank() {
            assertThat(provider.parseJsonParameters(null)).isEmpty();
            assertThat(provider.parseJsonParameters("")).isEmpty();
            assertThat(provider.parseJsonParameters("   ")).isEmpty();
        }
    }
}
