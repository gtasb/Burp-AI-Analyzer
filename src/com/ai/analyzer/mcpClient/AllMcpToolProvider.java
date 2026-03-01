package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
//import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.mcp.McpToolProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;


public class AllMcpToolProvider {

    private McpTransport transport;
    private McpClient mcpClient;


    /**
     * 获取 Burp MCP 的备用 URL（用于 404 fallback）
     * 统一使用根路径，若传入带 /sse 的 URL 则自动去除
     */
    public static String getAlternateBurpMcpUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "http://127.0.0.1:9876/";
        url = url.trim();
        if (url.endsWith("/sse")) {
            return url.substring(0, url.length() - 4).replaceAll("/+$", "") + "/";
        }
        return url.replaceAll("/+$", "") + "/";
    }
    
    /**
     * 创建 Legacy HTTP Transport（用于 Burp MCP Server）
     * Burp MCP Server 使用 SSE 协议，根路径 http://127.0.0.1:9876/ 即可连接
     * 注意：HttpMcpTransport 虽然已弃用，但这是唯一能连接 Burp MCP Server 的方式
     * 
     * 【智能超时策略】：
     * - 默认超时：30 秒（足够处理大多数 HTTP 请求）
     * - 如果请求格式正确（末尾有空行），通常 5-10 秒内完成
     * - 如果请求格式错误（缺少末尾空行），服务器会等待直到超时
     * - 建议：确保 HTTP 请求格式正确，避免不必要的超时
     * 
     * @param sseUrl MCP 服务器的 SSE 端点 URL
     * @return McpTransport 实例
     */
    @SuppressWarnings("deprecation")
    public McpTransport createHttpTransport(String sseUrl) {
        return new HttpMcpTransport.Builder()
                .sseUrl(sseUrl)   // MCP Server SSE endpoint
                .logRequests(true)  // 在日志中查看请求流量
                .logResponses(true)  // 在日志中查看响应流量
                .timeout(java.time.Duration.ofSeconds(30))  // 超时设置为 30 秒，给 HTTP 请求足够时间（包括慢速响应）
                .build();
    }


    /**
     * 创建 Streamable HTTP Transport（用于 Chrome MCP）
     * 【智能超时策略】：30 秒，与 Burp MCP Transport 保持一致
     * 
     * @param httpUrl MCP 服务器的 HTTP 端点 URL
     * @return McpTransport 实例
     */
    public McpTransport createStreamableHttpTransport(String httpUrl) {
        return new StreamableHttpMcpTransport.Builder()
                .url(httpUrl)
                .timeout(java.time.Duration.ofSeconds(30))  // 超时设置为 30 秒，与 Burp MCP 保持一致
                .build();
    }
    
    /**
     * 创建 Legacy HTTP Transport（兼容旧方法名）
     * @param sseUrl MCP 服务器的 SSE 端点 URL
     * @return McpTransport 实例
     */
    @SuppressWarnings("deprecation")
    public McpTransport createTransport(String sseUrl) {
        return createHttpTransport(sseUrl);
    }
    
    /**
     * 创建 Legacy HTTP Transport（使用默认 URL）
     * @return McpTransport 实例
     */
    @SuppressWarnings("deprecation")
    public McpTransport createTransport() {
        return createHttpTransport("http://127.0.0.1:9876/");
    }

    /**
     * 创建 Stdio Transport（用于 RAG MCP Server）
     * 参考文档: https://docs.langchain4j.dev/tutorials/mcp/#mcp-transport
     * 
     * @param command 命令列表，例如 ["uvx", "rag-mcp-server", "--knowledge-base", "path"]
     * @return McpTransport 实例
     */
    public McpTransport createStdioTransport(List<String> command) {
        return new StdioMcpTransport.Builder()
                .command(command)
                .logEvents(true)  // 启用日志以便调试
                .build();
    }

    /**
     * 创建 RAG MCP 的 Stdio Transport
     * @param knowledgeBasePath 知识库路径
     * @return McpTransport 实例
     */
    /**
     * 创建 RAG MCP 的 Stdio Transport
     * 与 Cursor mcp.json 配置保持一致：使用 uvx rag-mcp + PERSIST_DIRECTORY 环境变量
     * @param knowledgeBasePath 知识库路径
     * @return McpTransport 实例
     */
    public McpTransport createRagMcpTransport(String knowledgeBasePath) {
        List<String> command = new ArrayList<>();
        
        // Windows 系统需要通过 cmd 来执行 uvx
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            command.add("cmd");
            command.add("/c");
        }
        
        // 使用 rag-mcp（与 Cursor mcp.json 配置一致）
        command.add("uvx");
        command.add("rag-mcp");
        
        // 通过环境变量传递知识库路径
        Map<String, String> env = new HashMap<>();
        env.put("PERSIST_DIRECTORY", knowledgeBasePath);
        
        return createStdioTransportWithEnv(command, env);
    }
    
    /**
     * 创建带环境变量的 Stdio Transport
     * @param command 命令列表
     * @param environment 环境变量映射
     * @return McpTransport 实例
     */
    public McpTransport createStdioTransportWithEnv(List<String> command, Map<String, String> environment) {
        return new StdioMcpTransport.Builder()
                .command(command)
                .environment(environment)
                .logEvents(true)
                .build();
    }

    /**
     * 创建 MCP 客户端（带自定义 key）
     * @param transport MCP 传输
     * @param clientKey 客户端标识（用于区分多个客户端）
     * @return McpClient 实例
     */
    public McpClient createMcpClient(McpTransport transport, String clientKey) {
        return new DefaultMcpClient.Builder()
                .key(clientKey)
                .transport(transport)
                .cacheToolList(true)
                .build();
    }

    /**
     * 创建 MCP 客户端（默认 Burp 客户端）
     * @param transport MCP 传输
     * @return McpClient 实例
     */
    public McpClient createMcpClient(McpTransport transport) {
        return createMcpClient(transport, "BurpMCPClient");
    }

    /**
     * 创建 MCP 工具提供者（不带映射和过滤）
     */
    public McpToolProvider createToolProvider(List<McpClient> mcpClients) {
        return createToolProviderWithMapping(mcpClients, null, (String[]) null);
    }

    /**
     * 创建 MCP 工具提供者（带工具名称过滤）
     * @param mcpClients MCP 客户端列表
     * @param filterToolNames 要过滤的工具名称
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProvider(List<McpClient> mcpClients, String ... filterToolNames) {
        return createToolProviderWithMapping(mcpClients, null, filterToolNames);
    }

    /**
     * 创建 MCP 工具提供者（单个客户端，带工具名称过滤）
     * @param mcpClient 单个 MCP 客户端
     * @param filterToolNames 要过滤的工具名称
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProvider(McpClient mcpClient, String ... filterToolNames) {
        return createToolProviderWithMapping(List.of(mcpClient), null, filterToolNames);
    }
    
    /**
     * 创建 MCP 工具提供者（带映射配置）
     * @param mcpClients MCP 客户端列表
     * @param mappingConfig 映射配置（可为 null，表示不使用映射）
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProviderWithMapping(List<McpClient> mcpClients, McpToolMappingConfig mappingConfig) {
        return createToolProviderWithMapping(mcpClients, mappingConfig, (String[]) null);
    }

    /**
     * 创建 MCP 工具提供者（单个客户端，带映射配置和工具名称过滤）
     * @param mcpClient 单个 MCP 客户端
     * @param mappingConfig 映射配置（可为 null，表示不使用映射）
     * @param filterToolNames 要过滤的工具名称（可选）
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProviderWithMapping(McpClient mcpClient, McpToolMappingConfig mappingConfig, String ... filterToolNames) {
        return createToolProviderWithMapping(List.of(mcpClient), mappingConfig, filterToolNames);
    }
    
    /**
     * 创建 MCP 工具提供者（带映射配置和工具名称过滤）
     * 支持多个 MCP 客户端，参考文档: https://docs.langchain4j.dev/tutorials/mcp/#mcp-tool-provider
     * 
     * @param mcpClients MCP 客户端列表
     * @param mappingConfig 映射配置（可为 null，表示不使用映射）
     * @param filterToolNames 要过滤的工具名称（可选）
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProviderWithMapping(List<McpClient> mcpClients, McpToolMappingConfig mappingConfig, String ... filterToolNames) {
        var builder = dev.langchain4j.mcp.McpToolProvider.builder()
                .mcpClients(mcpClients);
        
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
     * 创建组合的 MCP 工具提供者（多个客户端）
     * 根据 LangChain4j 文档：一个 McpToolProvider 可以同时使用多个客户端
     * 
     * @param mcpClients 多个 MCP 客户端
     * @param filterToolNames 要过滤的工具名称（可选）
     * @return MCP 工具提供者
     */
    public McpToolProvider createCombinedToolProvider(List<McpClient> mcpClients, String ... filterToolNames) {
        if (mcpClients == null || mcpClients.isEmpty()) {
            return null;
        }
        
        var builder = McpToolProvider.builder()
                .mcpClients(mcpClients)
                .failIfOneServerFails(false);  // 一个服务器失败不影响其他服务器
        
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


}
