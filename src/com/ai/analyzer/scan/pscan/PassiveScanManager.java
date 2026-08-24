package com.ai.analyzer.scan.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;

import burp.api.montoya.core.Registration;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 被动扫描管理器 - 生产者消费者模型
 * 
 * 架构设计：
 * 1. 生产者：HttpHandler 监听所有 HTTP 流量，实时将新请求放入队列
 * 2. 消费者：多线程从队列中取请求进行 AI 安全分析
 * 3. 队列：BlockingQueue 实现线程安全的生产者-消费者通信
 * 
 * 使用流程：
 * 1. 调用 startPassiveScan() 开启被动扫描
 * 2. 流量自动进入队列并被消费者扫描
 * 3. 调用 stopPassiveScan() 停止被动扫描
 */
public class PassiveScanManager {
    
    // ========== 配置 ==========
    private static final int DEFAULT_THREAD_COUNT = 5;
    private static final int MAX_THREAD_COUNT = 50;
    private static final int MIN_THREAD_COUNT = 1;
    private static final int QUEUE_CAPACITY = 1000; // 队列容量
    private static final int DEFAULT_HOST_RATE_LIMIT = 20; // 每 Host 每秒最大入队请求数
    private static final int DEFAULT_BATCH_MAX_REQUESTS = 8; // 会话批次满多少请求触发分析
    private static final long DEFAULT_BATCH_WINDOW_MS = 30_000; // 会话窗口空闲多久触发分析
    
    // ========== 核心组件 ==========
    private final MontoyaApi api;
    private PassiveScanApiClient apiClient;
    
    // ========== 生产者-消费者模型 ==========
    // 请求队列（生产者放入，消费者取出）
    private final BlockingQueue<ScanResult> scanQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    
    // 消费者线程池
    private ExecutorService consumerExecutor;
    private int threadCount = DEFAULT_THREAD_COUNT;
    
    // HTTP 处理器注册（用于注销）
    private HttpHandler httpHandler;
    private Registration httpHandlerRegistration;
    
    // ========== 状态管理 ==========
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger queuedCount = new AtomicInteger(0); // 队列中等待的数量
    
    // ========== 扫描结果 ==========
    private final List<ScanResult> scanResults = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> scannedKeys = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger nextId = new AtomicInteger(1);

    // ========== 成本控制 ==========
    // L4 Host 限流：每 Host 每秒入队上限，防止单站点高频流量打爆队列/API 配额
    private HostRateLimiter hostRateLimiter = new HostRateLimiter(DEFAULT_HOST_RATE_LIMIT);
    private final AtomicInteger rateLimitedCount = new AtomicInteger(0); // 被限流丢弃的请求数

    // L3 会话批次：连续流量按会话聚合，一次 LLM 调用做序列级（逻辑漏洞）分析
    private SessionBatchCollector batchCollector =
            new SessionBatchCollector(DEFAULT_BATCH_MAX_REQUESTS, DEFAULT_BATCH_WINDOW_MS);
    private ScheduledExecutorService batchScheduler; // 过期窗口扫描（1 秒间隔）
    
    // ========== 回调函数 ==========
    private Consumer<ScanResult> onResultUpdated;
    private Consumer<ScanResult> onHighRiskResult;
    private Consumer<String> onStatusChanged;
    private Consumer<Integer> onProgressChanged;
    private Consumer<ScanResult> onNewRequestQueued; // 新请求入队回调
    private Consumer<String> onStreamingChunk; // 流式输出回调（新增）
    private volatile ScanResult currentStreamingScanResult; // 当前正在流式输出的扫描结果
    
    /**
     * 构造函数
     */
    public PassiveScanManager(MontoyaApi api) {
        this.api = api;
        this.apiClient = new PassiveScanApiClient(api);
    }
    
    // ========== 配置方法 ==========
    
