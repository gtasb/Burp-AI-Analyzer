package com.ai.analyzer.core;

import com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime;
import com.ai.analyzer.agent.runtime.RuntimeEvent;
import com.ai.analyzer.agent.runtime.RuntimeEventListener;
import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HarnessAgent（主动分析实际使用的运行时）在工具循环中是否保留 reasoning_content。
 *
 * <p>ReActAgentRuntime 已有同类用例；主动侧走 HarnessAgent + 会话状态持久化，
 * 长会话触发压缩/状态恢复时可能丢 ThinkingBlock，导致 DeepSeek 下一轮 400。
 */
@DisplayName("DeepSeek reasoning passthrough in HarnessAgent tool loop")
class DeepSeekHarnessLoopReasoningTest {

    public static class EchoTool {
        @Tool(name = "echo", description = "echo back")
        public String echo(@ToolParam(name = "x", description = "any", required = false) String x) {
            return "ok";
        }
    }

    @Test
    @DisplayName("reasoning survives long tool loops (compaction)")
    void reasoningSurvivesCompaction() throws Exception {
        final int toolRounds = 18;
        AtomicInteger calls = new AtomicInteger();
        java.util.List<String> bodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse;
            if (n <= toolRounds) {
                String callId = "call_" + n;
                sse = "data: {\"id\":\"c" + n + "\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"secret-reasoning-" + n + "\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"c" + n + "\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + callId + "\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n";
            } else {
                sse = "data: {\"id\":\"cf\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
            }
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, "sk-test", baseUrl, "deepseek-flash", false, "");
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTool());
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model)
                    .toolkit(toolkit)
                    .workspacePath(Files.createTempDirectory("harness-ds-compact"))
                    .systemPrompt("You are a test agent.")
                    .maxIters(toolRounds + 6)
                    .build();
            runtime.chat("hi", "harness-ds-compact-session", new RuntimeEventListener() {
                @Override
                public void onEvent(RuntimeEvent event) {
                }
            });
            Thread.sleep(200);

            // 只检查主 Agent 请求：记忆提取（MemoryFlush）是独立的 system+user 调用，
            // 不包含 assistant tool_calls，不需要 reasoning_content。
            for (int i = 1; i < bodies.size(); i++) {
                String body = bodies.get(i);
                if (!body.contains("\"tool_calls\"")) continue;
                assertThat(body)
                        .as("request #%d lost reasoning_content", i + 1)
                        .contains("reasoning_content");
            }
            // compaction 应至少触发一次（18 轮 × 2 条消息 > triggerMessages=30）
            assertThat(calls.get()).isGreaterThanOrEqualTo(toolRounds);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("older-turn assistant tool_call keeps reasoning in later turns")
    void olderTurnReasoningIsKept() throws Exception {
        AtomicInteger mainCalls = new AtomicInteger();
        java.util.List<String> mainBodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sse;
            if (body.contains("memory extraction assistant")) {
                // MemoryFlush 的独立调用：不算主会话轮次
                sse = "data: {\"id\":\"m\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"NO_REPLY\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            } else {
                int n = mainCalls.incrementAndGet();
                mainBodies.add(body);
                if (n == 1) {
                    sse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"secret-turn1\"},"
                            + "\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]},"
                            + "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
                } else {
                    sse = "data: {\"id\":\"c" + n + "\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                }
            }
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, "sk-test", baseUrl, "deepseek-flash", false, "");
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTool());
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model)
                    .toolkit(toolkit)
                    .workspacePath(Files.createTempDirectory("harness-ds-multiturn"))
                    .systemPrompt("You are a test agent.")
                    .maxIters(6)
                    .build();
            RuntimeEventListener noop = new RuntimeEventListener() {
                @Override
                public void onEvent(RuntimeEvent event) {
                }
            };
            runtime.chat("hi", "harness-ds-multiturn", noop);
            runtime.chat("continue", "harness-ds-multiturn", noop);
            Thread.sleep(200);

            // 第二轮请求里仍包含第一轮的 assistant tool_call，且必须有 reasoning_content
            boolean found = false;
            for (String body : mainBodies) {
                if (body.contains("tool_calls")) {
                    found = true;
                    assertThat(body)
                            .as("older-turn tool_call lost reasoning")
                            .contains("reasoning_content")
                            .contains("secret-turn1");
                }
            }
            assertThat(found).as("no request contained the historical tool_call").isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("second HarnessAgent model request keeps reasoning_content")
    void secondRequestKeepsReasoning() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (n == 2) secondBody.set(body);
            String sse;
            if (n == 1) {
                sse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"secret-reasoning\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n";
            } else {
                sse = "data: {\"id\":\"c2\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
            }
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, "sk-test", baseUrl, "deepseek-flash", false, "");
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTool());
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model)
                    .toolkit(toolkit)
                    .workspacePath(Files.createTempDirectory("harness-ds-test"))
                    .systemPrompt("You are a test agent.")
                    .build();
            Throwable[] chatError = {null};
            try {
                runtime.chat("hi", "harness-ds-session", new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                });
            } catch (Exception e) {
                chatError[0] = e;
            }

            String body = secondBody.get();
            assertThat(calls.get()).as("chat error: %s", chatError[0]).isGreaterThanOrEqualTo(2);
            assertThat(body).as("chat error: %s", chatError[0]).isNotNull();
            assertThat(body).contains("reasoning_content").contains("secret-reasoning");
        } finally {
            server.stop(0);
        }
    }
}
