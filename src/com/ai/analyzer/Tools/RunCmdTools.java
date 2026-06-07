package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.community.code.local.CommandLineExecutionEngine;
import com.ai.analyzer.utils.ArtifactCache;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 允许 AI Agent 执行本地 CLI 命令（仅限白名单内的工具）。
 * 底层使用 LangChain4j 的 CommandLineExecutionEngine，外层保留白名单与输出缓存保护。
 *
 * <p>AI 可通过以下三种方式指定要执行的工具：
 * <ol>
 *   <li>别名：可执行文件名（不含路径，不含扩展名），如 {@code nuclei}</li>
 *   <li>索引：1-based 序号，如 {@code 1} 代表白名单第一行</li>
 *   <li>完整路径/原始行：与白名单某行完全一致（路径斜杠自动规范化）</li>
 * </ol>
 *
 * <p>调用流程：先调 {@code list_cli_tools} 查看可用工具，再调 {@code run_cli} 执行。
 */
public class RunCmdTools {

    private static final int    MAX_OUTPUT_CHARS = 20_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** 解析后的白名单条目，每条保留原始行（用于执行）和识别别名（用于匹配）。 */
    private final List<WhitelistEntry> whitelist;
    private final String customToolPrompt;
    private final File   workingDirectory;
    private final Duration timeout;
    private final CommandLineExecutionEngine engine = new CommandLineExecutionEngine();

    // ────────────────────────────────────────────────────────────────────────

    public RunCmdTools(String whitelistText, String customToolPrompt, String workingDirectoryPath) {
        this(whitelistText, customToolPrompt, workingDirectoryPath, DEFAULT_TIMEOUT);
    }

    public RunCmdTools(String whitelistText, String customToolPrompt,
                       String workingDirectoryPath, Duration timeout) {
        this.whitelist         = parseWhitelist(whitelistText);
        this.customToolPrompt  = customToolPrompt != null ? customToolPrompt.trim() : "";
        this.workingDirectory  = resolveDir(workingDirectoryPath);
        this.timeout           = timeout != null ? timeout : DEFAULT_TIMEOUT;
    }

    // ────────────────────────────────────── Tools ────────────────────────────

    /**
     * 可选辅助工具：列出白名单中的工具。
     * 通常不需要主动调用——直接用 run_cli 传别名即可，匹配失败时 run_cli 会自动返回列表。
     */
    @Tool(name = "list_cli_tools", value = {
            "【可选】列出所有可调用的 CLI 工具别名和索引。",
            "注意：此工具每次任务只需调用一次；调用后请立即使用 run_cli 执行，不要重复调用本方法。",
            "大多数情况下可直接调用 run_cli(tool=\"别名\", args=\"...\")，匹配失败时会自动返回可用列表。"
    })
    public String listCliTools() {
        return buildToolList();
    }

