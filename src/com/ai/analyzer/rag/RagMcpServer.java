package com.ai.analyzer.rag;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.McpToolProvider;
import lombok.Getter;

import java.util.List;

/**
 * RAG MCP 服务器管理器
 * 使用 stdio 传输方式连接到 rag-mcp-server
 * 参考文档: https://docs.langchain4j.dev/tutorials/mcp/
 */
public class RagMcpServer {
    // 示例命令: uvx rag-mcp-server --knowledge-base PayloadsAllTheThings-master --embedding-model "all-MiniLM-L6-v2" --chunk-size 1000 --chunk-overlap 100 --top-k 5
    
    private McpTransport transport;
    private McpClient mcpClient;
    @Getter
    private McpToolProvider mcpToolProvider;
    @Getter
    private boolean initialized = false;

    /**
     * 创建 stdio 传输（用于运行本地 MCP 服务器子进程）
     * @param ragDocumentsPath RAG 知识库文档路径
     * @return MCP 传输实例
     */
    public McpTransport createTransport(String ragDocumentsPath) { 
        return new StdioMcpTransport.Builder()
                .command(List.of(
                    "uvx", "rag-mcp-server", 
                    "--knowledge-base", ragDocumentsPath, 
                    "--embedding-model", "all-MiniLM-L6-v2", 
                    "--chunk-size", "1000", 
                    "--chunk-overlap", "100", 
                    "--top-k", "3"))
                .logEvents(true) // 启用日志以便调试
                .build();
    }

    /**
     * 创建 MCP 客户端
     * @param transport MCP 传输
     * @return MCP 客户端实例
     */
    public McpClient createMcpClient(McpTransport transport) {
        return new DefaultMcpClient.Builder()
                .key("RagMcpClient")
                .transport(transport)
                .build();
    }

    /**
     * 创建 MCP 工具提供者（带工具名称过滤）
     * 根据 LangChain4j 文档，使用 filterToolNames 来限制 AI 只能使用指定的工具
     * @param mcpClient MCP 客户端
     * @param filterToolNames 要保留的工具名称（其他工具将被过滤掉）
     * @return MCP 工具提供者
     */
    public McpToolProvider createToolProvider(McpClient mcpClient, String... filterToolNames) {
        McpToolProvider.Builder builder = McpToolProvider.builder()
                .mcpClients(mcpClient);
        
        // 如果指定了工具名称过滤，则只保留这些工具
        if (filterToolNames != null && filterToolNames.length > 0) {
            builder.filterToolNames(filterToolNames);
        }
        
        return builder.build();
    }

    /**
     * 初始化 RAG MCP 服务器
     * @param ragDocumentsPath RAG 文档路径
     * @return 初始化成功返回 true，失败返回 false
     */
    public boolean initRagMcpServer(String ragDocumentsPath) {
        if (ragDocumentsPath == null || ragDocumentsPath.trim().isEmpty()) {
            System.err.println("[RagMcpServer] 错误: RAG 文档路径不能为空");
            return false;
        }
        
        try {
            // 1. 创建 stdio 传输（启动 rag-mcp-server 子进程）
            transport = createTransport(ragDocumentsPath.trim());
            
            // 2. 创建 MCP 客户端
            mcpClient = createMcpClient(transport);
            
            // 3. 创建工具提供者，只保留 semantic_search 工具
            mcpToolProvider = createToolProvider(mcpClient, "semantic_search");
            
            initialized = true;
            System.out.println("[RagMcpServer] RAG MCP 服务器初始化成功，知识库路径: " + ragDocumentsPath);
            return true;
        } catch (Exception e) {
            System.err.println("[RagMcpServer] RAG MCP 服务器初始化失败: " + e.getMessage());
            e.printStackTrace();
            initialized = false;
            return false;
        }
    }
    
    /**
     * 关闭 MCP 客户端连接
     */
    public void close() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
                System.out.println("[RagMcpServer] MCP 客户端已关闭");
            } catch (Exception e) {
                System.err.println("[RagMcpServer] 关闭 MCP 客户端失败: " + e.getMessage());
            }
        }
        initialized = false;
        mcpToolProvider = null;
        mcpClient = null;
        transport = null;
    }
}