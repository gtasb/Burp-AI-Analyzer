package com.ai.analyzer.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ShellExecTool {

    private static final int MAX_OUTPUT_BYTES = 100 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final String workspacePath;
    private final String projectPath;
    private final boolean inheritEnv;
    private final boolean enableUnrestricted;

    public ShellExecTool(String workspacePath, String projectPath, boolean inheritEnv, boolean enableUnrestricted) {
        this.workspacePath = workspacePath;
        this.projectPath = projectPath;
        this.inheritEnv = inheritEnv;
        this.enableUnrestricted = enableUnrestricted;
    }

    @Tool(name = "execute", description = "在本地操作系统上执行 shell 命令。支持管道、重定向、环境变量等 shell 特性。可指定工作目录和超时时间。返回退出码和命令输出。")
    public String execute(
            @ToolParam(name = "command", description = "要执行的 shell 命令字符串（必填），例如 \"dir\" 或 \"ls -la\"") String command,
            @ToolParam(name = "working_directory", description = "可选：命令执行的工作目录，默认为工作区根目录") String workingDirectory,
            @ToolParam(name = "timeout", description = "可选：超时秒数，默认 60，最大 300") Integer timeout
    ) {
        if (command == null || command.trim().isEmpty()) {
            return "错误: command 不能为空";
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        List<String> cmdLine = new ArrayList<>();
        if (isWindows) {
            // cmd.exe /c + chcp 65001 强制 UTF-8 输出，同时兼容 curl.exe、python 等原生工具
            cmdLine.add("cmd.exe");
            cmdLine.add("/c");
            cmdLine.add("chcp 65001 >nul && " + command);
        } else {
            cmdLine.add("/bin/sh");
            cmdLine.add("-c");
            cmdLine.add(command);
        }

        int timeoutSec = (timeout != null && timeout > 0) ? Math.min(timeout, 300) : DEFAULT_TIMEOUT_SECONDS;

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);

            if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
                java.io.File wd = new java.io.File(workingDirectory.trim());
                if (wd.exists() && wd.isDirectory()) {
                    pb.directory(wd);
                }
            } else if (workspacePath != null && !workspacePath.isEmpty()) {
                java.io.File wd = new java.io.File(workspacePath);
                if (wd.exists() && wd.isDirectory()) {
                    pb.directory(wd);
                }
            }

            Process process = pb.start();

            final java.util.concurrent.atomic.AtomicReference<String> outputRef = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    outputRef.set(readOutput(process));
                } catch (Exception e) {
                    outputRef.set("[读取输出失败: " + e.getMessage() + "]");
                }
            }, "shell-exec-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                reader.interrupt();
                String partial = outputRef.get() != null ? outputRef.get() : "";
                return "执行超时（超过 " + timeoutSec + " 秒），进程已终止。\n已捕获输出:\n" + partial;
            }

            try { reader.join(5000); } catch (InterruptedException ignored) {}

            String output = outputRef.get() != null ? outputRef.get() : "";
            int exitCode = process.exitValue();

            return "退出码: " + exitCode + "\n输出:\n" + output;

        } catch (Exception e) {
            return "执行失败: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private String readOutput(Process process) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalRead = 0;

        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    return decodeOutput(buf.toByteArray()) + "\n[输出读取被中断]";
                }
                if (totalRead + n > MAX_OUTPUT_BYTES) {
                    int remaining = MAX_OUTPUT_BYTES - totalRead;
                    if (remaining > 0) {
                        buf.write(buffer, 0, remaining);
                    }
                    return decodeOutput(buf.toByteArray()) + "\n[输出已截断，超过 " + (MAX_OUTPUT_BYTES / 1024) + "KB]";
                }
                buf.write(buffer, 0, n);
                totalRead += n;
            }
        }

        return decodeOutput(buf.toByteArray());
    }

    private static String decodeOutput(byte[] bytes) {
        if (bytes.length == 0) return "";
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        int replaced = 0;
        for (int i = 0; i < utf8.length(); i++) {
            if (utf8.charAt(i) == '\uFFFD') replaced++;
        }
        if (replaced > 0 && replaced * 100.0 / utf8.length() > 2.0) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ignored) {
                return utf8;
            }
        }
        return utf8;
    }
}
