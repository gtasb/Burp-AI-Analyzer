# ArtifactCache 与 MCP 工具：现状分析与落地建议

## 1. 现状

- `ArtifactCacheTools` 已经作为普通 `@Tool` 注册到 `Assistant` 里，`read_cached_artifact` / `describe_cached_artifact` 负责给 AI “分页”读取大文本。
- 目前没有**任何 MCP 工具**把结果写进 `ArtifactCache`；所有 MCP 工具返回的超大响应都直接进入 `ChatMemory`，容易造成：
  1. Token 消耗暴增（上下文爆炸）。
  2. 消息窗口滑动导致原始 `UserMessage` 被逐出（`InputRequiredException` 等）。
  3. 模型注意力被无关大段内容淹没。

## 2. 为什么 MCP 工具没用上 ArtifactCache

| 原因 | 说明 |
|------|------|
| 不在本代码库内 | MCP Server（如 Burp MCP、Chrome MCP）在独立进程/服务中运行，本项目只能消费其返回的原始字符串，无法直接改写它们把结果落盘。 |
| LangChain4j `McpToolProvider` 是黑盒代理 | `McpClient` 远程拿到 `ToolSpecification` 并调用，调用结果直接返回给 AI，中间没有拦截点。 |
| `CachingMcpToolProvider` 只做了**工具列表**缓存 | 当前代码用它缓存 `listTools()` 的结果（大响应缓存 12_000 行？实际语义是工具规范缓存），并非缓存每次调用的**执行结果**。 |
| 缺少“返回 fileId”的约定 | MCP Server 返回的是纯文本；要让 AI 后续用 `read_cached_artifact`，需要先有一个工具把文本缓存并返回 `fileId`。 |
| 提示词未引导 | 系统提示没有告诉 AI “当 MCP 工具返回内容很长时，优先调用 `read_cached_artifact` 分段读取”。 |

## 3. 让 MCP 工具使用 ArtifactCache 的两种思路

### 思路 A：在 AI 服务侧做“MCP 执行结果缓存”（推荐）

在 `McpToolProvider` 与 `AiServices` 之间加一层代理，自动把单次 MCP 工具返回的超长字符串写入 `ArtifactCache`，并替换为 `fileId + 摘要`。

实现方式：
1. 自定义 `ToolProvider` 包装 `McpToolProvider`。
2. 对每个 `ToolSpecification` 再包一层执行器。
3. 执行器里调用真实 MCP 工具；如果结果长度 > 阈值（如 2000 字符 / 50 行），则：
   - `ArtifactCache.create(result)` 写缓存拿到 `fileId`。
   - 生成摘要（前 N 行 + 总行数 + 大小）。
   - 返回 `fileId` 与一段“请用 `read_cached_artifact(fileId=..., startLine=1, lineCount=100)` 读取”的提示。
4. 仍把 `ArtifactCacheTools` 注册到 `Assistant`，系统提示里强调：
   - 如果工具返回只包含 `fileId`，不要直接分析，先读缓存。
   - 长内容分批读取后再总结。

优点：
- 不改动任何 MCP Server。
- 对 AI 透明；旧 MCP 工具立刻受益。

缺点：
- 需要额外实现“代理执行器”，兼容 `ToolProvider` 接口。
- 如果 MCP 工具有时被连续调用（A 结果作为 B 参数），要注意 `fileId` 不能作为真实参数传入；此时应跳过缓存或提供“真实内容”回退。

### 思路 B：修改 MCP Server 侧，让工具直接返回 `fileId`

把 Burp MCP、Chrome MCP 等 Server 源码改成：大响应先写内存/文件，返回 `{"fileId":"xxx","lines":N}`。

优点：传输量减少。

缺点：
- Burp MCP Server 很可能不是本项目维护的，无法落地。
- 与外部 MCP 生态不兼容。

**结论：思路 A 是本项目唯一可行方案。**

## 4. 思路 A 的推荐实现路径

### 4.1 新建 `McpExecutionCachingProvider`

位置建议：`src/com/ai/analyzer/mcpClient/McpExecutionCachingProvider.java`

职责：
- 接收一个真正的 `McpToolProvider`。
- 返回 `List<ToolSpecification>` 给 AI 时，保持原样（名称/参数/描述不变）。
- 拦截执行：拿到 `ToolExecutionRequest` -> 委托给 MCP -> 结果过大则缓存 -> 返回替换文本。

参考伪代码：

