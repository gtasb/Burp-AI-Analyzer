# 前置扫描器plan

为当前已开发的 主动扫描和被动扫描客户端 增加一个新功能：前置扫描器
开启这个功能时，由burp传过来的HTTP包先进行一次关键字匹配扫描，然后将扫描结果追加到 UserPrompt 中，提醒LLM需要重点关注
扫描结果应该是尽可能简短的（节约token），类似于以下格式：

"前置扫描器匹配到疑似 {xxx（类型）漏洞}：（检测到的字符串为） {xxx}，
{xxx（类型）漏洞}：（检测到的字符串为） {xxx}，
需重点关注，需检查漏洞真实性并进行测试"

## 开发进度

### ✅ 已完成 - 核心模块

1. **VulnerabilityRule.java** - 漏洞规则数据模型
   - **支持35+种漏洞类型**（从SQL注入到模板注入、从敏感信息泄露到HTTP走私）
   - 编译后的正则表达式缓存
   - 子类型支持（如16种数据库、12种模板引擎等）

2. **ScanMatch.java** - 匹配结果数据模型
   - 包含漏洞类型、匹配字符串、危险等级
   - 自动截断过长字符串
   - 提供UserPrompt和UI两种格式化输出

3. **RuleLoader.java** - 规则加载器
   - 从JSON文件加载规则库
   - 支持8种主要漏洞类型
   - 容错处理（单个规则失败不影响整体加载）

4. **PreScanFilter.java** - 前置扫描过滤器核心
   - 多线程并发扫描（线程池）
   - 可配置超时机制（默认500ms）
   - 自动生成简洁的提示文本

5. **PreScanFilterManager.java** - 管理器
   - 生命周期管理（初始化、启用、禁用、关闭）
   - 默认配置支持
   - 资源自动释放

### 📋 待完成 - UI集成

## 用户视图侧

在配置页中增加一个新开关，开关这个前置扫描器功能
当开启这个功能后，如果前置扫描器匹配到规则，聊天框中应该输出提示：
"前置扫描器匹配到疑似 {xxx（类型）漏洞}：（检测到的字符串为） {xxx}，
{xxx（类型）漏洞}：（检测到的字符串为） {xxx}"

**待完成任务**：
- [ ] 在 AIAnalyzerTab.java 中添加"启用前置扫描器"复选框
- [ ] 在 AgentApiClient.analyzeRequest() 中集成前置扫描
- [ ] 在 PassiveScanApiClient.analyzeRequest() 中集成前置扫描
- [ ] 在 AIExtension.initialize() 中初始化 PreScanFilterManager
- [ ] 将匹配结果输出到聊天框

## 工具侧

✅ 已经实现：
- 直接使用 `scanners/漏洞匹配规则库.json`
- 将扫描结果追加到 UserPrompt
- 支持多种输出格式（Prompt提示、UI消息）

## 性能侧

✅ 已实现：
- 多线程扫描器（默认4线程，可配置）
- 快速正则匹配
- 超时保护机制
- 线程池复用

## 技术架构

```
AIExtension
    ↓
PreScanFilterManager (生命周期管理)
    ↓
PreScanFilter (核心扫描引擎)
    ├── RuleLoader (规则加载)
    ├── VulnerabilityRule (规则模型)
    └── ScanMatch (匹配结果)
```

## 使用文档

详细使用说明请参考：[README.md](./README.md)