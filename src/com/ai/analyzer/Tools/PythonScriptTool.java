package com.ai.analyzer.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Python 执行工具。
 * 脚本保存在工作目录，不会执行后自动删除，便于持续迭代与复用。
 *
 * 兼容性改进：
 * - 支持 Windows py launcher（py -3），优先使用，避免 Microsoft Store 别名问题
 * - 检测时严格校验输出包含 "Python 3"，排除 Store 别名（WindowsApps）干扰
 * - 自动识别系统默认编码（Windows 中文环境常为 GBK），避免读取输出乱码
 * - 检测顺序根据操作系统自适应：Windows 优先 py -3 → python → python3；Unix 优先 python3 → python
 * - 命令以 List 形式存储，支持带参数的启动器（如 ["py", "-3"]）
 */
public class PythonScriptTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 20000;
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** Windows 上 Microsoft Store 的 python 别名所在目录特征 */
    private static final String STORE_ALIAS_MARKER = "WindowsApps";

    /** 以 List 形式存储完整命令，支持带参数（如 ["py", "-3"] 或 ["python"]） */
    private List<String> pythonCommand;
    private String pythonVersion;
    private String workingDirectory;
    /** 系统默认控制台编码，用于读取 Python 进程输出 */
    private final Charset systemCharset;

    public PythonScriptTool() {
        this.pythonCommand = detectPythonCommand();
        this.workingDirectory = "";
        this.systemCharset = resolveSystemCharset();
    }

    /**
     * 设置 Python 命令，支持字符串形式（自动按空白拆分）。
     * 例如 "py -3" 或 "python3" 或 "/usr/local/bin/python3.11"。
     */
    public void setPythonCommand(String pythonCommand) {
        if (pythonCommand != null && !pythonCommand.trim().isEmpty()) {
            this.pythonCommand = splitCommand(pythonCommand.trim());
            this.pythonVersion = null;
        }
    }

    /**
     * 设置 Python 命令，List 形式，支持带参数的启动器。
     */
    public void setPythonCommand(List<String> pythonCommand) {
        if (pythonCommand != null && !pythonCommand.isEmpty()) {
            this.pythonCommand = new ArrayList<>(pythonCommand);
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

    @Tool(name = "execute_python", description = "在 Workplace/python-workdir 中执行 Python 脚本，支持持续增删改查本地文件。脚本文件会保留，方便后续复用和迭代调试。建议始终用 print() 输出关键结果。")
    public String executePython(
            @ToolParam(name = "code", description = "完整 Python 代码。支持多行。") String code,
            @ToolParam(name = "scriptName", description = "可选：脚本文件名（如 poc_ssrf.py）。不填则自动生成。") String scriptName,
            @ToolParam(name = "timeoutSeconds", description = "可选：超时秒数，默认 30，最大 120。") Integer timeoutSeconds) {

        if (pythonCommand == null || pythonCommand.isEmpty()) {
            return "错误：未检测到 Python 环境。请确保系统已安装 Python 3 且在 PATH 中可用（python3 / python / py）。\n"
                 + "你也可以在插件配置中手动指定 Python 命令（如 py -3 或 python 的完整路径）。";
        }

        if (code == null || code.trim().isEmpty()) {
            return "错误：代码不能为空。请提供完整的 Python 代码，用 print() 输出结果。";
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            timeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        }

        Process process = null;
        ExecutorService executor = null;
        try {
            Path baseDir = resolveWorkingDirectory();
            Files.createDirectories(baseDir);
            Path scriptsDir = baseDir.resolve("scripts");
            Files.createDirectories(scriptsDir);

            Path scriptFile = resolveScriptPath(scriptsDir, scriptName);
            Files.writeString(scriptFile, code, StandardCharsets.UTF_8);

            // 构建完整命令：pythonCommand + ["-u", scriptFile]
            List<String> fullCommand = new ArrayList<>(pythonCommand);
            fullCommand.add("-u");
            fullCommand.add(scriptFile.toString());

            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            pb.redirectErrorStream(true);
            // 强制 Python 使用 UTF-8 进行 IO，避免 Windows 下 GBK 编码导致中文乱码
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            pb.directory(baseDir.toFile());
            process = pb.start();

            final Process proc = process;
            executor = Executors.newSingleThreadExecutor();
            Future<String> outputFuture = executor.submit(() -> readProcessOutput(proc));

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String partial = safeGetOutput(outputFuture);
                return "执行超时（超过 " + timeout + " 秒），进程已终止。\n脚本文件: " + scriptFile + "\n已捕获输出:\n" + partial;
            }

            String result = safeGetOutput(outputFuture);
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
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("Cannot run program") || msg.contains("No such file") || msg.contains("CreateProcess")) {
                return "错误：无法启动 Python（命令: " + String.join(" ", pythonCommand) + "）。\n"
                     + "系统可能未安装 Python 或不在 PATH 中。\n"
                     + "建议：在插件配置中手动指定 Python 命令（如 py -3，或 python.exe 的完整路径）。";
            }
            return "执行失败: " + msg;
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 检测系统可用的 Python 命令。
     *
     * 检测策略（按优先级）：
     * - Windows: "py -3" → "py" → "python" → "python3"
     *   （py launcher 是 Windows 官方推荐方式，最可靠；python3 常被 Store 别名占用，最后尝试）
     * - Unix/Linux/macOS: "python3" → "python"
     *
     * 每个候选都会严格验证：
     * 1. 进程在 8 秒内结束
     * 2. 退出码为 0
     * 3. 输出非空且包含 "Python 3"
     * 4. 可执行文件路径不位于 WindowsApps（排除 Microsoft Store 别名）
     */
    private static List<String> detectPythonCommand() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        List<List<String>> candidates = new ArrayList<>();
        if (isWindows) {
            candidates.add(Arrays.asList("py", "-3"));
            candidates.add(Arrays.asList("py"));
            candidates.add(Arrays.asList("python"));
            candidates.add(Arrays.asList("python3"));
        } else {
            candidates.add(Arrays.asList("python3"));
            candidates.add(Arrays.asList("python"));
        }

        for (List<String> candidate : candidates) {
            String version = tryGetVersion(candidate);
            if (version != null) {
                return candidate;
            }
        }

        // 全部检测失败，返回 null（运行时给出友好提示）
        return null;
    }

    /**
     * 尝试用给定命令获取 Python 版本，成功返回版本字符串，失败返回 null。
     * 严格校验输出和路径，排除 Microsoft Store 别名。
     */
    private static String tryGetVersion(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            process = pb.start();

            boolean done = process.waitFor(8, TimeUnit.SECONDS);
            if (!done) {
                return null; // 超时，可能是 Store 别名卡住
            }
            if (process.exitValue() != 0) {
                return null; // 退出码非0
            }

            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), resolveSystemCharset()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                output = sb.toString().trim();
            }

            // 严格校验：输出必须非空且包含 "Python 3"
            if (output.isEmpty() || !output.toLowerCase().contains("python 3")) {
                return null;
            }

            // Windows 上排除 Microsoft Store 别名（路径含 WindowsApps）
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                String exePath = findExecutablePath(command.get(0));
                if (exePath != null && exePath.contains(STORE_ALIAS_MARKER)) {
                    return null; // 跳过 Store 别名
                }
            }

            return output;

        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 查找可执行文件的实际路径（用于检测是否为 Store 别名）。
     */
    private static String findExecutablePath(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("where", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), resolveSystemCharset()))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // where 命令不可用（非 Windows），忽略
        }
        return null;
    }

    /**
     * 获取 Python 版本信息（懒加载）
     */
    public String getPythonVersion() {
        if (pythonVersion != null) return pythonVersion;
        if (pythonCommand == null || pythonCommand.isEmpty()) return "未检测到";
        String ver = tryGetVersion(pythonCommand);
        pythonVersion = (ver != null) ? ver : "未知";
        return pythonVersion;
    }

    /**
     * 获取当前使用的 Python 命令（用于 UI 显示和调试）。
     */
    public String getPythonCommandString() {
        if (pythonCommand == null || pythonCommand.isEmpty()) {
            return "未检测到";
        }
        return String.join(" ", pythonCommand);
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

    /**
     * 读取进程输出。
     * 优先使用 UTF-8（已通过 PYTHONIOENCODING=utf-8 强制），回退到系统编码。
     */
    private String readProcessOutput(Process process) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
            // UTF-8 读取失败，尝试用系统编码重新读取（此时流可能已部分消费，尽力而为）
            if (output.length() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), systemCharset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() >= MAX_OUTPUT_LENGTH) {
                            output.append("\n...[输出已截断，超过 ").append(MAX_OUTPUT_LENGTH).append(" 字符]");
                            break;
                        }
                        if (output.length() > 0) output.append("\n");
                        output.append(line);
                    }
                } catch (Exception e2) {
                    output.append("读取输出失败: ").append(e2.getMessage());
                }
            }
        }
        return output.toString();
    }

    private String safeGetOutput(Future<String> outputFuture) {
        try {
            String output = outputFuture.get(3, TimeUnit.SECONDS);
            return output == null ? "" : output;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 解析系统默认控制台编码。
     * Windows 中文环境默认 GBK（MS936），Unix 通常 UTF-8。
     */
    private static Charset resolveSystemCharset() {
        try {
            return Charset.defaultCharset();
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 将命令字符串按空白拆分为 List。
     * 简单实现：按空格拆分（Python 命令通常简单）。
     */
    private static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        for (String part : command.split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }
}