```java
public class McpExecutionCachingProvider implements ToolProvider {
    private final ToolProvider delegate;
    private final int cacheThresholdChars;

    public McpExecutionCachingProvider(ToolProvider delegate, int cacheThresholdChars) {
        this.delegate = delegate;
        this.cacheThresholdChars = cacheThresholdChars;
    }

    @Override
    public List<ToolSpecification> listTools() {
        return delegate.listTools();
    }

    @Override
    public boolean isCalledFor(ToolExecutionRequest request) {
        return delegate.isCalledFor(request); // 按 LangChain4j 实际接口调整
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, Object memoryId) {
        ToolExecutionResult result = delegate.execute(request, memoryId);
        if (result == null || result.text() == null) return result;
        String text = result.text();
        if (text.length() <= cacheThresholdChars && text.lines().count() <= 50) {
            return result;
        }
        try {
            String fileId = ArtifactCache.create(text);
            long lines = text.lines().count();
            String replacement = String.format(
                "内容已缓存，fileId=%s，共 %d 行，%.1f KB。" +
                "请先调用 read_cached_artifact(fileId=%s, startLine=1, lineCount=100) 按需读取。",
                fileId, lines, text.getBytes().length / 1024.0, fileId);
            return ToolExecutionResult.builder()
                    .text(replacement)
                    .request(request)
                    .build();
        } catch (Exception e) {
            // 缓存失败不要阻断原结果
            return ToolExecutionResult.builder()
                    .text("[缓存失败，返回原始内容]\n" + text)
                    .request(request)
                    .build();
        }
    }
}
```

> 注意：LangChain4j 1.16.x 的 `ToolProvider` API 具体方法需以实际版本为准。当前 `AgentApiClient` 是把 `McpToolProvider` 直接传入 `assistantBuilder.toolProvider(...)`。如果 `McpToolProvider` 已实现 `ToolProvider` 接口，则可以在外面包一个同样实现 `ToolProvider` 的装饰器。

### 4.2 修改 `AgentApiClient` / `PassiveScanApiClient`

把：

```java
providers.add(new CachingMcpToolProvider(mcpToolProvider, 12_000));
```

改成两层包装：

```java
// 外层：执行结果过长时落 ArtifactCache
ToolProvider cachedByExecution =
    new McpExecutionCachingProvider(mcpToolProvider, 2048);
// 内层：工具列表/规范缓存（保留原名语义）
providers.add(new CachingMcpToolProvider(cachedByExecution, 12_000));
```

并对 `PassiveScanApiClient` 做同样修改。

### 4.3 系统提示词补充

在 `SystemPromptBuilder`（主被动）里增加一段：

> 当任何 MCP 工具返回结果中出现 `fileId=` 或提示“内容已缓存”时，说明原始数据已被存入 ArtifactCache。你**不得**在没有读取缓存的情况下声称看到了完整内容。请先调用 `describe_cached_artifact(fileId=...)` 查看大小/行数，再用 `read_cached_artifact(fileId=..., startLine=..., lineCount=...)` 分批读取分析；建议每次读取 50-200 行，先读关键片段，降低上下文消耗。

### 4.4 ArtifactCache 增强（可选）

当前 `ArtifactCache.create(String)` / `readLines(String, int, int)` 已够用。若后续交互频繁，可考虑：
- 对相同内容做摘要哈希去重。
- 给缓存文件加 TTL / 容量上限，防止无限堆积。

## 5. 与“自定义 MCP 配置面板”的关系

如果后续实现用户提出的“自定义 MCP 配置面板”，思路 A 的收益会进一步扩大：

- 用户可以自由接入第三方 MCP Server（如 Fetch、Browser、PDF 解析）。
- 这些 Server 往往一次性返回网页源码 / 文档全文，最容易造成上下文爆炸。
- 只要它们接入的是同一个 `McpToolProvider` 流程，`McpExecutionCachingProvider` 会自动帮它们落缓存。

因此建议实现顺序：
1. 先落地 `McpExecutionCachingProvider`（改动小、收益快）。
2. 再按需新增自定义 MCP 配置面板。

## 6. 最小改动清单

1. `src/com/ai/analyzer/mcpClient/McpExecutionCachingProvider.java`（新增）
2. `src/com/ai/analyzer/Client/AgentApiClient.java`
   - `ensureAssistantInitialized()` 中调整 MCP provider 包装顺序。
3. `src/com/ai/analyzer/pscan/PassiveScanApiClient.java`
   - `createPerRequestAssistant(...)` 中同样包装。
4. `src/com/ai/analyzer/Client/SystemPromptBuilder.java` / `src/com/ai/analyzer/pscan/SystemPromptBuilder.java`
   - 追加缓存指引Prompt。
5. 可选：`ArtifactCache` 增加 `create(byte[])` 与统计信息接口，便于二进制 MCP 结果缓存。

## 7. 预期效果

- MCP 工具返回的长文本不再直接膨胀 `ChatMemory`。
- AI 会主动使用 `read_cached_artifact` 分段读取，上下文命中率/可控性提升。
- 自定义 MCP Server 接入后自动继承该能力，无需逐个适配。
