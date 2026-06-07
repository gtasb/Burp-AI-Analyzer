# 被动扫描功能 - 生产者消费者模型

## 开发状态：✅ 已完成

## 架构设计

### 生产者-消费者模型

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│    HTTP Traffic │ ───▶ │  BlockingQueue   │ ───▶ │  Consumer Pool  │
│    (Producer)   │      │  (Request Queue) │      │  (AI Scanners)  │
└─────────────────┘      └──────────────────┘      └─────────────────┘
        │                        │                         │
   HttpHandler              容量: 1000                 多线程 (1-10)
   实时监听流量             线程安全                   并发扫描
```

### 核心组件

1. **生产者 (Producer)**
   - 使用 `HttpHandler` 注册到 Burp，实时监听所有 HTTP 流量
   - 响应接收后自动将请求-响应对放入队列
   - 支持去重和静态资源过滤

2. **请求队列 (BlockingQueue)**
   - 容量 1000，防止内存溢出
   - 线程安全，支持阻塞等待
   - FIFO 顺序处理

3. **消费者 (Consumer Pool)**
   - 可配置线程数 (1-10)
   - 从队列取请求，调用 AI 进行安全分析
   - 支持优雅取消

## 使用流程

```
1. 用户点击 "开始扫描"
   ↓
2. 启动消费者线程池 (N个扫描线程)
   ↓
3. 注册 HttpHandler (生产者)
   ↓
4. [可选] 加载现有 HTTP History 到队列
   ↓
5. 流量实时进入 → 入队 → 消费者扫描 → 更新UI
   ↓
6. 用户点击 "停止扫描" → 注销 Handler + 停止线程池
```

## 核心类

### PassiveScanManager.java
- `startPassiveScan()` - 启动被动扫描（生产者+消费者）
- `stopPassiveScan()` - 停止被动扫描
- `consumerTask()` - 消费者任务（从队列取请求并扫描）
- `enqueueRequest()` - 将请求放入队列（生产者调用）

### PassiveScanApiClient.java (DAST风格)
- 共享 ChatMemory (最大50条消息)
- 支持 MCP 工具调用、联网搜索
- 线程安全的 Assistant 实例

### PassiveScanTask.java
- `shouldSkipRequest()` - 静态方法，过滤静态资源
- 单个请求的扫描逻辑

### ScanResult.java
- 扫描结果数据模型
- 风险等级枚举 (严重/高/中/低/信息/无)
- 扫描状态枚举 (等待中/扫描中/已完成/错误/已取消)

## 配置选项

- **线程数**: 1-10，默认 5
- **队列容量**: 1000
- **支持功能**: MCP工具、联网搜索、深度思考、知识库

## UI 集成

- 请求列表实时更新（onNewRequestQueued 回调）
- 状态栏显示：队列数、已完成数、总数
- 风险等级颜色标记

## 参考- LangChain4j ChatMemory: https://docs.langchain4j.dev/tutorials/chat-memory
- Burp Montoya API HttpHandler: 实时监听 HTTP 流量
