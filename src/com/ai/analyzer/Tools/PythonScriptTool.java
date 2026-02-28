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
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private String pythonCommand = "python";

    public PythonScriptTool() {}

    public void setPythonCommand(String pythonCommand) {
        if (pythonCommand != null && !pythonCommand.isEmpty()) {
            this.pythonCommand = pythonCommand;
        }
    }

    @Tool(name = "execute_python", value = {
        "在本地安全环境执行 Python 代码并返回结果。",
        "【适用场景】：编码/解码、数据处理、payload 生成、数学计算。",
        "【禁止】：文件删除、跨目录访问(../)、系统命令、subprocess、eval/exec。",
        "【注意】：使用 print() 输出，超时 30 秒，输出上限 10000 字符。"
    })
    public String executePython(
            @P("要执行的完整 Python 代码字符串") String code) {
        return executePythonWithTimeout(code, DEFAULT_TIMEOUT_SECONDS);
    }

    @Tool(name = "execute_python_with_timeout", value = {
        "执行 Python 代码（可自定义超时）。禁止：文件删除、跨目录、系统命令、subprocess、eval/exec。"
    })
    public String executePythonWithTimeout(
            @P("要执行的完整 Python 代码字符串") String code,
            @P("执行超时时间（秒），默认 30 秒，最大 120 秒") Integer timeoutSeconds) {
        if (code == null || code.trim().isEmpty()) {
            return "错误：代码不能为空";
        }
        String blocked = checkBlocked(code);
        if (blocked != null) {
            return "拒绝执行: " + blocked;
        }

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
                ? Math.min(timeoutSeconds, 120)
                : DEFAULT_TIMEOUT_SECONDS;

        Path tempFile = null;
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            tempFile = Files.createTempFile("agent_script_" + timestamp + "_", ".py");
            Files.writeString(tempFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonCommand, tempFile.toString());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
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
                return "执行超时（超过 " + timeout + " 秒），进程已终止\n" +
                       "已捕获的部分输出:\n" + output;
            }

            int exitCode = process.exitValue();
            String result = output.toString();

            if (exitCode != 0) {
                return "执行完成（退出码: " + exitCode + "）\n" + result;
            }

            return result.isEmpty() ? "执行成功（无输出）" : result;

        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    private static String checkBlocked(String code) {
        String[] blocked = {
            "os.remove", "os.unlink", "os.rmdir", "os.removedirs",
            "shutil.rmtree", "os.system", "subprocess.", "os.popen",
            "eval(", "exec(", "__import__", ".unlink(",
            "os.chdir", "../", "..\\"
        };
        for (String p : blocked) {
            if (code.contains(p)) return "禁止使用 " + p;
        }
        return null;
    }
}
