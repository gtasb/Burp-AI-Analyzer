package com.ai.analyzer.skills;

import burp.api.montoya.MontoyaApi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Skill 工具执行器
 * 负责执行 SkillTool 定义的本地命令/可执行文件
 * 
 * 安全考虑：
 * - 限制执行超时
 * - 限制输出大小
 * - 记录执行日志
 */
public class SkillToolExecutor {
    
    private static final int MAX_OUTPUT_SIZE = 100 * 1024; // 最大输出 100KB
    private static final int DEFAULT_TIMEOUT = 120; // 默认超时 120 秒
    
    private MontoyaApi api;
    private ExecutorService executor;
    
    public SkillToolExecutor() {
        this.executor = Executors.newCachedThreadPool();
    }
    
    public SkillToolExecutor(MontoyaApi api) {
        this.api = api;
        this.executor = Executors.newCachedThreadPool();
    }
    
    public void setApi(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 执行 SkillTool 定义的命令
     * 
     * @param tool 工具定义
     * @param paramValues 参数值映射
     * @return 执行结果
     */
    public ExecutionResult execute(SkillTool tool, Map<String, String> paramValues) {
        ExecutionResult result = new ExecutionResult();
        result.setToolName(tool.getName());
        result.setStartTime(System.currentTimeMillis());
        
        try {
            // 验证参数
            tool.validateParameters(paramValues);
            
            // 构建命令
            List<String> command = buildCommand(tool, paramValues);
            result.setCommand(String.join(" ", command));
            
            logInfo("执行 Skill 工具: " + tool.getName());
            logInfo("命令: " + result.getCommand());
            
            // 创建进程
            ProcessBuilder pb = new ProcessBuilder(command);
            
            // 设置工作目录
            if (tool.getWorkingDir() != null && !tool.getWorkingDir().isEmpty()) {
                File workDir = new File(tool.getWorkingDir());
                if (workDir.exists() && workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }
            
            // 合并错误流到标准输出
            pb.redirectErrorStream(true);
            
            // 设置环境变量（继承当前环境）
            Map<String, String> env = pb.environment();
            // 可以在这里添加额外的环境变量
            
            // 启动进程
            Process process = pb.start();
            
            // 读取输出（带超时）
            int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : DEFAULT_TIMEOUT;
            String output = readProcessOutput(process, timeout);
            
            // 等待进程结束
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                result.setSuccess(false);
                result.setError("执行超时（超过 " + timeout + " 秒）");
                result.setOutput(output + "\n[执行超时，进程已终止]");
            } else {
                int exitCode = process.exitValue();
                result.setExitCode(exitCode);
                result.setSuccess(exitCode == 0);
                result.setOutput(output);
                
                if (exitCode != 0) {
                    result.setError("进程退出码: " + exitCode);
                }
            }
            
        } catch (IllegalArgumentException e) {
            result.setSuccess(false);
            result.setError("参数错误: " + e.getMessage());
            logError("参数错误: " + e.getMessage());
        } catch (IOException e) {
            result.setSuccess(false);
            result.setError("执行失败: " + e.getMessage());
            logError("执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            result.setSuccess(false);
            result.setError("执行被中断");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            result.setSuccess(false);
            result.setError("未知错误: " + e.getMessage());
            logError("未知错误: " + e.getMessage());
        } finally {
            result.setEndTime(System.currentTimeMillis());
            result.setDuration(result.getEndTime() - result.getStartTime());
            logInfo("工具执行完成，耗时: " + result.getDuration() + "ms");
        }
        
        return result;
    }
    
    /**
     * 构建命令列表
     */
    private List<String> buildCommand(SkillTool tool, Map<String, String> paramValues) {
        List<String> command = new ArrayList<>();
        
        String cmd = tool.getCommand();
        
        // 判断是否需要通过 shell 执行
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        // 如果命令包含特殊字符或管道，需要通过 shell 执行
        String args = tool.buildArgs(paramValues);
        String fullCommand = cmd + (args.isEmpty() ? "" : " " + args);
        
        if (fullCommand.contains("|") || fullCommand.contains("&&") || fullCommand.contains("||") ||
            fullCommand.contains(">") || fullCommand.contains("<")) {
            // 需要通过 shell 执行
            if (isWindows) {
                command.add("cmd");
                command.add("/c");
                command.add(fullCommand);
            } else {
                command.add("/bin/sh");
                command.add("-c");
                command.add(fullCommand);
            }
        } else {
            // 直接执行
            command.add(cmd);
            if (!args.isEmpty()) {
                // 分割参数
                String[] argParts = args.split("\\s+");
                for (String arg : argParts) {
                    if (!arg.isEmpty()) {
                        command.add(arg);
                    }
                }
            }
        }
        
        return command;
    }
    
    /**
     * 读取进程输出（带大小限制）
     */
    private String readProcessOutput(Process process, int timeoutSeconds) throws IOException {
        StringBuilder output = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            
            char[] buffer = new char[4096];
            int totalRead = 0;
            int read;
            
            long startTime = System.currentTimeMillis();
            long timeoutMs = timeoutSeconds * 1000L;
            
            while ((read = reader.read(buffer)) != -1) {
                // 检查超时
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    output.append("\n[输出读取超时]");
                    break;
                }
                
                // 检查大小限制
                if (totalRead + read > MAX_OUTPUT_SIZE) {
                    int remaining = MAX_OUTPUT_SIZE - totalRead;
                    if (remaining > 0) {
                        output.append(buffer, 0, remaining);
                    }
                    output.append("\n[输出已截断，超过最大限制 " + (MAX_OUTPUT_SIZE / 1024) + "KB]");
                    break;
                }
                
                output.append(buffer, 0, read);
                totalRead += read;
            }
        }
        
        return output.toString();
    }
    
