package com.ai.analyzer.graph;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.analyzer.graph.GraphNormalizers.Param;
import com.ai.analyzer.graph.GraphNormalizers.ParsedRequest;
import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.Evidence;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;

/**
 * 知识图谱门面（单例）：Burp 数据接入 + Agent 推测/确认 + 子图检索与紧凑渲染。
 *
 * <p>Agent 不直接操作数据库，只通过 {@link GraphTool} 暴露的 graph_* 工具访问本类。
 * 所有写入都带来源与事实等级（OBSERVED/DERIVED/INFERRED/CONFIRMED/REJECTED）。
 */
public final class GraphStore {

    private static final GraphStore INSTANCE = new GraphStore();
    /** 指纹规则（观察事实，来源 header/body 特征）。 */
    private static final List<FingerprintRule> FINGERPRINT_RULES = List.of(
            new FingerprintRule("server: nginx", "Nginx", 0.9),
            new FingerprintRule("server: apache", "Apache HTTP Server", 0.88),
            new FingerprintRule("server: openresty", "OpenResty", 0.9),
            new FingerprintRule("server: iis", "Microsoft IIS", 0.9),
            new FingerprintRule("server: tomcat", "Apache Tomcat", 0.88),
            new FingerprintRule("server: cloudflare", "Cloudflare", 0.85),
            new FingerprintRule("x-powered-by: express", "Express", 0.92),
            new FingerprintRule("x-powered-by: asp.net", "ASP.NET", 0.92),
            new FingerprintRule("x-powered-by: php", "PHP", 0.9),
            new FingerprintRule("x-powered-by: next.js", "Next.js", 0.9),
            new FingerprintRule("x-powered-by: nuxt", "Nuxt", 0.9),
            new FingerprintRule("laravel_session", "Laravel", 0.9),
            new FingerprintRule("phpsessid", "PHP", 0.8),
            new FingerprintRule("jsessionid", "Java", 0.7),
            new FingerprintRule("wp-content", "WordPress", 0.8),
            new FingerprintRule("thinkphp", "ThinkPHP", 0.9),
            new FingerprintRule("__next_data__", "Next.js", 0.9),
            new FingerprintRule("data-reactroot", "React", 0.8),
            new FingerprintRule("ng-version", "Angular", 0.8),
            new FingerprintRule("layui", "LayUI", 0.7),
            new FingerprintRule("x-aspnet-version", "ASP.NET", 0.85),
            new FingerprintRule("set-cookie: jsessionid", "Java Servlet (Tomcat)", 0.85),
            new FingerprintRule("set-cookie: laravel_session", "Laravel (PHP)", 0.92),
            new FingerprintRule("set-cookie: phpsessid", "PHP", 0.9),
            new FingerprintRule("set-cookie: rememberme", "Apache Shiro", 0.85),
            new FingerprintRule("jeecgboot", "JeecgBoot", 0.85),
            new FingerprintRule("fastadmin", "FastAdmin", 0.85),
            new FingerprintRule("layui", "LayUI", 0.8),
            new FingerprintRule("thinkphp", "ThinkPHP", 0.85),
            new FingerprintRule("django", "Django", 0.8),
            new FingerprintRule("laravel", "Laravel (PHP)", 0.85),
            new FingerprintRule("spring", "Spring Framework", 0.75),
            new FingerprintRule("shiro", "Apache Shiro", 0.8),
            new FingerprintRule("vue", "Vue.js", 0.5)
    );

    private volatile Path baseDir;
    private volatile GraphDatabase db;
    /** 已完成旧 Notebook 导入的工作区（避免重复扫描） */
    private volatile Path importedLegacyBase;

