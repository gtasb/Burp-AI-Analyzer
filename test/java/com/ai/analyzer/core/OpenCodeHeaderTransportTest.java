package com.ai.analyzer.core;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenCode Go 网关头注入（x-opencode-session + User-Agent）。
 * 网关行为实测 2026-11：/v1/chat/completions 缺 x-opencode-session 返回 400
 * MissingSessionID；补头后 200。Cloudflare 拒绝无 UA 客户端。
 */
@DisplayName("OpenCodeHeaderTransport - OpenCode Go 网关头")
class OpenCodeHeaderTransportTest {

    /** 记录透传请求的假 transport。 */
    private static final class CapturingTransport implements HttpTransport {
        HttpRequest last;
        @Override public HttpResponse execute(HttpRequest request) { last = request; return null; }
        @Override public Flux<String> stream(HttpRequest request) { last = request; return Flux.empty(); }
        @Override public void close() { }
    }

    @Test
    @DisplayName("detects_opencode_upstream_host")
    void detectsUpstream() {
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://opencode.ai/zen/go/v1")).isTrue();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://api.opencode.ai/x")).isTrue();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://opencode.ai:443/v1")).isTrue();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://api.openai.com/v1")).isFalse();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://dashscope.aliyuncs.com/api/v1")).isFalse();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("https://evil-opencode.ai.example.com")).isFalse();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream(null)).isFalse();
        assertThat(OpenCodeHeaderTransport.isOpenCodeUpstream("")).isFalse();
    }

    @Test
    @DisplayName("injects_session_and_ua_when_missing")
    void injectsHeaders() {
        CapturingTransport capture = new CapturingTransport();
        HttpTransport wrapped = OpenCodeHeaderTransport.wrapIfOpenCode("https://opencode.ai/zen/go/v1", capture);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer k");
        HttpRequest req = HttpRequest.builder()
                .url("https://opencode.ai/zen/go/v1/chat/completions")
                .method("POST")
                .headers(headers)
                .body("{}")
                .build();
        wrapped.execute(req);

        assertThat(capture.last).isNotNull();
        Map<String, String> sent = capture.last.getHeaders();
        // session 头已注入且非空
        assertThat(sent.get("x-opencode-session")).isNotBlank();
        // 已有头保持不变
        assertThat(sent.get("Authorization")).isEqualTo("Bearer k");
        // UA 已注入
        assertThat(sent.get("User-Agent")).isNotBlank();
    }

    @Test
    @DisplayName("same_session_id_across_requests_stays_stable")
    void sessionStaysStable() {
        CapturingTransport capture = new CapturingTransport();
        HttpTransport wrapped = OpenCodeHeaderTransport.wrapIfOpenCode("https://opencode.ai/zen/go/v1", capture);

        wrapped.execute(HttpRequest.builder().url("https://opencode.ai/zen/go/v1/chat/completions")
                .method("POST").headers(new HashMap<>()).body("{}").build());
        String first = capture.last.getHeaders().get("x-opencode-session");
        wrapped.stream(HttpRequest.builder().url("https://opencode.ai/zen/go/v1/chat/completions")
                .method("POST").headers(new HashMap<>()).body("{}").build()).blockLast();
        String second = capture.last.getHeaders().get("x-opencode-session");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("client_provided_session_header_passes_through_untouched")
    void clientSessionWins() {
        CapturingTransport capture = new CapturingTransport();
        HttpTransport wrapped = OpenCodeHeaderTransport.wrapIfOpenCode("https://opencode.ai/zen/go/v1", capture);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-opencode-session", "my-own-session");
        wrapped.execute(HttpRequest.builder().url("https://opencode.ai/zen/go/v1/chat/completions")
                .method("POST").headers(headers).body("{}").build());

        assertThat(capture.last.getHeaders().get("x-opencode-session")).isEqualTo("my-own-session");
    }

    @Test
    @DisplayName("non_opencode_upstream_returns_delegate_unchanged")
    void nonOpenCodePassthrough() {
        CapturingTransport capture = new CapturingTransport();
        HttpTransport wrapped = OpenCodeHeaderTransport.wrapIfOpenCode("https://api.openai.com/v1", capture);
        assertThat(wrapped).isSameAs(capture);
    }

    @Test
    @DisplayName("provider_normalize_maps_old_openai_compat_opencode_url")
    void providerNormalize() {
        assertThat(AgentConfig.ApiProvider.normalize(
                AgentConfig.ApiProvider.OPENAI_COMPATIBLE, "https://opencode.ai/zen/go/v1"))
                .isEqualTo(AgentConfig.ApiProvider.OPENCODE_GO);
        assertThat(AgentConfig.ApiProvider.normalize(
                AgentConfig.ApiProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1"))
                .isEqualTo(AgentConfig.ApiProvider.OPENAI_COMPATIBLE);
        assertThat(AgentConfig.ApiProvider.normalize(
                AgentConfig.ApiProvider.OPENCODE_GO, "https://opencode.ai/zen/go/v1"))
                .isEqualTo(AgentConfig.ApiProvider.OPENCODE_GO);
        assertThat(AgentConfig.ApiProvider.normalize(
                AgentConfig.ApiProvider.DASHSCOPE, "https://dashscope.aliyuncs.com/api/v1"))
                .isEqualTo(AgentConfig.ApiProvider.DASHSCOPE);
    }
}
