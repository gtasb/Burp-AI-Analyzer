package com.ai.analyzer.Tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Burp工具定义类
 * 定义AI助手可以调用的Burp工具列表
 */
public class ToolDefinitions {
    
    /**
     * 获取所有可用的Burp工具定义
     * 返回OpenAI格式的工具定义JSON
     */
    public static JsonArray getBurpTools() {
        JsonArray tools = new JsonArray();
        
        // 1. 发送HTTP请求工具
        tools.add(createTool(
            "send_http_request",
            "发送HTTP请求到指定目标并返回响应。用于验证漏洞或测试payload。示例：发送SQL注入payload测试是否存在注入点。",
            createProperties(
                "targetHostname", "string", "目标主机名，例如：example.com",
                "targetPort", "integer", "目标端口号，例如：80 或 443",
                "usesHttps", "boolean", "是否使用HTTPS，true表示HTTPS，false表示HTTP",
                "method", "string", "HTTP方法，例如：GET、POST、PUT、DELETE",
                "path", "string", "请求路径，例如：/api/login 或 /index.php?id=1",
                "headers", "object", "HTTP头信息（JSON对象），例如：{\"Content-Type\": \"application/json\", \"Authorization\": \"Bearer token\"}",
                "body", "string", "请求体内容（可选），用于POST/PUT请求"
            ),
            createRequired("targetHostname", "targetPort", "usesHttps", "method", "path")
        ));
        
/*         // 2. 创建Repeater标签页
        tools.add(createTool(
            "create_repeater_tab",
            "在Burp Repeater中创建一个新标签页，用于手动修改和重放请求。requestContent必须是完整的HTTP请求，包括请求行、请求头和请求体（如果有）。",
            createProperties(
                "targetHostname", "string", "目标主机名，例如：example.com",
                "targetPort", "integer", "目标端口号，例如：80 或 443",
                "usesHttps", "boolean", "是否使用HTTPS，true表示HTTPS，false表示HTTP",
                "requestContent", "string", "完整的HTTP请求内容，格式：\"GET /path HTTP/1.1\\r\\nHost: example.com\\r\\nHeader: value\\r\\n\\r\\nbody\"",
                "tabName", "string", "标签页名称（可选），例如：SQLi_Test"
            ),
            createRequired("targetHostname", "targetPort", "usesHttps", "requestContent")
        )); */
        
/*         // 3. 发送到Intruder
        tools.add(createTool(
            "send_to_intruder",
            "将HTTP请求发送到Burp Intruder，用于批量测试。requestContent必须是完整的HTTP请求。",
            createProperties(
                "targetHostname", "string", "目标主机名，例如：example.com",
                "targetPort", "integer", "目标端口号，例如：80 或 443",
                "usesHttps", "boolean", "是否使用HTTPS，true表示HTTPS，false表示HTTP",
                "requestContent", "string", "完整的HTTP请求内容，格式：\"GET /path HTTP/1.1\\r\\nHost: example.com\\r\\nHeader: value\\r\\n\\r\\nbody\"",
                "tabName", "string", "标签页名称（可选），例如：Fuzz_Test"
            ),
            createRequired("targetHostname", "targetPort", "usesHttps", "requestContent")
        )); */
        
/*         // 4. 生成随机字符串
        tools.add(createTool(
            "generate_random_string",
            "生成指定长度的随机字符串。用于构造测试payload，如生成随机token或测试字符串。如果不提供characterSet，将使用默认字符集。",
            createProperties(
                "length", "integer", "字符串长度，例如：10",
                "characterSet", "string", "可选字符集，例如：abcdefghijklmnopqrstuvwxyz0123456789 或 abc123。如果不提供，将使用默认字符集"
            ),
            createRequired("length")
        )); */
        
        // 5. 获取代理历史记录
        tools.add(createTool(
            "get_proxy_history",
            "获取Burp代理历史记录。用于查看最近的HTTP请求和响应。",
            createProperties(
                "count", "integer", "返回的记录数量，建议值：10-50",
                "offset", "integer", "偏移量，从0开始，例如：0表示从第一条开始",
                "regex", "string", "可选的正则表达式过滤，例如：\".*login.*\" 用于过滤包含login的记录"
            ),
            createRequired("count", "offset")
        ));
        
        // 10. 获取扫描器问题
/*         tools.add(createTool(
            "get_scanner_issues",
            "获取Burp扫描器发现的安全问题。用于查看扫描结果和安全漏洞。",
            createProperties(
                "count", "integer", "返回的问题数量，建议值：10-50",
                "offset", "integer", "偏移量，从0开始，例如：0表示从第一个问题开始"
            ),
            createRequired("count", "offset")
        )); */
        
/*         // 6. 设置代理拦截状态
        tools.add(createTool(
            "set_proxy_intercept",
            "启用或禁用Burp代理拦截。用于控制是否拦截HTTP请求。",
            createProperties("intercepting", "boolean", "是否启用拦截，true表示启用拦截，false表示禁用拦截"),
            createRequired("intercepting")
        )); */
        
        // 12. 获取用户配置
/*         tools.add(createTool(
            "get_user_options",
            "获取Burp用户级配置选项的JSON格式。用于查看Burp的配置信息。",
            createProperties(),
            createRequired()
        )); */
        
        return tools;
    }
    
    private static JsonObject createTool(String name, String description, JsonObject properties, JsonArray required) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);
        
        function.add("parameters", parameters);
        tool.add("function", function);
        
        return tool;
    }
    
    private static JsonObject createProperties(String... props) {
        JsonObject properties = new JsonObject();
        // props格式: name1, type1, description1, name2, type2, description2, ...
        for (int i = 0; i < props.length; i += 3) {
            if (i + 2 >= props.length) break;
            String name = props[i];
            String type = props[i + 1];
            String description = props[i + 2];
            
            JsonObject prop = new JsonObject();
            prop.addProperty("type", type);
            prop.addProperty("description", description);
            properties.add(name, prop);
        }
        return properties;
    }
    
    private static JsonArray createRequired(String... fields) {
        JsonArray required = new JsonArray();
        for (String field : fields) {
            required.add(field);
        }
        return required;
    }
}

