package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.function.BiFunction;


public class McpToolProvider {

    private McpTransport transport;
    private McpClient mcpClient;

    /**
     * 创建 Legacy HTTP Transport（用于 Burp MCP Server）
     * 根据 curl 测试：Burp MCP Server 使用 SSE (Server-Sent Events) 协议
     * GET /sse 返回 SSE 流，服务器会提供动态的 /message?sessionId=xxx 端点
     * 注意：HttpMcpTransport 虽然已弃用，但这是唯一能连接 Burp MCP Server 的方式
     */
    @SuppressWarnings("deprecation")
    public McpTransport createTransport() {
        return new HttpMcpTransport.Builder()
                .sseUrl("http://localhost:9876/sse")   // Burp MCP Server SSE endpoint
                .logRequests(true)  // 在日志中查看请求流量
                .logResponses(true)  // 在日志中查看响应流量
                .build();
    }

    public McpClient createMcpClient(McpTransport transport) {
        return new DefaultMcpClient.Builder()
                .key("BurpMCPClient")  // 设置客户端标识
                .transport(transport)
                .cacheToolList(true)  // 启用工具列表缓存
                .build();
    }

    /**
     * 创建 MCP 工具提供者（不带映射和过滤）
     */
    public dev.langchain4j.mcp.McpToolProvider createToolProvider(McpClient mcpClient) {
        return createToolProviderWithMapping(mcpClient, null, (String[]) null);
    }

    /**
     * 创建 MCP 工具提供者（带工具名称过滤）
     * @param mcpClient MCP 客户端
     * @param filterToolNames 要过滤的工具名称
     * @return MCP 工具提供者
     */
    public dev.langchain4j.mcp.McpToolProvider createToolProvider(McpClient mcpClient, String ... filterToolNames) {
        return createToolProviderWithMapping(mcpClient, null, filterToolNames);
    }
    
    /**
     * 创建 MCP 工具提供者（带映射配置）
     * @param mcpClient MCP 客户端
     * @param mappingConfig 映射配置（可为 null，表示不使用映射）
     * @return MCP 工具提供者
     */
    public dev.langchain4j.mcp.McpToolProvider createToolProviderWithMapping(McpClient mcpClient, McpToolMappingConfig mappingConfig) {
        return createToolProviderWithMapping(mcpClient, mappingConfig, (String[]) null);
    }
    
    /**
     * 创建 MCP 工具提供者（带映射配置和工具名称过滤）
     * @param mcpClient MCP 客户端
     * @param mappingConfig 映射配置（可为 null，表示不使用映射）
     * @param filterToolNames 要过滤的工具名称（可选）
     * @return MCP 工具提供者
     */
    public dev.langchain4j.mcp.McpToolProvider createToolProviderWithMapping(McpClient mcpClient, McpToolMappingConfig mappingConfig, String ... filterToolNames) {
        var builder = dev.langchain4j.mcp.McpToolProvider.builder()
                .mcpClients(mcpClient);
        
        // 如果提供了映射配置，应用工具规范映射
        // 注意：不能同时设置 toolNameMapper 和 toolSpecificationMapper
        // 因此我们只使用 toolSpecificationMapper，在其中同时处理名称映射和描述映射
        if (mappingConfig != null) {
            // 应用工具规范映射（包含名称映射和描述映射）
            // 根据 LangChain4j 文档：https://docs.langchain4j.dev/tutorials/mcp#mcp-tool-specification-mapping
            // McpToolProvider 支持 toolSpecificationMapper 方法，可以修改工具的名称、描述、参数等
            BiFunction<McpClient, ToolSpecification, ToolSpecification> toolSpecMapper = mappingConfig.getToolSpecMapper();
            if (toolSpecMapper != null) {
                builder.toolSpecificationMapper(toolSpecMapper);
            }
        }
        
        // 如果提供了工具名称过滤，应用过滤
        if (filterToolNames != null && filterToolNames.length > 0) {
            builder.filterToolNames(filterToolNames);
        }
        
        return builder.build();
    }
    
