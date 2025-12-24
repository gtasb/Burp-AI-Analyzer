# AI漏洞分析助手 - Burp Suite插件 🎯

> 🚀 让AI成为你的安全测试助手！基于 LangChain4j + 通义千问 + MCP 协议的智能渗透测试工具

## ✨ 功能特性

### 🤖 AI 智能分析
- **流式输出** - 实时显示 AI 分析过程，体验流畅
- **深度思考模式** - 启用后 AI 会更深入地分析安全问题
- **联网搜索** - 可选启用网络搜索获取最新漏洞信息
- **上下文记忆** - 支持多轮对话，AI 记住之前的分析内容

### 🔧 MCP 工具调用
- **Burp MCP** - 直接操控 Burp Suite，自动发送请求、创建 Repeater 标签页
- **RAG MCP** - 接入本地知识库，结合安全文档进行分析
- **Chrome MCP** - 控制浏览器进行自动化测试（规划中）

### 📊 专业渲染
- **Markdown 渲染** - 支持标题、列表、代码块、链接等格式
- **工具执行可视化** - 清晰展示 AI 调用了哪些工具、传入了什么参数
- **语法高亮** - 代码块使用深色主题，便于阅读

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 运行环境 | Java 21 (LTS) |
| Burp API | Montoya API |
| AI 框架 | LangChain4j |
| 大模型 | 通义千问 (qwen-max) |
| 工具协议 | Model Context Protocol (MCP) |
| GUI | Swing |

## 📦 快速开始

### 1. 编译插件

```bash
mvn clean package
```

编译完成后，在 `target` 目录下生成 `ai-analyzer-1.0.0-jar-with-dependencies.jar`

### 2. 加载到 Burp Suite

1. 打开 Burp Suite
2. 进入 `Extensions` → `Installed`
3. 点击 `Add` → 选择 `Java` 类型
4. 选择编译好的 jar 文件
5. 点击 `Next` 完成加载

### 3. 配置 API

1. 在 Burp 中找到 `AI分析` 标签页
2. 填写配置：
   - **API Key**: 你的通义千问 API Key
   - **模型**: 默认 `qwen-max`（推荐）

### 4. 开始使用

1. 在 Proxy/Repeater/Target 中右键请求
2. 选择 `发送到AI分析`
3. 输入分析提示词，点击 `开始分析`

## 🔗 获取 API Key

1. 访问 [阿里云 DashScope 控制台](https://dashscope.console.aliyun.com/)
2. 登录/注册阿里云账号
3. 开通 DashScope 服务
4. 创建 API Key 并复制

## 🌟 使用示例

### SQL 注入检测
```
提示词：分析这个请求中是否存在 SQL 注入漏洞，给出测试 payload

AI 分析结果：
✓ 发现注入点：id 参数
✓ 数据库类型：MySQL
✓ 推荐 payload: ' OR '1'='1, 1' UNION SELECT NULL--
✓ WAF 绕过：使用 /**/ 注释、URL 编码
```

### XSS 漏洞分析
```
提示词：检查 XSS 攻击面

AI 分析结果：
✓ 输入点：search 参数
✓ 输出上下文：HTML 标签内
✓ 推荐 payload: <script>alert(1)</script>
✓ 需测试 CSP 绕过
```

### MCP 自动化测试
```
提示词：获取最近的代理历史记录，分析安全风险

AI 执行：
▶ get_proxy_http_history
   count=10
   
分析结果：发现敏感信息泄露...
```

## ⚙️ 高级配置

### MCP 工具配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 启用 Burp MCP | 允许 AI 操控 Burp Suite | 关闭 |
| Burp MCP 地址 | MCP 服务器地址 | `http://127.0.0.1:9876/sse` |
| 启用 RAG MCP | 接入本地知识库 | 关闭 |
| RAG 文档路径 | 知识库文档目录 | - |

### 模型参数

| 参数 | 说明 |
|------|------|
| 启用深度思考 | AI 进行更深入的推理分析 |
| 启用网络搜索 | 允许 AI 搜索最新漏洞信息 |

## 📋 环境要求

- Java 21 或更高版本
- Burp Suite Professional / Community Edition
- 通义千问 API Key

## 🔨 开发构建

```bash
# Maven 构建
mvn clean package

# Gradle 构建（可选）
gradle clean jar
```

详细说明请查看 [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) 和 [QUICKSTART.md](QUICKSTART.md)

## 📝 更新日志

### v1.0.0
- ✅ 基础 AI 分析功能
- ✅ 流式输出支持
- ✅ MCP 工具调用（Burp MCP、RAG MCP）
- ✅ Markdown 渲染
- ✅ 工具执行可视化

---

**Happy Hacking! 🎯🔍**

*让 AI 成为你的安全测试助手，发现更多漏洞！*
