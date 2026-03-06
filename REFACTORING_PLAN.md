# Side Panel 重建问题重构计划

## 问题分析

### 根本原因

Burp Suite 的 `HttpRequestEditorProvider` 和 `HttpResponseEditorProvider` 接口设计是：
- **每次需要显示 Editor 时，都会调用 `provideHttpRequestEditor` 或 `provideHttpResponseEditor` 方法**
- **每次调用都会返回一个新的 Editor 实例**
- Burp Suite 可能在以下情况重新调用这些方法：
  1. 用户切换不同的请求/响应
  2. 用户修改请求/响应
  3. UI 刷新或重新布局
  4. 某些异步操作完成后

### 当前问题

1. **每次调用都创建新的 ChatPanel 实例**：
   ```java
   // AISidePanelProvider.java
   ChatPanel newChatPanel = new ChatPanel(api, sharedApiClient); // 每次都新建！
   ```

2. **旧的 ChatPanel 实例被丢弃**：
   - 旧的 ChatPanel 可能正在执行 API 请求
   - 旧的 ChatPanel 的 UI 状态（聊天历史、输入框内容等）丢失
   - 旧的 ChatPanel 的 `currentWorker` 可能还在运行，导致资源浪费

3. **用户体验问题**：
   - 用户第一次点击发送时，Side Panel 可能还没完全初始化，所以不会重建
   - 用户第二次点击发送时，Burp Suite 重新创建了 Editor，导致：
     - 旧的 API 请求被中断（但可能还在后台运行）
     - 新的 ChatPanel 实例没有之前的聊天历史
     - 用户看到 Side Panel "重置"了

### 错误的修复尝试

之前的修复方案（第一次点击跳过）是错误的，因为：
- 问题的根源不是"第一次点击"，而是"Burp Suite 何时重建 Editor"
- 这个时机是不可预测的，可能在第一次、第二次、或任何一次点击后发生
- 使用静态计数器无法解决根本问题

## 解决方案

### 方案 1：Editor 实例缓存（推荐）

**核心思想**：根据 `EditorCreationContext` 缓存 Editor 实例，避免重复创建。

**实现方式**：
1. 在 `AISidePanelProvider` 中维护 Editor 实例缓存
2. 使用 `EditorCreationContext.toolSource()` 作为缓存键
3. 如果缓存中存在对应的 Editor，直接返回
4. 如果不存在，创建新的 Editor 并缓存

**优点**：
- 符合 Burp Suite 的设计理念（每个工具可能有独立的 Editor）
- 避免不必要的重建
- 保持每个工具的 Editor 状态独立

**缺点**：
- 需要管理缓存的生命周期
- 如果 Burp Suite 强制重建，缓存可能失效

### 方案 2：共享 ChatPanel 实例（备选）

**核心思想**：所有 Editor 共享同一个 ChatPanel 实例。

**实现方式**：
1. 在 `AISidePanelProvider` 中维护一个共享的 ChatPanel 实例
2. 所有 Request Editor 和 Response Editor 都使用这个共享实例
3. 使用 `setCurrentRequest()` 更新当前请求

**优点**：
- 实现简单
- 聊天历史完全保留
- 不会出现"重置"问题

**缺点**：
- 不符合 Burp Suite 的设计理念（每个 Editor 应该是独立的）
- 如果用户同时打开多个工具的 Side Panel，可能会有冲突

### 方案 3：ChatPanel 状态持久化（长期方案）

**核心思想**：将 ChatPanel 的状态（聊天历史、当前请求等）持久化到共享存储中。

**实现方式**：
1. 使用 `AgentApiClient` 的 `chatMemory` 作为共享状态
2. 每个 ChatPanel 实例都从共享状态恢复
3. 每次操作都同步到共享状态

**优点**：
- 即使 Editor 重建，状态也不会丢失
- 符合 Burp Suite 的设计理念
- 可以支持多个 Editor 实例

**缺点**：
- 实现复杂
- 需要重构 ChatPanel 的状态管理

## 推荐方案

**采用方案 1（Editor 实例缓存）+ 方案 3（状态持久化）的组合**：

1. **短期修复**：实现 Editor 实例缓存，避免不必要的重建
2. **长期优化**：将 ChatPanel 状态持久化到 `AgentApiClient`，即使重建也能恢复

## 实施步骤

### 阶段 1：移除错误的修复代码
1. 移除 `ChatPanel` 中的 `globalSendCount` 静态变量
2. 移除第一次点击跳过的逻辑

### 阶段 2：实现 Editor 实例缓存
1. 在 `AISidePanelProvider` 中添加 Editor 缓存
2. 使用 `EditorCreationContext.toolSource()` 作为缓存键
3. 实现缓存的创建、获取、清理逻辑

### 阶段 3：优化 ChatPanel 状态管理
1. 确保 `AgentApiClient` 的 `chatMemory` 是共享的
2. 确保 ChatPanel 从 `chatMemory` 恢复聊天历史
3. 确保每次操作都同步到 `chatMemory`

### 阶段 4：测试和验证
1. 测试多次点击发送，确保 Side Panel 不会重建
2. 测试切换不同的请求，确保状态正确
3. 测试多个工具的 Side Panel，确保不冲突

## 代码变更清单

### 需要修改的文件

1. **`src/com/ai/analyzer/provider/AISidePanelProvider.java`**
   - 添加 Editor 实例缓存
   - 修改 `provideHttpRequestEditor` 和 `provideHttpResponseEditor` 方法

2. **`src/com/ai/analyzer/ui/ChatPanel.java`**
   - 移除 `globalSendCount` 静态变量
   - 移除第一次点击跳过的逻辑
   - 优化状态恢复逻辑

3. **`src/com/ai/analyzer/api/AgentApiClient.java`**
   - 确保 `chatMemory` 是共享的
   - 添加状态同步方法

## 风险评估

### 低风险
- Editor 实例缓存实现简单，风险低
- 移除错误的修复代码，风险低

### 中风险
- ChatPanel 状态持久化需要重构，可能影响现有功能
- 需要充分测试各种场景

### 缓解措施
- 分阶段实施，每个阶段都进行测试
- 保留回滚方案
- 添加详细的日志，便于问题排查

## 预期效果

1. **Side Panel 不再频繁重建**：通过缓存机制，避免不必要的重建
2. **状态不会丢失**：即使重建，也能从共享状态恢复
3. **用户体验改善**：不再出现"重置"问题，聊天历史保留
4. **资源利用优化**：避免重复创建实例，减少资源浪费
