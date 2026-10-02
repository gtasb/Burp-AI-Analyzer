package com.ai.analyzer.core;

import com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime;
import com.ai.analyzer.scan.pscan.PassiveScanApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主动/被动 ReAct 单轮上限（maxIters）。
 *
 * <p>背景：AgentScope 的 {@code HarnessAgent} / {@code ReActAgent} 默认只有
 * 10 轮。用满后 AgentScope 会向模型注入「You have failed to generate response
 * within the maximum iterations. Now respond directly by summarizing the current
 * situation.」，模型于是停下做总结 —— 渗透测试里就表现为「半途停下问要不要继续」。
 *
 * <p>关于 0：实测 {@code ReActAgent} 在 build() 阶段就抛
 * {@code IllegalArgumentException: maxIters must be > 0: 0}，
 * <b>不是</b>无限循环。所以配置层必须钳到 &gt;= 1。
 */
@DisplayName("maxIters - 主动/被动单轮上限")
class MaxItersConfigTest {

    @Test
    @DisplayName("default_should_be_well_above_agentscope_builtin_10")
    void defaultIsAboveTen() {
        assertThat(new PluginSettings().getActiveMaxIters())
                .as("主动默认应为 60，明显高于 AgentScope 内置的 10")
                .isEqualTo(AgentScopeAgentRuntime.DEFAULT_MAX_ITERS)
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("should_round_trip_both_modes")
    void roundTrip() {
        PluginSettings s = new PluginSettings();
        s.setActiveMaxIters(123);
        s.setPassiveMaxIters(7);
        assertThat(s.getActiveMaxIters()).isEqualTo(123);
        assertThat(s.getPassiveMaxIters()).isEqualTo(7);
    }

    @Test
    @DisplayName("should_fall_back_when_legacy_settings_deserialize_to_zero")
    void legacyZeroFallsBack() {
        // 旧 .dat 没有这两个字段，反序列化后是 0；getter 必须兜底，
        // 否则会把 0 传给 ReActAgent 并直接抛 IllegalArgumentException。
        PluginSettings s = new PluginSettings();
        setField(s, "activeMaxIters", 0);
        setField(s, "passiveMaxIters", 0);

        assertThat(s.getActiveMaxIters()).isEqualTo(AgentScopeAgentRuntime.DEFAULT_MAX_ITERS);
        assertThat(s.getPassiveMaxIters()).isEqualTo(15);
    }

    @Test
    @DisplayName("active_client_should_clamp_non_positive_to_one")
    void activeClientClamps() {
        AgentApiClient client = new AgentApiClient();
        client.setActiveMaxIters(0);
        assertThat(client.getActiveMaxIters()).isEqualTo(1);

        client.setActiveMaxIters(-5);
        assertThat(client.getActiveMaxIters()).isEqualTo(1);

        client.setActiveMaxIters(42);
        assertThat(client.getActiveMaxIters()).isEqualTo(42);
    }

    @Test
    @DisplayName("passive_client_should_clamp_non_positive_to_one")
    void passiveClientClamps() {
        PassiveScanApiClient client = new PassiveScanApiClient();
        client.setPassiveMaxIters(0);
        assertThat(client.getPassiveMaxIters()).isEqualTo(1);

        client.setPassiveMaxIters(-3);
        assertThat(client.getPassiveMaxIters()).isEqualTo(1);

        client.setPassiveMaxIters(15);
        assertThat(client.getPassiveMaxIters()).isEqualTo(15);
    }

    @Test
    @DisplayName("runtime_builder_should_default_above_ten")
    void runtimeBuilderDefaultIsAboveTen() throws Exception {
        var m = AgentScopeAgentRuntime.Builder.class.getDeclaredMethod("maxIters", int.class);
        assertThat(m).isNotNull();

        var builder = AgentScopeAgentRuntime.builder();
        // 不调用 maxIters() 时，Builder 内部字段应已是我们的默认值而非 AgentScope 的 10
        var f = AgentScopeAgentRuntime.Builder.class.getDeclaredField("maxIters");
        f.setAccessible(true);
        assertThat((int) f.get(builder))
                .isEqualTo(AgentScopeAgentRuntime.DEFAULT_MAX_ITERS)
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("min_max_iters_must_be_one_because_zero_throws")
    void minIsOne() {
        // 依据实测：maxIters=0 -> IllegalArgumentException: maxIters must be > 0: 0
        assertThat(AgentScopeAgentRuntime.MIN_MAX_ITERS).isEqualTo(1);
    }

    private static void setField(Object target, String name, int value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new AssertionError("无法设置字段 " + name, e);
        }
    }
}