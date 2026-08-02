package com.ai.analyzer.agent.mcpclient;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentScopeMcpManager - MCP client registration")
class AgentScopeMcpManagerTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Nested
    @DisplayName("registerBurpMcp")
    class RegisterBurpMcp {

        @Test
        @DisplayName("should_return_empty_list_when_url_is_null")
        void should_return_empty_list_when_url_is_null() {
            var clients = AgentScopeMcpManager.registerBurpMcp(toolkit, null, null);
            assertThat(clients).isEmpty();
        }

        @Test
        @DisplayName("should_return_empty_list_when_url_is_empty")
        void should_return_empty_list_when_url_is_empty() {
            var clients = AgentScopeMcpManager.registerBurpMcp(toolkit, "   ", null);
            assertThat(clients).isEmpty();
        }

        @Test
        @DisplayName("should_return_empty_list_when_url_is_blank")
        void should_return_empty_list_when_url_is_blank() {
            var clients = AgentScopeMcpManager.registerBurpMcp(toolkit, "", null);
            assertThat(clients).isEmpty();
        }
    }

    @Nested
    @DisplayName("registerCustomMcp")
    class RegisterCustomMcp {

        @Test
        @DisplayName("should_return_null_when_server_name_is_null")
        void should_return_null_when_server_name_is_null() {
            var client = AgentScopeMcpManager.registerCustomMcp(
                    toolkit, null, "sse", "http://localhost:8080/sse", null, null);
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("should_return_null_when_url_or_command_is_null")
        void should_return_null_when_url_or_command_is_null() {
            var client = AgentScopeMcpManager.registerCustomMcp(
                    toolkit, "test-server", "sse", null, null, null);
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("should_return_null_when_server_name_is_blank")
        void should_return_null_when_server_name_is_blank() {
            var client = AgentScopeMcpManager.registerCustomMcp(
                    toolkit, "   ", "sse", "http://localhost:8080/sse", null, null);
            assertThat(client).isNull();
        }
    }

    @Nested
    @DisplayName("registerRagMcp")
    class RegisterRagMcp {

        @Test
        @DisplayName("should_return_null_when_url_is_null")
        void should_return_null_when_url_is_null() {
            var client = AgentScopeMcpManager.registerRagMcp(toolkit, null);
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("should_return_null_when_url_is_empty")
        void should_return_null_when_url_is_empty() {
            var client = AgentScopeMcpManager.registerRagMcp(toolkit, "");
            assertThat(client).isNull();
        }
    }

    @Nested
    @DisplayName("normalizeBearer - Authorization 规范化")
    class NormalizeBearer {

        @Test
        @DisplayName("已含 Bearer 前缀时不重复添加")
        void keeps_existing_bearer_prefix() {
            assertThat(AgentScopeMcpManager.normalizeBearer("Bearer abc123"))
                    .isEqualTo("Bearer abc123");
        }

        @Test
        @DisplayName("小写 bearer 前缀也视为已有前缀")
        void keeps_lowercase_bearer_prefix() {
            assertThat(AgentScopeMcpManager.normalizeBearer("bearer abc123"))
                    .isEqualTo("bearer abc123");
        }

        @Test
        @DisplayName("无前缀时补 Bearer")
        void adds_bearer_when_missing() {
            assertThat(AgentScopeMcpManager.normalizeBearer("abc123"))
                    .isEqualTo("Bearer abc123");
        }

        @Test
        @DisplayName("null 或空白返回 null")
        void null_or_blank_returns_null() {
            assertThat(AgentScopeMcpManager.normalizeBearer(null)).isNull();
            assertThat(AgentScopeMcpManager.normalizeBearer("   ")).isNull();
        }
    }

    @Nested
    @DisplayName("resolveTransportModes - 传输模式选择")
    class ResolveTransportModes {

        @Test
        @DisplayName("stdio 传输类型只走 stdio")
        void stdio_type_only_stdio() {
            assertThat(AgentScopeMcpManager.resolveTransportModes("python server.py", "stdio"))
                    .containsExactly("stdio");
        }

        @Test
        @DisplayName("/sse 结尾只走 SSE")
        void sse_path_only_sse() {
            assertThat(AgentScopeMcpManager.resolveTransportModes("http://host:8080/sse", null))
                    .containsExactly("sse");
        }

        @Test
        @DisplayName("/mcp 或含 streamable 只走 Streamable HTTP")
        void mcp_or_streamable_only_streamable() {
            assertThat(AgentScopeMcpManager.resolveTransportModes("http://host:8080/mcp", null))
                    .containsExactly("streamable");
            assertThat(AgentScopeMcpManager.resolveTransportModes("http://host:8080/streamable", null))
                    .containsExactly("streamable");
        }

        @Test
        @DisplayName("根路径 SSE 优先且回退 Streamable HTTP（BurpMCP-Ultra 场景）")
        void root_path_sse_then_streamable_fallback() {
            assertThat(AgentScopeMcpManager.resolveTransportModes("http://127.0.0.1:9876/", null))
                    .containsExactly("sse", "streamable");
            assertThat(AgentScopeMcpManager.resolveTransportModes("http://127.0.0.1:9876", null))
                    .containsExactly("sse", "streamable");
        }
    }
}