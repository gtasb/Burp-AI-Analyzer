package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Python 执行工具。
 * 脚本保存在工作目录，不会执行后自动删除，便于持续迭代与复用。
 */
public class PythonScriptTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 20000;
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private String pythonCommand;
    private String pythonVersion;
    private String workingDirectory;

    public PythonScriptTool() {
        this.pythonCommand = detectPythonCommand();
        this.workingDirectory = "";
    }

    public void setPythonCommand(String pythonCommand) {
        if (pythonCommand != null && !pythonCommand.isEmpty()) {
            this.pythonCommand = pythonCommand;
            this.pythonVersion = null;
        }
    }

    public void setWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null) {
            this.workingDirectory = "";
            return;
        }
        this.workingDirectory = workingDirectory.trim();
    }

    @Tool(name = "execute_python", value = {
        "在 Workplace/python-workdir 中执行 Python 脚本，支持持续增删改查本地文件。",
        "脚本文件会保留，方便后续复用和迭代调试。",
        "建议始终用 print() 输出关键结果。"
    })
    public String executePython(
            @P("完整 Python 代码。支持多行。") String code,
            @P("可选：脚本文件名（如 poc_ssrf.py）。不填则自动生成。") String scriptName,
            @P("可选：超时秒数，默认 30，最大 120。") Integer timeoutSeconds) {

        if (pythonCommand == null) {
            return "错误：未检测到 Python 环境。请确保系统已安装 Python 3 且在 PATH 中可用（python3 或 python）。";
        }

        if (code == null || code.trim().isEmpty()) {
            return "错误：代码不能为空。请提供完整的 Python 代码，用 print() 输出结果。";
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            timeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        }

        try {
            Path baseDir = resolveWorkingDirectory();
            Files.createDirectories(baseDir);
            Path scriptsDir = baseDir.resolve("scripts");
            Files.createDirectories(scriptsDir);

            Path scriptFile = resolveScriptPath(scriptsDir, scriptName);
            Files.writeString(scriptFile, code, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "-u", scriptFile.toString());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.directory(baseDir.toFile());
            Process process = pb.start();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> outputFuture = executor.submit(() -> readProcessOutput(process));

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String partial = safeGetOutput(outputFuture);
                executor.shutdownNow();
                return "执行超时（超过 " + timeout + " 秒），进程已终止。\n脚本文件: " + scriptFile + "\n已捕获输出:\n" + partial;
            }

            String result = safeGetOutput(outputFuture);
            executor.shutdownNow();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                return "Python 执行出错（退出码 " + exitCode + "）:\n" + result +
                       "\n\n脚本文件: " + scriptFile;
            }

            if (result.isEmpty()) {
                return "执行成功但无输出。\n脚本文件: " + scriptFile + "\n提示：请用 print() 输出关键结果。";
            }

            return "脚本文件: " + scriptFile + "\n\n" + result;

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Cannot run program") || msg.contains("No such file"))) {
                return "错误：无法启动 Python（命令: " + pythonCommand + "）。系统可能未安装 Python 或不在 PATH 中。";
            }
            return "执行失败: " + msg;
        }
    }

    /**
     * 检测系统可用的 Python 命令，优先 python3
     */
    private static String detectPythonCommand() {
        for (String cmd : new String[]{"python3", "python"}) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                if (done && p.exitValue() == 0) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                        String ver = r.readLine();
                        if (ver != null && ver.toLowerCase().contains("python 3")) {
                            return cmd;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // 回退：返回 python，让运行时报错
        return "python";
    }

    /**
     * 获取 Python 版本信息（懒加载）
     */
    public String getPythonVersion() {
        if (pythonVersion != null) return pythonVersion;
        if (pythonCommand == null) return "未检测到";
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    pythonVersion = r.readLine();
                }
            }
        } catch (Exception ignored) {}
        if (pythonVersion == null) pythonVersion = "未知";
        return pythonVersion;
    }

    private Path resolveWorkingDirectory() {
        if (workingDirectory == null || workingDirectory.isEmpty()) {
            return new File(System.getProperty("user.home"), "ai-analyzer-python-workdir").toPath();
        }
        return new File(workingDirectory).toPath();
    }

    private Path resolveScriptPath(Path scriptsDir, String scriptName) {
        if (scriptName == null || scriptName.trim().isEmpty()) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            return scriptsDir.resolve("agent_script_" + timestamp + ".py");
        }
        String normalized = scriptName.trim().replace("\\", "/");
        String fileName = new File(normalized).getName();
        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!fileName.endsWith(".py")) {
            fileName = fileName + ".py";
        }
        return scriptsDir.resolve(fileName);
    }

    private String readProcessOutput(Process process) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() >= MAX_OUTPUT_LENGTH) {
                    output.append("\n...[输出已截断，超过 ").append(MAX_OUTPUT_LENGTH).append(" 字符]");
                    break;
                }
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
        } catch (Exception e) {
            if (output.length() == 0) {
                output.append("读取输出失败: ").append(e.getMessage());
            }
        }
        return output.toString();
    }

    private String safeGetOutput(Future<String> outputFuture) {
        try {
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            return output == null ? "" : output;
        } catch (Exception ignored) {
            return "";
        }
    }
}
