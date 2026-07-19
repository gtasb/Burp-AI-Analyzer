package com.ai.analyzer.Tools;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件系统访问工具 - 让 AI 主动探索知识
 * 
 * 参考: https://blog.hikarilan.life/tech/2914/your-rag-system-might-be-killing-the-spirituality-of-llms/
 * 
 * 核心理念：授人以鱼不如授人以渔
 * - 传统 RAG：预处理 → 分块 → 嵌入 → 被动检索（上下文断裂）
 * - FileSystem Access：AI 主动浏览目录、搜索文件、读取内容（保持完整性）
 * 
 * 适用场景：
 * - 让 AI 阅读本地漏洞知识库、PoC 脚本、安全规则文档
 * - 探索项目代码结构，理解业务逻辑
 * - 查找配置文件、日志文件中的关键信息
 */
public class FileSystemAccessTools {
    
    private final MontoyaApi api;
    
    // 允许访问的根目录（安全限制）
    private String allowedRootPath = null;
    
    // 最大读取文件大小（防止读取超大文件）
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
    
    // 最大搜索结果数量
    private static final int MAX_SEARCH_RESULTS = 50;
    
    public FileSystemAccessTools(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 设置允许访问的根目录（安全限制）
     * 只有设置了根目录后，AI 才能访问文件系统
     */
    public void setAllowedRootPath(String rootPath) {
        this.allowedRootPath = rootPath;
        if (api != null) {
            api.logging().logToOutput("[FileSystemAccess] 已设置允许访问的根目录: " + rootPath);
        }
    }
    
    /**
     * 列出目录内容 - 让 AI 浏览文件结构
     */
    @Tool(name = "FSA_list_directory", value = {
        "列出指定目录下的文件和子目录。",
        "返回文件名、类型（文件/目录）、大小等信息。",
        "用于探索知识库结构、了解项目布局。"
    })
    public String listDirectory(
            @P("要列出的目录路径，相对于知识库根目录") String directoryPath
    ) {
        try {
            Path path = validateAndResolvePath(directoryPath);
            if (path == null) {
                return "❌ 错误：路径无效或未设置知识库根目录";
            }
            
            if (!Files.isDirectory(path)) {
                return "❌ 错误：指定路径不是目录: " + directoryPath;
            }
            
            StringBuilder result = new StringBuilder();
            result.append("📁 目录: ").append(directoryPath).append("\n\n");
            
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    String name = entry.getFileName().toString();
                    
                    if (attrs.isDirectory()) {
                        dirs.add("📂 " + name + "/");
                    } else {
                        long size = attrs.size();
                        String sizeStr = formatFileSize(size);
                        files.add("📄 " + name + " (" + sizeStr + ")");
                    }
                }
            }
            
            // 先显示目录，再显示文件
            dirs.sort(String::compareToIgnoreCase);
            files.sort(String::compareToIgnoreCase);
            
            if (!dirs.isEmpty()) {
                result.append("子目录 (").append(dirs.size()).append("):\n");
                for (String dir : dirs) {
                    result.append("  ").append(dir).append("\n");
                }
                result.append("\n");
            }
            
            if (!files.isEmpty()) {
                result.append("文件 (").append(files.size()).append("):\n");
                for (String file : files) {
                    result.append("  ").append(file).append("\n");
                }
            }
            
            if (dirs.isEmpty() && files.isEmpty()) {
                result.append("（空目录）");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 列出目录失败: " + e.getMessage();
        }
    }
    
    /**
     * 读取文件内容 - 让 AI 获取完整知识
     */
    @Tool(name = "FSA_read_file", value = {
        "读取指定文件的内容。",
        "支持文本文件：txt, md, json, yaml, xml, py, java, js, sql 等。",
        "可指定起始行和行数，用于读取大文件的部分内容。"
    })
    public String readFile(
            @P("要读取的文件路径，相对于知识库根目录") String filePath,
            @P("起始行号（从1开始），不指定则从头开始") Integer startLine,
            @P("要读取的行数，不指定则读取全部（最多1000行）") Integer lineCount
    ) {
        try {
            Path path = validateAndResolvePath(filePath);
            if (path == null) {
                return "❌ 错误：路径无效或未设置知识库根目录";
            }
            
            if (!Files.isRegularFile(path)) {
                return "❌ 错误：文件不存在: " + filePath;
            }
            
            // 检查文件大小
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                return "❌ 错误：文件过大 (" + formatFileSize(fileSize) + ")，请使用 startLine 和 lineCount 参数读取部分内容";
            }
            
            // 读取文件内容
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int totalLines = allLines.size();
            
            int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
            int count = (lineCount != null && lineCount > 0) ? lineCount : Math.min(1000, totalLines);
            int end = Math.min(start + count, totalLines);
            
            StringBuilder result = new StringBuilder();
            result.append("📄 文件: ").append(filePath).append("\n");
            result.append("📊 总行数: ").append(totalLines).append(", 显示: 第 ")
                  .append(start + 1).append("-").append(end).append(" 行\n\n");
            result.append("```\n");
            
            for (int i = start; i < end; i++) {
                result.append(String.format("%4d | ", i + 1)).append(allLines.get(i)).append("\n");
            }
            
            result.append("```");
            
            if (end < totalLines) {
                result.append("\n\n💡 提示：还有 ").append(totalLines - end)
                      .append(" 行未显示，可使用 startLine=").append(end + 1).append(" 继续读取");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 读取文件失败: " + e.getMessage();
        }
    }
    
