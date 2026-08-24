package com.ai.analyzer.tools;

import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSearchTools - 网络搜索工具")
class WebSearchToolsTest {

    @Test
    @DisplayName("工具规格应包含 web_search 与 fetch_url")
    void tool_specs_should_include_web_search_and_fetch_url() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        List<Tool> specs = new ArrayList<>();
        for (Method m : tools.getClass().getDeclaredMethods()) {
            Tool ann = m.getAnnotation(Tool.class);
            if (ann != null) specs.add(ann);
        }

        assertThat(specs).hasSize(3);
        assertThat(specs).extracting(Tool::name)
                .containsExactlyInAnyOrder("web_search", "fetch_url", "vulnerability_search");
        assertThat(specs.stream().filter(s -> "web_search".equals(s.name())).findFirst().orElseThrow().description())
                .contains("搜索");
        assertThat(specs.stream().filter(s -> "fetch_url".equals(s.name())).findFirst().orElseThrow().description())
                .contains("URL");
        assertThat(specs.stream().filter(s -> "vulnerability_search".equals(s.name())).findFirst().orElseThrow().description())
                .contains("漏洞");
    }

    @Test
    @DisplayName("工厂方法应返回可用实例")
    void factories_return_instances() {
        assertThat(WebSearchTools.tavily("k", "https://proxy.tavily.com")).isNotNull();
        assertThat(WebSearchTools.google("k", "csi")).isNotNull();
        assertThat(WebSearchTools.duckDuckGo()).isNotNull();
    }

    @Test
    @Tag("integration")
    @EnabledIfEnvironmentVariable(named = "RUN_WEB_SEARCH_INTEGRATION", matches = "true")
    @DisplayName("DuckDuckGo 实际搜索 - 模拟AI调用 web_search 工具")
    void duckDuckGo_search_simulates_ai_tool_call() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        String result = tools.searchWeb("OWASP Top 10 2025");

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
    @EnabledIfEnvironmentVariable(named = "RUN_WEB_SEARCH_INTEGRATION", matches = "true")
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

    @Test
    @DisplayName("fetch_url 应拒绝内网/回环地址（基础 SSRF 防护）")
    void fetch_url_should_block_private_hosts() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        assertThat(tools.fetchUrl("http://127.0.0.1/")).contains("不允许访问");
        assertThat(tools.fetchUrl("http://192.168.1.1/")).contains("不允许访问");
    }

    @Test
    @Tag("integration")
    @DisplayName("fetch_url 抓取公网页面（example.com）")
    void fetch_url_public_page() {
        WebSearchTools tools = WebSearchTools.duckDuckGo();
        String r = tools.fetchUrl("https://example.com/");
        assertThat(r).contains("[来源]").contains("example.com");
        assertThat(r.toLowerCase()).contains("example domain");
    }
}
