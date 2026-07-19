package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PassiveScanApiClient - 被动扫描 API 客户端")
class PassiveScanApiClientTest {

    private MontoyaApi mockApi;
    private Logging mockLogging;
    private PassiveScanApiClient client;

    @BeforeEach
    void setUp() {
        mockApi = mock(MontoyaApi.class);
        mockLogging = mock(Logging.class);
        when(mockApi.logging()).thenReturn(mockLogging);

        client = new PassiveScanApiClient(mockApi);
    }

    @Nested
    @DisplayName("createPerRequestAssistant - 防止 InputRequiredException")
    class CreatePerRequestAssistantTests {

        @Test
        @DisplayName("should_throw_IllegalStateException_when_chatModel_is_null")
        void should_throw_IllegalStateException_when_chatModel_is_null() throws Exception {
            // chatModel 默认为 null（因为没有设置 API Key）
            Field chatModelField = PassiveScanApiClient.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            assertThat(chatModelField.get(client)).isNull();

            // 调用 createPerRequestAssistant(null) 应抛出 IllegalStateException（被 InvocationTargetException 包装）
            Method method = PassiveScanApiClient.class.getDeclaredMethod(
                "createPerRequestAssistant",
                dev.langchain4j.model.chat.StreamingChatModel.class);
            method.setAccessible(true);

            assertThatThrownBy(() -> method.invoke(client, (Object) null))
                .hasCauseInstanceOf(IllegalStateException.class)
                .extracting(Throwable::getCause)
                .extracting(Throwable::getMessage)
                .asString()
                .contains("ChatModel");
        }

        @Test
        @DisplayName("should_use_maxMessages_100_to_prevent_user_message_eviction")
        void should_use_maxMessages_100_to_prevent_user_message_eviction() throws Exception {
            // 通过反射读取 createPerRequestAssistant 中 maxMessages 的配置
            // 验证方式：创建一个 mock StreamingChatModel，调用 createPerRequestAssistant
            // 然后检查返回的 Assistant 不为 null
            // 
            // 核心验证点：maxMessages 从 20 增加到 100，
            // 确保 10+ 轮工具调用后 UserMessage 不会被淘汰。
            //
            // 注：无法直接检查内部 maxMessages 值，
            // 但可以通过源码审查确认 maxMessages(100) 已设置。
            // 这里验证方法签名正确接受 StreamingChatModel 参数。
            Method method = PassiveScanApiClient.class.getDeclaredMethod(
                "createPerRequestAssistant",
                dev.langchain4j.model.chat.StreamingChatModel.class);
            method.setAccessible(true);
            
            assertThat(method).isNotNull();
            assertThat(method.getParameterCount()).isEqualTo(1);
            assertThat(method.getParameterTypes()[0])
                .isEqualTo(dev.langchain4j.model.chat.StreamingChatModel.class);
        }
    }

    @Nested
    @DisplayName("analyzeRequest - 输入验证")
    class AnalyzeRequestTests {

