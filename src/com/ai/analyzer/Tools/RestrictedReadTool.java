package com.ai.analyzer.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 受限的文件读取工具（被动扫描用）。
 *
 * <p>ReActAgent 没有 Harness 原生的 workspace 文件工具，而超长 HTTP 报文会被
 * {@code HttpFormatter} 落盘到工作区 {@code .cache/} 后只把预览塞进上下文。
 * 本工具让 agent 能按提示把落盘内容读回来——但<b>只允许读取 {@code .cache/} 目录内的文件</b>，
 * 拒绝目录穿越与工作区之外的任意路径，避免把被动扫描代理变成任意文件读取器。
 */
public class RestrictedReadTool {

    /** 单次最多读入内存的字节数（超长缓存不整段读进 JVM） */
    private static final int MAX_READ_BYTES = 512 * 1024;
    /** 单次返回的最大字符数（默认） */
    private static final int DEFAULT_LIMIT_CHARS = 16_000;
    /** 允许的最大 limit */
    private static final int MAX_LIMIT_CHARS = 50_000;

    private final Path allowedRoot;

    public RestrictedReadTool(String workplaceDirectory) {
        if (workplaceDirectory == null || workplaceDirectory.trim().isEmpty()) {
            this.allowedRoot = null;
        } else {
            this.allowedRoot = Path.of(workplaceDirectory.trim())
                    .toAbsolutePath().normalize()
                    .resolve(".cache").normalize();
        }
    }

    @Tool(name = "read_file", description =
            "读取工作区缓存目录（.cache/）内的文本文件，用于回读超长 HTTP 报文/工具输出的完整内容。仅允许访问工作区 .cache/ 目录，其它路径会被拒绝。支持 offset（字符偏移）与 limit（最大字符数）分段读取。")
    public String readFile(
            @ToolParam(name = "path", description = "要读取的缓存文件绝对路径") String path,
            @ToolParam(name = "offset", description = "可选：从第几个字符开始读（默认 0）", required = false) Integer offset,
            @ToolParam(name = "limit", description = "可选：返回的最大字符数（默认 16000，最大 50000）", required = false) Integer limit) {

        if (allowedRoot == null) {
            return "错误：工作区未配置，无法读取缓存文件。";
        }
        if (path == null || path.trim().isEmpty()) {
            return "错误：path 不能为空。";
        }

        Path target;
        try {
            target = Path.of(path.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "错误：路径无效: " + e.getMessage();
        }

        // 目录穿越 / 越界防护：目标必须落在工作区 .cache/ 之内
        if (!target.startsWith(allowedRoot)) {
            return "拒绝：仅允许读取工作区 .cache/ 目录内的文件（" + allowedRoot + "）。";
        }
        if (!Files.isRegularFile(target)) {
            return "错误：文件不存在或不是普通文件: " + target;
        }

        int off = Math.max(0, offset != null ? offset : 0);
        int lim = limit != null ? Math.min(Math.max(1, limit), MAX_LIMIT_CHARS) : DEFAULT_LIMIT_CHARS;

        try {
            String text = readLimitedText(target, MAX_READ_BYTES);
            if (text.isEmpty()) {
                return "（文件为空）";
            }
            boolean byteTruncated = text.length() >= MAX_READ_BYTES;
            if (off >= text.length()) {
                return "错误：offset 超出文件长度（文件共 " + text.length() + " 字符）。";
            }
            int end = Math.min(text.length(), off + lim);
            String slice = text.substring(off, end);
            String footer = "";
            if (end < text.length()) {
                footer = "\n...[已截断：从 " + off + " 读到 " + end + "，文件共 " + text.length()
                        + " 字符；可用 offset=" + end + " 继续读]";
            } else if (byteTruncated) {
                footer = "\n...[文件超过 " + (MAX_READ_BYTES / 1024) + "KB，仅读取前段]";
            }
            return slice + footer;
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private static String readLimitedText(Path file, int maxBytes) throws Exception {
        long fileSize = Files.size(file);
        int toRead = (int) Math.min(fileSize, (long) maxBytes);
        try (InputStream in = Files.newInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(toRead, 8192));
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < toRead) {
                int n = in.read(buf, 0, Math.min(buf.length, toRead - total));
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}