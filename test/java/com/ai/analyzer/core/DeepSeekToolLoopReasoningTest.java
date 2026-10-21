package com.ai.analyzer.core;

import com.ai.analyzer.agent.runtime.ReActAgentRuntime;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整 ReAct 工具循环下 DeepSeek reasoning_content 的回传验证：
 * 第一轮返回 reasoning + tool_call，工具执行后第二轮请求必须仍带 reasoning_content。
 */
@DisplayName("DeepSeek reasoning passthrough in ReAct tool loop")
class DeepSeekToolLoopReasoningTest {

    public static class EchoTool {
        @Tool(name = "echo", description = "echo back")
        public String echo(@ToolParam(name = "x", description = "any", required = false) String x) {
            return "ok";
        }
    }

    @Test
    @DisplayName("second model request keeps reasoning_content after tool execution")
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
            ReActAgentRuntime runtime = ReActAgentRuntime.builder()
                    .model(model)
                    .toolkit(toolkit)
                    .systemPrompt("You are a test agent.")
                    .build();
            try {
                runtime.chat("hi", "deepseek-test-session", new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                });
            } catch (Exception ignored) {
                // 本地假响应仅用于捕获请求体
            }

            String body = secondBody.get();
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
            assertThat(body).isNotNull();
            assertThat(body).contains("reasoning_content").contains("secret-reasoning");
        } finally {
            server.stop(0);
        }
    }
}
