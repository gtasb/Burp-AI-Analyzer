package com.ai.analyzer.skills;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SkillManager (FileSystemSkillLoader + Skills)")
class SkillManagerTest {

    @TempDir
    Path tempDir;

    private SkillManager manager;

    @BeforeEach
    void setUp() {
        manager = new SkillManager();
    }

    // ==================== Loading ====================

    @Nested
    @DisplayName("加载 Skills (FileSystemSkillLoader)")
    class LoadSkills {

        @Test
        @DisplayName("should_load_skill_from_subdirectory_via_official_loader")
        void should_load_skill_from_subdirectory_via_official_loader() throws IOException {
            Path skillDir = tempDir.resolve("my-skill");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: my-skill
                    description: Test skill
                    ---
                    
                    # My Skill
                    
                    Do something useful.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());

            assertThat(manager.getAllSkills()).hasSize(1);
            Skill skill = manager.getSkill("my-skill");
            assertThat(skill).isNotNull();
            assertThat(skill.getDescription()).isEqualTo("Test skill");
            assertThat(skill.getContent()).contains("Do something useful");
        }

        @Test
        @DisplayName("should_load_resources_via_FileSystemSkill")
        void should_load_resources_via_FileSystemSkill() throws IOException {
            Path skillDir = tempDir.resolve("with-resources");
            Path refsDir = skillDir.resolve("references");
            Files.createDirectories(refsDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: with-resources
                    description: Skill with resources
                    ---
                    
                    Check references.
                    """, StandardCharsets.UTF_8);
            Files.writeString(refsDir.resolve("guide.md"), "# Guide content", StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());

            Skill skill = manager.getSkill("with-resources");
            assertThat(skill).isNotNull();
            // FileSystemSkill should have loaded the resource
            assertThat(skill.getFileSystemSkill()).isNotNull();
            assertThat(skill.getFileSystemSkill().resources()).isNotEmpty();
            assertThat(skill.getFileSystemSkill().resources().get(0).content()).contains("Guide content");
        }

        @Test
        @DisplayName("should_parse_custom_tools_from_frontmatter")
        void should_parse_custom_tools_from_frontmatter() throws IOException {
            Path skillDir = tempDir.resolve("scanner");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: scanner
                    description: Network scanner
                    tools:
                      - name: ping_host
                        description: Ping a host
                        command: ping
                        args: "-c 4 {target}"
                        timeout: 30
                        parameters:
                          - name: target
                            description: Target IP
                            required: true
                      - name: echo_test
                        description: Echo test
                        command: echo
                        args: "{message}"
                        parameters:
                          - name: message
                            description: Message to echo
                            required: true
                    ---
                    
                    Network scanning instructions.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());

            Skill skill = manager.getSkill("scanner");
            assertThat(skill).isNotNull();
            assertThat(skill.getTools()).hasSize(2);
            assertThat(skill.getTools().get(0).getName()).isEqualTo("ping_host");
            assertThat(skill.getTools().get(0).getTimeout()).isEqualTo(30);
            assertThat(skill.getTools().get(0).getParameters()).hasSize(1);
            assertThat(skill.getTools().get(1).getName()).isEqualTo("echo_test");
        }

        @Test
        @DisplayName("should_skip_nonexistent_directory")
        void should_skip_nonexistent_directory() {
            manager.setSkillsDirectoryPath(tempDir.resolve("nonexistent").toString());
            assertThat(manager.getAllSkills()).isEmpty();
        }

        @Test
        @DisplayName("should_skip_empty_directory_path")
        void should_skip_empty_directory_path() {
            manager.setSkillsDirectoryPath("");
            assertThat(manager.getAllSkills()).isEmpty();
        }
    }

    // ==================== Enable / Disable ====================

    @Nested
    @DisplayName("启用/禁用")
    class EnableDisable {

        @BeforeEach
        void createSkill() throws IOException {
            Path skillDir = tempDir.resolve("test");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: test
                    description: Test
                    ---
                    Content.
                    """, StandardCharsets.UTF_8);
            manager.setSkillsDirectoryPath(tempDir.toString());
        }

        @Test
        @DisplayName("should_enable_and_disable_skill")
        void should_enable_and_disable_skill() {
            assertThat(manager.hasEnabledSkills()).isFalse();

            manager.enableSkill("test");
            assertThat(manager.hasEnabledSkills()).isTrue();
            assertThat(manager.getEnabledSkillCount()).isEqualTo(1);

            manager.disableSkill("test");
            assertThat(manager.hasEnabledSkills()).isFalse();
        }

