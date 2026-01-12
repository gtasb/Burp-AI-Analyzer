package com.ai.analyzer.utils;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 独立的文件日志系统
 * 
 * 功能：
 * 1. 输出到本地文件，不依赖 Burp API
 * 2. 带时间戳和调用栈信息
 * 3. 异步写入，不阻塞主线程
 * 4. 支持日志级别
 */
public class DebugLogger {
    
    private static DebugLogger instance;
    private static final String LOG_FILE = "burp_ai_debug.log";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private final Path logPath;
    private final BlockingQueue<String> logQueue;
    private final Thread writerThread;
    private volatile boolean running = true;
    
    // 日志级别
    public enum Level { DEBUG, INFO, WARN, ERROR }
    private Level minLevel = Level.DEBUG;
    
    private DebugLogger() {
        // 日志文件放在用户目录
        logPath = Paths.get(System.getProperty("user.home"), LOG_FILE);
        logQueue = new LinkedBlockingQueue<>();
        
        // 启动异步写入线程
        writerThread = new Thread(this::writeLoop, "DebugLogger-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
        
        // 写入启动信息
        info("========== 日志系统启动 ==========");
        info("日志文件: " + logPath.toAbsolutePath());
        info("时间: " + LocalDateTime.now());
    }
    
    public static synchronized DebugLogger getInstance() {
        if (instance == null) {
            instance = new DebugLogger();
        }
        return instance;
    }
    
    /**
     * 获取日志文件路径
     */
    public String getLogFilePath() {
        return logPath.toAbsolutePath().toString();
    }
    
    /**
     * 设置最小日志级别
     */
    public void setMinLevel(Level level) {
        this.minLevel = level;
    }
    
    // ========== 日志方法 ==========
    
    public void debug(String message) {
        log(Level.DEBUG, message);
    }
    
    public void info(String message) {
        log(Level.INFO, message);
    }
    
    public void warn(String message) {
        log(Level.WARN, message);
    }
    
    public void error(String message) {
        log(Level.ERROR, message);
    }
    
    public void error(String message, Throwable e) {
        log(Level.ERROR, message + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
        // 打印堆栈
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        log(Level.ERROR, sw.toString());
    }
    
    /**
     * 带调用者信息的日志
     */
    public void trace(String message) {
        StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        String callerInfo = caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1) 
                         + "." + caller.getMethodName() 
                         + ":" + caller.getLineNumber();
        log(Level.DEBUG, "[" + callerInfo + "] " + message);
    }
    
    /**
     * 核心日志方法
     */
    private void log(Level level, String message) {
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }
        
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String threadName = Thread.currentThread().getName();
        String logLine = String.format("[%s] [%s] [%s] %s", 
                timestamp, level.name(), threadName, message);
        
        logQueue.offer(logLine);
    }
    
    /**
     * 异步写入循环
     */
    private void writeLoop() {
        try (BufferedWriter writer = Files.newBufferedWriter(logPath, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND)) {
            
            while (running || !logQueue.isEmpty()) {
                try {
                    String line = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("DebugLogger 写入失败: " + e.getMessage());
        }
    }
    
    /**
     * 清空日志文件
     */
    public void clear() {
        try {
            Files.write(logPath, new byte[0]);
            info("========== 日志已清空 ==========");
        } catch (IOException e) {
            // 忽略
        }
    }
    
    /**
     * 关闭日志系统
     */
    public void shutdown() {
        info("========== 日志系统关闭 ==========");
        running = false;
        try {
            writerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ========== 便捷静态方法 ==========
    
    public static void d(String message) {
        getInstance().debug(message);
    }
    
    public static void i(String message) {
        getInstance().info(message);
    }
    
    public static void w(String message) {
        getInstance().warn(message);
    }
    
    public static void e(String message) {
        getInstance().error(message);
    }
    
    public static void e(String message, Throwable t) {
        getInstance().error(message, t);
    }
    
    public static void t(String message) {
        getInstance().trace(message);
    }
}