    /** 笔记广播（只提醒，不携带正文）：UI/正在运行的 Agent 订阅后自行拉取增量。 */
    private final CopyOnWriteArrayList<Consumer<Node>> noteListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService noteBroadcast = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-note-broadcast");
        t.setDaemon(true);
        return t;
    });

    private GraphStore() {
    }

    public static GraphStore getInstance() {
        return INSTANCE;
    }

    /** 设置工作区目录；图谱数据库落在 {@code <workspace>/knowledge-graph.db}。 */
    public synchronized void setWorkplaceDirectory(String dir) {
        Path next = (dir == null || dir.trim().isEmpty())
                ? null
                : Path.of(dir.trim()).toAbsolutePath().normalize();
        if (java.util.Objects.equals(next, baseDir)) {
            return;
        }
        baseDir = next;
        // 工作区变了必须换库：否则后续 observe/graph_* 仍读写旧 knowledge-graph.db
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {
            }
            db = null;
        }
    }

    /** 关闭底层数据库（插件卸载时调用，释放 SQLite 连接与 WAL 文件句柄）。 */
    public synchronized void close() {
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {
            }
            db = null;
        }
    }

    private synchronized GraphDatabase db() {
        if (db != null) {
            return db;
        }
        Path base = baseDir;
        if (base == null) {
            base = Path.of(System.getProperty("user.home"), "ai-analyzer-workplace");
        }
        try {
            db = new GraphDatabase(base.resolve("knowledge-graph.db").toString());
        } catch (Exception e) {
            throw new IllegalStateException("知识图谱数据库初始化失败: " + e.getMessage(), e);
        }
        maybeImportLegacyNotebooks(base);
        return db;
    }

    /** 首次打开某工作区的库时，把旧的 notebooks/*.md 迁成 NOTE 节点并删除原文件。 */
    private void maybeImportLegacyNotebooks(Path base) {
        if (base.equals(importedLegacyBase)) {
            return;
        }
        // 先置位，导入过程中 listRoots()/upsertNote 再次进入 db() 时不会递归
        importedLegacyBase = base;
        try {
            LegacyNotebookImporter.importOnce(base, this);
        } catch (Exception e) {
            com.ai.analyzer.util.DebugContext.log("GraphStore", "legacy_notebook_import_failed",
                    Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** 导入器遇到“暂无 root 可挂”时调用：让下次 db() 打开时重试导入。 */
    void markLegacyImportRetry() {
        importedLegacyBase = null;
    }

    /** 关闭底层数据库（测试用）。 */
    synchronized void resetForTest() {
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {
            }
            db = null;
        }
    }

    // ============ Burp 数据接入（被动观察） ============

    /**
     * 观察一次 HTTP 流量：Origin → Interface → Parameter 自动建图 + 技术指纹 + 证据。
     * 静默失败，绝不阻断 Burp 流量路径。
     */
    public void observe(HttpRequestResponse rr) {
        if (rr == null || rr.request() == null) {
            return;
        }
        try {
            String method = rr.request().method() != null ? rr.request().method() : "GET";
            String url = rr.request().url();
            // 大请求体（上传）不解析参数：bodyToString() 会把整包解码成字符串，
            // 在流量线程/图谱线程上都是不必要的大对象
            String body = "";
            try {
                var rawBody = rr.request().body();
                if (rawBody != null && rawBody.length() > 0 && rawBody.length() <= 256 * 1024) {
                    body = rawBody.toString();
                } else if (rawBody == null) {
                    // 兜底：部分 Montoya 实现/测试替身只提供 bodyToString()
                    String text = rr.request().bodyToString();
                    if (text != null && text.length() <= 256 * 1024) {
                        body = text;
                    }
                }
            } catch (Exception ignored) {
            }
            Node iface = observeInterface(method, url, body, "burp", null);
            if (rr.response() != null) {
                observeResponseMeta(method, url, rr, iface);
                fingerprint(rr);
            }
        } catch (Exception ignored) {
            // 观测失败不影响流量处理
        }
    }

    /**
     * 响应侧补充观测：页面标题/状态码/内容类型写到 Origin 属性；JS/CSS/source map
     * 作为 RESOURCE 节点入图（不送 LLM 扫描），并从 JS 中提取接口路径建立 CALLS 边。
     */
    private void observeResponseMeta(String method, String url, HttpRequestResponse rr, Node iface) {
        ParsedRequest parsed = GraphNormalizers.parse(method, url, null);
        GraphDatabase d = db();
        String rootId = parsed.origin();
        String contentType = "";
        for (var h : rr.response().headers()) {
            if ("content-type".equalsIgnoreCase(h.name())) {
                contentType = h.value() == null ? "" : h.value();
                break;
            }
        }
        // 只取前缀做标题/资源/JS 特征分析：不要先 bodyToString() 再截断，
        // 大响应（视频/安装包）会整包解码成字符串
        int maxProbe = 256 * 1024;
        int responseBytes = 0;
        String body = "";
        try {
            var rawResponseBody = rr.response().body();
            if (rawResponseBody != null) {
                responseBytes = rawResponseBody.length();
                if (responseBytes > 0) {
                    body = responseBytes > maxProbe
                            ? rawResponseBody.subArray(0, maxProbe).toString()
                            : rawResponseBody.toString();
                }
            } else {
                // 兜底：测试替身/旧实现只提供 bodyToString()
                String text = rr.response().bodyToString();
                if (text != null) {
                    responseBytes = text.length();
                    body = text.length() > maxProbe ? text.substring(0, maxProbe) : text;
                }
            }
        } catch (Exception ignored) {
        }
        String title = "";
        Matcher tm = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
        if (tm.find()) {
            title = tm.group(1).replaceAll("\\s+", " ").trim();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!contentType.isBlank()) meta.put("content_type", contentType);
        meta.put("status_code", rr.response().statusCode());
        if (!title.isBlank()) meta.put("page_title", GraphNormalizers.truncate(title, 120));
        d.mergeNodeProperties(rootId, NodeType.ORIGIN, rootId, meta);

        String lowerPath = parsed.path().toLowerCase(Locale.ROOT);
        String ct = contentType.toLowerCase(Locale.ROOT);
        boolean isResource = lowerPath.endsWith(".js") || lowerPath.endsWith(".css")
                || lowerPath.endsWith(".map") || ct.contains("javascript")
                || ct.contains("text/css") || ct.contains("sourcemap");
        if (!isResource) return;

        String kind = lowerPath.endsWith(".js") ? "js"
                : lowerPath.endsWith(".css") ? "css"
                : lowerPath.endsWith(".map") ? "sourcemap" : "other";
        String key = rootId + "|resource:" + parsed.path();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", kind);
        if (!contentType.isBlank()) props.put("content_type", contentType);
        props.put("status_code", rr.response().statusCode());
        props.put("bytes", responseBytes);
        // sourceMappingURL 注释通常在大文件末尾；body 只有前 256KB，末尾也要看一眼
        boolean hasSourceMap = body.contains("sourceMappingURL=");
        if (!hasSourceMap) {
            try {
                var rawTail = rr.response().body();
                if (rawTail != null && rawTail.length() > 0) {
                    int tailStart = Math.max(0, rawTail.length() - 4096);
                    hasSourceMap = rawTail.subArray(tailStart, rawTail.length()).toString()
                            .contains("sourceMappingURL=");
                }
            } catch (Exception ignored) {
            }
        }
        if (hasSourceMap) props.put("has_sourcemap", true);
        Node resource = d.upsertNode(rootId, NodeType.RESOURCE, parsed.path(), key,
                FactStatus.OBSERVED, 1.0, "burp", null, props);
        Node originNode = d.getNodeByCanonical(rootId, NodeType.ORIGIN, rootId);
        if (originNode != null) {
            d.upsertEdge(rootId, originNode.id(), resource.id(), EdgeType.LOADS,
                    FactStatus.OBSERVED, 1.0, "burp", null, Map.of());
        }
        if ("js".equals(kind)) {
            inferCallsFromJs(rootId, resource, body);
        }
    }

    /** 从 JS 文本中提取明显的 API 路径，仅当对应接口已存在时建立 CALLS 边（保守推断）。 */
    private void inferCallsFromJs(String rootId, Node resource, String js) {
        Matcher m = Pattern.compile(
                "[\"'`](\\/(?:api|rest|v\\d+|graphql|admin|user|order|upload|auth|login|search)"
                        + "[A-Za-z0-9_\\-./{}:]*(?:\\?[^\"'`]*)?)[\"'`]").matcher(js);
        GraphDatabase d = db();
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        while (m.find() && count++ < 60) {
            String path = m.group(1);
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            if (!seen.add(path)) continue;
            Node iface = d.getNodeByCanonical(rootId, NodeType.INTERFACE,
                    GraphNormalizers.interfaceKey(rootId, "GET", path));
            if (iface == null) {
                iface = d.getNodeByCanonical(rootId, NodeType.INTERFACE,
                        GraphNormalizers.interfaceKey(rootId, "POST", path));
            }
            if (iface != null) {
                d.upsertEdge(rootId, resource.id(), iface.id(), EdgeType.CALLS,
                        FactStatus.DERIVED, 0.6, "burp-js", null, Map.of("path", path));
            }
        }
    }

    /** 由 HTTP 消息创建/更新 Origin + Interface + Parameters，返回 interface 节点。 */
    public Node observeInterface(String method, String url, String body,
                                 String sourceType, String sourceId) {
        return upsertObservation(method, url, body, sourceType, sourceId);
    }

    private Node upsertObservation(String method, String url, String body,
                                   String sourceType, String sourceId) {
        return upsertObservation(method, url, body, sourceType, sourceId, FactStatus.OBSERVED);
    }

    /** status 只作用于本次新建的 INTERFACE 节点；已存在节点仍按事实等级推进规则处理。 */
    private Node upsertObservation(String method, String url, String body,
                                   String sourceType, String sourceId, FactStatus status) {
        ParsedRequest parsed = GraphNormalizers.parse(method, url, body);
        GraphDatabase d = db();
        String rootId = d.ensureRoot(parsed.origin(), parsed.scheme(), parsed.host(), parsed.port());

        // ORIGIN 节点（供 HAS_INTERFACE / USES_TECHNOLOGY 等边引用）
        Node originNode = d.upsertNode(rootId, NodeType.ORIGIN, parsed.origin(), parsed.origin(),
                FactStatus.OBSERVED, 1.0, sourceType, sourceId, Map.of());

        String ifaceKey = GraphNormalizers.interfaceKey(parsed.origin(), parsed.method(), parsed.path());
        Node iface = d.upsertNode(rootId, NodeType.INTERFACE,
                parsed.method() + " " + parsed.path(), ifaceKey,
                status, 1.0, sourceType, sourceId,
                Map.of("module", GraphNormalizers.guessModule(parsed.path())));
        d.upsertEdge(rootId, originNode.id(), iface.id(), EdgeType.HAS_INTERFACE,
                FactStatus.OBSERVED, 1.0, sourceType, sourceId, Map.of());

        // 自动模块边（推测，低置信度）
        String moduleName = GraphNormalizers.guessModule(parsed.path());
        if (!moduleName.isEmpty()) {
            Node module = upsertNamed(rootId, NodeType.MODULE, moduleName, FactStatus.DERIVED, 0.6, sourceType, sourceId);
            d.upsertEdge(rootId, iface.id(), module.id(), EdgeType.BELONGS_TO,
                    FactStatus.DERIVED, 0.6, sourceType, sourceId, Map.of());
        }

        for (Param p : parsed.params()) {
            String paramKey = iface.id() + "|" + p.location() + "|" + p.name();
            Node param = d.upsertNode(rootId, NodeType.PARAMETER, p.name(), paramKey,
                    FactStatus.OBSERVED, 1.0, sourceType, sourceId,
                    Map.of("location", p.location()));
            d.upsertEdge(rootId, iface.id(), param.id(), EdgeType.HAS_PARAMETER,
                    FactStatus.OBSERVED, 1.0, sourceType, sourceId, Map.of());
        }
        d.addEvidence(rootId, iface.id(), sourceType == null ? "burp" : sourceType,
                sourceId, GraphNormalizers.truncate(parsed.method() + " " + parsed.path(), 120), Map.of());
        return iface;
    }

    private Node upsertNamed(String rootId, NodeType type, String name, FactStatus status,
                             double confidence, String sourceType, String sourceId) {
        GraphDatabase d = db();
        String key = switch (type) {
            case MODULE -> GraphNormalizers.moduleKey(rootId, name);
            case BUSINESS_ENTITY -> GraphNormalizers.entityKey(rootId, name);
            case FUNCTION -> GraphNormalizers.functionKey(rootId, name);
            case TECHNOLOGY -> GraphNormalizers.techKey(rootId, name);
            default -> rootId + "|" + type.name().toLowerCase(Locale.ROOT) + ":" + GraphNormalizers.truncate(name.toLowerCase(Locale.ROOT), 80);
        };
        return d.upsertNode(rootId, type, name, key, status, confidence, sourceType, sourceId, Map.of());
    }

    // ============ 技术指纹 ============

    private void fingerprint(HttpRequestResponse rr) {
        StringBuilder probe = new StringBuilder();
        try {
            for (var h : rr.response().headers()) {
                probe.append(h.name()).append(": ").append(h.value()).append('\n');
            }
        } catch (Exception ignored) {
        }
        // 只取 body 前 4KB：与 observeResponseMeta 的 256KB 纪律一致。
        // bodyToString() 会把整个响应（含被特意入图的大压缩包）物化成 String，
        // 在流量线程/图谱线程上造成 GB 级分配，甚至 OOM（Error 不被 catch(Exception) 捕获）。
        try {
            var rawBody = rr.response().body();
            if (rawBody != null && rawBody.length() > 0) {
                int take = Math.min(rawBody.length(), 4096);
                probe.append(rawBody.subArray(0, take).toString());
            } else {
                // 兜底：测试替身/旧实现只提供 bodyToString()
                String body = rr.response().bodyToString();
                if (body != null) {
                    probe.append(body, 0, Math.min(body.length(), 4096));
                }
            }
        } catch (Exception ignored) {
        }
        String lower = probe.toString().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return;
        }
        GraphDatabase d = db();
        String origin = rootOf(rr);
        if (origin == null) {
            return;
        }
        Node originNode = d.getNodeByCanonical(origin, NodeType.ORIGIN, origin);
        if (originNode == null) {
            originNode = d.upsertNode(origin, NodeType.ORIGIN, origin, origin,
                    FactStatus.OBSERVED, 1.0, "burp-fingerprint", null, Map.of());
        }
        long originNodeId = originNode.id();
        for (FingerprintRule rule : FINGERPRINT_RULES) {
            int idx = lower.indexOf(rule.marker());
            if (idx < 0) {
                continue;
            }
            Node tech = upsertNamed(origin, NodeType.TECHNOLOGY, rule.name(), FactStatus.OBSERVED, rule.confidence(),
                    "burp-fingerprint", null);
            d.mergeNodeProperties(origin, NodeType.TECHNOLOGY, tech.canonicalKey(),
                    Map.of("category", categoryOf(rule.name())));
            extractVersion(origin, tech, lower, rule.name());
            d.upsertEdge(origin, originNodeId, tech.id(), EdgeType.USES_TECHNOLOGY,
                    FactStatus.OBSERVED, rule.confidence(), "burp-fingerprint", null, Map.of());
            int from = Math.max(0, idx - 40);
            int to = Math.min(probe.length(), idx + rule.marker().length() + 80);
            String snippet = probe.substring(from, to).replace('\n', ' ').replace('\r', ' ').trim();
            addEvidenceNode(origin, tech, rule.marker() + " @ " + snippet, "burp-fingerprint",
                    EdgeType.INDICATES, rule.marker());
        }
    }

    /** 技术分类：frontend / backend / server / other（用于图谱图例与推断）。 */
    static String categoryOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("vue") || n.contains("react") || n.contains("angular")
                || n.contains("bootstrap") || n.contains("jquery") || n.contains("layui")
                || n.contains("next") || n.contains("nuxt")) {
            return "frontend";
        }
        if (n.contains("nginx") || n.contains("apache") || n.contains("iis")
                || n.contains("openresty") || n.contains("tomcat") || n.contains("jetty")
                || n.contains("cloudflare")) {
            return "server";
        }
        if (n.contains("express") || n.contains("asp.net") || n.contains("php")
                || n.contains("laravel") || n.contains("thinkphp") || n.contains("shiro")
                || n.contains("spring") || n.contains("django") || n.contains("flask")
                || n.contains("fastapi") || n.contains("java") || n.contains("wordpress")
                || n.contains("jeecg") || n.contains("fastadmin")) {
            return "backend";
        }
        return "other";
    }

    /** 为某个节点登记一条证据并建立 INDICATES/SUPPORTS 边。 */
    private Node addEvidenceNode(String rootId, Node target, String summary, String sourceType,
                                 EdgeType relation) {
        return addEvidenceNode(rootId, target, summary, sourceType, relation, summary);
    }

    /** keyHint 用于稳定去重（指纹规则用 marker；Agent 证据用摘要）。 */
    private Node addEvidenceNode(String rootId, Node target, String summary, String sourceType,
                                 EdgeType relation, String keyHint) {
        if (target == null || summary == null || summary.isBlank()) return null;
        GraphDatabase d = db();
        String key = rootId + "|evidence:"
                + Integer.toHexString((target.id() + ":" + String.valueOf(keyHint)).hashCode());
        Node evidence = d.upsertNode(rootId, NodeType.EVIDENCE,
                "证据: " + GraphNormalizers.truncate(summary, 60), key,
                FactStatus.OBSERVED, 1.0, sourceType, null,
                Map.of("snippet", GraphNormalizers.truncate(summary, 500)));
        d.upsertEdge(rootId, evidence.id(), target.id(), relation,
                FactStatus.OBSERVED, 1.0, sourceType, null, Map.of());
        return evidence;
    }

    private Node extractVersion(String rootId, Node tech, String lower, String techName) {
        Matcher m = Pattern.compile("server:\\s*(?:openresty|nginx|apache|iis)\\s*/?\\s*([\\d.]+)").matcher(lower);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1);
        Node version = db().upsertNode(rootId, NodeType.VERSION, techName + " " + v,
                rootId + "|version:" + techName.toLowerCase(Locale.ROOT) + ":" + v,
                FactStatus.OBSERVED, 0.9, "burp-fingerprint", null, Map.of());
        db().upsertEdge(rootId, tech.id(), version.id(), EdgeType.HAS_VERSION,
                FactStatus.OBSERVED, 0.9, "burp-fingerprint", null, Map.of());
        return version;
    }

    private String rootOf(HttpRequestResponse rr) {
        HttpService svc = rr.request() != null ? rr.request().httpService() : null;
        if (svc != null && svc.host() != null && !svc.host().isEmpty()) {
            // host 统一小写：混合大小写 Host 头会把同一站点拆成两个 root
            return (svc.secure() ? "https" : "http") + "://"
                    + svc.host().toLowerCase(Locale.ROOT) + ":" + svc.port();
        }
        try {
            String u = rr.request().url();
            return GraphNormalizers.parse("GET", u, null).origin();
        } catch (Exception e) {
            return null;
        }
    }

    // ============ Agent 推测 / 确认 ============

    /** Agent 观察事实写入（type/name/root 形式，url 自动落 Origin+接口）。 */
    public String upsertAgentObservation(String type, String name, String rootOrUrl, String sourceType, String sourceId) {
        NodeType nt = safeNodeType(type);
        if (nt == NodeType.NOTE) {
            return "错误: NOTE 请使用 graph_add_note 创建";
        }
        if (rootOrUrl == null || rootOrUrl.isBlank()) {
            return "错误: root 或 url 不能为空";
        }
        if (nt == NodeType.INTERFACE) {
            return upsertAgentInterface(name, rootOrUrl, sourceType, sourceId);
        }
        // 非接口节点：root 必须是 Origin；若给了完整 URL 只取 origin 部分
        String rootId = rootOrUrl.contains("://")
                ? GraphNormalizers.parse("GET", rootOrUrl, null).origin()
                : rootOrUrl.trim();
        Node node = upsertNamed(rootId, nt, name, FactStatus.OBSERVED, 1.0, sourceType, sourceId);
        return nodeIdRepr(node);
    }

    /**
     * INTERFACE 的三类合法输入：
     * 1) name = 完整 URL（推荐）；
     * 2) rootOrUrl = 完整 URL（兼容旧用法）；
     * 3) root = Origin，name = "METHOD /path" 或 "/path"。
     *
     * <p>修复此前“无论传什么 URL 都生成 GET /”的问题：旧实现总是解析 rootOrUrl，
     * 把 origin 当成 URL，路径恒为 /。
     */
    private String upsertAgentInterface(String name, String rootOrUrl, String sourceType, String sourceId) {
        return nodeIdRepr(upsertInterfaceNode(name, rootOrUrl, sourceType, sourceId, FactStatus.OBSERVED));
    }

    private Node upsertInterfaceNode(String name, String rootOrUrl, String sourceType,
                                     String sourceId, FactStatus status) {
        String n = name == null ? "" : name.trim();
        String method = "GET";
        String url;
        if (n.contains("://")) {
            url = n;
        } else if (rootOrUrl.contains("://") && hasNonRootPath(rootOrUrl)) {
            url = rootOrUrl.trim();
        } else {
            String root = rootOrUrl.trim();
            String path;
            String[] parts = n.split("\\s+", 2);
            if (parts.length == 2 && isHttpMethod(parts[0])) {
                method = parts[0].toUpperCase(Locale.ROOT);
                path = parts[1];
            } else {
                path = n.isEmpty() ? "/" : n;
            }
            if (!path.startsWith("/")) path = "/" + path;
            url = root.endsWith("/") ? root.substring(0, root.length() - 1) + path : root + path;
        }
        return upsertObservation(method, url, null, sourceType, sourceId, status);
    }

    private static boolean hasNonRootPath(String url) {
        try {
            ParsedRequest parsed = GraphNormalizers.parse("GET", url, null);
            return !"/".equals(parsed.path());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHttpMethod(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    /** Agent 推测：为 subject 添加 relation → target（target 不存在则自动创建为 INFERRED）。 */
    public String addHypothesis(long subjectNodeId, String relation, String targetType,
                                String targetName, double confidence, String reason) {
        GraphDatabase d = db();
        Node subject = d.getNode(subjectNodeId);
        if (subject == null) {
            return "错误: subject 节点不存在 (" + subjectNodeId + ")";
        }
        NodeType tt = safeNodeType(targetType);
        if (tt == NodeType.NOTE) {
            return "错误: NOTE 不能作为推测目标，请使用 graph_add_note";
        }
        EdgeType et = safeEdgeType(relation);
        Node target = upsertNamed(subject.rootId(), tt, targetName, FactStatus.INFERRED, confidence, "agent", null);
        Map<String, Object> props = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            props.put("reason", reason);
        }
        Edge edge = d.upsertEdge(subject.rootId(), subject.id(), target.id(), et,
                FactStatus.INFERRED, confidence, "agent", null, props);
        d.addEvidence(subject.rootId(), subject.id(), "agent", null,
                GraphNormalizers.truncate("推测: " + subject.name() + " " + et + " " + target.name(), 150),
                Map.of("reason", reason == null ? "" : reason));
        return "推测已记录: edge=" + edge.id() + " " + subject.name() + " →" + et + "→ " + target.name()
                + " (INFERRED " + confidence + ")";
    }

    public String confirmHypothesis(long edgeId, String evidenceSummary, String sourceType) {
        GraphDatabase d = db();
        Edge edge = d.getEdge(edgeId);
        if (edge == null) {
            return "错误: 边不存在 (" + edgeId + ")";
        }
        d.setEdgeStatus(edgeId, FactStatus.CONFIRMED, Math.max(edge.confidence(), 0.9));
        d.addEvidence(edge.rootId(), edge.sourceNodeId(), sourceType == null ? "agent" : sourceType, null,
                GraphNormalizers.truncate("确认: " + (evidenceSummary == null ? "" : evidenceSummary), 150), Map.of());
        return "已确认 edge=" + edgeId + " → CONFIRMED";
    }

    public String rejectHypothesis(long edgeId) {
        Edge edge = db().getEdge(edgeId);
        if (edge == null) {
            return "错误: 边不存在 (" + edgeId + ")";
        }
        db().setEdgeStatus(edgeId, FactStatus.REJECTED, edge.confidence());
        return "已推翻 edge=" + edgeId + " → REJECTED";
    }

    // ============ 指纹 / 特征 / 后端推断（Agent 登记） ============

    private String resolveRootId(long interfaceId, String root) {
        GraphDatabase d = db();
        if (interfaceId > 0) {
            Node n = d.getNode(interfaceId);
            if (n != null) return n.rootId();
        }
        if (root != null && !root.isBlank()) {
            String r = root.trim();
            if (r.contains("://")) {
                return GraphNormalizers.parse("GET", r, null).origin();
            }
            return r;
        }
        return null;
    }

    /**
     * Agent 登记一条技术指纹（ARTEX 的 insert_assets 方式）：
     * 技术节点 + 可选版本 + 证据节点（INDICATES）+ Origin/接口 USES_TECHNOLOGY 边。
     */
    public String upsertFingerprint(long interfaceId, String root, String technology,
                                    String category, String version, String evidence,
                                    double confidence) {
        if (technology == null || technology.isBlank()) return "错误: technology 不能为空";
        String rootId = resolveRootId(interfaceId, root);
        if (rootId == null) return "错误: 需要 interface_id 或 root";
        GraphDatabase d = db();
        Node iface = interfaceId > 0 ? d.getNode(interfaceId) : null;
        double conf = Math.max(0.05, Math.min(1.0, confidence));
        boolean observed = evidence != null && !evidence.isBlank();
        FactStatus status = observed ? FactStatus.OBSERVED : FactStatus.DERIVED;
        Node tech = upsertNamed(rootId, NodeType.TECHNOLOGY, technology, status, conf, "agent", null);
        String cat = (category == null || category.isBlank()) ? categoryOf(technology)
                : category.trim().toLowerCase(Locale.ROOT);
        d.mergeNodeProperties(rootId, NodeType.TECHNOLOGY, tech.canonicalKey(),
                Map.of("category", cat));

        Node origin = d.getNodeByCanonical(rootId, NodeType.ORIGIN, rootId);
        if (origin != null) {
            d.upsertEdge(rootId, origin.id(), tech.id(), EdgeType.USES_TECHNOLOGY,
                    status, conf, "agent", null, Map.of("category", cat));
        }
        if (iface != null) {
            d.upsertEdge(rootId, iface.id(), tech.id(), EdgeType.USES_TECHNOLOGY,
                    status, conf, "agent", null, Map.of("scope", "interface", "category", cat));
        }

        StringBuilder sb = new StringBuilder("已登记指纹: ").append(technology)
                .append(" [node=").append(tech.id()).append(", category=").append(cat).append(']');
        if (version != null && !version.isBlank()) {
            Node ver = upsertNamed(rootId, NodeType.VERSION, technology + " " + version.trim(),
                    FactStatus.OBSERVED, conf, "agent", null);
            d.upsertEdge(rootId, tech.id(), ver.id(), EdgeType.HAS_VERSION,
                    FactStatus.OBSERVED, conf, "agent", null, Map.of());
            sb.append(" version=").append(version.trim());
        }
        if (observed) {
            addEvidenceNode(rootId, tech, evidence.trim(), "agent", EdgeType.INDICATES);
            sb.append("（附证据）");
        }
        return sb.toString();
    }

    /**
     * Agent 登记一个能力特征（actuator/swagger/graphql/upload/jwt...），
     * 可同时挂到接口并映射到漏洞类别（SUGGESTS）。
     */
    public String addFeature(long interfaceId, String root, String feature, String vulnClass,
                             String evidence, double confidence) {
        if (feature == null || feature.isBlank()) return "错误: feature 不能为空";
        String rootId = resolveRootId(interfaceId, root);
        if (rootId == null) return "错误: 需要 interface_id 或 root";
        GraphDatabase d = db();
        Node iface = interfaceId > 0 ? d.getNode(interfaceId) : null;
        double conf = Math.max(0.05, Math.min(1.0, confidence));
        boolean observed = evidence != null && !evidence.isBlank();
        Node feat = upsertNamed(rootId, NodeType.FEATURE, feature, observed ? FactStatus.OBSERVED : FactStatus.INFERRED,
                conf, "agent", null);
        if (iface != null) {
            d.upsertEdge(rootId, iface.id(), feat.id(), EdgeType.RELATED_TO,
                    FactStatus.INFERRED, conf, "agent", null, Map.of("kind", "feature"));
        }
        if (vulnClass != null && !vulnClass.isBlank()) {
            Node vc = upsertNamed(rootId, NodeType.VULN_CLASS, vulnClass, FactStatus.DERIVED, conf, "agent", null);
            d.upsertEdge(rootId, feat.id(), vc.id(), EdgeType.SUGGESTS,
                    FactStatus.DERIVED, conf, "agent", null, Map.of());
        }
        if (observed) {
            addEvidenceNode(rootId, feat, evidence.trim(), "agent", EdgeType.INDICATES);
        }
        return "已登记特征: " + feature + " [node=" + feat.id() + "]"
                + (vulnClass != null && !vulnClass.isBlank() ? " → " + vulnClass : "");
    }

    /**
     * Agent 登记一条后端实现推测（如 Spring Boot / Laravel / Node+Express），
     * 用 SUPPORTS 证据边保留推理依据。
     */
    public String addBackendHypothesis(long interfaceId, String root, String hypothesis,
                                       String evidence, double confidence) {
        if (hypothesis == null || hypothesis.isBlank()) return "错误: hypothesis 不能为空";
        String rootId = resolveRootId(interfaceId, root);
        if (rootId == null) return "错误: 需要 interface_id 或 root";
        GraphDatabase d = db();
        Node iface = interfaceId > 0 ? d.getNode(interfaceId) : null;
        double conf = Math.max(0.05, Math.min(1.0, confidence));
        Node hyp = upsertNamed(rootId, NodeType.BACKEND_HYPOTHESIS, hypothesis,
                FactStatus.INFERRED, conf, "agent", null);
        if (iface != null) {
            d.upsertEdge(rootId, hyp.id(), iface.id(), EdgeType.DERIVED_FROM,
                    FactStatus.INFERRED, conf, "agent", null, Map.of());
        }
        if (evidence != null && !evidence.isBlank()) {
            addEvidenceNode(rootId, hyp, evidence.trim(), "agent", EdgeType.SUPPORTS);
        }
        return "已登记后端推测: " + hypothesis + " [node=" + hyp.id() + ", confidence=" + conf + "]";
    }

    /**
     * 记录一条“分析事实锚点”（沿用 ARTEX 的 fact-anchor 语义）：
     * 一次完成的扫描/验证在接口上落一个 ANALYSIS_FACT 节点，并用 COVERS 边锚定。
     */
    public String recordAnalysisFact(String method, String url, String summary, String sourceId) {
        try {
            String m = method == null || method.isBlank() ? "GET" : method;
            ParsedRequest parsed = GraphNormalizers.parse(m, url, null);
            GraphDatabase d = db();
            String rootId = d.ensureRoot(parsed.origin(), parsed.scheme(), parsed.host(), parsed.port());
            String ifaceKey = GraphNormalizers.interfaceKey(parsed.origin(), parsed.method(), parsed.path());
            Node iface = d.getNodeByCanonical(rootId, NodeType.INTERFACE, ifaceKey);
            if (iface == null) {
                return "跳过: 接口不存在";
            }
            String sid = sourceId == null || sourceId.isBlank()
                    ? Integer.toHexString((method + url).hashCode()) : sourceId;
            String factKey = rootId + "|fact:" + sid;
            Map<String, Object> props = new LinkedHashMap<>();
            if (summary != null && !summary.isBlank()) {
                props.put("summary", GraphNormalizers.truncate(summary, 500));
            }
            props.put("method", parsed.method());
            props.put("path", parsed.path());
            Node fact = d.upsertNode(rootId, NodeType.ANALYSIS_FACT, "分析事实 " + sid, factKey,
                    FactStatus.OBSERVED, 1.0, "burp-scan", sid, props);
            d.upsertEdge(rootId, fact.id(), iface.id(), EdgeType.COVERS,
                    FactStatus.OBSERVED, 1.0, "burp-scan", sid, Map.of());
            return "已记录分析事实: " + iface.name();
        } catch (Exception e) {
            return "记录分析事实失败: " + e.getMessage();
        }
    }

    // ============ 笔记 / 评估 / 人工节点（原 Notebook 合并入图） ============

    private static final java.time.format.DateTimeFormatter NOTE_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static String nowTs() {
        return java.time.LocalDateTime.now().format(NOTE_TS);
    }

    /** 订阅笔记写入提醒（只提醒，正文请用 {@link #listNotes} 拉取）。 */
    public void subscribeNotes(Consumer<Node> listener) {
        if (listener != null) noteListeners.addIfAbsent(listener);
    }

    public void unsubscribeNotes(Consumer<Node> listener) {
        if (listener != null) noteListeners.remove(listener);
    }

    private void publishNote(Node note) {
        for (Consumer<Node> listener : noteListeners) {
            noteBroadcast.execute(() -> {
                try {
                    listener.accept(note);
                } catch (Exception ignored) {
                }
            });
        }
    }

    public record NoteResult(long nodeId, int version, boolean duplicate) {
    }

    /**
     * 追加一条笔记/牵引。nodeId 为空时挂在该 Origin 上（站点级笔记）。
     * 同目标下内容等价的笔记会被去重。
     */
    public NoteResult addNote(String root, Long nodeId, String content, String kind,
                              String author, String sourceType) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        String src = sourceType == null || sourceType.isBlank() ? "agent" : sourceType;
        String who = author == null || author.isBlank() ? src : author;
        String rootId = resolveRootId(nodeId == null ? 0 : nodeId, root);
        if (rootId == null) {
            throw new IllegalArgumentException("需要 node_id 或 root");
        }
        GraphDatabase.NoteInsert ins = db().upsertNote(rootId, nodeId, text, kind, who, src, who, null);
        if (!ins.duplicate()) {
            publishNote(ins.node());
        }
        return new NoteResult(ins.node().id(), ins.version(), ins.duplicate());
    }

    /** 历史 Notebook 导入：保留原版本号与作者，不广播。 */
    NoteResult importNote(String rootId, String content, String kind, String author, int version) {
        GraphDatabase.NoteInsert ins = db().upsertNote(rootId, null, content, kind, author,
                "notebook-import", author, version);
        return new NoteResult(ins.node().id(), ins.version(), ins.duplicate());
    }

    /** 某 Origin 的笔记，按版本升序；sinceVersion 只返回更新的条目。 */
    public List<Node> listNotes(String root, int sinceVersion) {
        String rootId = resolveRootId(0, root);
        if (rootId == null) {
            return List.of();
        }
        List<Node> all = new ArrayList<>(db().findNodes(rootId, NodeType.NOTE, null, 0));
        all.removeIf(n -> GraphDatabase.noteVersion(n) <= sinceVersion);
        all.sort(Comparator.comparingInt(GraphDatabase::noteVersion));
        return all;
    }

    /** 人类编辑笔记正文：递增版本并广播，让增量读取方（graph_get_notes since_version）能重新看到。 */
    public void updateNote(long noteId, String content, String author) {
        Node n = db().getNode(noteId);
        if (n == null || n.type() != NodeType.NOTE) {
            throw new IllegalArgumentException("笔记不存在: " + noteId);
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("content", text);
        props.put("edited_at", nowTs());
        if (author != null && !author.isBlank()) {
            props.put("edited_by", author);
        }
        db().bumpNoteVersionAndUpdate(noteId, GraphDatabase.noteTitle(text), props);
        Node updated = db().getNode(noteId);
        if (updated != null) {
            publishNote(updated);
        }
    }

    public void deleteNote(long noteId) {
        Node n = db().getNode(noteId);
        if (n == null || n.type() != NodeType.NOTE) {
            throw new IllegalArgumentException("笔记不存在: " + noteId);
        }
        db().deleteNode(noteId);
    }

    /**
     * 记录 Agent 对某个节点「是否值得继续」的判断。
     * priority: high / normal / low / none；low/none 表示已确认的死路。
     */
    public String assessNode(long nodeId, String priority, String assessment, String nextStep,
                             String author) {
        Node n = db().getNode(nodeId);
        if (n == null) {
            return "错误: 节点不存在 (" + nodeId + ")";
        }
        String p = normalizePriority(priority);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("assess_priority", p);
        if (assessment != null && !assessment.isBlank()) {
            props.put("assess_summary", GraphNormalizers.truncate(assessment.trim(), 500));
        }
        if (nextStep != null && !nextStep.isBlank()) {
            props.put("assess_next_step", GraphNormalizers.truncate(nextStep.trim(), 500));
        }
        props.put("assess_by", author == null || author.isBlank() ? "agent" : author);
        props.put("assess_at", nowTs());
        db().mergeNodePropertiesById(nodeId, props);
        return "已记录评估: " + n.name() + " → " + p;
    }

    static String normalizePriority(String priority) {
        String p = priority == null ? "" : priority.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "high", "high-priority", "高" -> "high";
            case "low", "低" -> "low";
            case "none", "no", "无" -> "none";
            default -> "normal";
        };
    }

    /**
     * 人类在图谱中手工新增节点。默认按推测处理（INFERRED）；
     * verified=true 表示人工已验证，直接记为 CONFIRMED（计入已测）。
     */
    public Node addHumanNode(String root, NodeType type, String name, long parentNodeId,
                             EdgeType relation, boolean verified, String note) {
        if (type == null || type == NodeType.NOTE || type == NodeType.ANALYSIS_FACT
                || type == NodeType.ORIGIN) {
            throw new IllegalArgumentException("该类型不能手工新增: " + type);
        }
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) {
            throw new IllegalArgumentException("节点名称不能为空");
        }
        String rootId = resolveRootId(parentNodeId, root);
        if (rootId == null) {
            throw new IllegalArgumentException("需要 root 或父节点");
        }
        FactStatus status = verified ? FactStatus.CONFIRMED : FactStatus.INFERRED;
        Node node = type == NodeType.INTERFACE
                ? upsertInterfaceNode(nm, rootId, "human", null, status)
                : upsertNamed(rootId, type, nm, status, verified ? 1.0 : 0.5, "human", null);
        if (verified) {
            db().setNodeStatus(node.id(), FactStatus.CONFIRMED, 1.0);
        }
        if (parentNodeId > 0) {
            Node parent = db().getNode(parentNodeId);
            if (parent != null && parent.id() != node.id()) {
                EdgeType rel = relation == null ? EdgeType.RELATED_TO : relation;
                // 边方向必须与 agent 创建边一致：BELONGS_TO/INDICATES/SUPPORTS/CALLS
                // 等是 child→parent，新节点是 source；否则图谱视图的包含树会反向嵌套
                // （整个站点挂到新 FEATURE 节点下面）。
                boolean childFirst = switch (rel) {
                    case BELONGS_TO, INDICATES, SUPPORTS, DERIVED_FROM, CALLS,
                            SUGGESTS, RELATED_TO -> false;
                    default -> true;
                };
                if (childFirst) {
                    db().upsertEdge(rootId, parent.id(), node.id(), rel,
                            status, verified ? 1.0 : 0.5, "human", null, Map.of("origin", "human"));
                } else {
                    db().upsertEdge(rootId, node.id(), parent.id(), rel,
                            status, verified ? 1.0 : 0.5, "human", null, Map.of("origin", "human"));
                }
            }
        }
        if (note != null && !note.isBlank()) {
            addNote(null, node.id(), note, "note", "human", "human");
        }
        return db().getNode(node.id());
    }

    // ============ 查询 / 子图 / 渲染 ============

    public Node getNode(long nodeId) {
        return db().getNode(nodeId);
    }

    // ============ 结构化读取（UI 可视化用） ============

    /** 已建立的 root（origin）列表，最新在前。 */
    public List<String> listRoots() {
        return db().listRoots();
    }

    /** 某 root 的节点快照（上限 limit）。 */
    public List<Node> listNodes(String rootId, int limit) {
        return db().listNodes(rootId, limit);
    }

    /** 某 root 的边快照（上限 limit）。 */
    public List<Edge> listEdges(String rootId, int limit) {
        return db().listEdges(rootId, limit);
    }

    public String getRootText(String origin) {
        String orig = origin == null ? "" : origin.trim();
        return "origin: " + orig + "\n" + db().listRoots().stream().filter(o -> o.equals(orig)).count() + " 个 root";
    }

    /** 目标站点图谱概览（接口/技术/模块清单），供 Agent 快速了解一个站点已积累的知识。 */
    public String overview(String rootId) {
        GraphDatabase d = db();
        StringBuilder sb = new StringBuilder();
        sb.append("ROOT: ").append(rootId).append('\n');
        List<Node> ifaces = d.findNodes(rootId, NodeType.INTERFACE, null, 200);
        sb.append("接口 (").append(ifaces.size()).append(")：\n");
        for (Node n : ifaces) {
            sb.append("  - ").append(n.name()).append(" [node=").append(n.id()).append("] ").append(n.status()).append('\n');
        }
        List<Node> techs = d.findNodes(rootId, NodeType.TECHNOLOGY, null, 50);
        if (!techs.isEmpty()) {
            sb.append("技术指纹：\n");
            for (Node t : techs) {
                StringBuilder line = new StringBuilder("  - ").append(t.name()).append(" (").append(t.status()).append(')');
                for (GraphDatabase.Junction j : d.neighbors(t.id(), Set.of(EdgeType.HAS_VERSION), 5)) {
                    line.append(" → ").append(j.node().name());
                }
                sb.append(line).append('\n');
            }
        }
        List<Node> modules = d.findNodes(rootId, NodeType.MODULE, null, 50);
        if (!modules.isEmpty()) {
            sb.append("模块：");
            modules.forEach(m -> sb.append(m.name()).append(' '));
            sb.append('\n');
        }
        List<Node> features = d.findNodes(rootId, NodeType.FEATURE, null, 50);
        if (!features.isEmpty()) {
            sb.append("能力特征：\n");
            for (Node f : features) {
                sb.append("  - ").append(f.name()).append(" [node=").append(f.id()).append("] ")
                        .append(f.status()).append('\n');
            }
        }
        List<Node> hypotheses = d.findNodes(rootId, NodeType.BACKEND_HYPOTHESIS, null, 30);
        if (!hypotheses.isEmpty()) {
            sb.append("后端实现推测：\n");
            for (Node h : hypotheses) {
                sb.append("  - ").append(h.name()).append(" (").append(h.status()).append(' ')
                        .append(renderConf(h)).append(") [node=").append(h.id()).append("]\n");
            }
        }
        List<Node> vulnClasses = d.findNodes(rootId, NodeType.VULN_CLASS, null, 30);
        if (!vulnClasses.isEmpty()) {
            sb.append("漏洞特征类别：");
            vulnClasses.forEach(v -> sb.append(v.name()).append(' '));
            sb.append('\n');
        }
        int resources = d.findNodes(rootId, NodeType.RESOURCE, null, 500).size();
        if (resources > 0) {
            sb.append("前端资源：").append(resources).append(" 个（JS/CSS/source map，见 graph_get_subgraph）\n");
        }
        List<Node> notes = listNotes(rootId, 0);
        if (!notes.isEmpty()) {
            sb.append("站点笔记/人工牵引（").append(notes.size()).append(" 条，最新在前）：\n");
            int from = Math.max(0, notes.size() - 10);
            for (int i = notes.size() - 1; i >= from; i--) {
                Node n = notes.get(i);
                sb.append("  - [v").append(GraphDatabase.noteVersion(n)).append(" · ")
                        .append(n.prop("kind")).append(" · ").append(n.prop("author")).append("] ")
                        .append(GraphNormalizers.truncate(
                                String.valueOf(n.prop("content")).replace('\n', ' '), 200))
                        .append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 查找接口（关键词 / 模块 / 业务实体过滤）。 */
    public String findInterfaces(String rootId, String keyword, String module, String entity) {
        GraphDatabase d = db();
        List<Node> nodes;
        if (module != null && !module.isBlank()) {
            nodes = d.findNodes(rootId, NodeType.INTERFACE, null, 0);
            nodes.removeIf(n -> !GraphNormalizers.guessModule(
                    n.name().contains(" ") ? n.name().substring(n.name().indexOf(' ') + 1) : n.name())
                    .equals(GraphNormalizers.normalizeForMatch(module)));
        } else if (keyword != null && !keyword.isBlank()) {
            nodes = d.findNodes(rootId, NodeType.INTERFACE, keyword, 50);
        } else if (entity != null && !entity.isBlank()) {
            nodes = d.findNodes(rootId, NodeType.INTERFACE, null, 0);
            nodes.removeIf(n -> !hasEntityRelation(d, n, entity));
        } else {
            nodes = d.findNodes(rootId, NodeType.INTERFACE, null, 50);
        }
        if (nodes.isEmpty()) {
            return "未找到匹配接口";
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            sb.append(n.name()).append(" [node=").append(n.id()).append("] ")
                    .append(n.status()).append("\n");
        }
        return sb.toString().trim();
    }

    private boolean hasEntityRelation(GraphDatabase d, Node iface, String entity) {
        for (var j : d.neighbors(iface.id(), Set.of(EdgeType.OPERATES_ON, EdgeType.IMPLEMENTS_FUNCTION), 40)) {
            if (j.node().type() == NodeType.BUSINESS_ENTITY && j.node().name().equalsIgnoreCase(entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 子图检索（BFS，带关系类型过滤与总量上限），并按安全相关性渲染成紧凑上下文。
     * 只把与当前节点相关的局部知识交给 LLM，绝不整图注入。
     */
    public String subgraph(long seedNodeId, int depth, Set<EdgeType> relationTypes,
                           boolean includeInferred, int cap) {
        GraphDatabase d = db();
        Node seed = d.getNode(seedNodeId);
        if (seed == null) {
            return "错误: 节点不存在 (" + seedNodeId + ")";
        }
        int dth = Math.max(0, Math.min(depth, 3));
        int maxNodes = Math.max(1, Math.min(cap <= 0 ? 60 : cap, 200));
        Set<Long> visited = new LinkedHashSet<>();
        List<GraphDatabase.Junction> junctions = new ArrayList<>();
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        queue.add(new long[]{seedNodeId, 0});
        visited.add(seedNodeId);
        while (!queue.isEmpty() && visited.size() < maxNodes) {
            long[] cur = queue.poll();
            long nodeId = cur[0];
            int level = (int) cur[1];
            if (level >= dth) {
                continue;
            }
            for (GraphDatabase.Junction j : d.neighbors(nodeId, relationTypes, 60)) {
                if (!includeInferred && (j.edge().status() == FactStatus.INFERRED || j.node().status() == FactStatus.INFERRED)) {
                    continue;
                }
                junctions.add(j);
                if (visited.add(j.node().id()) && visited.size() >= maxNodes) {
                    break;
                }
                queue.add(new long[]{j.node().id(), level + 1});
            }
        }
        StringBuilder out = new StringBuilder(render(seed, junctions));
        appendNodeContext(out, seed, d);
        return out.toString();
    }

    /** 在子图文本后追加当前节点的评估与相关笔记（人工牵引必须让 Agent 看到）。 */
    private static void appendNodeContext(StringBuilder sb, Node seed, GraphDatabase d) {
        Object priority = seed.prop("assess_priority");
        if (priority != null && !String.valueOf(priority).isBlank()) {
            sb.append("\n评估: ").append(priority);
            Object summary = seed.prop("assess_summary");
            if (summary != null && !String.valueOf(summary).isBlank()) {
                sb.append(" · ").append(summary);
            }
            Object next = seed.prop("assess_next_step");
            if (next != null && !String.valueOf(next).isBlank()) {
                sb.append("\n下一步: ").append(next);
            }
            sb.append('\n');
        }
        List<Node> notes = new ArrayList<>(d.findNodes(seed.rootId(), NodeType.NOTE, null, 0));
        notes.removeIf(n -> {
            Object t = n.prop("target");
            long target = t instanceof Number num ? (long) num.doubleValue() : 0;
            return target != 0 && target != seed.id();
        });
        if (!notes.isEmpty()) {
            notes.sort(Comparator.comparingInt(GraphDatabase::noteVersion));
            int from = Math.max(0, notes.size() - 5);
            sb.append("\n相关笔记:");
            for (int i = from; i < notes.size(); i++) {
                Node n = notes.get(i);
                sb.append("\n  - [").append(n.prop("kind")).append(" · ").append(n.prop("author")).append("] ")
                        .append(GraphNormalizers.truncate(String.valueOf(n.prop("content")), 300));
            }
            sb.append('\n');
        }
    }

    /** 把种子节点 + 邻接集合渲染成紧凑、相关性排序的文本上下文（§14 样例格式）。 */
    private static String render(Node seed, List<GraphDatabase.Junction> junctions) {
        StringBuilder sb = new StringBuilder();
        sb.append("ROOT: ").append(seed.rootId()).append('\n');
        sb.append("CURRENT: ").append(seed.name()).append("  (")
                .append(seed.status()).append(seed.status() != FactStatus.OBSERVED
                        ? String.format(Locale.ROOT, ", conf=%.2f", seed.confidence()) : "")
                .append(", node=").append(seed.id()).append(")\n");

        List<String> params = new ArrayList<>();
        List<String> functions = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> logics = new ArrayList<>();
        List<String> related = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        List<String> services = new ArrayList<>();
        List<String> techs = new ArrayList<>();
        List<String> vulns = new ArrayList<>();
        for (GraphDatabase.Junction j : junctions) {
            Edge e = j.edge();
            Node n = j.node();
            String tag = String.format(Locale.ROOT, "%s%s%s", n.name(), renderConf(n), " [node=" + n.id() + "]");
            switch (n.type()) {
                case PARAMETER -> params.add(tag + " (" + n.prop("location") + ")");
                case FUNCTION -> functions.add(tag);
                case BUSINESS_ENTITY -> entities.add(tag);
                case BUSINESS_LOGIC -> logics.add(tag);
                case MODULE -> modules.add(n.name());
                case SERVICE -> services.add(n.name());
                case TECHNOLOGY -> {
                    String t = n.name() + listChildren(n, j, "HAS_VERSION");
                    techs.add(t);
                }
                case VERSION -> {
                    // 由 TECHNOLOGY 分支带出
                }
                case VULNERABILITY -> vulns.add(n.name());
                case INTERFACE -> related.add(String.format(Locale.ROOT, "%s (%s, %s%s)",
                        n.name(), e.relationType(), n.status(), renderConf(n)));
                default -> {
                }
            }
        }
        appendSection(sb, "参数", params);
        appendSection(sb, "功能", functions);
        appendSection(sb, "业务实体", entities);
        appendSection(sb, "业务逻辑(推测)", logics);
        appendSection(sb, "相关接口", related);
        appendSection(sb, "模块", modules);
        appendSection(sb, "服务", services);
        appendSection(sb, "技术", techs);
        appendSection(sb, "可能受影响的N-day(仅相关提示,非结论)", vulns);
        Evidence ev = null;
        return sb.toString().trim();
    }

    private static String listChildren(Node n, GraphDatabase.Junction j, String edgeName) {
        // 简化：版本信息通过 TECH+VERSION 已带出（v1 展示层面省略子遍历）
        return "";
    }

    private static String renderConf(Node n) {
        if (n.status() == FactStatus.OBSERVED) {
            return "";
        }
        return " (" + n.status() + " conf=" + String.format(Locale.ROOT, "%.2f", n.confidence()) + ")";
    }

    private static void appendSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(":\n");
        for (String item : items) {
            sb.append("  - ").append(item).append('\n');
        }
    }

    private static String nodeIdRepr(Node node) {
        return node.type() + " [" + node.name() + ", node=" + node.id() + "] " + node.status();
    }

    private static NodeType safeNodeType(String type) {
        try {
            return type == null ? NodeType.FUNCTION : NodeType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return NodeType.FUNCTION;
        }
    }

    private static EdgeType safeEdgeType(String relation) {
        try {
            return relation == null ? EdgeType.RELATED_TO : EdgeType.valueOf(relation.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return EdgeType.RELATED_TO;
        }
    }

    private record FingerprintRule(String marker, String name, double confidence) {
    }
}