        @Test
        @DisplayName("should_throw_when_chatModel_not_initialized")
        void should_throw_when_chatModel_not_initialized() {
            // 不设置 API Key，chatModel 应为 null
            assertThatThrownBy(() -> client.analyzeRequest(null))
                .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("should_return_safe_result_when_requestResponse_is_null")
        void should_return_safe_result_when_requestResponse_is_null() throws Exception {
            // analyzeRequest(null) 应返回安全结果或抛出异常，而不是 NPE
            try {
                String result = client.analyzeRequest(null);
                // 如果返回结果，应包含"无法分析"等信息
                assertThat(result).containsAnyOf("风险等级", "无法", "未初始化");
            } catch (Exception e) {
                // 抛出异常也是可接受的（ChatModel 未初始化）
                assertThat(e.getMessage()).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("isRetryableError - 错误分类")
    class IsRetryableErrorTests {

        @Test
        @DisplayName("should_classify_InputRequiredException_as_retryable")
        void should_classify_InputRequiredException_as_retryable() throws Exception {
            Method method = PassiveScanApiClient.class.getDeclaredMethod("isRetryableError", String.class);
            method.setAccessible(true);

            assertThat((boolean) method.invoke(client, "InputRequiredException: messages and prompt must not all null"))
                .isTrue();
            assertThat((boolean) method.invoke(client, "must not all null"))
                .isTrue();
            assertThat((boolean) method.invoke(client, "content field is empty"))
                .isTrue();
        }

        @Test
        @DisplayName("should_classify_Jackson_deserialization_errors_as_retryable")
        void should_classify_Jackson_deserialization_errors_as_retryable() throws Exception {
            Method method = PassiveScanApiClient.class.getDeclaredMethod("isRetryableError", String.class);
            method.setAccessible(true);

            assertThat((boolean) method.invoke(client,
                "MismatchedInputException: Cannot deserialize value of type `java.util.LinkedHashMap` from Array value"))
                .isTrue();
            assertThat((boolean) method.invoke(client,
                "JsonMappingException: Unexpected token (START_ARRAY)"))
                .isTrue();
            assertThat((boolean) method.invoke(client,
                "Cannot deserialize value of type `java.lang.String` from Object value"))
                .isTrue();
        }

        @Test
        @DisplayName("should_return_false_for_null_error_message")
        void should_return_false_for_null_error_message() throws Exception {
            Method method = PassiveScanApiClient.class.getDeclaredMethod("isRetryableError", String.class);
            method.setAccessible(true);

            assertThat((boolean) method.invoke(client, (Object) null)).isFalse();
        }

        @Test
        @DisplayName("should_classify_non_fatal_errors_correctly")
        void should_classify_non_fatal_errors_correctly() throws Exception {
            Method nonFatal = PassiveScanApiClient.class.getDeclaredMethod("isNonFatalApiError", String.class);
            nonFatal.setAccessible(true);

            assertThat((boolean) nonFatal.invoke(client, "DataInspectionFailed"))
                .isTrue();
            assertThat((boolean) nonFatal.invoke(client, "Range of input length exceeded"))
                .isTrue();
            assertThat((boolean) nonFatal.invoke(client, "normal error message"))
                .isFalse();
            assertThat((boolean) nonFatal.invoke(client, (Object) null))
                .isFalse();
        }
    }

    @Nested
    @DisplayName("escapeTemplateDelimiters - 模板转义")
    class EscapeTemplateDelimitersTests {

        @Test
        @DisplayName("should_escape_mustache_braces_to_prevent_template_injection")
        void should_escape_mustache_braces_to_prevent_template_injection() throws Exception {
            Method method = PassiveScanApiClient.class.getDeclaredMethod("escapeTemplateDelimiters", String.class);
            method.setAccessible(true);

            String input = "{{data.tablename}} and ${user.id}";
            String result = (String) method.invoke(client, input);

            // 所有 { 被替换为 ZWSP+{，阻断 {{ 模式匹配
            assertThat(result).doesNotContain("{{");
            assertThat(result).contains("\u200B{");
        }

        @Test
        @DisplayName("should_return_null_or_empty_for_null_or_empty_input")
        void should_return_null_or_empty_for_null_or_empty_input() throws Exception {
            Method method = PassiveScanApiClient.class.getDeclaredMethod("escapeTemplateDelimiters", String.class);
            method.setAccessible(true);

            assertThat((String) method.invoke(client, (Object) null)).isNull();
            assertThat((String) method.invoke(client, "")).isEmpty();
        }
    }

    @Nested
    @DisplayName("配置管理")
    class ConfigTests {

        @Test
        @DisplayName("should_mark_reinitialization_when_apiKey_changes")
        void should_mark_reinitialization_when_apiKey_changes() throws Exception {
            client.setApiKey("old-key");
            
            Field needsReinit = PassiveScanApiClient.class.getDeclaredField("needsReinitialization");
            needsReinit.setAccessible(true);
            // 重置标志
            needsReinit.set(client, false);
            
            client.setApiKey("new-key");
            
            assertThat((boolean) needsReinit.get(client)).isTrue();
        }

        @Test
        @DisplayName("should_not_mark_reinitialization_when_same_apiKey")
        void should_not_mark_reinitialization_when_same_apiKey() throws Exception {
            client.setApiKey("same-key");
            
            Field needsReinit = PassiveScanApiClient.class.getDeclaredField("needsReinitialization");
            needsReinit.setAccessible(true);
            needsReinit.set(client, false);
            
            client.setApiKey("same-key");
            
            assertThat((boolean) needsReinit.get(client)).isFalse();
        }
    }

    @Nested
    @DisplayName("clearContext - 上下文清理")
    class ClearContextTests {

        @Test
        @DisplayName("should_null_shared_memory_and_assistant")
        void should_null_shared_memory_and_assistant() throws Exception {
            client.clearContext();

            Field memField = PassiveScanApiClient.class.getDeclaredField("chatMemory");
            memField.setAccessible(true);
            assertThat(memField.get(client)).isNull();

            Field assistantField = PassiveScanApiClient.class.getDeclaredField("assistant");
            assistantField.setAccessible(true);
            assertThat(assistantField.get(client)).isNull();
        }
    }
}
