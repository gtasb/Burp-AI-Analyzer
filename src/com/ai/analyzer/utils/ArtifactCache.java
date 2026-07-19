package com.ai.analyzer.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores oversized tool/HTTP results on disk and returns a small reference for the LLM context.
 */
public final class ArtifactCache {
    private static final int MAX_LINE_COUNT = 500;
    private static final int MAX_READ_CHARS = 30_000;
    private static final Path FALLBACK_CACHE_DIR = Path.of(
            System.getProperty("user.home", "."),
            ".burp-ai-analyzer",
            "cache"
    );
    private static volatile Path cacheDir = FALLBACK_CACHE_DIR;

    private ArtifactCache() {
    }

    public record ArtifactRef(String fileId, String filename, long size) {
        public String toPromptText() {
            return """
                    数据已缓存
                    fileId=%s
                    filename=%s
                    size=%d bytes
                    可使用 read_cached_artifact(fileId, startLine, lineCount) 分段读取。
                    """.formatted(fileId, filename, size);
        }
    }

    public static void setWorkplaceDirectory(String workplaceDirectory) {
        if (workplaceDirectory == null || workplaceDirectory.trim().isEmpty()) {
            cacheDir = FALLBACK_CACHE_DIR;
            return;
        }
        Path workplace = Path.of(workplaceDirectory.trim()).toAbsolutePath().normalize();
        cacheDir = workplace.resolve(".cache").normalize();
    }

    public static ArtifactRef saveText(String content, String label) throws IOException {
        String safeLabel = sanitizeLabel(label);
        String fileId = UUID.randomUUID().toString();
        String filename = safeLabel.isEmpty() ? fileId + ".txt" : fileId + "-" + safeLabel + ".txt";
        Path dir = currentCacheDir();
        Files.createDirectories(dir);

        Path file = dir.resolve(filename).normalize();
        String body = content == null ? "" : content;
        String header = "[created] " + Instant.now() + "\n[label] " + (label == null ? "" : label) + "\n\n";
        Files.writeString(file, header + body, StandardCharsets.UTF_8);
        return new ArtifactRef(fileId, file.toAbsolutePath().toString(), Files.size(file));
    }

    public static String readLines(String fileId, int startLine, int lineCount) throws IOException {
        Path file = resolveByFileId(fileId);
        int safeStartLine = Math.max(1, startLine);
        int safeLineCount = Math.max(1, Math.min(lineCount, MAX_LINE_COUNT));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String text = lines.stream()
                .skip(safeStartLine - 1L)
                .limit(safeLineCount)
                .collect(Collectors.joining("\n"));

        if (text.length() > MAX_READ_CHARS) {
            return text.substring(0, MAX_READ_CHARS)
                    + "\n\n...[读取结果已截断，请减少 lineCount 或提高 startLine 分段读取]...";
        }
        return text;
    }

    public static String describe(String fileId) throws IOException {
        Path file = resolveByFileId(fileId);
        long lineCount;
        try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
            lineCount = stream.count();
        }
        return """
                fileId=%s
                filename=%s
                size=%d bytes
                lines=%d
                """.formatted(fileId, file.toAbsolutePath(), Files.size(file), lineCount);
    }

    private static Path resolveByFileId(String fileId) throws IOException {
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IOException("fileId 不能为空");
        }
        String trimmed = fileId.trim();
        if (!trimmed.matches("[0-9a-fA-F\\-]{36}")) {
            throw new IOException("fileId 格式无效");
        }
        Path dir = currentCacheDir();
        if (!Files.isDirectory(dir)) {
            throw new IOException("缓存目录不存在");
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(trimmed))
                    .findFirst()
                    .orElseThrow(() -> new IOException("未找到缓存文件: " + trimmed));
        }
    }

    private static Path currentCacheDir() {
        return Objects.requireNonNullElse(cacheDir, FALLBACK_CACHE_DIR);
    }

    private static String sanitizeLabel(String label) {
        if (label == null) return "";
        String safe = label.trim().replaceAll("[^a-zA-Z0-9._-]+", "-");
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        return safe;
    }
}
