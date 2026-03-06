# 前置扫描器集成 & 问题修复总结

## 🎯 已解决的三个关键问题

### 1. ✅ 规则文件路径问题

#### 答案：规则文件应该放在项目根目录的 `scanners/` 目录下

```
Burp-AI-Analyzer/
├── scanners/
│   └── 漏洞匹配规则库.json  ← 规则文件位置
├── src/
├── target/
└── pom.xml
```

#### 加载机制（双重保障）

1. **优先从文件系统加载** - 开发环境
   - 路径: `scanners/漏洞匹配规则库.json`
   - 优点: 可直接修改，无需重新打包
   
2. **备选从JAR内部加载** - 生产环境
   - 路径: `/scanners/漏洞匹配规则库.json`
   - 打包时自动包含（已配置 pom.xml）

#### 验证规则加载成功

查看 Burp Suite 的 Output 标签页，应该看到：

```
[RuleLoader] 成功加载 1000+ 条规则
[PreScanFilterManager] 前置扫描过滤器初始化成功
[AIExtension] 前置扫描器初始化成功
```

---

### 2. ✅ 被动扫描逻辑确认

#### 答案：被动扫描逻辑是**正确的**，只扫描开启后新增的流量！

#### 工作原理

```
开启被动扫描
    ↓
注册 HttpHandler（监听器）
    ↓
新HTTP响应到达
    ↓
过滤：去重、静态资源
    ↓
放入队列（1000容量）
    ↓
多线程消费（3个线程）
    ↓
AI分析（流式输出）
    ↓
结果显示
```

#### 关键特性

| 特性 | 说明 |
|------|------|
| **监听时机** | 只有点击"开始被动扫描"后才开始监听 |
| **监听对象** | 从开启时刻起新到达的HTTP响应 |
| **历史记录** | **不会扫描** History 中已有的流量 |
| **停止后** | 不再监听新流量，队列中的任务会继续完成 |

#### 过滤规则

自动跳过：
- ✅ 静态资源（`.js`, `.css`, `.jpg`, `.png`, `.gif`, `.ico`, `.svg`, `.woff`, `.mp4` 等）
- ✅ 无参数的请求（无Query String、无POST数据、无Cookie）
- ✅ 重复请求（基于 `Method + URL + 参数名` 去重）

#### 实现代码

```java
// 只在 isRunning=true 时处理新流量
public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
    if (isRunning.get() && !cancelFlag.get()) {  // 关键：只处理开启后的流量
        enqueueRequest(requestResponse);
    }
    return ResponseReceivedAction.continueWith(responseReceived);
}
```

---

### 3. ✅ 被动扫描支持流式输出

#### 已实现！现在支持实时流式输出AI分析结果

#### 如何使用

1. **开启被动扫描**
2. **点击表格中正在扫描的行**（状态为"扫描中"）
3. **"AI分析结果"区域实时显示流式输出**

#### 特点

- ✨ 只有**选中正在扫描的行**才显示流式输出
- ✨ 切换到其他行会显示该行的完整结果
- ✨ 使用 Markdown 格式实时渲染
- ✨ 与主动分析模式的流式体验一致

#### 技术实现

```java
// PassiveScanManager.java - 支持流式回调
String aiResponse = apiClient.analyzeRequest(
    result.getRequestResponse(),
    cancelFlag,
    chunk -> {
        // 流式输出回调
        if (onStreamingChunk != null && currentStreamingScanResult == result) {
            onStreamingChunk.accept(chunk);
        }
    }
);

// AIAnalyzerTab.java - UI实时更新
passiveScanManager.setOnStreamingChunk(chunk -> {
    SwingUtilities.invokeLater(() -> {
        // 只有当前选中的行正在流式输出时，才更新UI
        if (selectedId == currentStreaming.getId()) {
            MarkdownRenderer.appendMarkdownStreaming(pane, chunk, position);
        }
    });
});
```

---

## 📦 打包文件

✅ **编译成功**

```
[INFO] BUILD SUCCESS
[INFO] Compiling 306 source files
[INFO] Building jar: target/ai-analyzer-Dev-jar-with-dependencies.jar
```

**JAR文件位置**: `target/ai-analyzer-Dev-jar-with-dependencies.jar`