    @Tool(name = "run_cli", value = {
            "执行本地 CLI 工具（仅限白名单）。",
            "tool 参数：① 工具别名（推荐，如 nuclei、sqlmap、python）② 1-based 序号（如 1）③ 完整命令行。",
            "tool 传空字符串时，将返回所有可用工具列表，无需提前调用 list_cli_tools。",
            "args 参数：追加给工具的命令行参数，例如 \"-h\" 或 \"-target example.com -severity high\"。",
            "安全限制：只能执行白名单工具；输出超过限制会自动截断。"
    })
    public String runCli(
            @P("工具别名（如 nuclei）、序号（如 1）或完整命令行；传空字符串可查看可用工具列表") String tool,
            @P("追加给工具的参数（可为空），例如 \"-target example.com\" 或 \"-h\"") String args
    ) {
        // 传空 → 自动返回工具列表，让 AI 知道可以用什么
        if (tool == null || tool.trim().isEmpty()) {
            return "tool 参数为空，以下是可用工具列表，请选择后重新调用 run_cli：\n\n" + buildToolList();
        }

        WhitelistEntry entry = resolve(tool.trim());
        if (entry == null) {
            return "执行失败: tool=\"" + tool.trim() + "\" 不在白名单中。可用工具列表：\n\n"
                    + buildToolList();
        }

        try {
            String commandLine = buildExecutionCommand(entry.rawLine, args);
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> engine.execute(commandLine));

            long waitMs = Math.max(1_000L, timeout.toMillis() - 750L);
            String rawOut = outputFuture.get(waitMs, TimeUnit.MILLISECONDS);
            if (rawOut == null) rawOut = "";
            String display = truncate(rawOut, MAX_OUTPUT_CHARS);

            StringBuilder sb = new StringBuilder();
            if (!customToolPrompt.isEmpty()) {
                sb.append("[用户自定义工具提示词]\n").append(customToolPrompt).append("\n\n");
            }
            sb.append("[engine]    LangChain4j CommandLineExecutionEngine\n");
            sb.append("[command]   ").append(commandLine).append('\n');
            sb.append("[output]\n").append(display);
            if (rawOut.length() > display.length()) {
                try {
                    ArtifactCache.ArtifactRef ref = ArtifactCache.saveText(rawOut, "cli-output");
                    sb.append("\n\n...[输出较长，完整内容已缓存，原始长度 ")
                      .append(rawOut.length())
                      .append(" 字符]...\n")
                      .append(ref.toPromptText());
                } catch (Exception cacheError) {
                    sb.append("\n...[输出已截断，完整长度 ")
                      .append(rawOut.length())
                      .append(" 字符，缓存失败: ")
                      .append(cacheError.getMessage())
                      .append("]...");
                }
            }
            return sb.toString();

        } catch (java.util.concurrent.TimeoutException e) {
            return "执行超时: 超过 " + timeout.toSeconds() + " 秒，任务已取消。";
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    // ────────────────────────────────────── 内部辅助 ─────────────────────────

    private String buildToolList() {
        if (whitelist.isEmpty()) return "白名单为空，没有可用的 CLI 工具。";
        StringBuilder sb = new StringBuilder("可用的 CLI 工具（共 " + whitelist.size() + " 个）：\n\n");
        for (int i = 0; i < whitelist.size(); i++) {
            WhitelistEntry e = whitelist.get(i);
            sb.append(String.format("  [%d] 别名=%-20s 完整命令=%s%n",
                    i + 1, e.alias, e.rawLine));
        }
        sb.append("\n直接调用：run_cli(tool=\"别名或序号\", args=\"追加参数\")");
        return sb.toString();
    }

    // ────────────────────────────────────── 匹配逻辑 ─────────────────────────

    /**
     * 解析 tool 参数，按优先级尝试以下三种匹配：
     * <ol>
     *   <li>1-based 数字索引</li>
     *   <li>别名（可执行文件名，不含路径/扩展名，大小写不敏感）</li>
     *   <li>路径规范化后的完整行匹配</li>
     * </ol>
     */
    private WhitelistEntry resolve(String tool) {
        // 1. 索引匹配
        try {
            int idx = Integer.parseInt(tool);
            if (idx >= 1 && idx <= whitelist.size()) {
                return whitelist.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {}

        String toolNorm = normalizePath(tool).toLowerCase();

        // 2. 别名匹配（无路径、无扩展）
        String toolAlias = extractAlias(tool).toLowerCase();
        for (WhitelistEntry e : whitelist) {
            if (e.alias.toLowerCase().equals(toolAlias)) return e;
        }

        // 3. 规范化路径匹配
        for (WhitelistEntry e : whitelist) {
            if (normalizePath(e.rawLine).toLowerCase().equals(toolNorm)) return e;
            // 仅比较路径的第一个 token（组合命令行中首个词是可执行文件）
            List<String> parts = splitCommandLine(e.rawLine);
            if (!parts.isEmpty() && normalizePath(parts.get(0)).toLowerCase().equals(toolNorm)) return e;
        }

        return null;
    }

    // ────────────────────────────────────── 解析工具 ─────────────────────────

    private static List<WhitelistEntry> parseWhitelist(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        return text.replace("\r\n", "\n")
                .lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.startsWith("#"))
                .distinct()
                .map(line -> {
                    // 提取第一个 token 作为可执行文件路径
                    List<String> parts = splitCommandLine(line);
                    String exe   = parts.isEmpty() ? line : parts.get(0);
                    String alias = extractAlias(exe);
                    return new WhitelistEntry(line, alias);
                })
                .collect(Collectors.toList());
    }

    /** 从路径/命令中提取可执行文件的"别名"（去掉路径和扩展名）。 */
    private static String extractAlias(String exe) {
        if (exe == null || exe.isEmpty()) return exe;
        // 取最后一段（路径分隔符之后）
        int slashPos = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        String name = slashPos >= 0 ? exe.substring(slashPos + 1) : exe;
        // 去掉扩展名
        int dotPos = name.lastIndexOf('.');
        return dotPos > 0 ? name.substring(0, dotPos) : name;
    }

    /** 规范化路径：统一用 `/` 分隔，方便比较。 */
    private static String normalizePath(String s) {
        return s == null ? "" : s.replace('\\', '/');
    }

    // ────────────────────────────────────── 命令行分词 ───────────────────────

    /** 简单命令行分词：支持单双引号包裹。 */
    private static List<String> splitCommandLine(String line) {
        if (line == null || line.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (char c : line.toCharArray()) {
            if (c == '\'' && !inDouble)      { inSingle = !inSingle; continue; }
            if (c == '"'  && !inSingle)      { inDouble = !inDouble; continue; }
            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ────────────────────────────────────── I/O 工具 ─────────────────────────

    private String buildExecutionCommand(String rawLine, String args) {
        String commandLine = rawLine == null ? "" : rawLine.trim();
        if (args != null && !args.trim().isEmpty()) {
            commandLine += " " + args.trim();
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            commandLine = commandLine.replace("\\\\", "\\");
        }
        return commandLine;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private static File resolveDir(String path) {
        if (path == null || path.isBlank()) return null;
        File f = new File(path.trim());
        return f.isDirectory() ? f : null;
    }

    // ────────────────────────────────────── 内部数据结构 ─────────────────────

    private static final class WhitelistEntry {
        final String rawLine; // 原始行（用于执行）
        final String alias;   // 别名（用于快速匹配）
        WhitelistEntry(String rawLine, String alias) {
            this.rawLine = rawLine;
            this.alias   = alias;
        }
    }
}
