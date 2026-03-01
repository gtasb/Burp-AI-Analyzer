# AI漏洞分析助手 - Burp Suite插件 🎯

> 🚀 让AI成为你的安全测试助手！基于 LangChain4j + 通义千问 + MCP 协议的智能渗透测试工具

![图片描述](img\2.png "图片title")

## ✨ 功能特性

### 🤖 AI 智能分析
- **流式输出** - 实时显示 AI 分析过程，体验流畅
- **深度思考模式** - 启用后 AI 会更深入地分析安全问题
- **联网搜索** - 可选启用网络搜索获取最新漏洞信息
- **上下文记忆** - 支持多轮对话，AI 记住之前的分析内容

### 🔧 Python 执行工具
- **脚本执行** 允许大模型执行自定义python脚本

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

编译完成后，在 `target` 目录下生成 `ai-analyzer-{latest-version}-jar-with-dependencies.jar`

### 2. 加载到 Burp Suite

1. 打开 Burp Suite
2. 进入 `Extensions` → `Installed`
3. 点击 `Add` → 选择 `Java` 类型
4. 选择编译好的 jar 文件
5. 点击 `Next` 完成加载

### 3. 配置 API

1. 在 Burp 中找到 `AI分析` 标签页
2. 填写配置：
   - **API Key**: 你的百炼 API Key
   - **模型**: 默认 `qwen-max`
   - **支持自定义 API**: 你也可以使用 openai 兼容格式接口，以支持更多模型

### 4. 使用教程

![图片描述](img\3.png "图片title")

#### 方式一：主动分析（请求分析标签页）

1. 在 **Proxy / Repeater / Target** 等任意 HTTP 请求处右键
2. 选择 **「发送到AI分析」**，请求会出现在左侧请求列表
3. 在右侧选中该请求，可查看 HTTP 请求/响应
4. 在底部「分析提示词」输入框填写或修改提示（默认：请分析这个请求中可能存在的安全漏洞）
5. 点击 **「开始分析」**，AI 将流式输出分析结果
6. 可勾选 **「启用深度思考」**、**「启用网络搜索」** 以提升分析质量

#### 方式二：AI 侧栏实时对话（推荐）

1. 在 **Proxy** 或 **Repeater** 中打开任意请求
2. 在请求/响应编辑器下方找到 **「AI助手」** 标签页（侧栏），在响应的编辑器中打开侧栏（如果在请求的编辑器中打开，大模型只会看到请求的内容）
3. 侧栏会自动加载当前请求的上下文
4. 在输入框直接提问，例如：
   - 「分析这个请求的安全风险」
   - 「是否存在 SQL 注入可能？」
   - 「帮我构造一个 XSS payload 测试」
5. 支持多轮对话，AI 会记住上下文
（目前存在一个小 bug ，启动 BP 后第一次运行插件时，会出现侧栏被重置的情况）

#### 方式三：被动扫描（批量分析）

1. 在 **AI分析** 标签页 → **请求分析** 子标签
2. 勾选 **「启用被动扫描」**，设置线程数（默认 3）
3. 点击 **「开始扫描」**
4. 插件会从 **HTTP History** 自动获取流量并交给 AI 分析
5. 扫描结果按风险等级（严重/高/中/低/信息）展示在表格中
6. 点击某行可查看该请求的详细分析与 HTTP 内容

#### 其他功能入口

| 功能 | 入口 |
|------|------|
| API / MCP / 知识库配置 | **配置** 标签页 |
| 自定义 Skills（指令 + 工具） | **Skills** 标签页 |
| Python 脚本执行 | 配置 → 启用 Python 脚本执行 |
| Burp MCP 工具调用 | 配置 → 启用 Burp MCP 工具 |

## RAG MCP （目前更建议使用直接查找或skills）

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
| Burp MCP 地址 | MCP 服务器地址 | `http://127.0.0.1:9876/` |
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
- Burp Suite Professional Edition
- 通义千问 API Key

## 🔨 开发构建

```bash
# Maven 构建
mvn clean package


详细说明请查看 [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) 和 [QUICKSTART.md](QUICKSTART.md)

## 📝 更新日志

### v1.2.0
- ✅ 允许 Agent 运行 python 代码
- ✅ 完善了被动扫描功能
- ✅ 参考 HaE、z0scan 等优秀开源工具，增加了前置扫描器
- ✅ 优化某些逻辑

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
