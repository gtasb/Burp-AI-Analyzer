package com.ai.analyzer.pscan;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;

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
    private static final int DEFAULT_THREAD_COUNT = 3;
    private static final int MAX_THREAD_COUNT = 10;
    private static final int MIN_THREAD_COUNT = 1;
    private static final int QUEUE_CAPACITY = 1000; // 队列容量
    
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
    
    // ========== 回调函数 ==========
    private Consumer<ScanResult> onResultUpdated;
    private Consumer<String> onStatusChanged;
    private Consumer<Integer> onProgressChanged;
    private Consumer<ScanResult> onNewRequestQueued; // 新请求入队回调
    private Consumer<String> onStreamingChunk; // 流式输出回调（新增）
    private ScanResult currentStreamingScanResult; // 当前正在流式输出的扫描结果
    
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
        
        // 2. 启动生产者（HTTP监听）
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
        
        // 2. 停止消费者
        stopConsumers();
        
        // 3. 清空队列
        scanQueue.clear();
        queuedCount.set(0);
        
        // 4. 标记未完成的任务为取消
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
                // 响应接收后，将请求-响应对放入队列
                if (isRunning.get() && !cancelFlag.get()) {
                    try {
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
        
        // 注册 HTTP 处理器
        api.http().registerHttpHandler(httpHandler);
        logInfo("生产者已启动 - HTTP 监听器已注册");
    }
    
    /**
     * 停止生产者 - 注销 HTTP 处理器
     */
    private void stopProducer() {
        if (httpHandler != null) {
            // 注意：Montoya API 可能不支持直接注销 handler
            // 依靠 isRunning 标志来停止处理
            httpHandler = null;
            logInfo("生产者已停止");
        }
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
        String threadName = Thread.currentThread().getName();
        logInfo("消费者线程启动: " + threadName);
        
        int processedCount = 0;
        int errorCount = 0;
        
        while (isRunning.get() && !cancelFlag.get()) {
            try {
                // 从队列取请求（阻塞等待，超时1秒）
                ScanResult result = scanQueue.poll(1, TimeUnit.SECONDS);
                
                if (result == null) {
                    // 超时，继续等待（这是正常的，表示队列为空）
                    continue;
                }
                
                // 更新队列计数
                queuedCount.decrementAndGet();
                
                // 检查是否已取消
                if (cancelFlag.get()) {
                    logInfo("消费者线程检测到取消标志，取消扫描: " + result.getShortUrl());
                    result.markCancelled();
                    notifyResultUpdated(result);
                    continue;
                }
                
                // 检查是否还在运行
                if (!isRunning.get()) {
                    logInfo("消费者线程检测到停止标志，取消扫描: " + result.getShortUrl());
                    result.markCancelled();
                    notifyResultUpdated(result);
                    continue;
                }
                
                // 执行扫描
                processedCount++;
                logInfo("消费者线程处理第 " + processedCount + " 个请求: " + result.getShortUrl());
                scanRequest(result);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logInfo("消费者线程被中断: " + threadName);
                break;
            } catch (Exception e) {
                errorCount++;
                logError("消费者任务异常 [" + errorCount + "]: " + e.getMessage());
                e.printStackTrace(); // 打印完整堆栈，便于诊断
                // 继续处理下一个请求，不退出循环
            }
        }
        
        logInfo("消费者线程退出: " + threadName + 
                " (处理: " + processedCount + ", 错误: " + errorCount + ", isRunning: " + isRunning.get() + 
                ", cancelFlag: " + cancelFlag.get() + ")");
    }
    
    /**
     * 将请求放入扫描队列（生产者调用）
     */
    private void enqueueRequest(HttpRequestResponse requestResponse) {
        // 创建 ScanResult
        ScanResult result = new ScanResult(nextId.getAndIncrement(), requestResponse);
        
        // 去重检查
        String dedupeKey = result.getDeduplicationKey();
        if (scannedKeys.contains(dedupeKey)) {
            return; // 已扫描过，跳过
        }
        
        // 过滤静态资源
        if (PassiveScanTask.shouldSkipRequest(requestResponse)) {
            return;
        }
        
        // 添加到去重集合
        scannedKeys.add(dedupeKey);
        
        // 添加到结果列表
        scanResults.add(result);
        totalCount.incrementAndGet();
        
        // 尝试放入队列
        boolean offered = scanQueue.offer(result);
        if (offered) {
            queuedCount.incrementAndGet();
            
            // 通知 UI 新请求入队
            if (onNewRequestQueued != null) {
                try {
                    onNewRequestQueued.accept(result);
                } catch (Exception e) {
                    logError("新请求回调异常: " + e.getMessage());
                }
            }
            
            // 更新状态
            updateStatus();
        } else {
            // 队列已满
            logError("扫描队列已满，丢弃请求: " + result.getShortUrl());
            result.markError("队列已满");
        }
    }
    
    /**
     * 执行单个请求的扫描
     */
    private void scanRequest(ScanResult result) {
        long startTime = System.currentTimeMillis();
        try {
            // 标记为扫描中
            result.markScanning();
            notifyResultUpdated(result);
            
            // 设置当前正在流式输出的扫描结果
            currentStreamingScanResult = result;
            
            // 调用 AI 分析（支持流式输出）
            String aiResponse = apiClient.analyzeRequest(
                result.getRequestResponse(),
                cancelFlag,
                chunk -> {
                    // 流式输出回调
                    if (onStreamingChunk != null && currentStreamingScanResult == result) {
                        try {
                            onStreamingChunk.accept(chunk);
                        } catch (Exception e) {
                            logError("流式输出回调异常: " + e.getMessage());
                        }
                    }
                }
            );
            
            // 清除当前流式输出的扫描结果
            if (currentStreamingScanResult == result) {
                currentStreamingScanResult = null;
            }
            
            // 检查是否已取消
            if (cancelFlag.get()) {
                logInfo("扫描过程中检测到取消标志: " + result.getShortUrl());
                result.markCancelled();
            } else {
                // 标记完成（会自动解析风险等级）
                long duration = System.currentTimeMillis() - startTime;
                logInfo("扫描完成: " + result.getShortUrl() + " (耗时: " + duration + "ms)");
                result.markCompleted(aiResponse);
            }
            
        } catch (Exception e) {
            // 清除当前流式输出的扫描结果
            if (currentStreamingScanResult == result) {
                currentStreamingScanResult = null;
            }
            
            long duration = System.currentTimeMillis() - startTime;
            if (cancelFlag.get()) {
                logInfo("扫描已取消: " + result.getShortUrl() + " (耗时: " + duration + "ms)");
                result.markCancelled();
            } else {
                result.markError(e.getMessage());
                logError("扫描请求失败: " + result.getShortUrl() + " (耗时: " + duration + "ms) - " + e.getMessage());
                // 如果是超时异常，记录更详细的信息
                if (e.getMessage() != null && e.getMessage().contains("超时")) {
                    logError("  → 超时详情: 请求可能包含大量工具调用或响应较慢");
                }
            }
        }
        
        // 更新计数和通知
        int completed = completedCount.incrementAndGet();
        notifyResultUpdated(result);
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
    public ScanResult addRequest(HttpRequestResponse requestResponse) {
        if (!isRunning.get()) {
            logInfo("被动扫描未启动，请先启动");
            return null;
        }
        
        ScanResult result = new ScanResult(nextId.getAndIncrement(), requestResponse);
        String dedupeKey = result.getDeduplicationKey();
        
        if (scannedKeys.contains(dedupeKey)) {
            logInfo("请求已存在，跳过: " + result.getShortUrl());
            return null;
        }
        
        scannedKeys.add(dedupeKey);
        scanResults.add(result);
        totalCount.incrementAndGet();
        
        if (scanQueue.offer(result)) {
            queuedCount.incrementAndGet();
            if (onNewRequestQueued != null) {
                onNewRequestQueued.accept(result);
            }
            updateStatus();
            return result;
        } else {
            result.markError("队列已满");
            return result;
        }
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
