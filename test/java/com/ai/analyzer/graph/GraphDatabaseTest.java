package com.ai.analyzer.graph;

import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.Evidence;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GraphDatabase - SQLite 图谱存储层")
class GraphDatabaseTest {

    private GraphDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("graph-db-test");
        db = new GraphDatabase(dir.resolve("test.db").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    @DisplayName("canonical key 去重：同一接口不产生重复节点")
    void upsert_node_dedups_by_canonical_key() {
        db.ensureRoot("https://target.com:443", "https", "target.com", 443);
        Node first = db.upsertNode("https://target.com:443", NodeType.INTERFACE, "GET /api/order/detail",
                "https://target.com:443|GET|/api/order/detail", FactStatus.OBSERVED, 1.0, "burp", null, Map.of());
        Node second = db.upsertNode("https://target.com:443", NodeType.INTERFACE, "GET /api/order/detail",
                "https://target.com:443|GET|/api/order/detail", FactStatus.OBSERVED, 1.0, "burp", null, Map.of());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(db.findNodes("https://target.com:443", NodeType.INTERFACE, null, 100)).hasSize(1);
    }

    @Test
    @DisplayName("事实等级推进：INFERRED 被 OBSERVED 覆盖")
    void status_promotes_inferred_to_observed() {
        String root = "https://t.com:443";
        db.ensureRoot(root, "https", "t.com", 443);
        String key = "https://t.com:443|GET|/api/user";
        Node inferred = db.upsertNode(root, NodeType.INTERFACE, "GET /api/user", key, FactStatus.INFERRED, 0.8, "agent", null, Map.of());
        Node observed = db.upsertNode(root, NodeType.INTERFACE, "GET /api/user", key, FactStatus.OBSERVED, 1.0, "burp", null, Map.of());

        assertThat(observed.id()).isEqualTo(inferred.id());
        assertThat(observed.status()).isEqualTo(FactStatus.OBSERVED);
    }

    @Test
    @DisplayName("事实等级不降级：OBSERVED 不被 INFERRED 覆盖")
    void status_never_downgrades() {
        String root = "https://t.com:443";
        db.ensureRoot(root, "https", "t.com", 443);
        String key = "https://t.com:443|GET|/api/user";
        db.upsertNode(root, NodeType.INTERFACE, "GET /api/user", key, FactStatus.OBSERVED, 1.0, "burp", null, Map.of());
        Node again = db.upsertNode(root, NodeType.INTERFACE, "GET /api/user", key, FactStatus.INFERRED, 0.5, "agent", null, Map.of());

        assertThat(again.status()).isEqualTo(FactStatus.OBSERVED);
    }

    @Test
    @DisplayName("边唯一约束 + 双向邻接 + 关系过滤")
    void edges_unique_and_neighbors_work() {
        String root = "https://t.com:443";
        db.ensureRoot(root, "https", "t.com", 443);
        Node a = db.upsertNode(root, NodeType.INTERFACE, "GET /api/a", root + "|GET|/api/a", FactStatus.OBSERVED, 1, "b", null, Map.of());
        Node b = db.upsertNode(root, NodeType.INTERFACE, "GET /api/b", root + "|GET|/api/b", FactStatus.OBSERVED, 1, "b", null, Map.of());

        db.upsertEdge(root, a.id(), b.id(), EdgeType.RELATED_TO, FactStatus.INFERRED, 0.7, "agent", null, Map.of());
        db.upsertEdge(root, a.id(), b.id(), EdgeType.RELATED_TO, FactStatus.INFERRED, 0.9, "agent", null, Map.of());

        assertThat(db.neighbors(a.id(), null, 100)).hasSize(1);
        assertThat(db.neighbors(b.id(), null, 100)).hasSize(1);
        assertThat(db.neighbors(a.id(), Set.of(EdgeType.HAS_PARAMETER), 100)).isEmpty();
        assertThat(db.neighbors(a.id(), null, 100).get(0).edge().confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("证据写入与按节点查询")
    void evidence_lifecycle() {
        String root = "https://t.com:443";
        db.ensureRoot(root, "https", "t.com", 443);
        Node n = db.upsertNode(root, NodeType.INTERFACE, "GET /api/a", root + "|GET|/api/a", FactStatus.OBSERVED, 1, "b", null, Map.of());

        db.addEvidence(root, n.id(), "burp", "req-1", "观察到 GET /api/a", Map.of("url", "https://t.com/a"));
        db.addEvidence(root, n.id(), "agent", null, "推测依据", Map.of());

        List<Evidence> evs = db.evidenceFor(n.id(), 10);
        assertThat(evs).hasSize(2);
        assertThat(evs.get(0).sourceType()).isEqualTo("agent");
        assertThat(evs.get(1).summary()).contains("GET /api/a");
    }
}