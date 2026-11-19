package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenCode 匿名通道（无 key 快速试用）")
class OpenCodeAnonProviderTest {

    @Test
    @DisplayName("anon_provider_allows_blank_apiKey_and_creates_OpenAI_model")
    void anon_provider_allows_blank_apiKey() {
        var model = AgentScopeModelFactory.create(ApiProvider.OPENCODE_ANON, "", null, "mimo-v2.6-flash-free", false, null);
        assertThat(model).isNotNull();
        assertThat(model.getClass().getSimpleName()).contains("OpenAI");
    }

    @Test
    @DisplayName("anon_provider_default_url_is_inference_v1")
    void anon_provider_default_url() {
        var model = AgentScopeModelFactory.create(ApiProvider.OPENCODE_ANON, null, "", "big-pickle", false, null);
        assertThat(model).isNotNull();
        // 默认端点是匿名网关（与 Go 的 /zen/go 不同）
        assertThat(AgentScopeModelFactory.OPENCODE_ANON_DEFAULT_URL).isEqualTo("https://opencode.ai/inference/v1");
    }

    @Test
    @DisplayName("anon_credential_is_literal_public")
    void anon_credential() {
        assertThat(AgentScopeModelFactory.OPENCODE_ANON_CREDENTIAL).isEqualTo("public");
    }

    @Test
    @DisplayName("other_providers_still_require_key")
    void other_providers_still_require_key() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                AgentScopeModelFactory.create(ApiProvider.OPENCODE_GO, "  ", null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AgentConfig_isValid_true_for_anon_even_without_key")
    void config_isValid_anon() {
        AgentConfig config = new AgentConfig();
        config.setApiProvider(ApiProvider.OPENCODE_ANON);
        config.setApiKey("");
        assertThat(config.isValid()).isTrue();
    }

    @Test
    @DisplayName("AgentConfig_isValid_false_for_go_without_key")
    void config_isValid_go() {
        AgentConfig config = new AgentConfig();
        config.setApiProvider(ApiProvider.OPENCODE_GO);
        config.setApiKey("");
        assertThat(config.isValid()).isFalse();
    }

    @Test
    @DisplayName("normalize_keeps_anon_and_migrates_inference_url_to_anon")
    void normalize_migrates() {
        assertThat(ApiProvider.normalize(ApiProvider.OPENCODE_ANON, null)).isEqualTo(ApiProvider.OPENCODE_ANON);
        assertThat(ApiProvider.normalize(ApiProvider.OPENAI_COMPATIBLE, "https://opencode.ai/inference/v1"))
                .isEqualTo(ApiProvider.OPENCODE_ANON);
        assertThat(ApiProvider.normalize(ApiProvider.OPENAI_COMPATIBLE, "https://opencode.ai/zen/go/v1"))
                .isEqualTo(ApiProvider.OPENCODE_GO);
    }

    @Test
    @DisplayName("displayName_is_user_facing_chinese")
    void displayName() {
        assertThat(ApiProvider.OPENCODE_ANON.getDisplayName()).isEqualTo("OpenCode 匿名(免费)");
        assertThat(ApiProvider.fromDisplayName("OpenCode 匿名(免费)")).isEqualTo(ApiProvider.OPENCODE_ANON);
    }

    @Test
    @DisplayName("ModelListFetcher_anon_endpoint_resolution")
    void model_list_endpoint_anon() {
        assertThat(ModelListFetcher.endpoint("OpenCode 匿名(免费)", "")).endsWith("/models");
    }
}
