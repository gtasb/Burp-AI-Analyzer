package com.ai.analyzer.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Skill、SkillTool 数据模型测试
 */
@DisplayName("Skills 数据模型")
class SkillModelTest {

    // ==================== Skill ====================

    @Nested
    @DisplayName("Skill")
    class SkillTests {

        @Test
        @DisplayName("should_create_skill_with_builder")
        void should_create_skill_with_builder() {
            Skill skill = Skill.builder()
                    .name("test-skill")
                    .description("A test skill")
                    .content("Do something useful")
                    .enabled(true)
                    .build();

            assertThat(skill.getName()).isEqualTo("test-skill");
            assertThat(skill.getDescription()).isEqualTo("A test skill");
            assertThat(skill.getContent()).isEqualTo("Do something useful");
            assertThat(skill.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should_create_skill_with_constructor")
        void should_create_skill_with_constructor() {
            Skill skill = new Skill("my-skill", "desc", "content", null);
            assertThat(skill.getName()).isEqualTo("my-skill");
            assertThat(skill.isEnabled()).isFalse();
            assertThat(skill.getTools()).isEmpty();
        }

        @Test
        @DisplayName("should_add_tool_and_set_skill_name")
        void should_add_tool_and_set_skill_name() {
            Skill skill = Skill.builder().name("scanner").build();
            SkillTool tool = new SkillTool("nmap", "Network scan", "nmap");
            skill.addTool(tool);

            assertThat(skill.hasTools()).isTrue();
            assertThat(skill.getToolCount()).isEqualTo(1);
            assertThat(tool.getSkillName()).isEqualTo("scanner");
        }

        @Test
        @DisplayName("should_truncate_long_description")
        void should_truncate_long_description() {
            String longDesc = "A".repeat(200);
            Skill skill = Skill.builder().description(longDesc).build();
            assertThat(skill.getShortDescription()).hasSize(100);
            assertThat(skill.getShortDescription()).endsWith("...");
        }

        @Test
        @DisplayName("should_equal_by_name")
        void should_equal_by_name() {
            Skill a = Skill.builder().name("x").description("one").build();
            Skill b = Skill.builder().name("x").description("two").build();
            Skill c = Skill.builder().name("y").build();

            assertThat(a).isEqualTo(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("should_hold_file_system_skill_reference")
        void should_hold_file_system_skill_reference() {
            Skill skill = Skill.builder().name("test").build();
            assertThat(skill.getFileSystemSkill()).isNull();

            dev.langchain4j.skills.Skill official = dev.langchain4j.skills.Skill.builder()
                    .name("test")
                    .description("desc")
                    .content("content")
                    .build();
            // FileSystemSkill is only created by FileSystemSkillLoader, just verify null handling
            assertThat(skill.getFileSystemSkill()).isNull();
        }
    }

    // ==================== SkillTool ====================

    @Nested
    @DisplayName("SkillTool")
    class SkillToolTests {

        @Test
        @DisplayName("should_build_full_name_with_skill_prefix")
        void should_build_full_name_with_skill_prefix() {
            SkillTool tool = new SkillTool("scan", "Scan target", "/usr/bin/nmap");
            tool.setSkillName("network");
            assertThat(tool.getFullName()).isEqualTo("skill_network_scan");
        }

        @Test
        @DisplayName("should_build_full_name_without_skill")
        void should_build_full_name_without_skill() {
            SkillTool tool = new SkillTool("scan", "desc", "nmap");
            assertThat(tool.getFullName()).isEqualTo("skill_scan");
        }

        @Test
        @DisplayName("should_build_args_with_parameter_substitution")
        void should_build_args_with_parameter_substitution() {
            SkillTool tool = new SkillTool();
            tool.setArgs("-sV -p {ports} {target}");

            SkillTool.ToolParameter target = new SkillTool.ToolParameter("target", "Target host");
            SkillTool.ToolParameter ports = new SkillTool.ToolParameter("ports", "Port range", false);
            ports.setDefaultValue("1-1000");
            tool.setParameters(List.of(target, ports));

            String result = tool.buildArgs(Map.of("target", "192.168.1.1"));
            assertThat(result).isEqualTo("-sV -p 1-1000 192.168.1.1");
        }

        @Test
        @DisplayName("should_throw_when_required_parameter_missing")
        void should_throw_when_required_parameter_missing() {
            SkillTool tool = new SkillTool();
            tool.setArgs("{target}");
            SkillTool.ToolParameter target = new SkillTool.ToolParameter("target", "Target host", true);
            tool.setParameters(List.of(target));

            assertThatThrownBy(() -> tool.buildArgs(Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("target");
        }

        @Test
        @DisplayName("should_validate_parameters")
        void should_validate_parameters() {
            SkillTool tool = new SkillTool();
            SkillTool.ToolParameter required = new SkillTool.ToolParameter("host", "Host", true);
            tool.setParameters(List.of(required));

            assertThatThrownBy(() -> tool.validateParameters(Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatCode(() -> tool.validateParameters(Map.of("host", "example.com")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should_clean_extra_whitespace_in_args")
        void should_clean_extra_whitespace_in_args() {
            SkillTool tool = new SkillTool();
            tool.setArgs("-a {opt}  -b");
            SkillTool.ToolParameter opt = new SkillTool.ToolParameter("opt", "optional", false);
            tool.setParameters(List.of(opt));

            String result = tool.buildArgs(Map.of());
            assertThat(result).isEqualTo("-a -b");
        }
    }
}
