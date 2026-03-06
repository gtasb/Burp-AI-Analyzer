package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Python 脚本执行工具 - 让 AI 可以在本地安全环境中执行 Python 代码
 *
 * 适用场景：
 * - 编码/解码操作（如自定义加密算法还原）
 * - 数据处理和分析（如解析复杂响应体）
 * - 生成测试 payload
 * - 执行数学计算或逻辑推理
 */
public class PythonScriptTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private static final Pattern[] BLOCKED_PATTERNS = {
        Pattern.compile("os\\s*\\.\\s*remove"),
        Pattern.compile("os\\s*\\.\\s*unlink"),
        Pattern.compile("os\\s*\\.\\s*rmdir"),
        Pattern.compile("os\\s*\\.\\s*removedirs"),
        Pattern.compile("shutil\\s*\\.\\s*rmtree"),
        Pattern.compile("os\\s*\\.\\s*system\\s*\\("),
        Pattern.compile("subprocess\\s*\\."),
        Pattern.compile("os\\s*\\.\\s*popen\\s*\\("),
        Pattern.compile("os\\s*\\.\\s*chdir\\s*\\("),
        Pattern.compile("__import__\\s*\\("),
        Pattern.compile("\\.\\./"),
        Pattern.compile("\\.\\.\\\\"),
    };

    private String pythonCommand;
    private String pythonVersion;

    public PythonScriptTool() {
        this.pythonCommand = detectPythonCommand();
    }

    public void setPythonCommand(String pythonCommand) {
        if (pythonCommand != null && !pythonCommand.isEmpty()) {
            this.pythonCommand = pythonCommand;
            this.pythonVersion = null;
        }
    }

    @Tool(name = "execute_python", value = {
        "在本地执行 Python 代码并返回 print() 的输出结果。",
        "",
        "【何时使用】：",
        "- 编码/解码：Base64、URL编码、Hex、自定义加密算法逆向",
        "- 数据处理：解析 JSON/XML 响应体、提取特定字段、正则匹配",
        "- Payload 生成：构造 fuzzing 字典、序列化 payload、生成 hash",
        "- 数学计算：密码学运算、偏移计算、长度推算",
        "",
        "【代码要求】：",
        "- 必须用 print() 输出结果，否则看不到任何返回值",
        "- 代码是完整的 Python 脚本，直接写顶层代码即可，不需要 if __name__",
        "- 可使用标准库：base64, hashlib, json, re, urllib.parse, struct, binascii, hmac, zlib 等",
        "- 可使用 requests（如已安装）发送 HTTP 请求",
        "",
        "【代码示例】：",
        "```",
        "import base64",
        "encoded = 'SGVsbG8gV29ybGQ='",
        "print(base64.b64decode(encoded).decode('utf-8'))",
        "```",
        "",
        "【禁止操作】：文件删除、subprocess、os.system、eval/exec、__import__、跨目录访问(../)",
        "【限制】：超时 30 秒（可通过 timeoutSeconds 调整，最大 120 秒），输出上限 10000 字符"
    })
    public String executePython(
            @P("完整的 Python 代码。必须用 print() 输出结果。支持多行，直接写顶层代码。") String code,
            @P("可选：执行超时秒数，默认 30，最大 120。长时间运算可设大一些。") Integer timeoutSeconds) {

        if (pythonCommand == null) {
            return "错误：未检测到 Python 环境。请确保系统已安装 Python 3 且在 PATH 中可用（python3 或 python）。";
        }

        if (code == null || code.trim().isEmpty()) {
            return "错误：代码不能为空。请提供完整的 Python 代码，用 print() 输出结果。";
        }

        String blocked = checkBlocked(code);
        if (blocked != null) {
            return "安全拦截: " + blocked + "。请移除该操作后重试。";
        }

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            timeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        }

        Path tempFile = null;
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            tempFile = Files.createTempFile("agent_script_" + timestamp + "_", ".py");
            Files.writeString(tempFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonCommand, tempFile.toString());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.directory(tempFile.getParent().toFile());
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > MAX_OUTPUT_LENGTH) {
                        output.append("\n...[输出已截断，超过 ").append(MAX_OUTPUT_LENGTH).append(" 字符]");
                        break;
                    }
                    if (output.length() > 0) output.append("\n");
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时（超过 " + timeout + " 秒），进程已终止。\n" +
                       "建议：增大 timeoutSeconds 参数，或优化代码减少耗时。\n" +
                       "已捕获的部分输出:\n" + output;
            }

            int exitCode = process.exitValue();
            String result = output.toString();

            if (exitCode != 0) {
                return "Python 执行出错（退出码 " + exitCode + "）:\n" + result +
                       "\n\n请检查代码语法和逻辑后重试。";
            }

            if (result.isEmpty()) {
                return "执行成功但无输出。提示：请确保用 print() 输出结果。";
            }

            return result;

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Cannot run program") || msg.contains("No such file"))) {
                return "错误：无法启动 Python（命令: " + pythonCommand + "）。系统可能未安装 Python 或不在 PATH 中。";
            }
            return "执行失败: " + msg;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
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

    private static String checkBlocked(String code) {
        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(code).find()) {
                String desc = p.pattern()
                    .replace("\\s*\\.\\s*", ".")
                    .replace("\\s*\\(", "(")
                    .replaceAll("\\\\[()./]", "");
                return "禁止使用 " + desc;
            }
        }
        return null;
    }
}