    public void setThreadCount(int count) {
        this.threadCount = Math.max(MIN_THREAD_COUNT, Math.min(MAX_THREAD_COUNT, count));
        logInfo("消费者线程数设置为: " + this.threadCount);
    }
    
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * 设置每 Host 每秒最大入队请求数（L4 限流）。
     */
    public void setHostRateLimit(int maxPerSecond) {
        this.hostRateLimiter = new HostRateLimiter(maxPerSecond);
        logInfo("Host 限流设置为: " + maxPerSecond + " req/s");
    }

    public int getHostRateLimit() {
        return hostRateLimiter != null ? hostRateLimiter.maxPerSecond() : DEFAULT_HOST_RATE_LIMIT;
    }

    public int getRateLimitedCount() {
        return rateLimitedCount.get();
    }

    /**
     * 配置会话批次参数（L3）。
     *
     * @param maxRequests 批次满多少请求触发分析（至少 1）
     * @param windowMs    窗口空闲多久触发分析（毫秒，至少 1）
     */
    public void setBatchConfig(int maxRequests, long windowMs) {
        this.batchCollector = new SessionBatchCollector(maxRequests, windowMs);
        logInfo("会话批次参数设置为: " + maxRequests + " 请求 / " + windowMs + "ms 窗口");
    }

    public SessionBatchCollector getBatchCollector() {
        return batchCollector;
    }
    
    public void setApiClient(PassiveScanApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    public PassiveScanApiClient getApiClient() {
        return apiClient;
    }
    
    // ========== 回调设置 ==========
    
    public void setOnResultUpdated(Consumer<ScanResult> callback) {
        this.onResultUpdated = callback;
    }

    /**
     * 注册高危结果回调：单请求分析完成且风险等级为 HIGH/CRITICAL 时触发一次，
     * 用于被动 → 主动协作（自动进入主动审计队列）。
     */
    public void setOnHighRiskResult(Consumer<ScanResult> callback) {
        this.onHighRiskResult = callback;
    }
    
    public void setOnStatusChanged(Consumer<String> callback) {
        this.onStatusChanged = callback;
    }
    
    public void setOnProgressChanged(Consumer<Integer> callback) {
        this.onProgressChanged = callback;
    }
    
    public void setOnNewRequestQueued(Consumer<ScanResult> callback) {
        this.onNewRequestQueued = callback;
    }
    
    /**
     * 设置流式输出回调
     * @param callback 接收流式输出文本块的回调函数
     */
    public void setOnStreamingChunk(Consumer<String> callback) {
        this.onStreamingChunk = callback;
    }
    
    /**
     * 获取当前正在流式输出的扫描结果
     */
    public ScanResult getCurrentStreamingScanResult() {
        return currentStreamingScanResult;
    }
    
    // ========== 生产者-消费者核心方法 ==========
    
    /**
     * 开始被动扫描
     * 启动生产者（HTTP监听）和消费者（扫描线程池）
     */
    public void startPassiveScan() {
        if (isRunning.get()) {
            logInfo("被动扫描已在运行中");
            return;
        }
        
        logInfo("========== 启动被动扫描（生产者-消费者模型）==========");
        
        // 重置状态
        isRunning.set(true);
        cancelFlag.set(false);
        completedCount.set(0);
        queuedCount.set(0);
        
        // 1. 启动消费者线程池
        startConsumers();

        // 2. 启动会话批次调度器（过期窗口 → 提交分析）
        startBatchScheduler();

        // 3. 启动生产者（HTTP监听）
        startProducer();
        
        // 注意：不加载历史记录，只扫描开启后新到达的流量
        // （原 loadExistingHistory() 已移除，符合“仅扫描新增流量”的设计）
        
        notifyStatusChanged("被动扫描已启动 - 监听中...");
        logInfo("被动扫描已启动，等待新流量...");
    }
    
    /**
     * 停止被动扫描
     */
    public void stopPassiveScan() {
        if (!isRunning.get()) {
            return;
        }
        
        logInfo("========== 停止被动扫描 ==========");
        
        // 设置停止标志
        isRunning.set(false);
        cancelFlag.set(true);
        
        // 1. 停止生产者（注销 HTTP 处理器）
        stopProducer();

        // 2. 停止会话批次调度器并丢弃残留批次
        stopBatchScheduler();

        // 3. 停止消费者
        stopConsumers();
        
        // 4. 清空队列
        scanQueue.clear();
        queuedCount.set(0);
        
        // 5. 标记未完成的任务为取消
        markPendingAsCancelled();
        
        notifyStatusChanged("被动扫描已停止");
        logInfo("被动扫描已停止");
    }
    
    /**
     * 启动生产者 - 注册 HTTP 处理器监听所有流量
     */
    private void startProducer() {
        httpHandler = new HttpHandler() {
            @Override
            public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
                // 请求发送前不处理
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            
            @Override
            public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
                if (isRunning.get() && !cancelFlag.get()) {
                    try {
                        // 只扫描来自 Proxy 的流量（用户浏览器），
                        // 跳过 Extensions/Scanner/Intruder/Repeater 产生的请求，防止插件工具触发死循环
                        ToolType source = responseReceived.toolSource().toolType();
                        if (source != ToolType.PROXY) {
                            return ResponseReceivedAction.continueWith(responseReceived);
                        }
                        
                        HttpRequestResponse requestResponse = HttpRequestResponse.httpRequestResponse(
                            responseReceived.initiatingRequest(),
                            responseReceived
                        );
                        enqueueRequest(requestResponse);
                    } catch (Exception e) {
                        logError("处理响应时出错: " + e.getMessage());
                    }
                }
                return ResponseReceivedAction.continueWith(responseReceived);
            }
        };
        
        // 注册 HTTP 处理器，保存 Registration 以便后续注销
        httpHandlerRegistration = api.http().registerHttpHandler(httpHandler);
        logInfo("生产者已启动 - HTTP 监听器已注册");
    }
    
