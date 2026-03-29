package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import dev.langchain4j.web.search.google.customsearch.GoogleCustomWebSearchEngine;
import dev.langchain4j.community.web.search.duckduckgo.DuckDuckGoWebSearchEngine;

import java.time.Duration;
import java.util.stream.Collectors;

public class WebSearchTools {
    private final WebSearchEngine searchEngine;

    public static WebSearchTools tavily(String apiKey, String baseUrl) {
        var builder = TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .timeout(Duration.ofSeconds(30));
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.baseUrl(baseUrl.trim());
        }
        return new WebSearchTools(builder.build());
    }

    public static WebSearchTools google(String apiKey, String csi) {
        return new WebSearchTools(GoogleCustomWebSearchEngine.builder()
                .apiKey(apiKey)
                .csi(csi)
                .timeout(Duration.ofSeconds(30))
                .build());
    }

    public static WebSearchTools duckDuckGo() {
        return new WebSearchTools(DuckDuckGoWebSearchEngine.builder()
                .duration(Duration.ofSeconds(30))
                .build());
    }

    public WebSearchTools(String apiKey) {
        this(apiKey, null);
    }

    public WebSearchTools(String apiKey, String baseUrl) {
        this(buildTavily(apiKey, baseUrl));
    }

    private WebSearchTools(WebSearchEngine engine) {
        this.searchEngine = engine;
    }

    private static WebSearchEngine buildTavily(String apiKey, String baseUrl) {
        var builder = TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .timeout(Duration.ofSeconds(30));
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    @Tool(name = "web_search", value = {
            "在互联网上搜索信息，用于查询最新漏洞(CVE)、技术文档、安全公告、资产信息等。",
            "参数 query 为搜索关键词或自然语言问题。"
    })
    public String searchWeb(@P("搜索关键词或自然语言问题") String query) {
        try {
            WebSearchRequest request = WebSearchRequest.builder()
                    .searchTerms(query)
                    .maxResults(5)
                    .build();
            WebSearchResults results = searchEngine.search(request);
            if (results == null || results.results() == null || results.results().isEmpty()) {
                return "未找到相关搜索结果";
            }
            return results.results().stream()
                    .map(r -> {
                        StringBuilder sb = new StringBuilder();
                        if (r.title() != null) sb.append("### ").append(r.title()).append("\n");
                        if (r.url() != null) sb.append("URL: ").append(r.url()).append("\n");
                        if (r.snippet() != null) sb.append(r.snippet()).append("\n");
                        return sb.toString();
                    })
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }
}
