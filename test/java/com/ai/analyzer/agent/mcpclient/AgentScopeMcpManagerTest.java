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
    @DisplayName("registerChromeMcp")
    class RegisterChromeMcp {

        @Test
        @DisplayName("should_return_null_when_url_is_null")
        void should_return_null_when_url_is_null() {
            var client = AgentScopeMcpManager.registerChromeMcp(toolkit, null);
            assertThat(client).isNull();
        }

        @Test
        @DisplayName("should_return_null_when_url_is_empty")
        void should_return_null_when_url_is_empty() {
            var client = AgentScopeMcpManager.registerChromeMcp(toolkit, "");
            assertThat(client).isNull();
        }
    }
}