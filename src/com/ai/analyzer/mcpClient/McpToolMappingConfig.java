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
        // 根据 Burp MCP Server 源码 (Tools.kt) 补全所有工具的参数和功能说明
        Map<String, String> descriptionMappings = new HashMap<>();
        
        // HTTP 请求功能
        descriptionMappings.put("send_http1_request", "发送 HTTP/1.1 请求到指定目标并返回响应。\n" +
                "【使用时机】：\n" +
                "- 分析发现高/中危漏洞后，需要验证漏洞是否存在\n" +
                "- 用户要求测试特定的 payload\n" +
                "- 需要探测接口响应特征\n" +
                "【决策条件】：\n" +
                "- 已识别出可测试的风险点（如SQL注入、XSS、命令注入等）\n" +
                "- 目标主机、端口、协议信息明确\n" +
                "【强制后续】：调用后必须调用 create_repeater_tab\n" +
                "参数：\n" +
                "- content: 完整的 HTTP 请求内容（包括请求行、请求头和请求体）\n" + 
                "- targetHostname: 目标主机名（域名或 IP 地址）\n" +
                "- targetPort: 目标端口号（如 80, 443, 8080 等）\n" +
                "- usesHttps: 是否使用 HTTPS 协议（true/false）\n" +
                "【🚨 CRITICAL - HTTP 请求格式要求（必须严格遵守）】：\n" +
                "**格式规则（按顺序检查）：**\n" +
                "1. 请求行格式：`METHOD /path?query HTTP/1.1\\r\\n`（协议版本必须在URL之后，用空格分隔）\n" +
                "2. 请求头格式：每个请求头一行，以 `\\r\\n` 结尾\n" +
                "3. **空行要求（最重要）**：请求头块末尾**必须**有一个空行（`\\r\\n\\r\\n`）\n" +
                "4. 请求体（如果有）：空行后直接跟请求体内容\n" +
                "**完整示例（GET请求）：**\n" +
                "```\n" +
                "GET /app/weborders.do?param=test HTTP/1.1\\r\\n" +
                "Host: 222.73.207.85:8080\\r\\n" +
                "Cookie: JSESSIONID=xxx\\r\\n" +
                "\\r\\n" +
                "```\n" +
                "**完整示例（POST请求）：**\n" +
                "```\n" +
                "POST /api/login HTTP/1.1\\r\\n" +
                "Host: example.com\\r\\n" +
                "Content-Type: application/json\\r\\n" +
                "Content-Length: 25\\r\\n" +
                "\\r\\n" +
                "{\\\"username\\\":\\\"admin\\\"}\n" +
                "```\n" +
                "**常见错误（必须避免）：**\n" +
                "- ❌ 请求头后没有空行 → 会导致 \"The HTTP header block doesn't have a blank line at the end\" 错误\n" +
                "- ❌ 协议版本在查询参数中 → 会导致 505 错误\n" +
                "- ❌ 使用单个 `\\n` 而不是 `\\r\\n` → 可能导致格式错误\n" +
                "**检查清单（调用前必须验证）：**\n" +
                "1. ✅ 请求行格式正确（METHOD /path HTTP/1.1）\n" +
                "2. ✅ 所有请求头以 `\\r\\n` 结尾\n" +
                "3. ✅ 最后一个请求头后有一个空行（`\\r\\n\\r\\n`）\n" +
                "4. ✅ 如果有请求体，空行后直接跟内容\n" +
                "注意：请求内容中的 \\n 会自动转换为 \\r\\n，但**必须确保头块末尾有空行**。");

        descriptionMappings.put("send_http2_request", "发送 HTTP/2 请求到指定目标并返回响应。支持 HTTP/2 协议特性（如伪头部字段）。\n" +
                "参数：\n" +
                "- pseudoHeaders: HTTP/2 伪头部字段（Map<String, String>），如 :method, :path, :scheme, :authority\n" +
                "- headers: 普通 HTTP 头部字段（Map<String, String>）\n" +
                "- requestBody: 请求体内容（字符串）\n" +
                "- targetHostname: 目标主机名（域名或 IP 地址）\n" +
                "- targetPort: 目标端口号（如 80, 443, 8080 等）\n" +
                "- usesHttps: 是否使用 HTTPS 协议（true/false）\n" +
                "注意：不要将头部字段传递给 body 参数。伪头部字段会自动添加 : 前缀。");

        descriptionMappings.put("create_repeater_tab", "【智能决策工具】将请求发送到 Burp Repeater 供人类验证。\n" +
                "【调用决策规则 - 必须遵循】：\n" +
                "- ✅ 发现漏洞/成功POC → **必须调用**（人类必须确认）\n" +
                "- ⚠️ 疑似漏洞/不确定 → 建议调用（需人类判断）\n" +
                "- ❌ 确认无漏洞 → **不调用**（减少噪音）\n" +
                "【特性】：异步执行，无需等待返回结果。\n" +
                "参数：content(HTTP请求), targetHostname, targetPort, usesHttps, tabName(建议用漏洞类型命名)");

        descriptionMappings.put("send_to_intruder", "【辅助工具】将请求发送到 Burp Intruder 进行批量测试。\n" +
                "【使用时机】：\n" +
                "- 需要批量 fuzz 测试（爆破、模糊测试）\n" +
                "- 用户明确要求发送到 Intruder\n" +
                "【特性】：异步执行，无需等待返回结果。\n" +
                "参数：content(HTTP请求), targetHostname, targetPort, usesHttps, tabName(可选)");
        
        // 编码/解码工具
        descriptionMappings.put("url_encode", "对字符串进行 URL 编码，将特殊字符转换为 URL 安全格式。\n" +
                "参数：\n" +
                "- content: 需要编码的字符串");
        descriptionMappings.put("url_decode", "对 URL 编码的字符串进行解码，还原原始字符串。\n" +
                "参数：\n" +
                "- content: 需要解码的 URL 编码字符串");
        descriptionMappings.put("base64_encode", "将字符串进行 Base64 编码。\n" +
                "参数：\n" +
                "- content: 需要编码的字符串");
        descriptionMappings.put("base64_decode", "将 Base64 编码的字符串进行解码。\n" +
                "参数：\n" +
                "- content: 需要解码的 Base64 编码字符串");
        descriptionMappings.put("generate_random_string", "生成指定长度和字符集的随机字符串，用于模糊测试和 payload 生成。\n" +
                "参数：\n" +
                "- length: 字符串长度（整数）\n" +
                "- characterSet: 字符集（字符串），例如 \"abcdefghijklmnopqrstuvwxyz0123456789\"");
        
        // 配置管理
        descriptionMappings.put("output_project_options", "以 JSON 格式输出当前项目级的所有配置选项。可用于确定可用配置选项的架构。\n" +
                "参数：无\n" +
                "返回：JSON 格式的配置对象");
        descriptionMappings.put("output_user_options", "以 JSON 格式输出当前用户级的所有配置选项。可用于确定可用配置选项的架构。\n" +
                "参数：无\n" +
                "返回：JSON 格式的配置对象");
        descriptionMappings.put("set_project_options", "设置项目级配置选项（JSON 格式）。配置将与现有配置合并。\n" +
                "参数：\n" +
                "- json: JSON 格式的配置对象（必须包含顶级 'user_options' 对象）\n" +
                "注意：设置前请先使用 output_project_options 导出当前配置以了解架构。需要启用配置编辑功能。");
        descriptionMappings.put("set_user_options", "设置用户级配置选项（JSON 格式）。配置将与现有配置合并。\n" +
                "参数：\n" +
                "- json: JSON 格式的配置对象（必须包含顶级 'project_options' 对象）\n" +
                "注意：设置前请先使用 output_user_options 导出当前配置以了解架构。需要启用配置编辑功能。");
        
        // 代理功能
        descriptionMappings.put("get_proxy_http_history", "【首选工具】获取 Burp Proxy 的 HTTP 请求历史记录。\n" +
                "【优先使用此工具】：先用此工具获取概览，再决定是否需要正则过滤。\n" +
                "参数：count(数量,建议20), offset(起始索引,默认0)\n" +
                "注意：返回超过5000字符会被截断。");
        descriptionMappings.put("get_proxy_http_history_regex", "按正则过滤获取代理历史（单次调用）。\n" +
                "【效率警告】：每次调用都需要等待，请合并多个关键词到一个正则！\n" +
                "【正确用法】：\n" +
                "- 多关键词合并：regex=\".*(login|api|upload|admin).*\" （一次查询4个关键词）\n" +
                "- 单关键词：regex=\".*login.*\"\n" +
                "【错误用法】：\n" +
                "- ❌ 分别调用4次查询 login、api、upload、admin\n" +
                "- ❌ 使用通配符语法 *login*（应该用 .*login.*）\n" +
                "参数：regex(正则), count(数量,建议10), offset(默认0)");
        descriptionMappings.put("get_proxy_websocket_history", "获取 Burp Proxy 的 WebSocket 消息历史记录（支持分页）。\n" +
                "参数：\n" +
                "- count: 返回的记录数量（整数）。表示本次请求返回多少条记录。\n" +
                "- offset: 起始索引（整数，从 0 开始）。表示跳过前面的多少条记录，从第 offset 条记录开始返回。\n" +
                "分页示例：\n" +
                "  - offset=0, count=10: 返回第 1-10 条记录\n" +
                "  - offset=10, count=10: 返回第 11-20 条记录\n" +
                "注意：如果 offset 超出总记录数，返回 \"Reached end of items\"。返回的 JSON 内容如果超过 5000 字符会被截断。");
        descriptionMappings.put("get_proxy_websocket_history_regex", "使用正则表达式过滤并获取代理 WebSocket 历史记录（支持分页）。\n" +
                "参数：\n" +
                "- regex: 正则表达式（字符串），用于过滤历史记录\n" +
                "- count: 返回的记录数量（整数）。表示本次请求返回多少条匹配的记录。\n" +
                "- offset: 起始索引（整数，从 0 开始）。表示跳过前面的多少条匹配记录，从第 offset 条匹配记录开始返回。\n" +
                "分页示例：\n" +
                "  - offset=0, count=10: 返回匹配的前 10 条记录\n" +
                "  - offset=10, count=10: 返回匹配的第 11-20 条记录\n" +
                "注意：如果 offset 超出匹配记录数，返回 \"Reached end of items\"。返回的 JSON 内容如果超过 5000 字符会被截断。");
        descriptionMappings.put("set_proxy_intercept_state", "启用或禁用 Burp Proxy 的请求/响应拦截功能。\n" +
                "参数：\n" +
                "- intercepting: 是否启用拦截（布尔值，true 表示启用，false 表示禁用）\n" +
                "返回：操作结果消息");
        
        // 扫描器功能（仅 Professional 版本）
        descriptionMappings.put("get_scanner_issues", "获取 Burp Scanner 发现的安全问题列表（支持分页）。仅适用于 Burp Suite Professional 版本。\n" +
                "参数：\n" +
                "- count: 返回的问题数量（整数）。表示本次请求返回多少个安全问题。\n" +
                "- offset: 起始索引（整数，从 0 开始）。表示跳过前面的多少个安全问题，从第 offset 个问题开始返回。\n" +
                "分页示例：\n" +
                "  - offset=0, count=10: 返回前 10 个安全问题\n" +
                "  - offset=10, count=10: 返回第 11-20 个安全问题\n" +
                "返回：JSON 格式的问题列表。如果 offset 超出总问题数，返回 \"Reached end of items\"。");
        
        // 任务执行引擎
        descriptionMappings.put("set_task_execution_engine_state", "暂停或恢复 Burp 的任务执行引擎。可用于控制 Burp 的自动化任务执行。\n" +
                "参数：\n" +
                "- running: 是否运行（布尔值，true 表示运行，false 表示暂停）\n" +
                "返回：操作结果消息");
        
        // 编辑器功能
        descriptionMappings.put("get_active_editor_contents", "获取当前活动消息编辑器的内容。如果当前没有活动的编辑器，返回 \"<No active editor>\"。\n" +
                "参数：无\n" +
                "返回：编辑器中的文本内容\n" +
                "注意：仅当焦点在 Burp 窗口内的 JTextArea 时才能获取内容。");
        descriptionMappings.put("set_active_editor_contents", "设置当前活动消息编辑器的内容。\n" +
                "参数：\n" +
                "- text: 要设置的文本内容\n" +
                "返回：操作结果消息（如果编辑器不存在或不可编辑，返回错误消息）\n" +
                "注意：仅当焦点在 Burp 窗口内的可编辑 JTextArea 时才能设置内容。");
        
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

