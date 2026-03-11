package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotebookTools {

    private static final int MAX_READ_CHARS = 20000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern URL_HOST_PATTERN = Pattern.compile("https?://([a-zA-Z0-9.-]+)(?::\\d+)?");
    private static final Pattern HOST_HEADER_PATTERN = Pattern.compile("(?im)^host\\s*:\\s*([a-zA-Z0-9.-]+)(?::\\d+)?\\s*$");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}\\b");
    private String workplaceDirectory = "";

    public void setWorkplaceDirectory(String workplaceDirectory) {
        this.workplaceDirectory = workplaceDirectory == null ? "" : workplaceDirectory.trim();
    }

    @Tool(name = "manage_notebook", value = {
        "管理项目 Notebook（人机协作共享笔记）。",
        "Notebook 位于 Workplace/notebooks 下，不同项目使用不同文件名；projectId 可留空自动识别。",
        "支持 action: read, append, overwrite, replace, delete_contains, clear, list."
    })
    public String manageNotebook(
            @P("项目标识（如域名、项目名）。可为空，工具会自动从内容中识别。") String projectId,
            @P("操作类型: read/append/overwrite/replace/delete_contains/clear/list") String action,
            @P("写入内容。append/overwrite 需要。") String content,
            @P("替换时旧文本。replace 需要。") String oldText,
            @P("替换时新文本。replace 需要。") String newText,
            @P("按包含关键字删除行。delete_contains 需要。") String keyword) {
        try {
            Path notebooksDir = resolveNotebookDirectory();
            Files.createDirectories(notebooksDir);

            String normalizedAction = action == null ? "" : action.trim().toLowerCase();
            if ("list".equals(normalizedAction)) {
                return listNotebooks(notebooksDir);
            }

            ProjectResolution resolution = resolveProjectId(notebooksDir, projectId, normalizedAction, content, oldText, newText, keyword);
            Path notebookPath = resolveNotebookPath(notebooksDir, resolution.projectId);
            ensureNotebookExists(notebookPath, resolution.projectId);

            switch (normalizedAction) {
                case "read":
                    return withProjectHint(readNotebook(notebookPath), resolution);
                case "append":
                    return withProjectHint(appendNotebook(notebookPath, content), resolution);
                case "overwrite":
                    return withProjectHint(overwriteNotebook(notebookPath, content), resolution);
                case "replace":
                    return withProjectHint(replaceText(notebookPath, oldText, newText), resolution);
                case "delete_contains":
                    return withProjectHint(deleteLinesContains(notebookPath, keyword), resolution);
                case "clear":
                    return withProjectHint(clearNotebook(notebookPath), resolution);
                default:
                    return "错误：未知 action。可用值: read, append, overwrite, replace, delete_contains, clear, list";
            }
        } catch (Exception e) {
            return "Notebook 操作失败: " + e.getMessage();
        }
    }

    private Path resolveNotebookDirectory() {
        if (workplaceDirectory != null && !workplaceDirectory.isEmpty()) {
            return new File(workplaceDirectory, "notebooks").toPath();
        }
        return new File(System.getProperty("user.home"), "ai-analyzer-workplace/notebooks").toPath();
    }

    private Path resolveNotebookPath(Path notebooksDir, String projectId) {
        String id = (projectId == null || projectId.trim().isEmpty()) ? "default" : projectId.trim();
        String safe = id.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return notebooksDir.resolve(safe + "_notebook.txt");
    }

    private void ensureNotebookExists(Path notebookPath, String projectId) throws Exception {
        if (Files.exists(notebookPath)) return;
        String id = (projectId == null || projectId.trim().isEmpty()) ? "default" : projectId.trim();
        String header = "# Project Notebook\n" +
                "project: " + id + "\n" +
                "created_at: " + LocalDateTime.now().format(TIME_FMT) + "\n\n";
        Files.writeString(notebookPath, header, StandardCharsets.UTF_8);
    }

    private String readNotebook(Path notebookPath) throws Exception {
        String text = Files.readString(notebookPath, StandardCharsets.UTF_8);
        if (text.length() > MAX_READ_CHARS) {
            text = text.substring(0, MAX_READ_CHARS) + "\n...[内容已截断]";
        }
        return "Notebook 文件: " + notebookPath + "\n\n" + text;
    }

    private String appendNotebook(Path notebookPath, String content) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            return "错误：append 需要 content。";
        }
        String block = "\n## " + LocalDateTime.now().format(TIME_FMT) + "\n" + content.trim() + "\n";
        Files.writeString(notebookPath, block, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        return "已追加到 Notebook: " + notebookPath;
    }

    private String overwriteNotebook(Path notebookPath, String content) throws Exception {
        if (content == null) content = "";
        Files.writeString(notebookPath, content, StandardCharsets.UTF_8);
        return "已覆盖 Notebook: " + notebookPath;
    }

    private String replaceText(Path notebookPath, String oldText, String newText) throws Exception {
        if (oldText == null || oldText.isEmpty()) {
            return "错误：replace 需要 oldText。";
        }
        String text = Files.readString(notebookPath, StandardCharsets.UTF_8);
        if (!text.contains(oldText)) {
            return "未找到要替换的文本。";
        }
        String replaced = text.replace(oldText, newText == null ? "" : newText);
        Files.writeString(notebookPath, replaced, StandardCharsets.UTF_8);
        return "替换完成: " + notebookPath;
    }

    private String deleteLinesContains(Path notebookPath, String keyword) throws Exception {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误：delete_contains 需要 keyword。";
        }
        List<String> lines = Files.readAllLines(notebookPath, StandardCharsets.UTF_8);
        int before = lines.size();
        lines.removeIf(line -> line.contains(keyword));
        Files.write(notebookPath, lines, StandardCharsets.UTF_8);
        int deleted = before - lines.size();
        return "删除完成: " + deleted + " 行，文件: " + notebookPath;
    }

    private String clearNotebook(Path notebookPath) throws Exception {
        Files.writeString(notebookPath, "", StandardCharsets.UTF_8);
        return "已清空 Notebook: " + notebookPath;
    }

    private String listNotebooks(Path notebooksDir) throws Exception {
        File[] files = notebooksDir.toFile().listFiles((dir, name) -> name.endsWith("_notebook.txt"));
        if (files == null || files.length == 0) {
            return "notebooks 目录为空: " + notebooksDir;
        }
        StringBuilder sb = new StringBuilder("notebooks 目录: ").append(notebooksDir).append("\n");
        for (File file : files) {
            sb.append("- ").append(file.getName()).append("\n");
        }
        return sb.toString();
    }

    private String withProjectHint(String result, ProjectResolution resolution) {
        if (resolution == null || result == null) return result;
        String mode = resolution.autoDetected ? "自动识别" : "显式指定";
        return "projectId(" + mode + "): " + resolution.projectId + "\n" + result;
    }

    private ProjectResolution resolveProjectId(Path notebooksDir, String projectId, String action,
                                               String content, String oldText, String newText, String keyword) {
        if (projectId != null && !projectId.trim().isEmpty()) {
            return new ProjectResolution(projectId.trim(), false);
        }

        String hint = buildHintContent(content, oldText, newText, keyword);
        String detected = detectProjectIdFromText(hint);
        if (!detected.isEmpty()) {
            return new ProjectResolution(detected, true);
        }

        String latest = resolveLatestNotebookProjectId(notebooksDir);
        if (!latest.isEmpty() && !"append".equals(action)) {
            return new ProjectResolution(latest, true);
        }
        return new ProjectResolution("default", true);
    }

    private String buildHintContent(String content, String oldText, String newText, String keyword) {
        StringBuilder sb = new StringBuilder();
        if (content != null) sb.append(content).append('\n');
        if (oldText != null) sb.append(oldText).append('\n');
        if (newText != null) sb.append(newText).append('\n');
        if (keyword != null) sb.append(keyword).append('\n');
        return sb.toString();
    }

    private String detectProjectIdFromText(String text) {
        if (text == null || text.isEmpty()) return "";

        Matcher urlMatcher = URL_HOST_PATTERN.matcher(text);
        if (urlMatcher.find()) return sanitizeProjectId(urlMatcher.group(1));

        Matcher hostMatcher = HOST_HEADER_PATTERN.matcher(text);
        if (hostMatcher.find()) return sanitizeProjectId(hostMatcher.group(1));

        Matcher domainMatcher = DOMAIN_PATTERN.matcher(text);
        if (domainMatcher.find()) return sanitizeProjectId(domainMatcher.group());

        return "";
    }

    private String resolveLatestNotebookProjectId(Path notebooksDir) {
        File[] files = notebooksDir.toFile().listFiles((dir, name) -> name.endsWith("_notebook.txt"));
        if (files == null || files.length == 0) return "";
        return Arrays.stream(files)
                .filter(File::isFile)
                .max(Comparator.comparingLong(File::lastModified))
                .map(File::getName)
                .map(name -> name.replaceFirst("_notebook\\.txt$", ""))
                .orElse("");
    }

    private String sanitizeProjectId(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase();
        value = value.replaceAll("[^a-z0-9._-]", "_");
        if (value.length() > 80) value = value.substring(0, 80);
        return value;
    }

    private static final class ProjectResolution {
        private final String projectId;
        private final boolean autoDetected;

        private ProjectResolution(String projectId, boolean autoDetected) {
            this.projectId = projectId;
            this.autoDetected = autoDetected;
        }
    }
}
