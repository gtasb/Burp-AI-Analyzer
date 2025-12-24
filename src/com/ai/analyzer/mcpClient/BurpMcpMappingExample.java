package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import java.util.List;

/**
 * Burp MCP 工具映射示例代码
 * 
 * 本示例演示如何使用 LangChain4j MCP 的工具名称映射和工具规范映射功能
 * 参考文档: https://docs.langchain4j.dev/tutorials/mcp
 * 
 * 功能说明：
 * 1. 工具名称映射：将英文工具名映射为中文友好名称
 * 2. 工具规范映射：为工具添加详细的中文描述
 * 3. 工具过滤：只暴露需要的工具给 AI
 */
public class BurpMcpMappingExample {
    
    /**
     * 示例 1: 使用完整的 Burp MCP 工具映射配置
     * 包含所有 22 个工具的中文名称映射和描述映射
     */
    public static void example1_FullMapping() {
        System.out.println("=== 示例 1: 使用完整的 Burp MCP 工具映射 ===");
        
        try {
            // 1. 创建 Transport
            AllMcpToolProvider mcpProvider = new AllMcpToolProvider();
            McpTransport transport = mcpProvider.createTransport();
            
            // 2. 创建 MCP Client
            McpClient mcpClient = mcpProvider.createMcpClient(transport);
            
            // 等待连接稳定
            Thread.sleep(2000);
            
            // 3. 创建完整的 Burp MCP 映射配置
            McpToolMappingConfig mappingConfig = McpToolMappingConfig.createBurpMapping();
            
            // 4. 使用映射配置创建 Tool Provider
            McpToolProvider toolProvider = mcpProvider.createToolProviderWithMapping(mcpClient, mappingConfig);
            
            // 5. 获取工具列表（此时工具名称已映射为中文）
            List<ToolSpecification> tools = mcpClient.listTools();
            
            System.out.println("工具总数: " + tools.size());
            System.out.println("\n工具列表（已映射为中文名称）:");
            for (ToolSpecification tool : tools) {
                System.out.println("  - " + tool.name());
                if (tool.description() != null) {
                    System.out.println("    描述: " + tool.description());
                }
            }
            
            // 清理资源
            mcpClient.close();
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例 2: 自定义工具名称映射
     * 只映射部分工具，其他工具保持原样
     */
    public static void example2_CustomNameMapping() {
        System.out.println("\n=== 示例 2: 自定义工具名称映射 ===");
        
        try {
            AllMcpToolProvider mcpProvider = new AllMcpToolProvider();
            McpTransport transport = mcpProvider.createTransport();
            McpClient mcpClient = mcpProvider.createMcpClient(transport);
            Thread.sleep(2000);
            
            // 创建自定义映射配置
            McpToolMappingConfig mappingConfig = new McpToolMappingConfig();
            
            // 只映射几个常用工具
            mappingConfig.addToolNameMapping("send_http1_request", "发送HTTP请求");
            mappingConfig.addToolNameMapping("get_proxy_http_history", "查看代理历史");
            mappingConfig.addToolNameMapping("url_encode", "URL编码工具");
            
            // 使用自定义映射创建 Tool Provider
            dev.langchain4j.mcp.McpToolProvider toolProvider = mcpProvider.createToolProviderWithMapping(mcpClient, mappingConfig);
            
            System.out.println("已映射的工具:");
            System.out.println("  - send_http1_request -> 发送HTTP请求");
            System.out.println("  - get_proxy_http_history -> 查看代理历史");
            System.out.println("  - url_encode -> URL编码工具");
            System.out.println("其他工具保持原英文名称");
            
            mcpClient.close();
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例 3: 工具过滤 + 名称映射
     * 只暴露部分工具给 AI，并应用名称映射
     */
    public static void example3_FilteredAndMapped() {
        System.out.println("\n=== 示例 3: 工具过滤 + 名称映射 ===");
        
        try {
            AllMcpToolProvider mcpProvider = new AllMcpToolProvider();
            McpTransport transport = mcpProvider.createTransport();
            McpClient mcpClient = mcpProvider.createMcpClient(transport);
            Thread.sleep(2000);
            
            // 创建映射配置
            McpToolMappingConfig mappingConfig = McpToolMappingConfig.createBurpMapping();
            
            // 只暴露安全的工具（只读操作）
            String[] safeTools = {
                "get_proxy_http_history",
                "get_scanner_issues",
                "url_encode",
                "url_decode",
                "base64_encode",
                "base64_decode"
            };
            
            // 使用映射配置和工具过滤创建 Tool Provider
            dev.langchain4j.mcp.McpToolProvider toolProvider = mcpProvider.createToolProviderWithMapping(
                mcpClient, 
                mappingConfig, 
                safeTools
            );
            
            System.out.println("只暴露以下安全工具（只读操作）:");
            for (String tool : safeTools) {
                System.out.println("  - " + tool);
            }
            System.out.println("这些工具的名称已映射为中文");
            
            mcpClient.close();
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例 4: 使用工具规范映射修改工具描述
     * 为工具添加更详细的中文描述
     */
    public static void example4_ToolSpecMapping() {
        System.out.println("\n=== 示例 4: 工具规范映射（修改工具描述） ===");
        
        try {
            AllMcpToolProvider mcpProvider = new AllMcpToolProvider();
            McpTransport transport = mcpProvider.createTransport();
            McpClient mcpClient = mcpProvider.createMcpClient(transport);
            Thread.sleep(2000);
            
            // 创建映射配置
            McpToolMappingConfig mappingConfig = new McpToolMappingConfig();
            
            // 添加工具描述映射
            mappingConfig.addToolDescription("send_http1_request", 
                "发送 HTTP/1.1 请求到指定目标。用于安全测试和漏洞验证。");
            mappingConfig.addToolDescription("get_proxy_http_history", 
                "获取 Burp Proxy 捕获的所有 HTTP 请求历史记录。支持分页查询。");
            
            // 设置工具规范映射函数（如果需要修改其他属性）
            mappingConfig.setToolSpecMapper((client, toolSpec) -> {
                // 这里可以修改工具规范的任何属性
                // 注意：ToolSpecification 是不可变的，需要使用 builder 创建新实例
                // 这里只是示例，实际使用时需要根据 LangChain4j API 创建新的 ToolSpecification
                
                String toolName = toolSpec.name();
                String description = mappingConfig.getToolDescriptionMapping().get(toolName);
                
                if (description != null) {
                    // 如果有自定义描述，使用它
                    // 实际实现需要使用 ToolSpecification.builder() 创建新实例
                    System.out.println("工具 " + toolName + " 的描述: " + description);
                }
                
                return toolSpec; // 返回原始规范（实际应该返回修改后的规范）
            });
            
            dev.langchain4j.mcp.McpToolProvider toolProvider = mcpProvider.createToolProviderWithMapping(mcpClient, mappingConfig);
            
            System.out.println("已为工具添加详细的中文描述");
            
            mcpClient.close();
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例 5: 在 AI Service 中使用映射后的工具
     * 展示如何将映射后的工具绑定到 AI 服务
     */
    public static void example5_UseWithAIService() {
        System.out.println("\n=== 示例 5: 在 AI Service 中使用映射后的工具 ===");
        
        try {
            AllMcpToolProvider mcpProvider = new AllMcpToolProvider();
            McpTransport transport = mcpProvider.createTransport();
            McpClient mcpClient = mcpProvider.createMcpClient(transport);
            Thread.sleep(2000);
            
            // 创建完整的 Burp MCP 映射配置
            McpToolMappingConfig mappingConfig = McpToolMappingConfig.createBurpMapping();
            
            // 创建 Tool Provider（带映射）
            dev.langchain4j.mcp.McpToolProvider toolProvider = mcpProvider.createToolProviderWithMapping(mcpClient, mappingConfig);
            
            // 在 AI Service 中使用（示例代码，需要实际的 ChatModel）
            /*
            ChatModel chatModel = ...; // 你的 ChatModel 实例
            
            Assistant assistant = AiServices.builder(Assistant.class)
                    .chatModel(chatModel)
                    .toolProvider(toolProvider)  // 使用映射后的工具提供者
                    .build();
            
            // 现在 AI 可以使用中文名称的工具了
            String response = assistant.chat("请查看最近的代理历史记录");
            */
            
            System.out.println("Tool Provider 已创建，可以绑定到 AI Service");
            System.out.println("AI 将使用中文名称的工具，例如：");
            System.out.println("  - 发送HTTP请求");
            System.out.println("  - 获取代理HTTP历史");
            System.out.println("  - URL编码");
            
            mcpClient.close();
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 主方法：运行所有示例
     */
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Burp MCP 工具映射示例");
        System.out.println("参考文档: https://docs.langchain4j.dev/tutorials/mcp");
        System.out.println("========================================\n");
        
        // 注意：运行示例前请确保 Burp MCP Server 正在运行
        // 默认地址: http://127.0.0.1:9876/sse
        
        // 运行示例（注释掉不需要的示例）
        // example1_FullMapping();
        // example2_CustomNameMapping();
        // example3_FilteredAndMapped();
        // example4_ToolSpecMapping();
        // example5_UseWithAIService();
        
        System.out.println("\n提示：取消注释上面的示例方法来运行它们");
        System.out.println("确保 Burp MCP Server 正在运行（http://127.0.0.1:9876/sse）");
    }
}

