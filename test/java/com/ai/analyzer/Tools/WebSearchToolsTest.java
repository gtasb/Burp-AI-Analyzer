package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSearchTools - 网络搜索工具")
class WebSearchToolsTest {

    @Test
    @DisplayName("DuckDuckGo 工具规格应包含 web_search")
    void duckDuckGo_tool_spec_should_contain_web_search() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tools);

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("web_search");
        assertThat(specs.get(0).description()).contains("搜索");
    }

    @Test
    @Tag("integration")
    @DisplayName("DuckDuckGo 实际搜索 - 模拟AI调用 web_search 工具")
    void duckDuckGo_search_simulates_ai_tool_call() throws Exception {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        Method searchMethod = WebSearchTools.class.getMethod("searchWeb", String.class);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-test-001")
                .name("web_search")
                .arguments("{\"arg0\": \"OWASP Top 10 2025\"}")
                .build();

        DefaultToolExecutor executor = new DefaultToolExecutor(tools, searchMethod);
        String result = executor.execute(request, null);

        System.out.println("=== DuckDuckGo 搜索结果 (模拟AI调用) ===");
        System.out.println("Query: OWASP Top 10 2025");
        System.out.println(result);
        System.out.println("==========================================");

        assertThat(result).isNotNull();
        assertThat(result).doesNotStartWith("搜索失败");
        assertThat(result).containsIgnoringCase("owasp");
    }

    @Test
    @Tag("integration")
    @DisplayName("DuckDuckGo 直接方法调用搜索")
    void duckDuckGo_direct_search() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        String result = tools.searchWeb("CVE-2024-3400 Palo Alto");

        System.out.println("=== DuckDuckGo 直接搜索 ===");
        System.out.println(result);
        System.out.println("============================");

        assertThat(result).isNotNull();
        assertThat(result).doesNotStartWith("搜索失败");
    }
}