        @Test
        @DisplayName("should_toggle_skill")
        void should_toggle_skill() {
            manager.toggleSkill("test");
            assertThat(manager.getSkill("test").isEnabled()).isTrue();
            manager.toggleSkill("test");
            assertThat(manager.getSkill("test").isEnabled()).isFalse();
        }

        @Test
        @DisplayName("should_preserve_enabled_state_on_reload")
        void should_preserve_enabled_state_on_reload() {
            manager.enableSkill("test");
            assertThat(manager.hasEnabledSkills()).isTrue();
            manager.loadSkills();
            assertThat(manager.getSkill("test").isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should_restore_enabled_names")
        void should_restore_enabled_names() {
            manager.setEnabledSkillNames(java.util.List.of("test"));
            assertThat(manager.getSkill("test").isEnabled()).isTrue();
        }
    }

    // ==================== Official Skills Integration ====================

    @Nested
    @DisplayName("官方 Skills (Tool Mode)")
    class OfficialSkillsIntegration {

        @Test
        @DisplayName("should_provide_tool_provider_when_skills_enabled")
        void should_provide_tool_provider_when_skills_enabled() throws IOException {
            Path skillDir = tempDir.resolve("activated");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: activated
                    description: Activated skill
                    ---
                    Activated content.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());
            assertThat(manager.getSkillsToolProvider()).isNull();

            manager.enableSkill("activated");
            assertThat(manager.getSkillsToolProvider()).isNotNull();
        }

        @Test
        @DisplayName("should_return_null_tool_provider_when_no_skills_enabled")
        void should_return_null_tool_provider_when_no_skills_enabled() {
            assertThat(manager.getSkillsToolProvider()).isNull();
        }

        @Test
        @DisplayName("should_generate_format_available_skills_via_official_api")
        void should_generate_format_available_skills_via_official_api() throws IOException {
            Path skillDir = tempDir.resolve("xss-tester");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: xss-tester
                    description: XSS vulnerability testing
                    ---
                    Test XSS vulnerabilities.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());
            manager.enableSkill("xss-tester");

            String catalogue = manager.formatAvailableSkills();
            assertThat(catalogue).isNotEmpty();
            assertThat(catalogue).contains("xss-tester");
            assertThat(catalogue).contains("XSS vulnerability testing");
        }

        @Test
        @DisplayName("should_rebuild_official_skills_on_enable_disable")
        void should_rebuild_official_skills_on_enable_disable() throws IOException {
            Path skillDir = tempDir.resolve("dynamic");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: dynamic
                    description: Dynamic test
                    ---
                    Dynamic.
                    """, StandardCharsets.UTF_8);
            manager.setSkillsDirectoryPath(tempDir.toString());

            assertThat(manager.formatAvailableSkills()).isEmpty();

            manager.enableSkill("dynamic");
            assertThat(manager.formatAvailableSkills()).contains("dynamic");

            manager.disableSkill("dynamic");
            assertThat(manager.formatAvailableSkills()).isEmpty();
        }
    }

    // ==================== Tool Accessors ====================

    @Nested
    @DisplayName("工具访问")
    class ToolAccessors {

        @Test
        @DisplayName("should_return_all_enabled_tools")
        void should_return_all_enabled_tools() throws IOException {
            Path skillDir = tempDir.resolve("multi");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: multi
                    description: Multi-tool skill
                    tools:
                      - name: tool_a
                        description: Tool A
                        command: echo
                        args: "a"
                      - name: tool_b
                        description: Tool B
                        command: echo
                        args: "b"
                    ---
                    Use these tools.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());
            manager.enableSkill("multi");

            assertThat(manager.getAllEnabledTools()).hasSize(2);
            assertThat(manager.getEnabledToolCount()).isEqualTo(2);
            assertThat(manager.hasEnabledTools()).isTrue();
        }

        @Test
        @DisplayName("should_find_tool_by_short_name_or_full_name")
        void should_find_tool_by_short_name_or_full_name() throws IOException {
            Path skillDir = tempDir.resolve("finder");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: finder
                    description: Finder skill
                    tools:
                      - name: grep_tool
                        description: Grep stuff
                        command: grep
                    ---
                    Find things.
                    """, StandardCharsets.UTF_8);

            manager.setSkillsDirectoryPath(tempDir.toString());

            assertThat(manager.getToolByName("grep_tool")).isNotNull();
            assertThat(manager.getToolByName("skill_finder_grep_tool")).isNotNull();
            assertThat(manager.getToolByName("nonexistent")).isNull();
        }
    }
}
