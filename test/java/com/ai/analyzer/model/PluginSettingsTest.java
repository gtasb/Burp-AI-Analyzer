package com.ai.analyzer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginSettings - 插件配置模型")
class PluginSettingsTest {

    @Nested
    @DisplayName("默认构造")
    class DefaultConstruction {

        @Test
        @DisplayName("should_have_sensible_defaults_when_created")
        void should_have_sensible_defaults_when_created() {
            PluginSettings settings = new PluginSettings();

            assertThat(settings.getApiUrl()).isEqualTo("https://dashscope.aliyuncs.com/api/v1");
            assertThat(settings.getApiKey()).isEmpty();
            assertThat(settings.getModel()).isEqualTo("qwen3-max");
            assertThat(settings.isEnableThinking()).isTrue();
            assertThat(settings.isEnableSearch()).isTrue();
            assertThat(settings.isEnableMcp()).isFalse();
            assertThat(settings.isEnableRag()).isFalse();
        }

        @Test
        @DisplayName("should_have_proper_default_mcp_url_when_created")
        void should_have_proper_default_mcp_url_when_created() {
            PluginSettings settings = new PluginSettings();
            assertThat(settings.getMcpUrl()).isEqualTo("http://127.0.0.1:9876/");
        }

        @Test
        @DisplayName("should_have_valid_empty_url_for_rag_mcp_when_created [BUG#4 FIXED]")
        void should_have_valid_empty_url_for_rag_mcp_when_created() {
            PluginSettings settings = new PluginSettings();
            assertThat(settings.getRagMcpUrl()).isEmpty();
        }

        @Test
        @DisplayName("should_have_valid_empty_url_for_chrome_mcp_when_created [BUG#4 FIXED]")
        void should_have_valid_empty_url_for_chrome_mcp_when_created() {
            PluginSettings settings = new PluginSettings();
            assertThat(settings.getChromeMcpUrl()).isEmpty();
        }

        @Test
        @DisplayName("should_have_DashScope_as_default_api_provider")
        void should_have_DashScope_as_default_api_provider() {
            PluginSettings settings = new PluginSettings();
            assertThat(settings.getApiProvider()).isEqualTo("DashScope");
        }
    }

    @Nested
    @DisplayName("参数化构造")
    class ParameterizedConstruction {

        @Test
        @DisplayName("should_set_basic_params_when_four_arg_constructor")
        void should_set_basic_params_when_four_arg_constructor() {
            PluginSettings settings = new PluginSettings(
                "https://api.openai.com/v1", "sk-test", "gpt-4", "Analyze this");

            assertThat(settings.getApiUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(settings.getApiKey()).isEqualTo("sk-test");
            assertThat(settings.getModel()).isEqualTo("gpt-4");
            assertThat(settings.getUserPrompt()).isEqualTo("Analyze this");
            assertThat(settings.isEnableThinking()).isTrue();
            assertThat(settings.isEnableSearch()).isTrue();
        }

        @Test
        @DisplayName("should_set_thinking_and_search_when_six_arg_constructor")
        void should_set_thinking_and_search_when_six_arg_constructor() {
            PluginSettings settings = new PluginSettings(
                "url", "key", "model", "prompt", false, false);

            assertThat(settings.isEnableThinking()).isFalse();
            assertThat(settings.isEnableSearch()).isFalse();
        }
    }

    @Nested
    @DisplayName("Getter/Setter 行为")
    class GetterSetter {

        @Test
        @DisplayName("should_return_DashScope_when_api_provider_is_null")
        void should_return_DashScope_when_api_provider_is_null() {
            PluginSettings settings = new PluginSettings();
            settings.setApiProvider(null);
            assertThat(settings.getApiProvider()).isEqualTo("DashScope");
        }

        @Test
        @DisplayName("should_return_empty_string_when_custom_parameters_is_null")
        void should_return_empty_string_when_custom_parameters_is_null() {
            PluginSettings settings = new PluginSettings();
            settings.setCustomParameters(null);
            assertThat(settings.getCustomParameters()).isEmpty();
        }

        @Test
        @DisplayName("should_return_empty_list_when_enabled_skills_is_null")
        void should_return_empty_list_when_enabled_skills_is_null() {
            PluginSettings settings = new PluginSettings();
            settings.setEnabledSkillNames(null);
            assertThat(settings.getEnabledSkillNames()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should_store_skill_names_when_set")
        void should_store_skill_names_when_set() {
            PluginSettings settings = new PluginSettings();
            List<String> skills = Arrays.asList("skill1", "skill2");
            settings.setEnabledSkillNames(skills);
            assertThat(settings.getEnabledSkillNames()).containsExactly("skill1", "skill2");
        }

        @Test
        @DisplayName("should_update_all_boolean_flags")
        void should_update_all_boolean_flags() {
            PluginSettings settings = new PluginSettings();

            settings.setEnableMcp(true);
            assertThat(settings.isEnableMcp()).isTrue();

            settings.setEnableRagMcp(true);
            assertThat(settings.isEnableRagMcp()).isTrue();

            settings.setEnableChromeMcp(true);
            assertThat(settings.isEnableChromeMcp()).isTrue();

            settings.setEnableFileSystemAccess(true);
            assertThat(settings.isEnableFileSystemAccess()).isTrue();

            settings.setEnablePreScanFilter(true);
            assertThat(settings.isEnablePreScanFilter()).isTrue();

            settings.setEnablePythonScript(true);
            assertThat(settings.isEnablePythonScript()).isTrue();

            settings.setEnableSkills(true);
            assertThat(settings.isEnableSkills()).isTrue();
        }

        @Test
        @DisplayName("should_update_all_string_paths")
        void should_update_all_string_paths() {
            PluginSettings settings = new PluginSettings();

            settings.setRagDocumentsPath("/docs");
            assertThat(settings.getRagDocumentsPath()).isEqualTo("/docs");

            settings.setRagMcpDocumentsPath("/rag-docs");
            assertThat(settings.getRagMcpDocumentsPath()).isEqualTo("/rag-docs");

            settings.setSkillsDirectoryPath("/skills");
            assertThat(settings.getSkillsDirectoryPath()).isEqualTo("/skills");
        }
    }

    @Nested
    @DisplayName("序列化兼容性")
    class Serialization {

        @Test
        @DisplayName("should_be_serializable")
        void should_be_serializable() {
            assertThat(java.io.Serializable.class).isAssignableFrom(PluginSettings.class);
        }
    }
}
