package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
//import dev.langchain4j.model.tool.ToolSpecification;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MCPtoolProvider {

    // 移除 static 关键字，避免在 main 方法中的访问问题
    private McpTransport transport;
    private McpClient mcpClient;

    public McpTransport createTransport() {
        return new StreamableHttpMcpTransport.Builder()
                .url("http://127.0.0.1:9876/sse")   // Burp MCP
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    public McpClient createMcpClient(McpTransport transport) {
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    public McpToolProvider createToolProvider(McpClient mcpClient) {
        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                //.filterToolNames("get_issue", "get_issue_comments", "list_issues")
                .build();
    }

    public static void main(String[] args) {
        MCPtoolProvider mcpProvider = new MCPtoolProvider();

        try {
            System.out.println("=== 开始连接 MCP SSE 服务器 ===");

            // 创建 transport
            System.out.println("1. 创建 Transport...");
            mcpProvider.transport = mcpProvider.createTransport();
            if (mcpProvider.transport == null) {
                throw new IllegalStateException("Transport creation failed - returned null");
            }
            System.out.println("   Transport 创建成功: http://127.0.0.1:9876/sse");

            // 创建 mcpClient
            System.out.println("2. 创建 MCP Client...");
            mcpProvider.mcpClient = mcpProvider.createMcpClient(mcpProvider.transport);
            if (mcpProvider.mcpClient == null) {
                throw new IllegalStateException("MCP Client creation failed - returned null");
            }
            System.out.println("   MCP Client 创建成功");

            // 初始化连接
            System.out.println("3. 初始化 MCP 连接...");
            //mcpProvider.mcpClient.initialize();
            System.out.println("   MCP 连接初始化成功");

            // 等待连接建立
            System.out.println("4. 等待连接稳定...");
            Thread.sleep(2000);

            // 创建 toolProvider
            System.out.println("5. 创建 Tool Provider...");
            McpToolProvider toolProvider = mcpProvider.createToolProvider(mcpProvider.mcpClient);
            if (toolProvider == null) {
                throw new IllegalStateException("Tool Provider creation failed - returned null");
            }
            System.out.println("   Tool Provider 创建成功");


        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 清理资源
            try {
                if (mcpProvider.mcpClient != null) {
                    mcpProvider.mcpClient.close();
                }
            } catch (Exception e) {
                System.err.println("关闭连接时出错: " + e.getMessage());
            }
        }
    }
}
