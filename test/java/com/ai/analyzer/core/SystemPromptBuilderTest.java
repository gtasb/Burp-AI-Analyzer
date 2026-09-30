package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SystemPromptBuilder (active) - prompt construction")
class SystemPromptBuilderTest {

    @Nested
    @DisplayName("Default prompt")
    class DefaultPrompt {

        @Test
        @DisplayName("should_return_non_empty_default_prompt")
        void should_return_non_empty_default_prompt() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("should_contain_role_definition")
        void should_contain_role_definition() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Role");
            assertThat(prompt).contains("penetration test");
        }

        @Test
        @DisplayName("should_contain_decision_framework")
        void should_contain_decision_framework() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Decision Framework");
        }

        @Test
        @DisplayName("should_contain_prohibited_behaviors")
        void should_contain_prohibited_behaviors() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Prohibited Behaviors");
        }

        @Test
        @DisplayName("should_contain_output_format_section")
        void should_contain_output_format_section() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Output Format");
            assertThat(prompt).contains("Markdown");
        }

        @Test
        @DisplayName("should_contain_subagent_usage_rules")
        void should_contain_subagent_usage_rules() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("agent_spawn");
            assertThat(prompt).contains("Sub-agent Usage Rules");
        }

        @Test
        @DisplayName("should_contain_large_response_rules")
        void should_contain_cache_rules() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Large Response Rules");
            assertThat(prompt).contains("read_file");
        }
    }

    @Nested
    @DisplayName("Builder")
    class Builder {

        @Test
        @DisplayName("should_return_non_empty_prompt_when_built_with_defaults")
        void should_return_non_empty_prompt_when_built_with_defaults() {
            String prompt = new SystemPromptBuilder().build();
            assertThat(prompt).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("should_use_custom_prompt_when_provided")
        void should_use_custom_prompt_when_provided() {
            String custom = "Custom pentest prompt";
            String prompt = new SystemPromptBuilder()
                    .customBasePrompt(custom)
                    .build();
            assertThat(prompt).startsWith(custom + "\n");
            // 防护节无条件追加（即使是自定义提示词）
            assertThat(prompt).contains("Untrusted Data Handling");
        }

        @Test
        @DisplayName("should_use_default_when_custom_is_empty")
        void should_use_default_when_custom_is_empty() {
            String prompt = new SystemPromptBuilder()
                    .customBasePrompt("")
                    .build();
            assertThat(prompt).contains("Role");
        }

        @Test
        @DisplayName("should_use_default_when_custom_is_blank")
        void should_use_default_when_custom_is_blank() {
            String prompt = new SystemPromptBuilder()
                    .customBasePrompt("   ")
                    .build();
            assertThat(prompt).contains("Role");
        }
    }

    @Nested
    @DisplayName("Path facts")
    class PathFacts {

        @Test
        @DisplayName("should_contain_path_facts_section_when_set")
        void should_contain_path_facts_section_when_set() {
            String prompt = new SystemPromptBuilder()
                    .pathFacts("D:\\ws", "D:\\ws\\alice", "alice")
                    .build();
            assertThat(prompt).contains("文件与执行路径");
            assertThat(prompt).contains("D:\\ws");
            assertThat(prompt).contains("D:\\ws\\alice");
            assertThat(prompt).contains("alice");
            assertThat(prompt).contains("working_directory");
        }

        @Test
        @DisplayName("should_omit_path_facts_section_when_unset")
        void should_omit_path_facts_section_when_unset() {
            String prompt = new SystemPromptBuilder().build();
            assertThat(prompt).doesNotContain("文件与执行路径");
        }

        @Test
        @DisplayName("should_omit_path_facts_section_when_file_root_blank")
        void should_omit_path_facts_section_when_file_root_blank() {
            String prompt = new SystemPromptBuilder()
                    .pathFacts("D:\\ws", "", "alice")
                    .build();
            assertThat(prompt).doesNotContain("文件与执行路径");
        }
    }
}