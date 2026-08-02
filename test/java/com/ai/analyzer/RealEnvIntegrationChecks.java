package com.ai.analyzer;

import com.ai.analyzer.agent.mcpclient.AgentScopeMcpManager;
import com.ai.analyzer.core.AgentConfig.ApiProvider;
import com.ai.analyzer.core.AgentScopeModelFactory;
import com.ai.analyzer.tools.WebSearchTools;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境集成测试：从项目根目录 test_env.txt 读取真实凭据（该文件已在 .gitignore 中，
 * 缺失时整个测试类跳过）。类名不以 Test 结尾，默认 surefire 不会执行；
 * 手动运行：{@code mvn test -Dtest=RealEnvIntegrationChecks}。
 *
 * <p>覆盖单元测试无法覆盖的盲区：真实 MCP 服务器注册（含 Authorization 头透传）、
 * 真实 LLM 流式调用、真实搜索 API 调用。
 *
 * <p>注意：本测试类内禁止打印或断言任何凭据值。
 */
@DisplayName("真实环境集成测试（需 test_env.txt + 外部服务在线）")
class RealEnvIntegrationChecks {

    private static String burpMcpToken;
    private static String burpMcpUrl;
    private static String deepseekUrl;
    private static String deepseekKey;
    private static String tavilyKey;

    @BeforeAll
    static void loadCredentials() {
        Path envFile = Paths.get("test_env.txt");
        assumeTrue(Files.exists(envFile), "test_env.txt 不存在，跳过集成测试");
        try {
            List<String[]> blocks = new ArrayList<>();
            List<String> current = new ArrayList<>();
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    if (!current.isEmpty()) {
                        blocks.add(current.toArray(new String[0]));
                        current = new ArrayList<>();
                    }
                    continue;
                }
                current.add(line.trim());
            }
            if (!current.isEmpty()) {
                blocks.add(current.toArray(new String[0]));
            }
            for (String[] b : blocks) {
                String name = b[0].toLowerCase();
                if (name.contains("burp")) {
                    burpMcpToken = b.length > 1 ? b[1] : null;
                    burpMcpUrl = b.length > 2 ? b[2] : null;
                } else if (name.contains("deepseek")) {
                    deepseekUrl = b.length > 1 ? b[1] : null;
                    deepseekKey = b.length > 2 ? b[2] : null;
                } else if (name.contains("tavily")) {
                    tavilyKey = b.length > 1 ? b[1] : null;
                }
            }
        } catch (Exception e) {
            assumeTrue(false, "test_env.txt 解析失败: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("BurpMCP-Ultra 真实注册")
    class BurpMcp {

        @Test
        @DisplayName("真实 token 注册根路径端点并列出工具（覆盖 Authorization 头透传）")
        void register_with_real_token_and_list_tools() {
            assumeTrue(burpMcpUrl != null && burpMcpToken != null, "缺少 Burp MCP 凭据");
            Toolkit toolkit = new Toolkit();
            List<McpClientWrapper> clients =
                    AgentScopeMcpManager.registerBurpMcp(toolkit, burpMcpUrl, burpMcpToken);
            assertThat(clients).as("Burp MCP 注册应成功（含 Authorization 透传）").isNotEmpty();
            McpClientWrapper client = clients.get(0);
            assertThat(client.isInitialized()).isTrue();
            List<McpSchema.Tool> tools = client.listTools().block();
            assertThat(tools).as("服务器应提供工具").isNotEmpty();
            System.out.println("[integration] Burp MCP 工具数量: " + tools.size());
            tools.stream().map(McpSchema.Tool::name).limit(8)
                    .forEach(t -> System.out.println("[integration]   工具: " + t));
            client.close();
        }
    }

    @Nested
    @DisplayName("DeepSeek 真实流式调用")
    class DeepSeek {

        @Test
        @DisplayName("真实 key 流式生成（覆盖模型工厂 + 流式链路）")
        void stream_generate_with_real_key() {
            assumeTrue(deepseekUrl != null && deepseekKey != null, "缺少 DeepSeek 凭据");
            Model model = AgentScopeModelFactory.create(
                    ApiProvider.OPENAI_COMPATIBLE, deepseekKey, deepseekUrl,
                    "deepseek-v4-pro", false, false, null);
            Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("你是测试助手，回答尽量简短").build();
            Msg user = Msg.builder().role(MsgRole.USER).textContent("用一句话回答：1+1 等于几？").build();
            List<ChatResponse> chunks = model.stream(List.of(system, user), List.of(), null)
                    .collectList().block();
            assertThat(chunks).as("应收到流式响应 chunk").isNotEmpty();
            StringBuilder text = new StringBuilder();
            chunks.forEach(c -> c.getContent().forEach(b -> text.append(b.toString())));
            assertThat(text.toString()).as("输出不应为空").isNotBlank();
            System.out.println("[integration] DeepSeek chunk 数: " + chunks.size()
                    + ", 输出长度: " + text.length());
        }
    }

    @Nested
    @DisplayName("Tavily 真实搜索")
    class Tavily {

        @Test
        @DisplayName("真实 key 调用 web_search")
        void search_with_real_key() {
            assumeTrue(tavilyKey != null, "缺少 Tavily 凭据");
            WebSearchTools tools = WebSearchTools.tavily(tavilyKey, null);
            String result = tools.searchWeb("Burp Suite 扩展 开发 2026");
            assertThat(result).as("搜索结果不应为空").isNotBlank();
            System.out.println("[integration] Tavily 结果长度: " + result.length()
                    + ", 预览: " + result.substring(0, Math.min(120, result.length())).replace('\n', ' '));
        }
    }
}