    /**
     * 停止生产者 - 注销 HTTP 处理器
     */
    private void stopProducer() {
        if (httpHandlerRegistration != null) {
            try {
                httpHandlerRegistration.deregister();
            } catch (Exception e) {
                logError("注销 HTTP 处理器失败: " + e.getMessage());
            }
            httpHandlerRegistration = null;
        }
        httpHandler = null;
        logInfo("生产者已停止");
    }
    
    /**
     * 启动消费者线程池
     */
    private void startConsumers() {
        consumerExecutor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r);
            t.setName("PassiveScan-Consumer-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        
        // 启动消费者任务
        for (int i = 0; i < threadCount; i++) {
            consumerExecutor.submit(this::consumerTask);
        }
        
        logInfo("消费者已启动 - " + threadCount + " 个扫描线程");
    }

    /**
     * 启动会话批次调度器：每秒扫描过期窗口（空闲超时未满批次的会话）并提交分析。
     */
    private void startBatchScheduler() {
        batchScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("PassiveScan-BatchScheduler");
            t.setDaemon(true);
            return t;
        });
        batchScheduler.scheduleAtFixedRate(() -> {
            try {
                for (SessionBatchCollector.Batch batch : batchCollector.expire(System.currentTimeMillis())) {
                    submitBatch(batch);
                }
            } catch (Exception e) {
                logError("批次调度异常: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
        logInfo("会话批次调度器已启动");
    }

    /**
     * 停止批次调度器并丢弃所有未提交的窗口批次。
     */
    private void stopBatchScheduler() {
        if (batchScheduler != null) {
            batchScheduler.shutdownNow();
            batchScheduler = null;
        }
        int dropped = batchCollector.pendingRequestCount();
        batchCollector.clear();
        if (dropped > 0) {
            logInfo("丢弃未完成的会话批次: " + dropped + " 个请求");
        }
    }
    
    /**
     * 停止消费者
     */
    private void stopConsumers() {
        if (consumerExecutor != null && !consumerExecutor.isShutdown()) {
            consumerExecutor.shutdownNow();
            try {
                if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logError("消费者线程池未能在5秒内关闭");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consumerExecutor = null;
            logInfo("消费者已停止");
        }
    }
    
    /**
     * 消费者任务 - 从队列取请求进行扫描
     */
    private void consumerTask() {
        logInfo("消费者线程启动: " + Thread.currentThread().getName());
        
        while (isRunning.get() && !cancelFlag.get()) {
            try {
                // 从队列取请求（阻塞等待，超时1秒）
                ScanResult result = scanQueue.poll(1, TimeUnit.SECONDS);
                
                if (result == null) {
                    // 超时，继续等待
                    continue;
                }
                
                // 更新队列计数
                queuedCount.decrementAndGet();
                
                // 检查是否已取消
                if (cancelFlag.get()) {
                    result.markCancelled();
                    notifyResultUpdated(result);
                    continue;
                }
                
                // 执行扫描
                scanRequest(result);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logError("消费者任务异常: " + e.getMessage());
            }
        }
        
        logInfo("消费者线程退出: " + Thread.currentThread().getName());
    }
    
    /**
     * 将请求放入扫描队列（生产者调用）
     */
    private void enqueueRequest(HttpRequestResponse requestResponse) {
        // L0 过滤静态资源（无需持锁，纯读操作）
        if (PassiveScanTask.shouldSkipRequest(requestResponse)) {
            return;
        }

        // L4 Host 限流：高频流量（同一 Host 短时间大量请求）在此丢弃。
        // 高频请求通常与已分析请求高度重复，损失可忽略；同时保护 LLM API 配额。
        String host = RequestFingerprint.hostOf(requestResponse);
        if (!hostRateLimiter.tryAcquire(host)) {
            rateLimitedCount.incrementAndGet();
            return;
        }

        // L1 精确去重：method|host|path|规范化query|body摘要。
        // 参数值变化（id=1→id=2）不重复丢弃，保留 IDOR/逻辑漏洞探测信号。
        String dedupeKey = RequestFingerprint.of(requestResponse);
        if (!scannedKeys.add(dedupeKey)) {
            return;
        }

        // L3 会话批次聚合：请求先进入会话窗口，满批次或窗口过期时打包为一次序列级分析。
        // 单请求在窗口空闲超时后也会以批次（1 个请求）形式提交，保证低流量场景不饿死。
        SessionBatchCollector.Batch fullBatch = batchCollector.add(requestResponse);
        if (fullBatch != null) {
            submitBatch(fullBatch);
        }
    }

    /**
     * 提交一个会话批次：创建批次 ScanResult 并入队（一次 LLM 调用分析整个序列）。
     */
    private void submitBatch(SessionBatchCollector.Batch batch) {
        if (batch == null || batch.requests.isEmpty()) return;
        HttpRequestResponse primary = batch.requests.get(0);
        ScanResult result = new ScanResult(nextId.getAndIncrement(), primary, batch.requests);

        scanResults.add(result);
        totalCount.incrementAndGet();

        boolean offered = scanQueue.offer(result);
        if (offered) {
            queuedCount.incrementAndGet();
            if (onNewRequestQueued != null) {
                try {
                    onNewRequestQueued.accept(result);
                } catch (Exception e) {
                    logError("新请求回调异常: " + e.getMessage());
                }
            }
            updateStatus();
        } else {
            logError("扫描队列已满，丢弃批次: " + result.getShortUrl());
            result.markError("队列已满");
        }
    }
    
    /**
     * 执行单个请求的扫描
     */
    private void scanRequest(ScanResult result) {
        try {
            // 标记为扫描中
            result.markScanning();
            notifyResultUpdated(result);
            
            // 设置当前正在流式输出的扫描结果
            currentStreamingScanResult = result;
            
            // 调用 AI 分析（支持流式输出）：批次结果走序列级分析，单请求走单请求分析
            Consumer<String> streamHandler = chunk -> {
                // 流式输出回调
                if (onStreamingChunk != null && currentStreamingScanResult == result) {
                    try {
                        onStreamingChunk.accept(chunk);
                    } catch (Exception e) {
                        logError("流式输出回调异常: " + e.getMessage());
                    }
                }
            };
            String aiResponse = result.isBatch()
                ? apiClient.analyzeBatch(result.getBatchRequests(), cancelFlag, streamHandler)
                : apiClient.analyzeRequest(result.getRequestResponse(), cancelFlag, streamHandler);
            
            // 清除当前流式输出的扫描结果
            if (currentStreamingScanResult == result) {
                currentStreamingScanResult = null;
            }
            
            // 检查是否已取消
            if (cancelFlag.get()) {
                result.markCancelled();
            } else {
                // 标记完成（会自动解析风险等级）
                result.markCompleted(aiResponse);
            }
            
        } catch (Exception e) {
            // 清除当前流式输出的扫描结果
            if (currentStreamingScanResult == result) {
                currentStreamingScanResult = null;
            }
            
            if (cancelFlag.get()) {
                result.markCancelled();
            } else {
                result.markError(e.getMessage());
                logError("扫描请求失败: " + result.getShortUrl() + " - " + e.getMessage());
            }
        }
        
        // 更新计数和通知
        int completed = completedCount.incrementAndGet();
        notifyResultUpdated(result);
        notifyHighRiskIfApplicable(result);
        updateStatus();
        
        // 通知进度
        int total = totalCount.get();
        if (total > 0) {
            notifyProgressChanged(completed * 100 / total);
        }
    }
    
    /**
     * 更新状态显示
     */
    private void updateStatus() {
        int queued = queuedCount.get();
        int completed = completedCount.get();
        int total = totalCount.get();
        
        String status = String.format("被动扫描中 - 队列: %d, 已完成: %d/%d", queued, completed, total);
        notifyStatusChanged(status);
    }
    
    /**
     * 标记所有等待中的任务为取消
     */
    private void markPendingAsCancelled() {
        synchronized (scanResults) {
            for (ScanResult result : scanResults) {
                if (result.getStatus() == ScanResult.ScanStatus.PENDING ||
                    result.getStatus() == ScanResult.ScanStatus.SCANNING) {
                    result.markCancelled();
                    notifyResultUpdated(result);
                }
            }
        }
    }
    
    // ========== 手动添加请求 ==========
    
    /**
     * 手动添加单个请求到扫描队列
     */
    /**
     * 手动添加单个请求并立即异步分析（右键「发送到AI分析」入口）。
     * 即使被动扫描未启动也能独立分析，结果通过 onResultUpdated 回调通知 UI。
     */
    public void analyzeSingleRequest(HttpRequestResponse requestResponse) {
        ScanResult result = addRequest(requestResponse);
        if (result != null) {
            new Thread(() -> scanRequest(result), "pscan-direct-" + result.getId()).start();
        }
    }

    public ScanResult addRequest(HttpRequestResponse requestResponse) {
        ScanResult result = new ScanResult(nextId.getAndIncrement(), requestResponse);
        String dedupeKey = result.getDeduplicationKey();
        
        if (scannedKeys.contains(dedupeKey)) {
            logInfo("请求已存在，跳过: " + result.getShortUrl());
            return null;
        }
        
        scannedKeys.add(dedupeKey);
        scanResults.add(result);
        totalCount.incrementAndGet();
        
        if (isRunning.get()) {
            if (scanQueue.offer(result)) {
                queuedCount.incrementAndGet();
                if (onNewRequestQueued != null) {
                    onNewRequestQueued.accept(result);
                }
            } else {
                result.markError("队列已满");
            }
        }
        updateStatus();
        return result;
    }
    
    // ========== 主动审计结果合并 ==========

    /**
     * 把 Burp 主动审计发现的问题作为已完成结果直接并入结果列表（不再入队 AI 分析）。
     * 与被动结果共用同一列表与去重键，UI 无需额外处理。
     */
    public ScanResult addAuditIssue(burp.api.montoya.scanner.audit.issues.AuditIssue issue) {
        if (issue == null) {
            return null;
        }
        ScanResult result = ScanResult.fromAuditIssue(nextId.getAndIncrement(), issue);
        String dedupeKey = result.getDeduplicationKey();
        if (scannedKeys.contains(dedupeKey)) {
            logInfo("主动审计问题已存在，跳过: " + result.getShortUrl());
            return null;
        }
        scannedKeys.add(dedupeKey);
        scanResults.add(result);
        completedCount.incrementAndGet();
        totalCount.incrementAndGet();
        notifyResultUpdated(result);
        updateStatus();
        return result;
    }

    // ========== 清空和重置 ==========
    
    /**
     * 清空扫描结果
     */
    public void clearResults() {
        if (isRunning.get()) {
            stopPassiveScan();
        }
        
        scanResults.clear();
        scannedKeys.clear();
        scanQueue.clear();
        completedCount.set(0);
        totalCount.set(0);
        queuedCount.set(0);
        nextId.set(1);
        rateLimitedCount.set(0);
        hostRateLimiter.reset();
        batchCollector.clear();
        
        notifyStatusChanged("已清空");
        logInfo("扫描结果已清空");
    }
    
    // ========== 状态查询 ==========
    
    public boolean isRunning() {
        return isRunning.get();
    }
    
    public int getCompletedCount() {
        return completedCount.get();
    }
    
    public int getTotalCount() {
        return totalCount.get();
    }
    
    public int getQueuedCount() {
        return queuedCount.get();
    }
    
    public int getProgress() {
        int total = totalCount.get();
        if (total == 0) return 0;
        return completedCount.get() * 100 / total;
    }
    
    public List<ScanResult> getScanResults() {
        return new ArrayList<>(scanResults);
    }
    
    public ScanResult getResultById(int id) {
        synchronized (scanResults) {
            for (ScanResult result : scanResults) {
                if (result.getId() == id) {
                    return result;
                }
            }
        }
        return null;
    }
    
    public Map<ScanResult.RiskLevel, Integer> getStatsByRiskLevel() {
        Map<ScanResult.RiskLevel, Integer> stats = new EnumMap<>(ScanResult.RiskLevel.class);
        for (ScanResult.RiskLevel level : ScanResult.RiskLevel.values()) {
            stats.put(level, 0);
        }
        
        synchronized (scanResults) {
            for (ScanResult result : scanResults) {
                if (result.getStatus() == ScanResult.ScanStatus.COMPLETED) {
                    ScanResult.RiskLevel level = result.getRiskLevel();
                    stats.put(level, stats.get(level) + 1);
                }
            }
        }
        
        return stats;
    }
    
    // ========== 回调通知 ==========
    
    private void notifyResultUpdated(ScanResult result) {
        if (onResultUpdated != null) {
            try {
                onResultUpdated.accept(result);
            } catch (Exception e) {
                logError("结果更新回调异常: " + e.getMessage());
            }
        }
    }

    /**
     * 高危（HIGH/CRITICAL）且已完成的结果触发协作回调（被动 → 主动审计队列）。
     * 批次结果（isBatch）不触发，仅针对单请求分析。
     */
    private void notifyHighRiskIfApplicable(ScanResult result) {
        if (onHighRiskResult == null || result == null) {
            return;
        }
        if (result.getStatus() != ScanResult.ScanStatus.COMPLETED || result.isBatch()) {
            return;
        }
        ScanResult.RiskLevel level = result.getRiskLevel();
        if (level != ScanResult.RiskLevel.HIGH && level != ScanResult.RiskLevel.CRITICAL) {
            return;
        }
        if (result.getRequestResponse() == null) {
            return;
        }
        try {
            onHighRiskResult.accept(result);
        } catch (Exception e) {
            logError("高危结果回调异常: " + e.getMessage());
        }
    }
    
    private void notifyStatusChanged(String status) {
        if (onStatusChanged != null) {
            try {
                onStatusChanged.accept(status);
            } catch (Exception e) {
                logError("状态变化回调异常: " + e.getMessage());
            }
        }
    }
    
    private void notifyProgressChanged(int progress) {
        if (onProgressChanged != null) {
            try {
                onProgressChanged.accept(progress);
            } catch (Exception e) {
                logError("进度变化回调异常: " + e.getMessage());
            }
        }
    }
    
    // ========== 日志 ==========
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[PassiveScanManager] " + message);
        }
    }
    
    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[PassiveScanManager] " + message);
        }
    }
}
