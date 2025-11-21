package com.ai.analyzer.mcpClient;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiFunction;

/**
 * MCP 工具映射配置
 * 用于配置工具名称映射和工具规范映射
 * 
 * 参考文档: https://docs.langchain4j.dev/tutorials/mcp
 */
public class McpToolMappingConfig {
    
    /**
     * 工具名称映射表
     * Key: 原始工具名称, Value: 映射后的工具名称
     */
    private final Map<String, String> toolNameMapping = new HashMap<>();
    
    /**
     * 工具规范映射函数
     * 可以修改工具的描述、参数等
     */
    private BiFunction<McpClient, ToolSpecification, ToolSpecification> toolSpecMapper = null;
    
    /**
     * 工具中文描述映射表
     * Key: 工具名称, Value: 中文描述
     */
    private final Map<String, String> toolDescriptionMapping = new HashMap<>();
    
    /**
     * 添加工具名称映射
     * @param originalName 原始工具名称
     * @param mappedName 映射后的工具名称
     */
    public void addToolNameMapping(String originalName, String mappedName) {
        toolNameMapping.put(originalName, mappedName);
    }
    
    /**
     * 批量添加工具名称映射
     * @param mappings 映射表
     */
    public void addToolNameMappings(Map<String, String> mappings) {
        toolNameMapping.putAll(mappings);
    }
    
    /**
     * 添加工具描述映射
     * @param toolName 工具名称
     * @param description 中文描述
     */
    public void addToolDescription(String toolName, String description) {
        toolDescriptionMapping.put(toolName, description);
    }
    
    /**
     * 批量添加工具描述映射
     * @param descriptions 描述映射表
     */
    public void addToolDescriptions(Map<String, String> descriptions) {
        toolDescriptionMapping.putAll(descriptions);
    }
    
    /**
     * 设置工具规范映射函数
     * @param mapper 映射函数，接收 (McpClient, ToolSpecification) 返回新的 ToolSpecification
     */
    public void setToolSpecMapper(BiFunction<McpClient, ToolSpecification, ToolSpecification> mapper) {
        this.toolSpecMapper = mapper;
    }
    
    /**
     * 获取工具名称映射函数
     * 用于 McpToolProvider.builder().toolNameMapper()
     */
    public BiFunction<McpClient, ToolSpecification, String> getToolNameMapper() {
        return (client, toolSpec) -> {
            String originalName = toolSpec.name();
            // 先检查是否有手动配置的映射
            String mappedName = toolNameMapping.get(originalName);
            if (mappedName != null) {
                return mappedName;
            }
            // 如果没有配置映射，返回原始名称
            return originalName;
        };
    }
    
    /**
     * 获取工具规范映射函数
     * 用于修改工具的名称、描述、参数等
     * 根据 LangChain4j 文档：https://docs.langchain4j.dev/tutorials/mcp#mcp-tool-specification-mapping
     * 可以使用 toolSpecificationMapper 修改工具规范
     * 
     * 注意：由于不能同时设置 toolNameMapper 和 toolSpecificationMapper，
     * 因此此方法同时处理名称映射和描述映射
     */
    public BiFunction<McpClient, ToolSpecification, ToolSpecification> getToolSpecMapper() {
        return (client, toolSpec) -> {
            // 如果配置了自定义映射函数，优先使用
            if (toolSpecMapper != null) {
                return toolSpecMapper.apply(client, toolSpec);
            }
            
            // 应用名称映射和描述映射
            String originalName = toolSpec.name();
            String mappedName = toolNameMapping.get(originalName);
            String customDescription = toolDescriptionMapping.get(originalName);
            
            // 如果既没有名称映射也没有描述映射，返回原始规范
            if (mappedName == null && (customDescription == null || customDescription.isEmpty())) {
                return toolSpec;
            }
            
            // 使用 ToolSpecification.toBuilder() 创建新的工具规范
            // 根据 LangChain4j 文档，ToolSpecification 应该支持 toBuilder() 方法
            try {
                var builder = toolSpec.toBuilder();
                
                // 如果有名称映射，应用名称映射
                if (mappedName != null) {
                    builder.name(mappedName);
                }
                
                // 如果有描述映射，应用描述映射
                if (customDescription != null && !customDescription.isEmpty()) {
                    builder.description(customDescription);
                }
                
                return builder.build();
            } catch (Exception e) {
                // 如果 toBuilder() 方法不可用，返回原始规范
                // 映射可能不会生效，但不会导致错误
                return toolSpec;
            }
        };
    }
    
