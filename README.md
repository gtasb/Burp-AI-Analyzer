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

### 🎯 Skills 自定义技能
- **自定义指令** - 创建专属技能指导 AI 行为
- **可执行工具** - 定义本地命令/工具，让 AI 自动调用（如 nmap、sqlmap 等）
- **参数模板** - 灵活的参数配置，AI 智能填充

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
| RAG | rag-mcp-server |
| Skills | Anthropic Agent Skills 规范 |
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

## RAG MCP

RAG MCP 是一个本地知识库，用于存储和检索安全文档。

1. 安装RAG MCP Server

使用uvx（推荐）
```bash
# Install with uvx (comes with uv)
uvx rag-mcp
```

使用pip（需要配置好环境变量）
```bash
pip install rag-mcp
```

2. 配置 RAG MCP

文档我们用的是PayloadsAllTheThings

## 🎯 Skills 自定义技能

Skills 是用户自定义的技能系统，参考 [Anthropic Agent Skills](https://github.com/anthropics/skills) 规范实现。

### Skills 能做什么？

1. **自定义指令** - 创建专属的 AI 行为指导，如「SQL注入测试专家」「代码审计助手」
2. **可执行工具** - 定义本地命令行工具，AI 在需要时自动调用（如 nmap、sqlmap、nuclei 等）
3. **参数化执行** - 支持参数模板，AI 根据上下文智能填充参数

### 快速开始

1. 在插件的 **Skills** 标签页中启用 Skills 功能
2. 设置 Skills 目录路径（存放 SKILL.md 文件的目录）
3. 点击「创建示例 Skill」生成示例文件
4. 勾选启用想要使用的技能

### SKILL.md 格式

每个技能是一个文件夹，包含一个 `SKILL.md` 文件：

```
skills/
├── sql-injection-tester/
│   └── SKILL.md
├── network-scanner/
│   └── SKILL.md
└── code-auditor/
    └── SKILL.md
```

#### 基础格式（仅指令）

```yaml
---
name: sql-injection-tester
description: SQL注入测试专家技能
---

# SQL注入测试专家

当用户提供 HTTP 请求时，按以下流程进行 SQL 注入测试：

## 测试流程
1. 识别所有可能的注入点（GET/POST 参数、Cookie、Header）
2. 按危害程度排序测试
3. 构造测试 payload 并验证

## 常用 Payload
- 单引号测试: `'`
- 布尔盲注: `' AND '1'='1`
- 时间盲注: `' AND SLEEP(5)--`
...
```

#### 高级格式（包含可执行工具）

```yaml
---
name: network-scanner
description: 网络扫描技能，包含 nmap 等工具
tools:
  - name: nmap_scan
    description: 使用 nmap 进行端口扫描
    command: "C:/Tools/nmap/nmap.exe"
    args: "-sV -p {ports} {target}"
    working_dir: "C:/Tools/nmap"
    timeout: 300
    parameters:
      - name: target
        description: 目标 IP 或域名
        required: true
      - name: ports
        description: 端口范围
        default: "1-1000"
  
  - name: nuclei_scan
    description: 使用 nuclei 进行漏洞扫描
    command: "/usr/local/bin/nuclei"
    args: "-u {url} -t {templates}"
    timeout: 600
    parameters:
      - name: url
        description: 目标 URL
        required: true
      - name: templates
        description: 模板路径
        default: "cves/"
---

# Network Scanner

当需要进行网络扫描时，使用以下工具：

## 可用工具

### nmap_scan
用于端口扫描和服务识别。

使用场景：
- 发现开放端口
- 识别服务版本
- 初步侦察

### nuclei_scan
用于自动化漏洞扫描。

使用场景：
- CVE 漏洞检测
- 配置错误检测
- 批量漏洞扫描
```

### 工具定义参数说明

| 参数 | 必需 | 说明 |
|------|------|------|
| `name` | ✓ | 工具名称，AI 调用时使用 |
| `description` | ✓ | 工具描述，告诉 AI 这个工具做什么 |
| `command` | ✓ | 可执行文件路径（支持相对路径，相对于 SKILL.md 所在目录） |
| `args` | | 参数模板，使用 `{param_name}` 作为占位符 |
| `working_dir` | | 工作目录 |
| `timeout` | | 执行超时（秒），默认 120 |
| `parameters` | | 参数定义列表 |

### 参数定义

| 字段 | 说明 |
|------|------|
| `name` | 参数名（对应 args 中的占位符） |
| `description` | 参数描述 |
| `type` | 类型：string/number/boolean |
| `required` | 是否必需 |
| `default` | 默认值 |

### AI 调用流程

当 AI 需要使用 Skill 工具时：

1. AI 调用 `list_skill_tools` 查看可用工具列表
2. AI 调用 `execute_skill_tool(toolName, parameters)` 执行工具
3. 工具执行完成后，AI 接收输出并分析结果

```
用户: 扫描一下 192.168.1.1 的常用端口

AI: 我来使用 nmap 进行端口扫描。

▶ execute_skill_tool
   toolName=skill_network_nmap_scan
   parameters={"target": "192.168.1.1", "ports": "21,22,80,443,3306,8080"}

[执行 nmap -sV -p 21,22,80,443,3306,8080 192.168.1.1]

扫描结果分析：
- 22/tcp open - SSH (OpenSSH 8.2)
- 80/tcp open - HTTP (nginx 1.18)
- 443/tcp open - HTTPS
...
```

### 安全注意事项

- ⚠️ 工具执行有超时限制（默认 120 秒）
- ⚠️ 输出大小限制（100KB）
- ⚠️ 仅对授权目标进行测试
- ⚠️ 确保工具路径正确且有执行权限

## 🔗 获取 API Key

1. 访问 [阿里云 DashScope 控制台](https://dashscope.console.aliyun.com/)
2. 登录/注册阿里云账号
3. 开通 DashScope 服务
4. 创建 API Key 并复制

## 🌟 使用示例





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

### Skills 配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| 启用 Skills | 允许使用自定义技能 | 关闭 |
| Skills 目录 | 存放 SKILL.md 的目录 | - |

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

### v1.1.0
- ✅ Skills 自定义技能系统
- ✅ 支持定义可执行工具（nmap、sqlmap 等）
- ✅ 参数模板和智能填充
- ✅ 工具执行超时和输出限制

### v1.0.0
- ✅ 基础 AI 分析功能
- ✅ 流式输出支持
- ✅ MCP 工具调用（Burp MCP、RAG MCP）
- ✅ Markdown 渲染
- ✅ 工具执行可视化

---

**Happy Hacking! 🎯🔍**

*让 AI 成为你的安全测试助手，发现更多漏洞！*
