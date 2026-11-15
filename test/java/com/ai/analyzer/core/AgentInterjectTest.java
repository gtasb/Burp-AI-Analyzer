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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行中插话（interject → HarnessAgent.interrupt(Msg)）语义回归。
 *
 * <p>用本地假模型服务器验证：插话不打断订阅（chat 仍正常完成）、
 * 插话消息进入后续请求上下文（下一轮模型能看到）。
 */
@DisplayName("Interject - 运行中插话语义")
class AgentInterjectTest {

    public static class SlowEchoTool {
        @Tool(name = "slow_echo", description = "sleep then echo")
        public String slowEcho(@ToolParam(name = "x", description = "any", required = false) String x) {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
            }
            return "echo:" + (x == null ? "" : x);
        }
    }

    @Test
    @DisplayName("interject during run keeps stream alive and reaches next turn")
    void interjectReachesNextTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicReference<String> lastBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = calls.incrementAndGet();
            if (n == 1) {
                bodies.add(body);
            }
            lastBody.set(body);
            String sse;
            if (n == 1) {
                // 第一轮：调用一个耗时 700ms 的工具
                sse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"slow_echo\",\"arguments\":\"{\\\"x\\\":\\\"hi\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
            } else {
                String content = "seen-" + (body.contains("NOTE-FROM-HUMAN") ? "yes" : "no");
                String quoted = new com.google.gson.Gson().toJson(content);
                sse = "data: {\"id\":\"c" + n + "\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":" + quoted
                        + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
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
            toolkit.registerTool(new SlowEchoTool());
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model)
                    .toolkit(toolkit)
                    .workspacePath(Files.createTempDirectory("interject-test"))
                    .systemPrompt("You are a test agent.")
                    .maxIters(8)
                    .build();
            String sessionId = "interject-" + System.nanoTime();

            // 后台线程：第一轮工具执行期间（阻塞在 slow_echo）插入人类消息
            Thread interjector = new Thread(() -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                }
                boolean okj = runtime.interject("NOTE-FROM-HUMAN: stop and answer directly");
                System.out.println("[interject-test] interject returned " + okj);
            });
            interjector.setDaemon(true);

            StringBuilder firstTurn = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean sawInterrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
            interjector.start();
            runtime.chat("start", sessionId, new RuntimeEventListener() {
                @Override
                public void onEvent(RuntimeEvent event) {
                    if (event.type() == com.ai.analyzer.agent.runtime.RuntimeEventType.TEXT_DELTA
                            && event.text() != null) {
                        firstTurn.append(event.text());
                    }
                    if (event.type() == com.ai.analyzer.agent.runtime.RuntimeEventType.INTERRUPTED) {
                        sawInterrupted.set(true);
                    }
                }
            });

            // 第二轮：同一 session，检查插话是否已经进入上下文
            StringBuilder secondTurn = new StringBuilder();
            runtime.chat("next", sessionId, new RuntimeEventListener() {
                @Override
                public void onEvent(RuntimeEvent event) {
                    if (event.type() == com.ai.analyzer.agent.runtime.RuntimeEventType.TEXT_DELTA
                            && event.text() != null) {
                        secondTurn.append(event.text());
                    }
                }
            });

            // 插话语义验证（AgentScope interrupt(userId, sessionId, msg) 实测，2026-11）：
            // 1) 流不因插话崩溃/挂起 —— 两轮都必须正常收尾；
            // 2) 插话让本轮以 INTERRUPTED 收尾（必须发 INTERRUPTED 事件，上层据此不触发整理 agent）；
            // 3) 收尾后 chat 循环自动把插话作为下一轮输入 —— 第二轮的模型请求必须包含插话文本。
            String secondBody = lastBody.get();
            boolean presentInSecondRequest = secondBody != null && secondBody.contains("NOTE-FROM-HUMAN");
            assertThat(sawInterrupted.get())
                    .as("插话收尾必须发 INTERRUPTED 事件（否则上层会把停止当完成去触发整理）")
                    .isTrue();
            assertThat(presentInSecondRequest)
                    .as("插话文本必须进入后续模型请求（AgentScope 不持久化 interrupt 消息，由运行时队列补投）")
                    .isTrue();
            // 核心断言：会话没有因插话损坏，第二轮仍能正常产出
            assertThat(secondTurn.toString())
                    .as("插话后第二轮必须正常完成")
                    .contains("seen-");
        } finally {
            server.stop(0);
        }
    }
}
