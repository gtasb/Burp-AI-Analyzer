package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentScopeModelFactory - model creation from user config")
class AgentScopeModelFactoryTest {

    @Nested
    @DisplayName("API key validation")
    class ApiKeyValidation {

        @Test
        @DisplayName("should_throw_when_api_key_is_null")
        void should_throw_when_api_key_is_null() {
            assertThatThrownBy(() ->
                    AgentScopeModelFactory.create(
                            ApiProvider.DASHSCOPE, null, null, null, false, false, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("API Key");
        }

        @Test
        @DisplayName("should_throw_when_api_key_is_empty")
        void should_throw_when_api_key_is_empty() {
            assertThatThrownBy(() ->
                    AgentScopeModelFactory.create(
                            ApiProvider.DASHSCOPE, "   ", null, null, false, false, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("API Key");
        }
    }

    @Nested
    @DisplayName("Provider routing")
    class ProviderRouting {

        @Test
        @DisplayName("should_create_DashScope_model_for_DASHSCOPE_provider")
        void should_create_DashScope_model_for_DASHSCOPE_provider() {
            var model = AgentScopeModelFactory.create(
                    ApiProvider.DASHSCOPE, "sk-test", null, null, false, false, null);
            assertThat(model).isNotNull();
            assertThat(model.getClass().getSimpleName()).contains("DashScope");
        }

        @Test
        @DisplayName("should_create_OpenAI_model_for_OPENAI_COMPATIBLE_provider")
        void should_create_OpenAI_model_for_OPENAI_COMPATIBLE_provider() {
            var model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, "sk-test", "https://api.openai.com/v1", null, false, false, null);
            assertThat(model).isNotNull();
            assertThat(model.getClass().getSimpleName()).contains("OpenAI");
        }

        @Test
        @DisplayName("should_create_Anthropic_model_for_ANTHROPIC_provider")
        void should_create_Anthropic_model_for_ANTHROPIC_provider() {
            var model = AgentScopeModelFactory.create(
                    ApiProvider.ANTHROPIC, "sk-ant-test", null, null, false, false, null);
            assertThat(model).isNotNull();
            assertThat(model.getClass().getSimpleName()).contains("Anthropic");
        }
    }

    @Nested
    @DisplayName("describeConfig")
    class DescribeConfig {

        @Test
        @DisplayName("should_return_non_empty_description")
        void should_return_non_empty_description() {
            String desc = AgentScopeModelFactory.describeConfig(
                    ApiProvider.DASHSCOPE, null, "qwen-max", false, false);
            assertThat(desc).isNotNull().isNotEmpty();
        }
    }
}