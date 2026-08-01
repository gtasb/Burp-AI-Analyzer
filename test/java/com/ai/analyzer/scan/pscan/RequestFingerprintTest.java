package com.ai.analyzer.scan.pscan;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RequestFingerprint - 请求指纹（L1 去重 / 会话归属）")
class RequestFingerprintTest {

    private HttpRequestResponse mockRr;
    private HttpRequest mockRequest;
    private HttpService mockService;

    @BeforeEach
    void setUp() {
        mockRr = mock(HttpRequestResponse.class);
        mockRequest = mock(HttpRequest.class);
        mockService = mock(HttpService.class);
        when(mockRr.request()).thenReturn(mockRequest);
        when(mockRequest.httpService()).thenReturn(mockService);
        when(mockService.host()).thenReturn("example.com");
        when(mockRequest.method()).thenReturn("GET");
    }

    private void url(String u) {
        when(mockRequest.url()).thenReturn(u);
    }

    @Nested
    @DisplayName("normalizeQuery")
    class NormalizeQuery {

        @Test
        @DisplayName("should_sort_parameters")
        void should_sort_parameters() {
            assertThat(RequestFingerprint.normalizeQuery("b=2&a=1")).isEqualTo("a=1&b=2");
        }

        @Test
        @DisplayName("should_ignore_timestamp_param_values")
        void should_ignore_timestamp_param_values() {
            assertThat(RequestFingerprint.normalizeQuery("timestamp=1000&a=1"))
                    .isEqualTo("a=1&timestamp=*");
        }

        @Test
        @DisplayName("should_keep_semantic_param_values")
        void should_keep_semantic_param_values() {
            // id 值参与去重 —— IDOR 探测信号不丢失
            assertThat(RequestFingerprint.normalizeQuery("id=2&id=1")).isEqualTo("id=1&id=2");
        }

        @Test
        @DisplayName("should_handle_null_and_empty")
        void should_handle_null_and_empty() {
            assertThat(RequestFingerprint.normalizeQuery(null)).isEmpty();
            assertThat(RequestFingerprint.normalizeQuery("")).isEmpty();
        }

        @Test
        @DisplayName("should_be_case_insensitive_for_ignored_params")
        void should_be_case_insensitive_for_ignored_params() {
            assertThat(RequestFingerprint.normalizeQuery("_T=abc")).isEqualTo("_t=*");
        }
    }

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        @DisplayName("should_include_normalized_query_in_key")
        void should_include_normalized_query_in_key() {
            url("https://example.com/api/users?id=1");
            assertThat(RequestFingerprint.of(mockRr))
                    .isEqualTo("GET|example.com|https://example.com/api/users|id=1");
        }

        @Test
        @DisplayName("should_treat_param_reorder_as_same_request")
        void should_treat_param_reorder_as_same_request() {
            url("https://example.com/api/users?b=2&a=1");
            String key1 = RequestFingerprint.of(mockRr);
            url("https://example.com/api/users?a=1&b=2");
            assertThat(RequestFingerprint.of(mockRr)).isEqualTo(key1);
        }

        @Test
        @DisplayName("should_keep_different_param_values_as_different_keys")
        void should_keep_different_param_values_as_different_keys() {
            url("https://example.com/api/users?id=1");
            String key1 = RequestFingerprint.of(mockRr);
            url("https://example.com/api/users?id=2");
            assertThat(RequestFingerprint.of(mockRr)).isNotEqualTo(key1);
        }

        @Test
        @DisplayName("should_ignore_timestamp_value_changes")
        void should_ignore_timestamp_value_changes() {
            url("https://example.com/api/list?timestamp=111&p=1");
            String key1 = RequestFingerprint.of(mockRr);
            url("https://example.com/api/list?timestamp=222&p=1");
            assertThat(RequestFingerprint.of(mockRr)).isEqualTo(key1);
        }

        @Test
        @DisplayName("should_return_empty_for_null")
        void should_return_empty_for_null() {
            assertThat(RequestFingerprint.of(null)).isEmpty();
        }

        @Test
        @DisplayName("should_include_body_digest_for_post")
        void should_include_body_digest_for_post() {
            url("https://example.com/api/update");
            when(mockRequest.method()).thenReturn("POST");
            when(mockRequest.bodyToString()).thenReturn("a=1&b=2");

            assertThat(RequestFingerprint.of(mockRr))
                    .isEqualTo("POST|example.com|https://example.com/api/update|d"
                            + Integer.toHexString("a=1&b=2".hashCode()));
        }

        @Test
        @DisplayName("should_normalize_token_params_in_form_body")
        void should_normalize_token_params_in_form_body() {
            url("https://example.com/api/update");
            when(mockRequest.method()).thenReturn("POST");
            when(mockRequest.bodyToString()).thenReturn("csrf_token=aaa&a=1");
            String key1 = RequestFingerprint.of(mockRr);

            when(mockRequest.bodyToString()).thenReturn("csrf_token=bbb&a=1");
            assertThat(RequestFingerprint.of(mockRr)).isEqualTo(key1);
        }
    }

    @Nested
    @DisplayName("sessionKey")
    class SessionKey {

        @Test
        @DisplayName("should_derive_session_from_cookie")
        void should_derive_session_from_cookie() {
            url("https://example.com/app");
            HttpHeader cookieHeader = mock(HttpHeader.class);
            when(cookieHeader.name()).thenReturn("Cookie");
            when(cookieHeader.value()).thenReturn("JSESSIONID=ABC123; theme=dark");
            when(mockRequest.headers()).thenReturn(java.util.List.of(cookieHeader));

            assertThat(RequestFingerprint.sessionKey(mockRr))
                    .isEqualTo("example.com|jsessionid=ABC123");
        }

        @Test
        @DisplayName("should_derive_session_from_authorization_bearer")
        void should_derive_session_from_authorization_bearer() {
            url("https://example.com/app");
            HttpHeader authHeader = mock(HttpHeader.class);
            when(authHeader.name()).thenReturn("Authorization");
            when(authHeader.value()).thenReturn("Bearer TOKEN_XYZ");
            when(mockRequest.headers()).thenReturn(java.util.List.of(authHeader));

            assertThat(RequestFingerprint.sessionKey(mockRr))
                    .isEqualTo("example.com|TOKEN_XYZ");
        }

        @Test
        @DisplayName("should_fallback_to_anon_without_session")
        void should_fallback_to_anon_without_session() {
            url("https://example.com/app");
            assertThat(RequestFingerprint.sessionKey(mockRr)).isEqualTo("example.com|anon");
        }
    }
}
