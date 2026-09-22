package com.ai.analyzer.tools.NotebookUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notebook 黑板引擎：主动扫描 Agent 与多个被动扫描 Agent 之间共享关键发现的
 * 轻量级持久化知识库（Blackboard）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>按域名隔离</b>：每个目标域名对应一个 {@code <domain>.md} 文件，
 *       例如 {@code example.com.md}，持久保留该域名的攻击面、接口、参数、漏洞线索等关键发现。</li>
 *   <li><b>原子写 + 文件级写锁</b>：写入采用「读-改-写 + 临时文件 + ATOMIC_MOVE 原子替换」，
 *       并同时持有 JVM 内 {@link ReentrantLock} 与 OS 级 {@link FileLock}（{@code <domain>.lock}），
 *       多个异步 Agent 并发写入不会内容冲突或损坏。</li>
 *   <li><b>递增版本号</b>：每个域名独立维护从 1 开始的单调递增版本号，写入成功后 +1，
 *       版本号持久化在 {@code .md} 文件的条目元数据里（跨重启仍可解析）。</li>
 *   <li><b>增量更新</b>：{@link #getUpdates(String, int)} 按 {@code since_version} 返回
 *       版本号大于阈值的全部新增条目，Agent 即使错过广播或重启后也能按版本补齐。</li>
 *   <li><b>去重</b>：对内容做归一化（压空白、小写）后取摘要，与已有条目摘要比对，
 *       相同则跳过追加（不产生新版本、不广播）。</li>
 *   <li><b>进程内广播</b>：每次成功写入后通过 {@link #subscribe(Consumer)} 注册的监听器
 *       广播 {@link NotebookUpdate}，只提醒不上载数据。</li>
 * </ul>
 *
 * <p>整体只依赖本地文件系统，不引入 Redis / Kafka / NATS 等外部消息系统。
 * 工具层（{@link com.ai.analyzer.tools.NotebookTool}）已经把这些机制全部封装，Agent 只需调用
 * {@code read / write / get_updates / list} 即可，无需自己管理锁、版本或消息队列。
 */
public final class NotebookStore {

    private static final DateTimeFormatter META_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 单条内容归一化后做去重摘要 */
    private static final String HASH_ALGO = "SHA-256";

    /** 条目元数据标记：单行 HTML 注释，隐藏在渲染结果里，但可稳定用正则解析 */
    private static final Pattern ENTRY_MARKER = Pattern.compile(
            "(?m)^<!--\\s*nb:entry\\s+v=(\\d+)\\s+agent=(\\S+)\\s+type=(\\S+)\\s+hash=([0-9a-fA-F]+)\\s+time=(\\S+)\\s*-->");

    private static final NotebookStore INSTANCE = new NotebookStore();

    /** 工作区目录（{@code null} 时回落到用户主目录下的默认目录） */
    private volatile Path workplaceDir;

    /** 每个域名一把 JVM 内锁，保证同进程（多异步 Agent）写入互斥 */
    private final ConcurrentHashMap<String, ReentrantLock> domainLocks = new ConcurrentHashMap<>();

    /** 进程内广播订阅者（提醒用，不承载数据） */
    private final CopyOnWriteArrayList<Consumer<NotebookUpdate>> listeners = new CopyOnWriteArrayList<>();

    /** 广播专用单线程执行器：避免监听器阻塞写入线程、避免监听器回调重入锁 */
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notebook-broadcast");
        t.setDaemon(true);
        return t;
    });

    private NotebookStore() {
    }

    public static NotebookStore getInstance() {
        return INSTANCE;
    }

    /**
     * 设置工作区目录。Notebook 实际落在 {@code <workplace>/notebooks/} 下；
     * 未设置时回落到 {@code ~/ai-analyzer-workplace/notebooks/}。
     */
    public void setWorkplaceDirectory(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            workplaceDir = null;
        } else {
            workplaceDir = Path.of(dir.trim()).toAbsolutePath().normalize();
        }
    }

    // ============ 广播订阅 ============

    /** 订阅广播通知（只提醒，不承载数据）。返回的 Consumer 可再次传入 {@link #unsubscribe(Consumer)} 取消。 */
    public void subscribe(Consumer<NotebookUpdate> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void unsubscribe(Consumer<NotebookUpdate> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void publishAsync(NotebookUpdate update) {
        for (Consumer<NotebookUpdate> listener : listeners) {
            broadcastExecutor.execute(() -> {
                try {
                    listener.accept(update);
                } catch (Exception ignored) {
                    // 广播只负责提醒，单个监听器异常不能影响其他订阅者
                }
            });
        }
    }

    // ============ 读 / 写 / 增量 / 列表 ============

    /**
     * 读取某域名的完整 Notebook 原始 Markdown（不含内容截断，截断交给工具层）。
     * 不存在时返回空字符串。
     */
    public String read(String domain) throws IOException {
        String d = normalizeDomain(domain);
        return withFileLock(d, () -> {
            Path target = resolveNotebookPath(d);
            if (!Files.exists(target)) {
                return "";
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        });
    }

    /** 某域名 Notebook 的一次性读取结果：正文 + 是否存在 + 最新版本（单次持锁，避免二次读盘）。 */
    public record ReadResult(String content, boolean exists, int version) {}

    public ReadResult readWithMeta(String domain) throws IOException {
        String d = normalizeDomain(domain);
        return withFileLock(d, () -> {
            Path target = resolveNotebookPath(d);
            if (!Files.exists(target)) {
                return new ReadResult("", false, 0);
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            int version = parseEntries(content).stream()
                    .mapToInt(NotebookEntry::version)
                    .max()
                    .orElse(0);
            return new ReadResult(content, true, version);
        });
    }

    /**
     * 追加一条关键发现。内容为空返回 {@code appended=false}；与已有条目内容重复时
     * 返回 {@code duplicate=true}（不产生新版本、不广播）。
     */
    public WriteResult write(String domain, String type, String content, String sourceAgent) throws IOException {
        String d = normalizeDomain(domain);
        String normalizedType = normalizeType(type);
        String c = content == null ? "" : content.trim();
        if (c.isEmpty()) {
            return WriteResult.empty(d);
        }
        String agent = normalizeToken(sourceAgent);
        String hash = hashOf(normalizeContent(c));
        String time = LocalDateTime.now().format(META_TIME);

        WriteResult result = withFileLock(d, () -> {
            Files.createDirectories(notebooksDir());
            Path target = resolveNotebookPath(d);
            String current = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
            List<NotebookEntry> entries = parseEntries(current);

            int latest = entries.stream().mapToInt(NotebookEntry::version).max().orElse(0);
            boolean duplicated = entries.stream().anyMatch(e -> e.hash().equals(hash));
            if (duplicated) {
                return WriteResult.duplicate(d, latest);
            }

            int newVersion = latest + 1;
            NotebookEntry entry = new NotebookEntry(newVersion, agent, normalizedType, time, hash, c);
            String updated = renderNotebook(current, d, entry);
            atomicWrite(target, updated);
            return WriteResult.appended(d, newVersion);
        });

        // 广播在锁外进行，避免监听器回调（可能再次 read/write）重入文件锁导致死锁
        if (result.appended()) {
            publishAsync(new NotebookUpdate(d, result.version(), agent, normalizedType, time));
        }
        return result;
    }

    /**
     * 按版本增量读取：只返回 {@code version > sinceVersion} 的条目。
     * 保证错过广播、当时未运行或重启后的 Agent 也能补齐。
     */
    public List<NotebookEntry> getUpdates(String domain, int sinceVersion) throws IOException {
        String d = normalizeDomain(domain);
        return withFileLock(d, () -> {
            Path target = resolveNotebookPath(d);
            if (!Files.exists(target)) {
                return List.of();
            }
            return parseEntries(Files.readString(target, StandardCharsets.UTF_8)).stream()
                    .filter(e -> e.version() > sinceVersion)
                    .toList();
        });
    }

    /** 某域名的当前最新版本号；不存在时为 0。 */
    public int latestVersion(String domain) throws IOException {
        String d = normalizeDomain(domain);
        return withFileLock(d, () -> {
            Path target = resolveNotebookPath(d);
            if (!Files.exists(target)) {
                return 0;
            }
            return parseEntries(Files.readString(target, StandardCharsets.UTF_8)).stream()
                    .mapToInt(NotebookEntry::version)
                    .max()
                    .orElse(0);
        });
    }

    /** 列出 notebooks 目录下所有域名的摘要（域名 + 最新版本 + 条目数），按域名排序。 */
    public List<DomainSummary> listDomains() throws IOException {
        Path dir = notebooksDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        List<DomainSummary> out = new ArrayList<>(files.size());
        for (Path file : files) {
            String name = file.getFileName().toString();
            String domain = name.substring(0, name.length() - ".md".length());
            try {
                List<NotebookEntry> entries = parseEntries(Files.readString(file, StandardCharsets.UTF_8));
                int version = entries.stream().mapToInt(NotebookEntry::version).max().orElse(0);
                out.add(new DomainSummary(domain, version, entries.size()));
            } catch (Exception ignored) {
                // 单个损坏文件跳过，不影响列出其它域名
                out.add(new DomainSummary(domain, 0, 0));
            }
        }
        out.sort(Comparator.comparing(DomainSummary::domain));
        return out;
    }

    /**
     * 去除 Markdown 里的条目元数据标记行（HTML 注释），让 read 输出更干净。
     * 只用于展示，不影响文件本体与解析。
     */
    public static String stripEntryMarkers(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String stripped = ENTRY_MARKER.matcher(markdown).replaceAll("");
        return stripped.replaceAll("\n{3,}", "\n\n").strip();
    }

    // ============ 文件名/目录 ============

    private Path notebooksDir() {
        Path base = workplaceDir;
        if (base == null || base.toString().isBlank()) {
            base = Path.of(System.getProperty("user.home"), "ai-analyzer-workplace");
        }
        return base.resolve("notebooks");
    }

    private Path resolveNotebookPath(String domain) {
        return notebooksDir().resolve(domain + ".md");
    }

    // ============ 写锁 ============

    /**
     * 在域名写锁 + 文件级锁保护下执行读-改-写体。
     * 先取 JVM 内 {@link ReentrantLock}（同进程公平互斥），再取 OS 级 {@link FileLock}（跨进程互斥）。
     */
    private <T> T withFileLock(String domain, CheckedSupplier<T> body) throws IOException {
        ReentrantLock lock = domainLocks.computeIfAbsent(domain, k -> new ReentrantLock());
        lock.lock();
        try {
            Path dir = notebooksDir();
            Files.createDirectories(dir);
            Path lockFile = dir.resolve(domain + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return body.get();
            }
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws IOException;
    }

    // ============ 原子写 ============

    private static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ============ 渲染 / 解析 ============

    /** 把新条目追加（或首次写入）成一份完整 Markdown 文本。首次写入先写标题。 */
    private static String renderNotebook(String current, String domain, NotebookEntry e) {
        StringBuilder sb = new StringBuilder();
        if (current == null || current.isBlank()) {
            sb.append("# Notebook · ").append(domain).append('\n');
        } else {
            sb.append(current);
            if (!current.endsWith("\n")) {
                sb.append('\n');
            }
        }
        sb.append("\n<!-- nb:entry v=").append(e.version())
                .append(" agent=").append(e.sourceAgent())
                .append(" type=").append(e.type())
                .append(" hash=").append(e.hash())
                .append(" time=").append(e.time())
                .append(" -->\n\n")
                .append("## [v").append(e.version()).append("] ")
                .append(e.type()).append(" · ").append(e.sourceAgent())
                .append(" · ").append(e.time().replace('T', ' ')).append("\n\n")
                .append(e.content()).append('\n');
        return sb.toString();
    }

    /** 从 Markdown 全文解析出所有条目（含正文），并保留版本、来源、类型、时间、摘要。 */
    private static List<NotebookEntry> parseEntries(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = ENTRY_MARKER.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<Object[]> metas = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
            metas.add(new Object[]{
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    matcher.group(5)
            });
        }
        List<NotebookEntry> entries = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : text.length();
            String body = stripLeadingHeading(text.substring(bodyStart, bodyEnd));
            Object[] meta = metas.get(i);
            // 顺序需与 NotebookEntry 记录 (version, sourceAgent, type, time, hash, content) 对齐
            entries.add(new NotebookEntry(
                    (int) meta[0], (String) meta[1], (String) meta[2], (String) meta[4], (String) meta[3], body));
        }
        return entries;
    }

    /** 剥掉条目正文前面的标题行（{@code ## [vN] ...}）与首尾空白，只留正文。 */
    private static String stripLeadingHeading(String section) {
        String s = section.stripLeading();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(nl + 1);
        }
        return s.strip();
    }

    // ============ 归一化 ============

    /** 域名归一化：去协议/端口/路径，小写，非法字符替换为下划线。 */
    public static String normalizeDomain(String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "");
        d = d.replaceAll("[:/].*$", "");
        d = d.replaceAll("[^a-z0-9._-]", "_");
        d = d.replaceAll("^_+|_+$", "");
        if (d.isEmpty()) {
            d = "default";
        }
        if (d.length() > 80) {
            d = d.substring(0, 80);
        }
        return d;
    }

    /** 类型归一化：单 token，非法字符替换为下划线。 */
    static String normalizeType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9_]+", "_");
        t = t.replaceAll("^_+|_+$", "");
        return t.isEmpty() ? "note" : t;
    }

    /** 来源 Agent 标识归一化：单 token。 */
    static String normalizeToken(String s) {
        if (s == null || s.isBlank()) {
            return "agent";
        }
        String t = s.trim().replaceAll("\\s+", "_");
        t = t.replaceAll("[^a-zA-Z0-9._-]", "_");
        return t.isEmpty() ? "agent" : t;
    }

    /** 内容归一化用于去重：压空白 + 小写。 */
    private static String normalizeContent(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String hashOf(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必然存在；极端兜底
            return Integer.toHexString(s.hashCode());
        }
    }

    // ============ 结果类型 ============

    /** 一次写入的结果。 */
    public record WriteResult(String domain, int version, boolean appended, boolean duplicate, String error) {
        public static WriteResult appended(String domain, int version) {
            return new WriteResult(domain, version, true, false, null);
        }

        public static WriteResult duplicate(String domain, int version) {
            return new WriteResult(domain, version, false, true, null);
        }

        public static WriteResult empty(String domain) {
            return new WriteResult(domain, -1, false, false, "内容为空");
        }
    }

    /** 某域名 Notebook 的摘要。 */
    public record DomainSummary(String domain, int version, int entryCount) {
    }
}