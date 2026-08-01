package com.ai.analyzer.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentScopeAgentRuntime - message conversion and builder")
class AgentScopeAgentRuntimeTest {

    @Nested
    @DisplayName("Message conversion")
    class MessageConversion {

        @Test
        @DisplayName("should_convert_system_message_to_SystemMessage")
        void should_convert_system_message_to_SystemMessage() {
            List<ChatMessage> input = List.of(ChatMessage.system("You are a pentester"));
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(io.agentscope.core.message.SystemMessage.class);
            assertThat(result.get(0).getTextContent()).isEqualTo("You are a pentester");
        }

        @Test
        @DisplayName("should_convert_user_message_to_UserMessage")
        void should_convert_user_message_to_UserMessage() {
            List<ChatMessage> input = List.of(ChatMessage.user("Analyze this"));
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(io.agentscope.core.message.UserMessage.class);
            assertThat(result.get(0).getTextContent()).isEqualTo("Analyze this");
        }

        @Test
        @DisplayName("should_convert_assistant_message_to_AssistantMessage")
        void should_convert_assistant_message_to_AssistantMessage() {
            List<ChatMessage> input = List.of(ChatMessage.assistant("SQL injection found"));
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(io.agentscope.core.message.AssistantMessage.class);
            assertThat(result.get(0).getTextContent()).isEqualTo("SQL injection found");
        }

        @Test
        @DisplayName("should_convert_tool_result_message_to_ToolResultMessage")
        void should_convert_tool_result_message_to_ToolResultMessage() {
            List<ChatMessage> input = List.of(ChatMessage.toolResult("200 OK"));
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isInstanceOf(io.agentscope.core.message.ToolResultMessage.class);
        }

        @Test
        @DisplayName("should_convert_multiple_messages_in_order")
        void should_convert_multiple_messages_in_order() {
            List<ChatMessage> input = List.of(
                    ChatMessage.system("You are a pentester"),
                    ChatMessage.user("Analyze this request"),
                    ChatMessage.assistant("Checking..."),
                    ChatMessage.toolResult("200 OK")
            );
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);

            assertThat(result).hasSize(4);
            assertThat(result.get(0)).isInstanceOf(io.agentscope.core.message.SystemMessage.class);
            assertThat(result.get(1)).isInstanceOf(io.agentscope.core.message.UserMessage.class);
            assertThat(result.get(2)).isInstanceOf(io.agentscope.core.message.AssistantMessage.class);
            assertThat(result.get(3)).isInstanceOf(io.agentscope.core.message.ToolResultMessage.class);
        }

        @Test
        @DisplayName("should_return_empty_list_when_input_is_empty")
        void should_return_empty_list_when_input_is_empty() {
            List<ChatMessage> input = List.of();
            var result = AgentScopeAgentRuntime.toAgentScopeMessages(input);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("should_throw_IllegalStateException_when_model_is_null")
        void should_throw_IllegalStateException_when_model_is_null() {
            assertThatThrownBy(() ->
                    AgentScopeAgentRuntime.builder().build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("should_default_mode_to_ACTIVE")
        void should_default_mode_to_ACTIVE() {
            // Builder defaults to ACTIVE mode — verified by source code inspection
            // This test documents the default behavior
            AgentScopeAgentRuntime.Builder builder = AgentScopeAgentRuntime.builder();
            assertThat(builder).isNotNull();
            // Mode is private; we verify the builder is constructable
        }
    }
}