    /**
     * 创建完整的 Burp MCP 工具映射配置
     * 包含所有 22 个工具的中文名称映射和描述映射
     */
    public static McpToolMappingConfig createBurpMapping() {
        McpToolMappingConfig config = new McpToolMappingConfig();
        
        // ========== 工具名称映射（将英文名称映射为中文友好名称） ==========
        // 暂时注释掉名称映射，保持工具原始英文名称
        /*
        Map<String, String> nameMappings = new HashMap<>();
        
        // HTTP 请求功能
        nameMappings.put("send_http1_request", "发送HTTP1请求");
        nameMappings.put("send_http2_request", "发送HTTP2请求");
        nameMappings.put("create_repeater_tab", "创建Repeater标签页");
        nameMappings.put("send_to_intruder", "发送到Intruder");
        
        // 编码/解码工具
        nameMappings.put("url_encode", "URL编码");
        nameMappings.put("url_decode", "URL解码");
        nameMappings.put("base64_encode", "Base64编码");
        nameMappings.put("base64_decode", "Base64解码");
        nameMappings.put("generate_random_string", "生成随机字符串");
        
        // 配置管理
        nameMappings.put("output_project_options", "输出项目配置");
        nameMappings.put("output_user_options", "输出用户配置");
        nameMappings.put("set_project_options", "设置项目配置");
        nameMappings.put("set_user_options", "设置用户配置");
        
        // 代理功能
        nameMappings.put("get_proxy_http_history", "获取代理HTTP历史");
        nameMappings.put("get_proxy_http_history_regex", "按正则获取代理HTTP历史");
        nameMappings.put("get_proxy_websocket_history", "获取代理WebSocket历史");
        nameMappings.put("get_proxy_websocket_history_regex", "按正则获取代理WebSocket历史");
        nameMappings.put("set_proxy_intercept_state", "设置代理拦截状态");
        
        // 扫描器功能
        nameMappings.put("get_scanner_issues", "获取扫描器问题");
        
        // 任务执行引擎
        nameMappings.put("set_task_execution_engine_state", "设置任务执行引擎状态");
        
        // 编辑器功能
        nameMappings.put("get_active_editor_contents", "获取活动编辑器内容");
        nameMappings.put("set_active_editor_contents", "设置活动编辑器内容");
        
        config.addToolNameMappings(nameMappings);
        */
        
        // ========== 工具描述映射（为每个工具添加详细的中文描述） ==========
        Map<String, String> descriptionMappings = new HashMap<>();
        
        // HTTP 请求功能
        descriptionMappings.put("send_http1_request", "发送 HTTP/1.1 请求到指定目标并返回响应。用于测试和验证漏洞。\n" +
                "参数：\n" +
                "- targetHostname: 目标主机名\n" +
                "- targetPort: 目标端口号\n" +
                "- usesHttps: 是否使用HTTPS\n" +
                "- requestContent: 请求内容\n" +
                "- tabName: 标签页名称，尽量使用中文命名");

        descriptionMappings.put("send_http2_request", "发送 HTTP/2 请求到指定目标并返回响应。支持 HTTP/2 协议特性。\n" +
                "参数：\n" +
                "- targetHostname: 目标主机名\n" +
                "- targetPort: 目标端口号\n" +
                "- usesHttps: 是否使用HTTPS\n" +
                "- requestContent: 请求内容\n" +
                "- tabName: 标签页名称，尽量使用中文命名");

        descriptionMappings.put("create_repeater_tab", "在 Burp Repeater 中创建新标签页，用于手动修改和重放请求。\n" + 
                "优先级：\n" + 
                "低于send_http1_request和send_http2_request，因为create_repeater_tab是手动创建标签页，而send_http1_request和send_http2_request是自动发送请求。\n" +
                "可以搭配get_active_editor_contents使用，获取当前活动消息编辑器的内容，然后使用set_active_editor_contents设置请求内容。\n" +
                "参数：\n" +
                "- targetHostname: 目标主机名\n" +
                "- targetPort: 目标端口号\n" +
                "- usesHttps: 是否使用HTTPS\n" +
                "- requestContent: 请求内容\n" +
                "- tabName: 标签页名称，尽量使用中文命名");

        descriptionMappings.put("send_to_intruder", "将请求发送到 Burp Intruder，多用于批量爆破payload。\n" +
                "优先级：\n" + 
                "低于send_http1_request和send_http2_request\n" +
                "可以搭配get_active_editor_contents使用，获取当前活动消息编辑器的内容，然后使用set_active_editor_contents设置请求内容。\n" +
                "参数：\n" +
                "- targetHostname: 目标主机名\n" +
                "- targetPort: 目标端口号\n" +
                "- usesHttps: 是否使用HTTPS\n" +
                "- requestContent: 请求内容\n" +
                "- tabName: 标签页名称，尽量使用中文命名");
        
        // 编码/解码工具
        descriptionMappings.put("url_encode", "对字符串进行 URL 编码，将特殊字符转换为 URL 格式。");
        descriptionMappings.put("url_decode", "对 URL 编码的字符串进行解码，还原原始字符串。");
        descriptionMappings.put("base64_encode", "将字符串进行 Base64 编码。");
        descriptionMappings.put("base64_decode", "将 Base64 编码的字符串进行解码。");
        descriptionMappings.put("generate_random_string", "生成指定长度和字符集的随机字符串，用于fuzz。");
        
        // 配置管理
        descriptionMappings.put("output_project_options", "以 JSON 格式输出当前项目的所有配置选项。");
        descriptionMappings.put("output_user_options", "以 JSON 格式输出当前用户的所有配置选项。");
        descriptionMappings.put("set_project_options", "设置项目级配置选项（JSON 格式）。");
        descriptionMappings.put("set_user_options", "设置用户级配置选项（JSON 格式）。");
        
        // 代理功能
        descriptionMappings.put("get_proxy_http_history", "获取 Burp Proxy 的 HTTP 请求历史记录。");
        descriptionMappings.put("get_proxy_http_history_regex", "使用正则表达式过滤并获取代理 HTTP 历史记录。");
        descriptionMappings.put("get_proxy_websocket_history", "获取 Burp Proxy 的 WebSocket 消息历史记录。");
        descriptionMappings.put("get_proxy_websocket_history_regex", "使用正则表达式过滤并获取代理 WebSocket 历史记录。");
        descriptionMappings.put("set_proxy_intercept_state", "启用或禁用 Burp Proxy 的请求/响应拦截功能。");
        
        // 扫描器功能
        descriptionMappings.put("get_scanner_issues", "获取 Burp Scanner 发现的安全问题列表。");
        
        // 任务执行引擎
        descriptionMappings.put("set_task_execution_engine_state", "暂停或恢复 Burp 的任务执行引擎。");
        
        // 编辑器功能
        descriptionMappings.put("get_active_editor_contents", "获取当前活动消息编辑器的内容。");
        descriptionMappings.put("set_active_editor_contents", "设置当前活动消息编辑器的内容。");
        
        config.addToolDescriptions(descriptionMappings);
        
        // ========== 工具规范映射（可选：如果需要修改工具规范的其他属性） ==========
        // 注意：ToolSpecification 是不可变的，如果需要修改，需要使用 builder 创建新实例
        // 这里我们主要使用描述映射，实际的规范修改可以在需要时通过 builder 实现
        
        return config;
    }
    
    /**
     * 创建默认的 Burp MCP 工具映射配置（简化版）
     * 仅包含工具名称映射，不包含详细描述
     */
    public static McpToolMappingConfig createDefaultBurpMapping() {
        return createBurpMapping();
    }
    
    /**
     * 创建空的映射配置（不进行任何映射）
     */
    public static McpToolMappingConfig createEmpty() {
        return new McpToolMappingConfig();
    }
    
    /**
     * 获取工具描述映射表（用于外部查询）
     */
    public Map<String, String> getToolDescriptionMapping() {
        return new HashMap<>(toolDescriptionMapping);
    }
}