    /**
     * 关闭执行器
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[SkillToolExecutor] " + message);
        } else {
            System.out.println("[SkillToolExecutor] " + message);
        }
    }
    
    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[SkillToolExecutor] " + message);
        } else {
            System.err.println("[SkillToolExecutor] " + message);
        }
    }
    
    /**
     * 工具执行结果
     */
    public static class ExecutionResult {
        private String toolName;
        private String command;
        private boolean success;
        private int exitCode;
        private String output;
        private String error;
        private long startTime;
        private long endTime;
        private long duration;
        
        // Getters and Setters
        public String getToolName() {
            return toolName;
        }
        
        public void setToolName(String toolName) {
            this.toolName = toolName;
        }
        
        public String getCommand() {
            return command;
        }
        
        public void setCommand(String command) {
            this.command = command;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public int getExitCode() {
            return exitCode;
        }
        
        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }
        
        public String getOutput() {
            return output;
        }
        
        public void setOutput(String output) {
            this.output = output;
        }
        
        public String getError() {
            return error;
        }
        
        public void setError(String error) {
            this.error = error;
        }
        
        public long getStartTime() {
            return startTime;
        }
        
        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }
        
        public long getEndTime() {
            return endTime;
        }
        
        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }
        
        public long getDuration() {
            return duration;
        }
        
        public void setDuration(long duration) {
            this.duration = duration;
        }
        
        /**
         * 转换为适合AI阅读的格式
         */
        public String toAIReadableFormat() {
            StringBuilder sb = new StringBuilder();
            sb.append("## 工具执行结果: ").append(toolName).append("\n\n");
            sb.append("**状态**: ").append(success ? "成功 ✓" : "失败 ✗").append("\n");
            sb.append("**执行时间**: ").append(duration).append("ms\n");
            
            if (exitCode != 0) {
                sb.append("**退出码**: ").append(exitCode).append("\n");
            }
            
            if (error != null && !error.isEmpty()) {
                sb.append("**错误信息**: ").append(error).append("\n");
            }
            
            sb.append("\n**输出内容**:\n```\n");
            sb.append(output != null ? output : "(无输出)");
            sb.append("\n```\n");
            
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return toAIReadableFormat();
        }
    }
}
