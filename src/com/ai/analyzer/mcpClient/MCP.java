package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

public class MCP {
    public static void transport() {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://127.0.0.1:9876/sse")   // Burp MCP
                .logRequests(true) // 如果你想在日志中查看流量
                .logResponses(true)
                .build();
    }
}