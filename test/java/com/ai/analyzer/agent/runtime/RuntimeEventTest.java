package com.ai.analyzer.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RuntimeEvent - framework-neutral event type")
class RuntimeEventTest {

    @Nested
    @DisplayName("Convenience factories")
    class ConvenienceFactories {

        @Test
        @DisplayName("should_create_text_delta_event_with_correct_type")
        void should_create_text_delta_event_with_correct_type() {
            RuntimeEvent event = RuntimeEvent.textDelta("hello");
            assertThat(event.type()).isEqualTo(RuntimeEventType.TEXT_DELTA);
            assertThat(event.text()).isEqualTo("hello");
        }

        @Test
        @DisplayName("should_create_thinking_event_with_correct_type")
        void should_create_thinking_event_with_correct_type() {
            RuntimeEvent event = RuntimeEvent.thinking("analyzing...");
            assertThat(event.type()).isEqualTo(RuntimeEventType.THINKING);
            assertThat(event.text()).isEqualTo("analyzing...");
        }

        @Test
        @DisplayName("should_create_tool_start_event_with_name_and_args")
        void should_create_tool_start_event_with_name_and_args() {
            RuntimeEvent event = RuntimeEvent.toolStart("send_http_request", "{\"url\":\"http://test\"}");
            assertThat(event.type()).isEqualTo(RuntimeEventType.TOOL_START);
            assertThat(event.toolName()).isEqualTo("send_http_request");
            assertThat(event.toolArgs()).isEqualTo("{\"url\":\"http://test\"}");
        }

        @Test
        @DisplayName("should_create_tool_start_event_with_null_args")
        void should_create_tool_start_event_with_null_args() {
            RuntimeEvent event = RuntimeEvent.toolStart("spawn_subagent", null);
            assertThat(event.type()).isEqualTo(RuntimeEventType.TOOL_START);
            assertThat(event.toolName()).isEqualTo("spawn_subagent");
            assertThat(event.toolArgs()).isNull();
        }

        @Test
        @DisplayName("should_create_tool_end_event_with_success_status")
        void should_create_tool_end_event_with_success_status() {
            RuntimeEvent event = RuntimeEvent.toolEnd("send_http_request", "200 OK", false, 150);
            assertThat(event.type()).isEqualTo(RuntimeEventType.TOOL_END);
            assertThat(event.toolName()).isEqualTo("send_http_request");
            assertThat(event.toolResult()).isEqualTo("200 OK");
            assertThat(event.toolFailed()).isFalse();
            assertThat(event.durationMs()).isEqualTo(150);
        }

        @Test
        @DisplayName("should_create_tool_end_event_with_failure_status")
        void should_create_tool_end_event_with_failure_status() {
            RuntimeEvent event = RuntimeEvent.toolEnd("bad_tool", "connection refused", true, 5000);
            assertThat(event.type()).isEqualTo(RuntimeEventType.TOOL_END);
            assertThat(event.toolFailed()).isTrue();
            assertThat(event.durationMs()).isEqualTo(5000);
        }

        @Test
        @DisplayName("should_create_reply_end_event")
        void should_create_reply_end_event() {
            RuntimeEvent event = RuntimeEvent.replyEnd("analysis complete");
            assertThat(event.type()).isEqualTo(RuntimeEventType.REPLY_END);
            assertThat(event.text()).isEqualTo("analysis complete");
        }

        @Test
        @DisplayName("should_create_error_event_with_exception")
        void should_create_error_event_with_exception() {
            Exception ex = new RuntimeException("test error");
            RuntimeEvent event = RuntimeEvent.error(ex);
            assertThat(event.type()).isEqualTo(RuntimeEventType.ERROR);
            assertThat(event.error()).isSameAs(ex);
        }

        @Test
        @DisplayName("should_create_subagent_event_with_name_and_text")
        void should_create_subagent_event_with_name_and_text() {
            RuntimeEvent event = RuntimeEvent.subagent("recon", "scanning ports...");
            assertThat(event.type()).isEqualTo(RuntimeEventType.SUBAGENT);
            assertThat(event.subagentName()).isEqualTo("recon");
            assertThat(event.text()).isEqualTo("scanning ports...");
        }

        @Test
        @DisplayName("should_create_subagent_event_with_null_text")
        void should_create_subagent_event_with_null_text() {
            RuntimeEvent event = RuntimeEvent.subagent("vuln-analyzer", null);
            assertThat(event.type()).isEqualTo(RuntimeEventType.SUBAGENT);
            assertThat(event.subagentName()).isEqualTo("vuln-analyzer");
            assertThat(event.text()).isNull();
        }

        @Test
        @DisplayName("should_create_memory_event_with_action_and_text")
        void should_create_memory_event_with_action_and_text() {
            RuntimeEvent event = RuntimeEvent.memory("store", "remembered target: example.com");
            assertThat(event.type()).isEqualTo(RuntimeEventType.MEMORY);
            assertThat(event.memoryAction()).isEqualTo("store");
            assertThat(event.text()).isEqualTo("remembered target: example.com");
        }
    }

    @Nested
    @DisplayName("Builder pattern")
    class BuilderPattern {

        @Test
        @DisplayName("should_build_complete_tool_end_event_with_all_fields")
        void should_build_complete_tool_end_event_with_all_fields() {
            RuntimeEvent event = RuntimeEvent.builder(RuntimeEventType.TOOL_END)
                    .toolName("scan")
                    .toolResult("found 3 ports")
                    .toolFailed(false)
                    .durationMs(1234)
                    .build();

            assertThat(event.type()).isEqualTo(RuntimeEventType.TOOL_END);
            assertThat(event.toolName()).isEqualTo("scan");
            assertThat(event.toolResult()).isEqualTo("found 3 ports");
            assertThat(event.toolFailed()).isFalse();
            assertThat(event.durationMs()).isEqualTo(1234);
        }

        @Test
        @DisplayName("should_default_duration_ms_to_negative_one_when_not_set")
        void should_default_duration_ms_to_negative_one_when_not_set() {
            RuntimeEvent event = RuntimeEvent.builder(RuntimeEventType.TOOL_END)
                    .toolName("scan")
                    .build();

            assertThat(event.durationMs()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should_include_type_in_toString")
        void should_include_type_in_toString() {
            RuntimeEvent event = RuntimeEvent.textDelta("test");
            assertThat(event.toString()).contains("TEXT_DELTA");
        }

        @Test
        @DisplayName("should_include_tool_name_in_toString")
        void should_include_tool_name_in_toString() {
            RuntimeEvent event = RuntimeEvent.toolStart("my_tool", null);
            assertThat(event.toString()).contains("TOOL_START");
            assertThat(event.toString()).contains("my_tool");
        }

        @Test
        @DisplayName("should_include_error_message_in_toString")
        void should_include_error_message_in_toString() {
            RuntimeEvent event = RuntimeEvent.error(new RuntimeException("boom"));
            assertThat(event.toString()).contains("ERROR");
            assertThat(event.toString()).contains("boom");
        }

        @Test
        @DisplayName("should_truncate_long_text_in_toString")
        void should_truncate_long_text_in_toString() {
            String longText = "a".repeat(200);
            RuntimeEvent event = RuntimeEvent.textDelta(longText);
            String str = event.toString();
            assertThat(str).doesNotContain(longText);
            assertThat(str).contains("a".repeat(80));
        }
    }
}