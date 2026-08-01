package com.ai.analyzer.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatMessage - framework-neutral message type")
class ChatMessageTest {

    @Nested
    @DisplayName("Role enum")
    class RoleEnum {

        @Test
        @DisplayName("should_Have_four_role_values")
        void should_Have_four_role_values() {
            assertThat(ChatMessage.Role.values()).containsExactly(
                    ChatMessage.Role.SYSTEM,
                    ChatMessage.Role.USER,
                    ChatMessage.Role.ASSISTANT,
                    ChatMessage.Role.TOOL_RESULT
            );
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("should_create_system_message_with_correct_role")
        void should_create_system_message_with_correct_role() {
            ChatMessage msg = ChatMessage.system("You are a pentester");
            assertThat(msg.role()).isEqualTo(ChatMessage.Role.SYSTEM);
            assertThat(msg.content()).isEqualTo("You are a pentester");
        }

        @Test
        @DisplayName("should_create_user_message_with_correct_role")
        void should_create_user_message_with_correct_role() {
            ChatMessage msg = ChatMessage.user("Analyze this request");
            assertThat(msg.role()).isEqualTo(ChatMessage.Role.USER);
            assertThat(msg.content()).isEqualTo("Analyze this request");
        }

        @Test
        @DisplayName("should_create_assistant_message_with_correct_role")
        void should_create_assistant_message_with_correct_role() {
            ChatMessage msg = ChatMessage.assistant("Found SQL injection");
            assertThat(msg.role()).isEqualTo(ChatMessage.Role.ASSISTANT);
            assertThat(msg.content()).isEqualTo("Found SQL injection");
        }

        @Test
        @DisplayName("should_create_tool_result_message_with_correct_role")
        void should_create_tool_result_message_with_correct_role() {
            ChatMessage msg = ChatMessage.toolResult("200 OK body");
            assertThat(msg.role()).isEqualTo(ChatMessage.Role.TOOL_RESULT);
            assertThat(msg.content()).isEqualTo("200 OK body");
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEquality {

        @Test
        @DisplayName("should_be_equal_when_role_and_content_match")
        void should_be_equal_when_role_and_content_match() {
            ChatMessage a = new ChatMessage(ChatMessage.Role.USER, "hello");
            ChatMessage b = new ChatMessage(ChatMessage.Role.USER, "hello");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("should_not_be_equal_when_role_differs")
        void should_not_be_equal_when_role_differs() {
            ChatMessage a = new ChatMessage(ChatMessage.Role.USER, "hello");
            ChatMessage b = new ChatMessage(ChatMessage.Role.ASSISTANT, "hello");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("should_not_be_equal_when_content_differs")
        void should_not_be_equal_when_content_differs() {
            ChatMessage a = new ChatMessage(ChatMessage.Role.USER, "hello");
            ChatMessage b = new ChatMessage(ChatMessage.Role.USER, "world");
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should_include_role_and_content")
        void should_include_role_and_content() {
            ChatMessage msg = ChatMessage.user("test");
            String str = msg.toString();
            assertThat(str).contains("USER");
            assertThat(str).contains("test");
        }
    }
}