**规则文件已打包**: ✅ `scanners/漏洞匹配规则库.json` 已包含在JAR中

---

## 📚 相关文档

| 文档 | 说明 |
|------|------|
| [DEPLOYMENT.md](src/com/ai/analyzer/rulesMatch/DEPLOYMENT.md) | 规则文件部署指南（问题1详解） |
| [PASSIVE_SCAN_LOGIC.md](src/com/ai/analyzer/pscan/PASSIVE_SCAN_LOGIC.md) | 被动扫描逻辑说明（问题2详解） |
| [README.md](src/com/ai/analyzer/rulesMatch/README.md) | 前置扫描器使用说明 |
| [VULNERABILITY_TYPES.md](src/com/ai/analyzer/rulesMatch/VULNERABILITY_TYPES.md) | 支持的35+种漏洞类型 |
| [INTEGRATION_GUIDE.md](src/com/ai/analyzer/rulesMatch/INTEGRATION_GUIDE.md) | 集成指南 |

---

## 🚀 使用方法

### 1. 加载插件

将 `target/ai-analyzer-Dev-jar-with-dependencies.jar` 加载到 Burp Suite

### 2. 配置API

在"配置"标签页：
- 设置通义千问 API Key
- 启用前置扫描器（可选）
- 配置其他功能开关

### 3. 使用被动扫描

在"被动扫描"标签页：
1. ✅ 勾选"启用被动扫描"
2. ✅ 设置线程数（建议2-5）
3. ✅ 点击"开始被动扫描"
4. ✅ 点击正在扫描的行，实时查看流式输出

### 4. 查看结果

- **表格**: 显示所有扫描请求
- **HTTP请求/响应**: 显示选中行的原始流量
- **AI分析结果**: 
  - 完成的扫描 → 显示完整Markdown结果
  - 正在扫描的行 → 实时流式输出

---

## 🎉 核心特性总结

| 特性 | 状态 | 说明 |
|------|------|------|
| ✅ 前置扫描器 | 已集成 | 35+种漏洞类型，1000+规则 |
| ✅ 规则文件加载 | 已实现 | 文件系统优先，JAR备选 |
| ✅ 被动扫描逻辑 | 正确 | 只扫描开启后新流量 |
| ✅ 流式输出 | 已支持 | 选中行实时显示 |
| ✅ 主动扫描集成 | 已完成 | 右键菜单支持前置扫描 |
| ✅ 被动扫描集成 | 已完成 | 自动监听新流量 |
| ✅ 工具调用 | 已支持 | MCP、联网搜索 |
| ✅ 深度思考 | 已支持 | 通义千问专属 |

---

## ⚠️ 注意事项

### 规则文件
- 确保 `scanners/漏洞匹配规则库.json` 存在
- 文件大小约 200KB，包含1000+规则
- 加载失败会在 Error 日志中显示

### 被动扫描
- 只监听开启后的新流量
- 不会回溯扫描历史记录
- 队列容量1000，超出会丢弃

### 流式输出
- 必须选中"扫描中"状态的行
- 不要频繁切换选中行
- 流式输出使用 Markdown 渲染

### 性能
- 线程数建议: 低流量2-3，高流量5-10
- 前置扫描平均增加100-500ms延迟
- AI分析时间取决于模型响应速度

---

## 📊 测试建议

### 1. 测试规则文件加载

```
1. 加载插件
2. 查看 Output 标签页
3. 确认看到"成功加载 1000+ 条规则"
```

### 2. 测试被动扫描逻辑

```
1. 清空 History
2. 开启被动扫描
3. 访问几个网页
4. 确认只有新访问的页面被扫描
5. 停止被动扫描
6. 再访问其他网页
7. 确认没有新扫描任务
```

### 3. 测试流式输出

```
1. 开启被动扫描
2. 访问一个复杂网页（触发AI分析）
3. 快速点击表格中"扫描中"的行
4. 确认 AI分析结果区域实时显示流式输出
5. 切换到其他行
6. 确认显示该行的完整结果
```

---

**更新时间**: 2026-01-31 16:49  
**版本**: Dev  
**状态**: ✅ 所有问题已解决，编译打包成功
