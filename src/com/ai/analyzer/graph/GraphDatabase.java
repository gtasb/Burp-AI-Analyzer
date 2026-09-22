package com.ai.analyzer.graph;

import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.Evidence;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱的 SQLite 存储层（TODO2 §9/§10）。
 *
 * <p>四张表：graph_roots / graph_nodes / graph_edges / graph_evidence。
 * 去重由 canonical key 的唯一约束保证：节点 (root_id,type,canonical_key)，
 * 边 (root_id,source,target,relation_type)。所有写入走同一连接 + synchronized，
 * 满足低写入频率的 HTTP 观测场景；single-writer 保证一致性。
 */
public class GraphDatabase implements AutoCloseable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Gson GSON = new Gson();

    private final Connection conn;
    private final String dbPath;

    public GraphDatabase(String dbPath) throws SQLException {
        this.dbPath = dbPath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC 驱动缺失", e);
        }
        java.io.File parent = new java.io.File(dbPath).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        createSchema();
    }

    public String dbPath() {
        return dbPath;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS graph_roots (
                      id TEXT PRIMARY KEY,
                      origin TEXT NOT NULL UNIQUE,
                      scheme TEXT, host TEXT, port INTEGER,
                      created_at TEXT, updated_at TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS graph_nodes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      root_id TEXT NOT NULL,
                      type TEXT NOT NULL,
                      name TEXT NOT NULL,
                      canonical_key TEXT NOT NULL,
                      properties TEXT,
                      status TEXT NOT NULL,
                      confidence REAL NOT NULL DEFAULT 1.0,
                      source_type TEXT,
                      source_id TEXT,
                      created_at TEXT, updated_at TEXT,
                      UNIQUE(root_id, type, canonical_key)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_nodes_root ON graph_nodes(root_id, type)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS graph_edges (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      root_id TEXT NOT NULL,
                      source_node_id INTEGER NOT NULL,
                      target_node_id INTEGER NOT NULL,
                      relation_type TEXT NOT NULL,
                      status TEXT NOT NULL,
                      confidence REAL NOT NULL DEFAULT 1.0,
                      source_type TEXT,
                      source_id TEXT,
                      properties TEXT,
                      created_at TEXT, updated_at TEXT,
                      UNIQUE(root_id, source_node_id, target_node_id, relation_type)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_edges_src ON graph_edges(source_node_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_edges_dst ON graph_edges(target_node_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS graph_evidence (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      root_id TEXT NOT NULL,
                      node_id INTEGER,
                      source_type TEXT,
                      source_id TEXT,
                      summary TEXT,
                      metadata TEXT,
                      created_at TEXT
                    )""");
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static String json(Object o) {
        return GSON.toJson(o == null ? new LinkedHashMap<>() : o);
    }

    private static Map<String, Object> fromJson(String s) {
        if (s == null || s.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return GSON.fromJson(s, new com.google.gson.reflect.TypeToken<LinkedHashMap<String, Object>>() {}.getType());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ============ ROOT ============

    /** 确保目标 Origin 存在，返回其 id（= origin）。 */
    public synchronized String ensureRoot(String origin, String scheme, String host, int port) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO graph_roots(id, origin, scheme, host, port, created_at, updated_at) VALUES(?,?,?,?,?,?,?) "
                        + "ON CONFLICT(origin) DO UPDATE SET updated_at=excluded.updated_at")) {
            String t = now();
            ps.setString(1, origin);
            ps.setString(2, origin);
            ps.setString(3, scheme);
            ps.setString(4, host);
            ps.setInt(5, port);
            ps.setString(6, t);
            ps.setString(7, t);
            ps.executeUpdate();
            return origin;
        } catch (SQLException e) {
            throw new IllegalStateException("ensureRoot failed: " + e.getMessage(), e);
        }
    }

    public synchronized List<String> listRoots() {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT origin FROM graph_roots ORDER BY created_at DESC LIMIT 200")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("listRoots failed: " + e.getMessage(), e);
        }
        return out;
    }

    // ============ NODE ============

    /**
     * 按 canonical key upsert 节点：已有则按事实等级推进状态（OBSERVED/DEVIVED/…），
     * 不产生重复节点。
     */
    public synchronized Node upsertNode(String rootId, NodeType type, String name, String canonicalKey,
                                        FactStatus status, double confidence,
                                        String sourceType, String sourceId, Map<String, Object> props) {
        String t = now();
        String sql = """
                INSERT INTO graph_nodes(root_id, type, name, canonical_key, properties, status, confidence, source_type, source_id, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(root_id, type, canonical_key) DO UPDATE SET
                  status=CASE
                    WHEN substr(status,1,1)='R' AND excluded.status IN ('OBSERVED','CONFIRMED') THEN excluded.status
                    WHEN (SELECT CASE
                            WHEN graph_nodes.type IN ('INTERFACE') AND excluded.status='OBSERVED' AND graph_nodes.status='INFERRED' THEN 1
                            WHEN graph_nodes.status='OBSERVED' THEN 0
                            WHEN excluded.status IN ('OBSERVED','CONFIRMED','DERIVED') AND graph_nodes.status IN ('INFERRED','DERIVED') THEN 1
                            WHEN excluded.status = graph_nodes.status THEN 1 ELSE 0 END
                         ) = 1 THEN excluded.status
                    ELSE graph_nodes.status END,
                  confidence=CASE WHEN excluded.confidence > graph_nodes.confidence THEN excluded.confidence ELSE graph_nodes.confidence END,
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rootId);
            ps.setString(2, type.name());
            ps.setString(3, name);
            ps.setString(4, canonicalKey);
            ps.setString(5, json(props));
            ps.setString(6, status.name());
            ps.setDouble(7, confidence);
            ps.setString(8, sourceType);
            ps.setString(9, sourceId);
            ps.setString(10, t);
            ps.setString(11, t);
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getLong(1) : getNodeByCanonical(rootId, type, canonicalKey).id();
            }
            return getNode(id);
        } catch (SQLException e) {
            throw new IllegalStateException("upsertNode failed: " + e.getMessage(), e);
        }
    }

    public synchronized Node getNode(long id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM graph_nodes WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readNode(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getNode failed: " + e.getMessage(), e);
        }
    }

    public synchronized Node getNodeByCanonical(String rootId, NodeType type, String canonicalKey) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM graph_nodes WHERE root_id=? AND type=? AND canonical_key=?")) {
            ps.setString(1, rootId);
            ps.setString(2, type.name());
            ps.setString(3, canonicalKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readNode(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getNodeByCanonical failed: " + e.getMessage(), e);
        }
    }

    public synchronized List<Node> findNodes(String rootId, NodeType type, String keyword, int limit) {
        List<Node> out = new ArrayList<>();
        String sql = "SELECT * FROM graph_nodes WHERE root_id=?" + (type != null ? " AND type=?" : "")
                + (keyword != null && !keyword.isBlank() ? " AND (name LIKE ? OR canonical_key LIKE ?)" : "")
                + " ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, rootId);
            if (type != null) {
                ps.setString(i++, type.name());
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readNode(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("findNodes failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** 显式设置节点状态（确认/推翻假设）。 */
    public synchronized void setNodeStatus(long nodeId, FactStatus status, double confidence) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE graph_nodes SET status=?, confidence=?, updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setDouble(2, confidence);
            ps.setString(3, now());
            ps.setLong(4, nodeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setNodeStatus failed: " + e.getMessage(), e);
        }
    }

    // ============ EDGE ============

    public synchronized Edge upsertEdge(String rootId, long sourceNodeId, long targetNodeId, EdgeType relation,
                                        FactStatus status, double confidence,
                                        String sourceType, String sourceId, Map<String, Object> props) {
        String t = now();
        String sql = """
                INSERT INTO graph_edges(root_id, source_node_id, target_node_id, relation_type, status, confidence, source_type, source_id, properties, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(root_id, source_node_id, target_node_id, relation_type) DO UPDATE SET
                  status=(
                    SELECT CASE WHEN e.status='OBSERVED' THEN 'OBSERVED'
                                WHEN excluded.status='CONFIRMED' THEN 'CONFIRMED'
                                WHEN excluded.status='OBSERVED' THEN 'OBSERVED'
                                WHEN e.status='REJECTED' THEN 'REJECTED'
                                ELSE (CASE WHEN e.status='DERIVED' OR excluded.status='DERIVED' THEN 'DERIVED' ELSE excluded.status END) END
                    FROM graph_edges e WHERE e.id=graph_edges.id),
                  confidence=CASE WHEN excluded.confidence > graph_edges.confidence THEN excluded.confidence ELSE graph_edges.confidence END,
                  updated_at=excluded.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rootId);
            ps.setLong(2, sourceNodeId);
            ps.setLong(3, targetNodeId);
            ps.setString(4, relation.name());
            ps.setString(5, status.name());
            ps.setDouble(6, confidence);
            ps.setString(7, sourceType);
            ps.setString(8, sourceId);
            ps.setString(9, json(props));
            ps.setString(10, t);
            ps.setString(11, t);
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getLong(1) : getEdge(rootId, sourceNodeId, targetNodeId, relation).id();
            }
            return getEdge(id);
        } catch (SQLException e) {
            throw new IllegalStateException("upsertEdge failed: " + e.getMessage(), e);
        }
    }

    public synchronized Edge getEdge(long id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM graph_edges WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEdge(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getEdge failed: " + e.getMessage(), e);
        }
    }

    private synchronized Edge getEdge(String rootId, long src, long dst, EdgeType type) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM graph_edges WHERE root_id=? AND source_node_id=? AND target_node_id=? AND relation_type=?")) {
            ps.setString(1, rootId);
            ps.setLong(2, src);
            ps.setLong(3, dst);
            ps.setString(4, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEdge(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getEdge failed: " + e.getMessage(), e);
        }
    }

    public synchronized void setEdgeStatus(long edgeId, FactStatus status, double confidence) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE graph_edges SET status=?, confidence=?, updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setDouble(2, confidence);
            ps.setString(3, now());
            ps.setLong(4, edgeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setEdgeStatus failed: " + e.getMessage(), e);
        }
    }

    // ============ NEIGHBORS / SUBGRAPH ============

    /** 邻接对：一条边 + 对端节点。 */
    public record Junction(Edge edge, Node node) {
    }

    /** 邻接（双向）：返回 (边, 对端节点) 列表，可按关系类型过滤。 */
    public synchronized List<Junction> neighbors(long nodeId, Set<EdgeType> relationTypes, int cap) {
        List<Junction> out = new ArrayList<>();
        String typeFilter = (relationTypes != null && !relationTypes.isEmpty())
                ? " AND e.relation_type IN (" + placeholders(relationTypes.size()) + ")" : "";
        String sql = """
                SELECT e.*, n.id AS nid, n.root_id AS nroot, n.type AS ntype, n.name AS nname,
                       n.canonical_key AS nkey, n.properties AS nprops, n.status AS nstatus,
                       n.confidence AS nconf, n.source_type AS nsrc, n.source_id AS nsrcid,
                       n.created_at AS nc, n.updated_at AS nu
                FROM graph_edges e JOIN graph_nodes n
                  ON n.id = CASE WHEN e.source_node_id=? THEN e.target_node_id WHEN e.target_node_id=? THEN e.source_node_id END
                WHERE n.id IS NOT NULL AND n.status != 'REJECTED'""" + typeFilter + " LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, nodeId);
            ps.setLong(i++, nodeId);
            if (relationTypes != null && !relationTypes.isEmpty()) {
                for (EdgeType t : relationTypes) {
                    ps.setString(i++, t.name());
                }
            }
            ps.setInt(i, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Edge e = readEdge(rs);
                    Node n = readNodeFrom(rs);
                    out.add(new Junction(e, n));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("neighbors failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    // ============ EVIDENCE ============

    public synchronized Evidence addEvidence(String rootId, Long nodeId, String sourceType,
                                             String sourceId, String summary, Map<String, Object> metadata) {
        String t = now();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO graph_evidence(root_id, node_id, source_type, source_id, summary, metadata, created_at) VALUES(?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rootId);
            if (nodeId != null) {
                ps.setLong(2, nodeId);
            } else {
                ps.setNull(2, java.sql.Types.INTEGER);
            }
            ps.setString(3, sourceType);
            ps.setString(4, sourceId);
            ps.setString(5, summary);
            ps.setString(6, json(metadata));
            ps.setString(7, t);
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getLong(1) : -1;
            }
            return new Evidence(id, rootId, nodeId, sourceType, sourceId, summary, metadata != null ? metadata : new LinkedHashMap<>(), t);
        } catch (SQLException e) {
            throw new IllegalStateException("addEvidence failed: " + e.getMessage(), e);
        }
    }

    public synchronized List<Evidence> evidenceFor(Long nodeId, int limit) {
        List<Evidence> out = new ArrayList<>();
        String sql = "SELECT * FROM graph_evidence WHERE " + (nodeId != null ? "node_id=?" : "1=1") + " ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (nodeId != null) {
                ps.setLong(i++, nodeId);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object nodeIdObj = rs.getObject("node_id");
                    Long rowNodeId = nodeIdObj == null ? null : ((Number) nodeIdObj).longValue();
                    out.add(new Evidence(rs.getLong("id"), rs.getString("root_id"),
                            rowNodeId, rs.getString("source_type"),
                            rs.getString("source_id"), rs.getString("summary"),
                            fromJson(rs.getString("metadata")), rs.getString("created_at")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("evidenceFor failed: " + e.getMessage(), e);
        }
        return out;
    }

    // ============ 读取辅助 ============

    private static Node readNode(ResultSet rs) throws SQLException {
        return new Node(rs.getLong("id"), rs.getString("root_id"), NodeType.valueOf(rs.getString("type")),
                rs.getString("name"), rs.getString("canonical_key"),
                FactStatus.valueOf(rs.getString("status")), rs.getDouble("confidence"),
                rs.getString("source_type"), rs.getString("source_id"),
                fromJson(rs.getString("properties")), rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static Node readNodeFrom(ResultSet rs) throws SQLException {
        return new Node(rs.getLong("nid"), rs.getString("nroot"), NodeType.valueOf(rs.getString("ntype")),
                rs.getString("nname"), rs.getString("nkey"),
                FactStatus.valueOf(rs.getString("nstatus")), rs.getDouble("nconf"),
                rs.getString("nsrc"), rs.getString("nsrcid"),
                fromJson(rs.getString("nprops")), rs.getString("nc"), rs.getString("nu"));
    }

    private static Edge readEdge(ResultSet rs) throws SQLException {
        return new Edge(rs.getLong("id"), rs.getString("root_id"),
                rs.getLong("source_node_id"), rs.getLong("target_node_id"),
                EdgeType.valueOf(rs.getString("relation_type")),
                FactStatus.valueOf(rs.getString("status")), rs.getDouble("confidence"),
                rs.getString("source_type"), rs.getString("source_id"),
                fromJson(rs.getString("properties")), rs.getString("created_at"), rs.getString("updated_at"));
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}