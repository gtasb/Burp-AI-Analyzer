package com.ai.analyzer.scan.pscan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式输出必须按结果 id 分流。
 *
 * <p>曾经的回归：{@code onStreamingChunk} 只有文本没有 id，UI 侧靠一个
 * 「当前流式结果」共享字段反查 id。消费者线程池有多个线程并发扫描，该字段会被
 * 互相覆盖，导致 A 请求的增量被丢弃或串到 B 请求界面上；UI 侧也只有一个共享
 * StringBuilder，切换行就清空并混内容。
 */
@DisplayName("PassiveScanManager - 流式输出按 id 分流")
class PassiveScanStreamingRoutingTest {

    @Test
    @DisplayName("streaming_callback_should_carry_result_id")
    void streamingCallbackCarriesResultId() throws Exception {
        Field field = PassiveScanManager.class.getDeclaredField("onStreamingChunk");
        assertThat(field.getType())
                .as("流式回调必须是 (resultId, chunk) 二元组，否则并发扫描无法分流")
                .isEqualTo(BiConsumer.class);
    }

    @Test
    @DisplayName("should_not_hold_shared_current_streaming_result")
    void noSharedCurrentStreamingResult() {
        // 该字段是多消费者线程争用的竞态来源，已随 id 分流一并移除
        assertThat(List.of(PassiveScanManager.class.getDeclaredFields()))
                .noneMatch(f -> f.getName().equals("currentStreamingScanResult"));
    }

    @Test
    @DisplayName("setter_should_accept_bi_consumer")
    void setterAcceptsBiConsumer() throws Exception {
        Method setter = PassiveScanManager.class.getMethod("setOnStreamingChunk", BiConsumer.class);
        assertThat(Modifier.isPublic(setter.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("should_not_expose_single_streaming_result_getter")
    void noSingleStreamingResultGetter() {
        // UI 已改为按 id 渲染，不再需要「当前流式结果」这个全局概念
        assertThat(List.of(PassiveScanManager.class.getMethods()))
                .noneMatch(m -> m.getName().equals("getCurrentStreamingScanResult"));
    }
}
