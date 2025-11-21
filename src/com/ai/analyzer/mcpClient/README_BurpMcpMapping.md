# Burp MCP 工具映射使用指南

## 概述

本模块实现了 Burp MCP 工具的名称映射和规范映射功能，参考 [LangChain4j MCP 文档](https://docs.langchain4j.dev/tutorials/mcp)。

## 功能特性

### 1. 工具名称映射
将 Burp MCP 的英文工具名称映射为中文友好名称，例如：
- `send_http1_request` → `发送HTTP1请求`
- `get_proxy_http_history` → `获取代理HTTP历史`
- `url_encode` → `URL编码`

### 2. 工具规范映射
为每个工具添加详细的中文描述，帮助 AI 更好地理解和使用工具。

### 3. 工具过滤
可以只暴露需要的工具给 AI，提高安全性和减少混淆。

## 快速开始

### 基本使用

```java
// 1. 创建 MCP 连接
MCPtoolProvider mcpProvider = new MCPtoolProvider();
McpTransport transport = mcpProvider.createTransport();
McpClient mcpClient = mcpProvider.createMcpClient(transport);
Thread.sleep(2000); // 等待连接稳定

// 2. 创建完整的 Burp MCP 映射配置
McpToolMappingConfig mappingConfig = McpToolMappingConfig.createBurpMapping();

// 3. 使用映射配置创建 Tool Provider
McpToolProvider toolProvider = mcpProvider.createToolProvider(mcpClient, mappingConfig);

// 4. 在 AI Service 中使用
Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(chatModel)
        .toolProvider(toolProvider)  // 使用映射后的工具
        .build();
```

### 自定义映射

```java
// 创建自定义映射配置
McpToolMappingConfig mappingConfig = new McpToolMappingConfig();

// 只映射需要的工具
mappingConfig.addToolNameMapping("send_http1_request", "发送HTTP请求");
mappingConfig.addToolNameMapping("get_proxy_http_history", "查看代理历史");

// 添加工具描述
mappingConfig.addToolDescription("send_http1_request", 
    "发送 HTTP/1.1 请求到指定目标。用于安全测试和漏洞验证。");

// 使用自定义映射
McpToolProvider toolProvider = mcpProvider.createToolProvider(mcpClient, mappingConfig);
```

### 工具过滤

```java
// 只暴露安全的工具（只读操作）
String[] safeTools = {
    "get_proxy_http_history",
    "get_scanner_issues",
    "url_encode",
    "url_decode"
};

// 使用映射配置和工具过滤
McpToolProvider toolProvider = mcpProvider.createToolProvider(
    mcpClient, 
    mappingConfig, 
    safeTools  // 只暴露这些工具
);
```

## 完整的工具映射列表

### HTTP 请求功能
- `send_http1_request` → `发送HTTP1请求`
- `send_http2_request` → `发送HTTP2请求`
- `create_repeater_tab` → `创建Repeater标签页`
- `send_to_intruder` → `发送到Intruder`

### 编码/解码工具
- `url_encode` → `URL编码`
- `url_decode` → `URL解码`
- `base64_encode` → `Base64编码`
- `base64_decode` → `Base64解码`
- `generate_random_string` → `生成随机字符串`

### 配置管理
- `output_project_options` → `输出项目配置`
- `output_user_options` → `输出用户配置`
- `set_project_options` → `设置项目配置`
- `set_user_options` → `设置用户配置`

### 代理功能
- `get_proxy_http_history` → `获取代理HTTP历史`
- `get_proxy_http_history_regex` → `按正则获取代理HTTP历史`
- `get_proxy_websocket_history` → `获取代理WebSocket历史`
- `get_proxy_websocket_history_regex` → `按正则获取代理WebSocket历史`
- `set_proxy_intercept_state` → `设置代理拦截状态`

### 扫描器功能
- `get_scanner_issues` → `获取扫描器问题`

### 任务执行引擎
- `set_task_execution_engine_state` → `设置任务执行引擎状态`

### 编辑器功能
- `get_active_editor_contents` → `获取活动编辑器内容`
- `set_active_editor_contents` → `设置活动编辑器内容`

## 参考文档

- [LangChain4j MCP 教程](https://docs.langchain4j.dev/tutorials/mcp)
- [MCP 官方文档](https://modelcontextprotocol.io/)

## 示例代码

详细示例请参考 `BurpMcpMappingExample.java` 文件。

