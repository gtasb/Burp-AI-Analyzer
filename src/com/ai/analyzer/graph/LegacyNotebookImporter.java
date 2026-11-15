package com.ai.analyzer.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性把旧的 {@code <workspace>/notebooks/<domain>.md} 导入知识图谱的 NOTE 节点，
 * 导入成功后删除原文件。Notebook 子系统已合并进图谱，这里只负责历史数据迁移。
 */
public final class LegacyNotebookImporter {

    private static final Pattern ENTRY_MARKER = Pattern.compile(
            "(?m)^<!--\\s*nb:entry\\s+v=(\\d+)\\s+agent=(\\S+)\\s+type=(\\S+)\\s+hash=([0-9a-fA-F]+)\\s+time=(\\S+)\\s*-->");

    private LegacyNotebookImporter() {
    }

    private record Entry(int version, String agent, String type, String content) {
    }

    public static void importOnce(Path base, GraphStore store) {
        Path dir = base.resolve("notebooks");
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList();
        } catch (IOException e) {
            com.ai.analyzer.util.DebugContext.log("LegacyNotebookImporter", "list_failed",
                    java.util.Map.of("error", String.valueOf(e.getMessage())));
            return;
        }
        for (Path file : files) {
            try {
                importFile(dir, file, store);
            } catch (Exception e) {
                // 单文件失败保留原文件，避免丢数据
                com.ai.analyzer.util.DebugContext.log("LegacyNotebookImporter", "import_failed",
                        java.util.Map.of("file", file.getFileName().toString(),
                                "error", String.valueOf(e.getMessage())));
            }
        }
    }

    private static void importFile(Path dir, Path file, GraphStore store) throws IOException {
        String fileName = file.getFileName().toString();
        String domain = fileName.substring(0, fileName.length() - ".md".length());
        List<Entry> entries = parseEntries(Files.readString(file, StandardCharsets.UTF_8));
        if (entries.isEmpty()) {
            deleteLegacy(dir, domain, file);
            return;
        }
        List<String> roots = rootsFor(domain, store);
        if (roots.isEmpty()) {
            // 没有匹配 root：保留文件等真实流量出现后重试（下次 db() 打开会再次扫描）。
            // 注意：不更新 importedLegacyBase 守卫语义 —— 守卫按“工作区”置位，
            // 这里把文件保留即可，但守卫会阻止重试；因此这种情况要让守卫失效。
            store.markLegacyImportRetry();
            com.ai.analyzer.util.DebugContext.log("LegacyNotebookImporter", "deferred_no_root",
                    java.util.Map.of("domain", domain,
                            "entries", String.valueOf(entries.size())));
            return;
        }
        for (String root : roots) {
            for (Entry e : entries) {
                store.importNote(root, e.content(), e.type(), e.agent(), e.version());
            }
        }
        deleteLegacy(dir, domain, file);
        com.ai.analyzer.util.DebugContext.log("LegacyNotebookImporter", "imported",
                java.util.Map.of("domain", domain, "entries", String.valueOf(entries.size()),
                        "roots", String.valueOf(roots.size())));
    }

    /** 同名 host 的全部 root（http/https、不同端口都会收到同一份历史笔记）。 */
    private static List<String> rootsFor(String domain, GraphStore store) {
        String target = domain.toLowerCase(Locale.ROOT);
        List<String> roots = new ArrayList<>();
        for (String origin : store.listRoots()) {
            try {
                String host = GraphNormalizers.parse("GET", origin, null).host();
                if (host != null && host.equalsIgnoreCase(target)) {
                    roots.add(origin);
                }
            } catch (Exception ignored) {
            }
        }
        // 没有任何已存在 root 时不造 https 幽灵 root：真实流量尚未出现，
        // 造出来的 root 会出现在站点下拉里且没有 ORIGIN 图节点，导入的笔记
        // 会变成孤儿（挂不上站点级 NOTE 边）。保留 .md 文件，等真实 root 出现后重试。
        return roots;
    }

    private static void deleteLegacy(Path dir, String domain, Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir.resolve(domain + ".lock"));
    }

    private static List<Entry> parseEntries(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = ENTRY_MARKER.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String[]> metas = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
            metas.add(new String[]{matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        List<Entry> entries = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String body = stripLeadingHeading(text.substring(bodyStart, bodyEnd));
            if (body.isBlank()) continue;
            String[] meta = metas.get(i);
            entries.add(new Entry(Integer.parseInt(meta[0]), meta[1], meta[2], body));
        }
        return entries;
    }

    private static String stripLeadingHeading(String section) {
        String s = section.stripLeading();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(nl + 1);
        }
        return s.strip();
    }
}
