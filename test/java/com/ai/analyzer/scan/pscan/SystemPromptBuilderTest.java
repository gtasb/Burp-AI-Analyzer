package com.ai.analyzer.scan.pscan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SystemPromptBuilder (passive scan) - prompt construction")
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
            assertThat(prompt).contains("passive scan");
            assertThat(prompt).contains("DAST");
        }

        @Test
        @DisplayName("should_contain_decision_framework")
        void should_contain_decision_framework() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Decision Framework");
        }

        @Test
        @DisplayName("should_contain_batch_processing_guidance")
        void should_contain_batch_processing_guidance() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Batch Processing");
        }

        @Test
        @DisplayName("should_contain_output_format_with_risk_levels")
        void should_contain_output_format_with_risk_levels() {
            String prompt = SystemPromptBuilder.getDefaultBasePrompt();
            assertThat(prompt).contains("Output Format");
            assertThat(prompt).contains("Critical");
            assertThat(prompt).contains("Risk Level");
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
            String custom = "Custom passive scan prompt";
            String prompt = new SystemPromptBuilder()
                    .customBasePrompt(custom)
                    .build();
            assertThat(prompt).startsWith(custom + "\n");
            // 防护节无条件追加（即使是自定义提示词）
            assertThat(prompt).contains("Untrusted Data Handling");
        }

        @Test
        @DisplayName("should_include_search_section_when_enabled")
        void should_include_search_section_when_enabled() {
            String prompt = new SystemPromptBuilder()
                    .enableSearch(true)
                    .build();
            assertThat(prompt).contains("Web Search");
        }

        @Test
        @DisplayName("should_not_include_search_section_when_disabled")
        void should_not_include_search_section_when_disabled() {
            String prompt = new SystemPromptBuilder()
                    .enableSearch(false)
                    .build();
            assertThat(prompt).doesNotContain("Web Search");
        }
    }
}