    /**
     * 获取 JSON Schema 的类型名称
     */
    private static String getSchemaType(Object schema) {
        if (schema == null) return "unknown";
        String className = schema.getClass().getSimpleName();
        // 简化类型名称
        if (className.contains("String")) return "string";
        if (className.contains("Integer")) return "integer";
        if (className.contains("Boolean")) return "boolean";
        if (className.contains("Object")) return "object";
        if (className.contains("Array")) return "array";
        return className.toLowerCase().replace("jsonschema", "");
    }
    
    /**
     * 检查参数是否为必需
     */
    private static boolean isRequired(JsonObjectSchema schema, String paramName) {
        if (schema == null || schema.required() == null) return false;
        return schema.required().contains(paramName);
    }

    /*
    public static void main(String[] args) {
        MCPtoolProvider mcpProvider = new MCPtoolProvider();

        try {
            System.out.println("=== 开始连接 MCP 服务器 ===");

            // 创建 transport
            System.out.println("1. 创建 Transport...");
            mcpProvider.transport = mcpProvider.createTransport();
            if (mcpProvider.transport == null) {
                throw new IllegalStateException("Transport creation failed - returned null");
            }
            System.out.println("   Transport 创建成功: http://localhost:9876/sse");

            // 创建 mcpClient
            System.out.println("2. 创建 MCP Client...");
            mcpProvider.mcpClient = mcpProvider.createMcpClient(mcpProvider.transport);
            if (mcpProvider.mcpClient == null) {
                throw new IllegalStateException("MCP Client creation failed - returned null");
            }
            System.out.println("   MCP Client 创建成功");

            // 等待连接建立
            System.out.println("3. 等待连接稳定...");
            Thread.sleep(2000);

            // 创建 toolProvider
            System.out.println("4. 创建 Tool Provider...");
            McpToolProvider toolProvider = mcpProvider.createToolProvider(mcpProvider.mcpClient);
            if (toolProvider == null) {
                throw new IllegalStateException("Tool Provider creation failed - returned null");
            }
            System.out.println("   Tool Provider 创建成功");

            // 获取工具列表
            System.out.println("5. 获取可用工具列表...");
            List<ToolSpecification> tools = mcpProvider.mcpClient.listTools();
            
            System.out.println("\n=== Burp MCP Server 可用工具列表 ===");
            System.out.println("工具总数: " + tools.size());
            System.out.println();
            
            // 循环打印每个工具的详细信息
            if (tools.isEmpty()) {
                System.out.println("警告: 未找到任何工具！");
                System.out.println("可能的原因：");
                System.out.println("1. Burp MCP Server 尚未注册任何工具");
                System.out.println("2. 服务器配置问题");
            } else {
                for (int i = 0; i < tools.size(); i++) {
                    ToolSpecification tool = tools.get(i);
                    System.out.println("工具 #" + (i + 1) + ":");
                    System.out.println("  名称: " + tool.name());
                    System.out.println("  描述: " + (tool.description() != null ? tool.description() : "(无描述)"));
                    
                    // 打印参数信息
                    if (tool.parameters() != null) {
                        JsonObjectSchema params = tool.parameters();
                        if (params.properties() != null && !params.properties().isEmpty()) {
                            System.out.println("  参数:");
                            params.properties().forEach((paramName, paramSchema) -> {
                                System.out.println("    - " + paramName + ": " + 
                                    getSchemaType(paramSchema) + 
                                    (isRequired(params, paramName) ? " (必需)" : " (可选)"));
                            });
                        } else {
                            System.out.println("  参数: (无参数)");
                        }
                    } else {
                        System.out.println("  参数: (无参数)");
                    }
                    System.out.println();
                }
            }
            
            System.out.println("=== 工具列表获取完成 ===");

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
    } */
}
