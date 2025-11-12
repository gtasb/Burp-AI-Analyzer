# 更新日志

## [1.0.0] - 2025-10-23

### 新增功能
- ✨ 集成通义千问AI API进行HTTP请求安全分析
- 🎨 友好的UI标签页界面，包含三个主要区域：
  - API配置区：自定义API URL和API Key
  - 请求分析区：显示HTTP请求和用户提示词
  - 结果展示区：显示AI分析结果
- 🖱️ 右键菜单集成：在任意HTTP请求上右键即可发送到AI分析
- 📝 自定义提示词功能：用户可以根据需求定制分析方向
- ⚡ 异步分析：使用SwingWorker避免UI冻结
- 📊 实时日志输出：在Burp Suite的Output标签页显示操作日志

### 技术特性
- 🔧 使用Java 21开发
- 📦 集成Gson库处理JSON
- 🌐 使用Java 11+ HttpClient进行API调用
- 🎯 基于Burp Suite Montoya API
- 🔐 支持Bearer Token认证

### 文档
- 📖 完整的README.md使用说明
- 📚 详细的USAGE_GUIDE.md使用指南
- 🎯 丰富的PROMPT_TEMPLATES.md提示词模板库
- 🔨 提供多种构建方式（Maven、Gradle、批处理脚本）

### 支持的分析场景
- SQL注入检测
- XSS漏洞分析
- 命令注入识别
- 文件操作漏洞
- 认证授权问题
- API安全评估
- 业务逻辑漏洞
- 更多...

### 已知限制
- 需要稳定的网络连接访问阿里云API
- API调用会产生费用（根据token使用量）
- 分析结果依赖AI模型，需要人工验证
- 大型请求可能需要较长分析时间

### 安全提示
- ⚠️ HTTP请求内容会发送到通义千问API，请注意敏感信息
- 🔑 妥善保管API Key，避免泄露
- 🛡️ 建议在测试环境中使用，避免发送生产环境数据

---

## 未来计划

### v1.1.0（计划中）
- [ ] 支持多个AI模型（OpenAI、Claude等）
- [ ] 分析结果历史记录
- [ ] 导出分析报告（PDF、HTML）
- [ ] 批量分析功能
- [ ] 自定义提示词模板管理

### v1.2.0（计划中）
- [ ] 漏洞知识库集成
- [ ] 自动化测试建议
- [ ] 与Scanner集成
- [ ] 协作功能（团队共享分析结果）

### v2.0.0（远期规划）
- [ ] 本地AI模型支持
- [ ] 自定义规则引擎
- [ ] 机器学习模型训练
- [ ] 插件生态系统

---

## 贡献指南

欢迎提交Issue和Pull Request！

### 如何贡献
1. Fork本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启Pull Request

### 报告Bug
请包含以下信息：
- Burp Suite版本
- Java版本
- 插件版本
- 详细的错误描述和日志
- 复现步骤

### 功能建议
欢迎提出新功能建议，请说明：
- 功能描述
- 使用场景
- 预期效果
- 可能的实现方案

---

## 致谢

感谢以下项目和服务：
- [Burp Suite](https://portswigger.net/burp) - 强大的Web安全测试平台
- [通义千问](https://tongyi.aliyun.com/) - 阿里云大语言模型
- [Gson](https://github.com/google/gson) - Google的JSON处理库
- 所有贡献者和用户的反馈

---

## 许可证

MIT License - 详见 LICENSE 文件

---

## 联系方式

- 项目主页：[GitHub Repository]
- 问题反馈：[Issues]
- 邮件联系：[Email]

---

**Happy Hacking! 🎯**

