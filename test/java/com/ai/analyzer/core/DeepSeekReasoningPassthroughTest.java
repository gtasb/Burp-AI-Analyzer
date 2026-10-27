package com.ai.analyzer.core;

import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 思考模式协议回归：带工具调用的 assistant 消息在下一轮请求中必须回传
 * {@code reasoning_content}，否则 DeepSeek 返回 400。
 *
 * <p>用本地 HTTP 服务捕获实际请求体，不依赖外网与真实 Key。
 */
@DisplayName("DeepSeek reasoning_content passthrough")
class DeepSeekReasoningPassthroughTest {

    @Test
    @DisplayName("assistant thinking must be echoed back after a tool call")
    void reasoningContentIsEchoedAfterToolCall() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
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

            Msg user = Msg.builder().name("user").role(MsgRole.USER).textContent("hi").build();
            Msg assistant = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                    .content(ThinkingBlock.builder().thinking("secret-reasoning").build(),
                            ToolUseBlock.builder().id("call_1").name("noop").input(Map.of()).build())
                    .build();
            Msg toolResult = Msg.builder().name("tool").role(MsgRole.TOOL)
                    .content(ToolResultBlock.builder().id("call_1").name("noop")
                            .output(TextBlock.builder().text("result").build()).build())
                    .build();

            Throwable[] streamError = {null};
            try {
                model.stream(List.of(user, assistant, toolResult), List.of(), null).blockLast();
            } catch (Exception e) {
                // 本地假响应只用于捕获请求体，解析失败不影响断言
                streamError[0] = e;
            }

            String body = captured.get();
            assertThat(body).as("stream error: %s", streamError[0]).isNotNull();
            assertThat(body).contains("reasoning_content").contains("secret-reasoning");
        } finally {
            server.stop(0);
        }
    }
}
