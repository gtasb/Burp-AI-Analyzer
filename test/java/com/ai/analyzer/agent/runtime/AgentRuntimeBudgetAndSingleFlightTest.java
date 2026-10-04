package com.ai.analyzer.agent.runtime;

import com.ai.analyzer.core.AgentApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两项回归：
 *
 * <ol>
 *   <li><b>10 分钟硬超时</b>：两个 runtime 的 {@code chatTimeoutMs} 曾默认 10 分钟，
 *       直接喂给 {@code blockLast(...)}，于是每次分析都被卡在 10 分钟，
 *       真实渗透里读快照/跑脚本必然超限，流整条断在
 *       「Timeout on blocking read for 600000000000 NANOSECONDS」。
 *       600000000000 纳秒正好 = 10 分钟，可据此确认来源。</li>
 *   <li><b>侧栏与主动分析并发</b>：两者共用 AgentApiClient，而
 *       {@code setSystemNoticeConsumer} 是替换语义、modelBehaviorConsumer 是共享列表，
 *       并发时后启动者会摘掉前者的 consumer。</li>
 * </ol>
 */
@DisplayName("超时预算与单飞保护")
class AgentRuntimeBudgetAndSingleFlightTest {

    private static final long TEN_MINUTES_NANOS = 600_000_000_000L;

    @Test
    @DisplayName("报错里的 600000000000 纳秒确实等于 10 分钟")
    void errorMessageMatchesTenMinutes() {
        // 说明用户看到的报错就是 10 分钟预算，而不是 HTTP 客户端默认值
        assertThat(java.time.Duration.ofNanos(TEN_MINUTES_NANOS).toMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("active_runtime_should_default_to_unlimited_timeout")
    void activeDefaultUnlimited() throws Exception {
        var builder = AgentScopeAgentRuntime.builder();
        Field f = AgentScopeAgentRuntime.Builder.class.getDeclaredField("chatTimeoutMs");
        f.setAccessible(true);
        assertThat((long) f.get(builder))
                .as("默认必须不限时：真实渗透 10 分钟必然不够，且原值无人覆盖")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("passive_runtime_should_default_to_unlimited_timeout")
    void passiveDefaultUnlimited() throws Exception {
        var builder = ReActAgentRuntime.builder();
        Field f = ReActAgentRuntime.Builder.class.getDeclaredField("chatTimeoutMs");
        f.setAccessible(true);
        assertThat((long) f.get(builder)).isEqualTo(0L);
    }

    /** 最小可用 Model：只需要让 Builder.build() 通过校验，本测试不真正发请求 */
    private static final class StubModel implements io.agentscope.core.model.Model {
        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                java.util.List<io.agentscope.core.message.Msg> msgs,
                java.util.List<io.agentscope.core.model.ToolSchema> tools,
                io.agentscope.core.model.GenerateOptions options) {
            return reactor.core.publisher.Flux.empty();
        }
    }

    @Test
    @DisplayName("timeout_resolver_should_return_null_for_unlimited")
    void timeoutResolverReturnsNullWhenUnlimited() throws Exception {
        AgentScopeAgentRuntime rt =
                AgentScopeAgentRuntime.builder().model(new StubModel()).build();
        var m = AgentScopeAgentRuntime.class.getDeclaredMethod("timeout");
        m.setAccessible(true);
        assertThat(m.invoke(rt)).as("null 交给 blockLast() 表示不限时").isNull();

        AgentScopeAgentRuntime limited = AgentScopeAgentRuntime.builder()
                .model(new StubModel()).chatTimeoutMs(1_234L).build();
        assertThat(m.invoke(limited)).isEqualTo(java.time.Duration.ofMillis(1_234L));
    }

    @Test
    @DisplayName("passive_timeout_resolver_should_honour_explicit_value")
    void passiveTimeoutResolver() throws Exception {
        ReActAgentRuntime rt = ReActAgentRuntime.builder()
                .model(new StubModel()).chatTimeoutMs(2_500L).build();
        var m = ReActAgentRuntime.class.getDeclaredMethod("timeout");
        m.setAccessible(true);
        assertThat(m.invoke(rt)).isEqualTo(java.time.Duration.ofMillis(2_500L));

        ReActAgentRuntime unlimited =
                ReActAgentRuntime.builder().model(new StubModel()).build();
        assertThat(m.invoke(unlimited)).isNull();
    }

    @Test
    @DisplayName("client_should_reject_second_concurrent_stream")
    void rejectsConcurrentStream() throws Exception {
        AgentApiClient client = new AgentApiClient();
        assertThat(client.isAnalysisInFlight()).isFalse();

        // 模拟另一次分析（侧栏或主动分析）正占用通道
        Field f = AgentApiClient.class.getDeclaredField("analysisInFlight");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(client)).set(true);

        assertThatThrownBy(() -> client.analyzeRequestStream("", "hi", c -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AgentApiClient.CONCURRENT_ANALYSIS_MESSAGE);

        ((AtomicBoolean) f.get(client)).set(false);
    }

    @Test
    @DisplayName("client_should_release_channel_even_when_stream_fails")
    void releasesChannelOnFailure() throws Exception {
        AgentApiClient client = new AgentApiClient();
        // 没有可用模型/网络，调用必然抛错；通道必须被释放，否则永久卡死
        assertThatThrownBy(() -> client.analyzeRequestStream("", "hi", c -> { }))
                .isInstanceOf(Exception.class);
        assertThat(client.isAnalysisInFlight())
                .as("finally 必须复位 analysisInFlight")
                .isFalse();
    }
}