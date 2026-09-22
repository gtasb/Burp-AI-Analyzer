package com.ai.analyzer.graph;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            new FingerprintRule("x-powered-by: express", "Express", 0.92),
            new FingerprintRule("x-powered-by: asp.net", "ASP.NET", 0.92),
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

    private GraphStore() {
    }

    public static GraphStore getInstance() {
        return INSTANCE;
    }

    /** 设置工作区目录；图谱数据库落在 {@code <workspace>/knowledge-graph.db}。 */
    public void setWorkplaceDirectory(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            baseDir = null;
        } else {
            baseDir = Path.of(dir.trim()).toAbsolutePath().normalize();
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
        return db;
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
            String body = rr.request().bodyToString();
            observeInterface(method, url, body, "burp", null);
            if (rr.response() != null) {
                fingerprint(rr);
            }
        } catch (Exception ignored) {
            // 观测失败不影响流量处理
        }
    }

    /** 由 HTTP 消息创建/更新 Origin + Interface + Parameters，返回 interface 节点。 */
    public Node observeInterface(String method, String url, String body,
                                 String sourceType, String sourceId) {
        return upsertObservation(method, url, body, sourceType, sourceId);
    }

    private Node upsertObservation(String method, String url, String body,
                                   String sourceType, String sourceId) {
        ParsedRequest parsed = GraphNormalizers.parse(method, url, body);
        GraphDatabase d = db();
        String rootId = d.ensureRoot(parsed.origin(), parsed.scheme(), parsed.host(), parsed.port());

        // ORIGIN 节点（供 HAS_INTERFACE / USES_TECHNOLOGY 等边引用）
        Node originNode = d.upsertNode(rootId, NodeType.ORIGIN, parsed.origin(), parsed.origin(),
                FactStatus.OBSERVED, 1.0, sourceType, sourceId, Map.of());

        String ifaceKey = GraphNormalizers.interfaceKey(parsed.origin(), parsed.method(), parsed.path());
        Node iface = d.upsertNode(rootId, NodeType.INTERFACE,
                parsed.method() + " " + parsed.path(), ifaceKey,
                FactStatus.OBSERVED, 1.0, sourceType, sourceId,
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
        try {
            String body = rr.response().bodyToString();
            if (body != null) {
                probe.append(body, 0, Math.min(body.length(), 4096));
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
            if (!lower.contains(rule.marker())) {
                continue;
            }
            Node tech = upsertNamed(origin, NodeType.TECHNOLOGY, rule.name(), FactStatus.OBSERVED, rule.confidence(),
                    "burp-fingerprint", null);
            extractVersion(origin, tech, lower, rule.name());
            d.upsertEdge(origin, originNodeId, tech.id(), EdgeType.USES_TECHNOLOGY,
                    FactStatus.OBSERVED, rule.confidence(), "burp-fingerprint", null, Map.of());
        }
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
            return (svc.secure() ? "https" : "http") + "://" + svc.host() + ":" + svc.port();
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
        GraphDatabase d = db();
        // 允许直接给 url 自动落 root/interface
        if (rootOrUrl == null || rootOrUrl.isBlank()) {
            return "错误: root 或 url 不能为空";
        }
        Node node;
        if (rootOrUrl.contains("://")) {
            ParsedRequest parsed = GraphNormalizers.parse("GET", rootOrUrl, null);
            node = upsertObservation("GET", rootOrUrl, null, sourceType, sourceId);
            if ("INTERFACE".equalsIgnoreCase(type)) {
                return nodeIdRepr(node);
            }
        } else {
            // type 必须是受支持节点类型
            NodeType nt = safeNodeType(type);
            node = upsertNamed(rootOrUrl, nt, name, FactStatus.OBSERVED, 1.0, sourceType, sourceId);
        }
        return nodeIdRepr(node);
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

    // ============ 查询 / 子图 / 渲染 ============

    public Node getNode(long nodeId) {
        return db().getNode(nodeId);
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
        return render(seed, junctions);
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