    /**
     * 搜索文件 - 按文件名查找
     */
    @Tool(name = "FSA_find_files", value = {
        "按文件名模式搜索文件。",
        "支持通配符：* 匹配多个字符，? 匹配单个字符。",
        "例如：*.md 查找所有 Markdown 文件，sql*.txt 查找 sql 开头的 txt 文件。"
    })
    public String findFiles(
            @P("文件名模式，支持通配符 * 和 ?，例如：*.md, poc_*.py") String pattern,
            @P("搜索的目录路径，不指定则搜索整个知识库") String searchPath
    ) {
        try {
            String basePath = (searchPath != null && !searchPath.isEmpty()) ? searchPath : "";
            Path path = validateAndResolvePath(basePath);
            if (path == null) {
                return "❌ 错误：路径无效或未设置知识库根目录";
            }
            
            // 将通配符模式转换为正则表达式
            String regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".");
            Pattern filePattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            
            List<String> results = new ArrayList<>();
            
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_SEARCH_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    
                    String fileName = file.getFileName().toString();
                    if (filePattern.matcher(fileName).matches()) {
                        String relativePath = path.relativize(file).toString().replace("\\", "/");
                        String sizeStr = formatFileSize(attrs.size());
                        results.add(relativePath + " (" + sizeStr + ")");
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            
            StringBuilder result = new StringBuilder();
            result.append("🔍 搜索: ").append(pattern).append("\n");
            result.append("📁 范围: ").append(basePath.isEmpty() ? "/" : basePath).append("\n\n");
            
            if (results.isEmpty()) {
                result.append("未找到匹配的文件");
            } else {
                result.append("找到 ").append(results.size()).append(" 个文件:\n");
                for (String r : results) {
                    result.append("  📄 ").append(r).append("\n");
                }
                if (results.size() >= MAX_SEARCH_RESULTS) {
                    result.append("\n⚠️ 结果已截断，仅显示前 ").append(MAX_SEARCH_RESULTS).append(" 个");
                }
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 搜索文件失败: " + e.getMessage();
        }
    }
    
    /**
     * 在文件中搜索内容 - 类似 grep
     */
    @Tool(name = "FSA_grep_search", value = {
        "在文件内容中搜索关键词或正则表达式。",
        "类似 grep 命令，返回匹配的行及其行号。",
        "适用于在知识库中查找特定漏洞、代码模式、配置项等。"
    })
    public String grepSearch(
            @P("搜索的关键词或正则表达式") String searchPattern,
            @P("要搜索的文件或目录路径") String searchPath,
            @P("文件名过滤模式，例如：*.java 只搜索 Java 文件") String filePattern,
            @P("是否区分大小写，默认不区分") Boolean caseSensitive
    ) {
        try {
            Path path = validateAndResolvePath(searchPath);
            if (path == null) {
                return "❌ 错误：路径无效或未设置知识库根目录";
            }
            
            boolean isCaseSensitive = caseSensitive != null && caseSensitive;
            int flags = isCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern contentPattern = Pattern.compile(searchPattern, flags);
            
            // 文件名过滤
            Pattern fileFilterPattern = null;
            if (filePattern != null && !filePattern.isEmpty()) {
                String regex = filePattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".");
                fileFilterPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            }
            
            List<String> results = new ArrayList<>();
            final Pattern finalFileFilter = fileFilterPattern;
            
            if (Files.isRegularFile(path)) {
                // 搜索单个文件
                searchInFile(path, contentPattern, "", results);
            } else {
                // 搜索目录
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (results.size() >= MAX_SEARCH_RESULTS * 3) {
                            return FileVisitResult.TERMINATE;
                        }
                        
                        // 文件名过滤
                        String fileName = file.getFileName().toString();
                        if (finalFileFilter != null && !finalFileFilter.matcher(fileName).matches()) {
                            return FileVisitResult.CONTINUE;
                        }
                        
                        // 只搜索文本文件（根据扩展名判断）
                        if (!isTextFile(fileName)) {
                            return FileVisitResult.CONTINUE;
                        }
                        
                        String relativePath = path.relativize(file).toString().replace("\\", "/");
                        searchInFile(file, contentPattern, relativePath, results);
                        
                        return FileVisitResult.CONTINUE;
                    }
                    
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            
            StringBuilder result = new StringBuilder();
            result.append("🔍 搜索: \"").append(searchPattern).append("\"\n");
            result.append("📁 范围: ").append(searchPath).append("\n");
            if (filePattern != null) {
                result.append("📋 文件过滤: ").append(filePattern).append("\n");
            }
            result.append("\n");
            
            if (results.isEmpty()) {
                result.append("未找到匹配内容");
            } else {
                result.append("找到 ").append(results.size()).append(" 处匹配:\n\n");
                for (String r : results) {
                    result.append(r).append("\n");
                }
                if (results.size() >= MAX_SEARCH_RESULTS * 3) {
                    result.append("\n⚠️ 结果已截断");
                }
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 搜索失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取文件信息
     */
    @Tool(name = "FSA_file_info", value = {
        "获取文件或目录的详细信息。",
        "包括大小、创建时间、修改时间、文件类型等。"
    })
    public String getFileInfo(
            @P("文件或目录路径") String filePath
    ) {
        try {
            Path path = validateAndResolvePath(filePath);
            if (path == null) {
                return "❌ 错误：路径无效或未设置知识库根目录";
            }
            
            if (!Files.exists(path)) {
                return "❌ 错误：路径不存在: " + filePath;
            }
            
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            
            StringBuilder result = new StringBuilder();
            result.append("📋 文件信息: ").append(filePath).append("\n\n");
            result.append("类型: ").append(attrs.isDirectory() ? "目录" : "文件").append("\n");
            result.append("大小: ").append(formatFileSize(attrs.size())).append("\n");
            result.append("创建时间: ").append(attrs.creationTime()).append("\n");
            result.append("修改时间: ").append(attrs.lastModifiedTime()).append("\n");
            result.append("访问时间: ").append(attrs.lastAccessTime()).append("\n");
            
            if (attrs.isDirectory()) {
                // 统计目录内容
                long fileCount = 0, dirCount = 0;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path entry : stream) {
                        if (Files.isDirectory(entry)) {
                            dirCount++;
                        } else {
                            fileCount++;
                        }
                    }
                }
                result.append("包含: ").append(dirCount).append(" 个子目录, ")
                      .append(fileCount).append(" 个文件\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 获取信息失败: " + e.getMessage();
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 验证并解析路径（安全检查）
     */
    private Path validateAndResolvePath(String relativePath) {
        if (allowedRootPath == null) {
            return null;
        }
        
        try {
            Path rootPath = Paths.get(allowedRootPath).toAbsolutePath().normalize();
            Path targetPath;
            
            if (relativePath == null || relativePath.isEmpty() || relativePath.equals("/")) {
                targetPath = rootPath;
            } else {
                // 移除开头的 / 或 \
                String cleanPath = relativePath.replaceFirst("^[\\\\/]+", "");
                targetPath = rootPath.resolve(cleanPath).normalize();
            }
            
            // 安全检查：确保目标路径在允许的根目录内
            if (!targetPath.startsWith(rootPath)) {
                if (api != null) {
                    api.logging().logToError("[FileSystemAccess] 安全警告：尝试访问根目录外的路径: " + relativePath);
                }
                return null;
            }
            
            return targetPath;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 在文件中搜索内容
     */
    private void searchInFile(Path file, Pattern pattern, String relativePath, List<String> results) {
        try {
            // 跳过大文件
            if (Files.size(file) > MAX_FILE_SIZE) {
                return;
            }
            
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && results.size() < MAX_SEARCH_RESULTS * 3; i++) {
                String line = lines.get(i);
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String displayPath = relativePath.isEmpty() ? file.getFileName().toString() : relativePath;
                    String truncatedLine = line.length() > 100 ? line.substring(0, 97) + "..." : line;
                    results.add("📄 " + displayPath + ":" + (i + 1) + "\n   " + truncatedLine.trim());
                }
            }
        } catch (Exception e) {
            // 忽略无法读取的文件
        }
    }
    
    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".html") || lower.endsWith(".css")
                || lower.endsWith(".sql") || lower.endsWith(".sh") || lower.endsWith(".bat")
                || lower.endsWith(".ps1") || lower.endsWith(".rb") || lower.endsWith(".go")
                || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h")
                || lower.endsWith(".php") || lower.endsWith(".conf") || lower.endsWith(".cfg")
                || lower.endsWith(".ini") || lower.endsWith(".log") || lower.endsWith(".csv")
                || lower.endsWith(".properties") || lower.endsWith(".env") || lower.endsWith(".toml")
                || lower.endsWith(".rst") || lower.endsWith(".tex") || lower.endsWith(".vue")
                || lower.endsWith(".jsx") || lower.endsWith(".tsx");
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
    }
}
