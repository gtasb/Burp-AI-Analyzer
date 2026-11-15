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
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话上下文完整性审计（2.0.5 升级后回归）：
 * 1) 同 sessionId 多轮后，第 N 轮请求必须包含第 1 轮的用户输入、assistant 文本与工具结果
 *    （当前 session 的完整上下文，不是只有最近一轮）；
 * 2) 不同 sessionId 之间上下文隔离（主动/整理 agent 各自独立）；
 * 3) 每轮请求里的历史 assistant tool_calls 必须带 reasoning_content（DeepSeek 协议）。
 */
@DisplayName("Multi-turn session context audit")
class MultiTurnContextAuditTest {

    public static class MarkerTool {
        @Tool(name = "mark", description = "return a fixed marker")
        public String mark(@ToolParam(name = "v", description = "any", required = false) String v) {
            return "MARKED-42";
        }
    }

    @Test
    @DisplayName("turn N request contains turn 1 user text, assistant text and tool result")
    void fullContextCarriesAcrossTurns(@TempDir java.nio.file.Path tmp) throws Exception {
        List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            // 第 1 轮：工具调用；其余：纯文本
            String sse;
            if (body.contains("\"mark\"") && bodies.size() == 1) {
                sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"mark\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
            } else {
                sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ack\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            }
            byte[] b = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            try (var out = ex.getResponseBody()) { out.write(b); }
        });
        server.start();
        try {
            var model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, "sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "deepseek-flash", false, "");
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MarkerTool());
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model).toolkit(toolkit)
                    .workspacePath(tmp)
                    .stateWorkspacePath(tmp.resolve("state"))
                    .disableMemoryTools().disableMemoryHooks()
                    .systemPrompt("You are a test agent.")
                    .maxIters(8)
                    .build();
            String session = "audit-" + System.nanoTime();
            RuntimeEventListener noop = new RuntimeEventListener() {
                @Override public void onEvent(RuntimeEvent e) { }
            };
            runtime.chat("FIRST-Secret-User-Input", session, noop);
            runtime.chat("second turn", session, noop);
            runtime.chat("third turn", session, noop);
            runtime.chat("fourth turn", session, noop);

            String last = bodies.get(bodies.size() - 1);
            // 1) 第 1 轮用户输入仍在上下文
            assertThat(last).as("第 4 轮请求必须包含第 1 轮用户输入").contains("FIRST-Secret-User-Input");
            // 2) 工具轨迹（assistant tool_calls + 工具结果）仍在上下文
            assertThat(last).as("历史工具调用必须保留").contains("\"mark\"");
            assertThat(last).as("历史工具结果必须保留").contains("MARKED-42");
            // 3) 历史轮次的 assistant tool_calls 带 reasoning_content（DeepSeek 400 回归）
            int toolCallTurns = countToolCallAssistantWithoutReasoning(last);
            assertThat(toolCallTurns).as("带 tool_calls 的历史 assistant 必须带 reasoning_content").isZero();

            // 4) 隔离：另一个 sessionId 的请求不包含该上下文
            List<String> before = new ArrayList<>(bodies);
            runtime.chat("other session", session + "-other", noop);
            String otherBody = null;
            for (int i = before.size(); i < bodies.size(); i++) {
                otherBody = bodies.get(i);
            }
            assertThat(otherBody).as("另一会话的请求不应包含本会话第 1 轮输入")
                    .doesNotContain("FIRST-Secret-User-Input");
        } finally {
            server.stop(0);
        }
    }

    /** 统计带 tool_calls 但缺 reasoning_content 的 assistant 消息数（粗粒度 JSON 扫描）。 */
    private static int countToolCallAssistantWithoutReasoning(String body) {
        int count = 0;
        int idx = 0;
        while (true) {
            int tc = body.indexOf("\"tool_calls\"", idx);
            if (tc < 0) break;
            // 找包含该 tool_calls 的 assistant 消息片段（向前找最近的 "role":"assistant"，向后找该消息的结尾）
            int roleStart = body.lastIndexOf("\"role\"", tc);
            int roleEnd = roleStart >= 0 ? body.indexOf('}', roleStart) : -1;
            String roleSeg = roleEnd > roleStart ? body.substring(roleStart, roleEnd) : "";
            int msgEnd = body.indexOf("\"role\"", tc + 1);
            if (msgEnd < 0) msgEnd = body.length();
            String msgSeg = body.substring(tc, Math.min(msgEnd, tc + 2000));
            if (roleSeg.contains("assistant") && !msgSeg.contains("reasoning_content")) {
                count++;
            }
            idx = tc + 12;
        }
        return count;
    }
}
