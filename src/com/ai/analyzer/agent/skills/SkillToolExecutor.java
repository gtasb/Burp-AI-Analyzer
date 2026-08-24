package com.ai.analyzer.agent.skills;

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
            // 在单独线程中读取输出，避免 stdout 缓冲区满导致进程阻塞，
            // 主线程同步等待进程结束（共用同一 timeout，不超过配置值）
            java.util.concurrent.atomic.AtomicReference<String> outputRef = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    outputRef.set(readProcessOutput(process, timeout));
                } catch (IOException e) {
                    outputRef.set("[读取输出失败: " + e.getMessage() + "]");
                }
            }, "skill-stdout-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                reader.interrupt();
                result.setSuccess(false);
                result.setError("执行超时（超过 " + timeout + " 秒）");
                result.setOutput((outputRef.get() != null ? outputRef.get() : "") + "\n[执行超时，进程已终止]");
            } else {
                // 等读取线程完成
                try { reader.join(5000); } catch (InterruptedException ignored) {}
                int exitCode = process.exitValue();
                result.setExitCode(exitCode);
                result.setSuccess(exitCode == 0);
                result.setOutput(outputRef.get() != null ? outputRef.get() : "");
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

        boolean needsShell = ExecutionPolicy.containsShellOperators(fullCommand)
                || (isWindows && (cmd.toLowerCase().endsWith(".bat") || cmd.toLowerCase().endsWith(".cmd")));

        if (needsShell) {
            if (isWindows) {
                command.add("cmd.exe");
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
                command.addAll(tokenizeArgs(args));
            }
        }

        return command;
    }

    /**
     * 引号感知的命令行拆词（支持单引号、双引号与反斜杠转义）。
     * 这样带空格且被引号包裹的参数（如 Windows 带空格路径）不会在
     * {@code split("\\s+")} 下被错误拆开 —— 之前的实现会导致
     * curl 等命令写入带空格路径时报 "文件创建目录或语法不正确"。
     *
     * <p>Windows 路径中的反斜杠（如 {@code D:\My Folder\file.txt}）不会被误认为转义：
     * 反斜杠仅在 {@code \"}、{@code \\}、{@code \'} 时作为转义处理，保留原反斜杠仅为路径分隔符。
     */
    private List<String> tokenizeArgs(String args) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && i + 1 < args.length() && !inSingle) {
                char next = args.charAt(i + 1);
                if (next == '"' || next == '\\' || next == '\'') {
                    cur.append(next);
                    i++;
                    continue;
                }
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    private String readProcessOutput(Process process, int timeoutSeconds) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        int read;

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        try (InputStream in = process.getInputStream()) {
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    return decodeOutput(buf.toByteArray()) + "\n[输出读取被中断]";
                }
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    return decodeOutput(buf.toByteArray()) + "\n[输出读取超时]";
                }

                if (totalRead + read > MAX_OUTPUT_SIZE) {
                    int remaining = MAX_OUTPUT_SIZE - totalRead;
                    if (remaining > 0) {
                        buf.write(buffer, 0, remaining);
                    }
                    return decodeOutput(buf.toByteArray()) + "\n[输出已截断，超过最大限制 " + (MAX_OUTPUT_SIZE / 1024) + "KB]";
                }

                buf.write(buffer, 0, read);
                totalRead += read;
            }
        }

        return decodeOutput(buf.toByteArray());
    }

    /**
     * 子进程输出解码：优先 UTF-8，若检测到大量替换字符（U+FFFD）则说明是
     * Windows 中文系统的 GBK/GB18030 输出（cmd/curl 默认 GBK 代码页），
     * 自动回退用 GBK 解码 —— 之前固定 UTF-8 导致中文错误信息全部乱码。
     */
    private String decodeOutput(byte[] bytes) {
        if (bytes.length == 0) return "";
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        int replaced = 0;
        for (int i = 0; i < utf8.length(); i++) {
            if (utf8.charAt(i) == '\uFFFD') replaced++;
        }
        if (replaced > 0 && replaced * 100.0 / utf8.length() > 2.0) {
            try {
                return new String(bytes, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception ignored) {
                return utf8;
            }
        }
        return utf8;
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
