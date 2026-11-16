package com.ai.analyzer.core;

import com.ai.analyzer.agent.runtime.AgentScopeAgentRuntime;
import com.ai.analyzer.agent.runtime.RuntimeEvent;
import com.ai.analyzer.agent.runtime.RuntimeEventListener;
import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话切换语义（session switcher 的地基）：
 * 同一 runtime（生产配置：disableMemoryTools/Hooks，但不禁用 per-session 状态）
 * 上，session-1 → session-2 → 切回 session-1，第三轮的模型请求必须：
 * 1) 仍包含 session-1 第一轮的用户输入（上下文没被 session-2 冲掉）；
 * 2) 不包含 session-2 的内容（隔离没破）。
 * 依据：MultiTurnContextAuditTest 证明同 sessionId 上下文跨轮保留、异 sessionId 隔离；
 * 本测试证明「来回切换」也成立——这是 UI 会话下拉（切回旧会话继续聊）的前提。
 */
@DisplayName("Session switch - 会话来回切换的上下文保留")
class SessionSwitchContextTest {

    @Test
    @DisplayName("switch back to session1 keeps its context and excludes session2")
    void switchBackRetainsContext(@TempDir java.nio.file.Path tmp) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ack\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
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
            AgentScopeAgentRuntime runtime = AgentScopeAgentRuntime.builder()
                    .model(model).toolkit(new io.agentscope.core.tool.Toolkit())
                    .workspacePath(tmp)
                    .stateWorkspacePath(tmp.resolve("state"))
                    .disableMemoryTools().disableMemoryHooks()
                    .systemPrompt("You are a test agent.")
                    .maxIters(8)
                    .build();
            RuntimeEventListener noop = new RuntimeEventListener() {
                @Override public void onEvent(RuntimeEvent e) { }
            };
            String s1 = "sess-A-" + System.nanoTime();
            String s2 = "sess-B-" + System.nanoTime();

            runtime.chat("SECRET-ONE-first-turn", s1, noop);
            runtime.chat("SECRET-TWO-other-session", s2, noop);
            runtime.chat("back-to-one", s1, noop);

            String resumed = bodies.get(bodies.size() - 1);
            assertThat(resumed).as("切回 session-1 后，其第一轮输入仍在上下文")
                    .contains("SECRET-ONE-first-turn");
            assertThat(resumed).as("session-2 的内容不得泄入 session-1 的恢复上下文")
                    .doesNotContain("SECRET-TWO-other-session");
        } finally {
            server.stop(0);
        }
    }
}
