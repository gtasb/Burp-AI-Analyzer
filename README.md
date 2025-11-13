# AI漏洞分析助手 - Burp Suite插件 🎯

> 🚀 让AI成为你的安全测试助手！

## 📖 项目简介

这是一个基于**通义千问AI**的Burp Suite扩展插件，可以帮助安全测试人员快速分析HTTP请求中的潜在漏洞和安全测试点。通过AI的智能分析，大幅提高漏洞发现效率，降低安全测试门槛。

详细步骤请查看 [QUICKSTART.md](QUICKSTART.md)

**我并不擅长开发，希望借助这个项目抛砖引玉。langchain4j用起来真的很难受，希望有大佬能提供些指导意见。**

### 使用流程
```
发现请求 → 右键发送 → 填写提示词 → AI分析 → 人工验证
```

## 🛠️ 技术栈

- **Java 21** - 最新LTS版本
- **Burp Suite Montoya API** - 最新扩展API
- **通义千问API** - 阿里云大语言模型
- **Gson** - JSON处理
- **Swing** - GUI框架
- **Langchain4j** - 基于Java的AI开发框架

## 📦 构建方式

### Maven
```bash
mvn clean package
```

### 方式2: Gradle
```bash
gradle clean jar
```

详细说明请查看 [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md)

## 🌟 使用示例

### 示例1: SQL注入检测
```
用户提示词：
请详细分析这个请求中是否存在SQL注入漏洞，包括：
1. 哪些参数可能存在注入点
2. 建议的测试payload
3. 可能的数据库类型
4. 绕过WAF的技巧

AI分析结果：
✓ 发现潜在注入点：id参数
✓ 推荐payload: ' OR '1'='1, 1' UNION SELECT NULL--
✓ 数据库可能为MySQL（基于错误信息）
✓ WAF绕过技巧：使用/**/注释、编码等
```

### 示例2: XSS漏洞分析
```
用户提示词：
分析这个请求中的XSS攻击面

AI分析结果：
✓ 用户输入点：search参数
✓ 输出上下文：HTML标签内
✓ 推荐payload: <script>alert(1)</script>
✓ 需要测试CSP绕过
```

更多示例请查看 [PROMPT_TEMPLATES.md](PROMPT_TEMPLATES.md)

## 🔗 获取API Key

1. 访问 [阿里云DashScope控制台](https://dashscope.console.aliyun.com/)
2. 登录/注册阿里云账号
3. 开通DashScope服务
4. 创建API Key并复制

**Happy Hacking! 🎯🔍**

*让AI成为你的安全测试助手，发现更多漏洞！*

## 环境要求

- Java 21或更高版本
- Burp Suite Professional/Community Edition
- 通义千问API Key（从阿里云获取）

## 安装步骤

### 1. 编译插件

使用Maven编译项目：

```bash
mvn clean package
```

编译完成后，在`target`目录下会生成`ai-analyzer-1.0.0-jar-with-dependencies.jar`文件。

### 2. 加载到Burp Suite

1. 打开Burp Suite
2. 进入`Extensions` -> `Installed`标签页
3. 点击`Add`按钮
4. 选择`Extension type`为`Java`
5. 点击`Select file`，选择编译好的jar文件
6. 点击`Next`完成加载

## 使用方法

### 1. 配置API

1. 在Burp Suite中找到`AI分析`标签页
2. 在顶部的API配置区域填写：
   - **API URL**：默认为`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
   - **API Key**：填入你的通义千问API Key

### 2. 发送请求进行分析

1. 在Burp Suite的任意位置（Proxy、Repeater、Target等）找到要分析的HTTP请求
2. 右键点击请求，选择`发送到AI分析`
3. 请求内容会自动填充到AI分析标签页

### 3. 执行分析

1. 在`用户提示词`区域输入你的分析需求，例如：
   - "请分析这个请求中可能存在的SQL注入漏洞"
   - "检查是否存在XSS攻击向量"
   - "分析这个API接口的安全风险"
2. 点击`开始分析`按钮
3. 等待AI分析完成，结果会显示在下方的`AI分析结果`区域

### 4. 查看结果

AI会返回详细的分析报告，包括：
- 识别出的潜在漏洞
- 安全测试点建议
- 具体的测试方法
- 风险评估

## 获取通义千问API Key

1. 访问[阿里云DashScope控制台](https://dashscope.console.aliyun.com/)
2. 登录或注册阿里云账号
3. 开通DashScope服务
4. 在API-KEY管理页面创建新的API Key
5. 复制API Key到插件配置中



