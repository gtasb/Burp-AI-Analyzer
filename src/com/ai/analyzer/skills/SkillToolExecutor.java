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
 *
 * 负责执行 SkillTool 定义的本地命令/二进制文件/脚本。
 * 所有执行请求都经过 {@link ExecutionPolicy} 安全校验。
 *
 * 安全措施：
 * - ExecutionPolicy 校验（路径存在性、扩展名白名单、参数注入检测）
 * - 执行超时强制终止
 * - 输出大小限制（防止 OOM）
 * - 执行日志审计
 */
public class SkillToolExecutor {

    private static final int MAX_OUTPUT_SIZE = 100 * 1024; // 100KB
    private static final int DEFAULT_TIMEOUT = 120;

    private MontoyaApi api;
    private ExecutionPolicy policy;

    public SkillToolExecutor() {
        this.policy = new ExecutionPolicy();
    }

    public SkillToolExecutor(MontoyaApi api) {
        this.api = api;
        this.policy = new ExecutionPolicy();
    }

    public SkillToolExecutor(MontoyaApi api, ExecutionPolicy policy) {
        this.api = api;
        this.policy = policy != null ? policy : new ExecutionPolicy();
    }

    public void setApi(MontoyaApi api) {
        this.api = api;
    }

    public ExecutionPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(ExecutionPolicy policy) {
        this.policy = policy != null ? policy : new ExecutionPolicy();
    }

    /**
     * 执行 SkillTool 定义的命令。
     * 先进行安全策略校验，通过后才执行。
     */
    public ExecutionResult execute(SkillTool tool, Map<String, String> paramValues) {
        ExecutionResult result = new ExecutionResult();
        result.setToolName(tool != null ? tool.getName() : "unknown");
        result.setStartTime(System.currentTimeMillis());

        try {
            // 安全策略校验
            ExecutionPolicy.ValidationResult validation = policy.validate(tool, paramValues);
            if (!validation.isSuccess()) {
                result.setSuccess(false);
                result.setError("安全策略拒绝: " + validation.getMessage());
                logError("安全策略拒绝执行 " + tool.getName() + ": " + validation.getMessage());
                return result;
            }

            // 参数清理（复制为可变 Map 以支持 Map.of() 等不可变输入）
            if (paramValues != null && !paramValues.isEmpty()) {
                java.util.Map<String, String> mutable = new java.util.HashMap<>(paramValues);
                mutable.replaceAll((k, v) -> policy.sanitizeArgValue(v));
                paramValues = mutable;
            }

            // 验证参数完整性
            tool.validateParameters(paramValues);

            // 构建命令
            List<String> command = buildCommand(tool, paramValues);
            result.setCommand(String.join(" ", command));

            logInfo("执行 Skill 工具: " + tool.getName());
            logInfo("命令: " + result.getCommand());

            // 创建进程
            ProcessBuilder pb = new ProcessBuilder(command);

            if (tool.getWorkingDir() != null && !tool.getWorkingDir().isEmpty()) {
                File workDir = new File(tool.getWorkingDir());
                if (workDir.exists() && workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }

            pb.redirectErrorStream(true);

            Process process = pb.start();

            int timeout = tool.getTimeout() > 0 ? tool.getTimeout() : DEFAULT_TIMEOUT;
            String output = readProcessOutput(process, timeout);

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
            logInfo("工具执行完成，耗时: " + result.getDuration() + "ms, 成功: " + result.isSuccess());
        }

        return result;
    }

    private List<String> buildCommand(SkillTool tool, Map<String, String> paramValues) {
        List<String> command = new ArrayList<>();

        String cmd = tool.getCommand();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        String args = tool.buildArgs(paramValues);
        String fullCommand = cmd + (args.isEmpty() ? "" : " " + args);

        if (ExecutionPolicy.containsShellOperators(fullCommand)) {
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
            command.add(cmd);
            if (!args.isEmpty()) {
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
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    output.append("\n[输出读取超时]");
                    break;
                }

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

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public int getExitCode() { return exitCode; }
        public void setExitCode(int exitCode) { this.exitCode = exitCode; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }

        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }

        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }

        public String toAIReadableFormat() {
            StringBuilder sb = new StringBuilder();
            sb.append("## 工具执行结果: ").append(toolName).append("\n\n");
            sb.append("**状态**: ").append(success ? "成功" : "失败").append("\n");
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
