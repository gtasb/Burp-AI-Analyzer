package com.ai.analyzer.graph;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GraphStore - 知识图谱门面（Burp 接入 / 推测 / 子图）")
class GraphStoreTest {

    private GraphStore store;
    private Path dir;

    @BeforeEach
    void setUp() throws Exception {
        store = GraphStore.getInstance();
        store.resetForTest();
        dir = Files.createTempDirectory("graph-store-test");
        store.setWorkplaceDirectory(dir.toString());
    }

    @AfterEach
    void tearDown() {
        store.resetForTest();
    }

    private HttpRequestResponse rr(String method, String url, String body,
                                   boolean secure, int port) {
        HttpRequestResponse rr = mock(HttpRequestResponse.class);
        HttpRequest req = mock(HttpRequest.class);
        HttpService svc = mock(HttpService.class);
        when(rr.request()).thenReturn(req);
        when(req.method()).thenReturn(method);
        when(req.url()).thenReturn(url);
        when(req.bodyToString()).thenReturn(body);
        when(req.httpService()).thenReturn(svc);
        when(svc.host()).thenReturn("target.com");
        when(svc.port()).thenReturn(port);
        when(svc.secure()).thenReturn(secure);
        return rr;
    }

    @Test
    @DisplayName("Burp 流量自动建图：Origin→接口→参数")
    void observe_creates_origin_interface_params() {
        store.observe(rr("GET", "https://target.com/api/order/detail?id=123&page=1", "",
                true, 443));

        String ifaces = store.findInterfaces("https://target.com:443", "order", null, null);
        assertThat(ifaces).contains("GET /api/order/detail");
        assertThat(ifaces).contains("OBSERVED");
    }

    @Test
    @DisplayName("同一 URL 重复观测不产生重复接口")
    void observe_dedup() {
        for (int i = 0; i < 3; i++) {
            store.observe(rr("GET", "https://target.com/api/user/list?id=1", "", true, 443));
        }
        String ifaces = store.findInterfaces("https://target.com:443", null, null, null);
        assertThat(ifaces.lines().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("子图渲染：接口周边出现推测的功能与相关接口")
    void subgraph_contains_hypotheses() {
        String url = "https://target.com/api/order/detail?id=1";
        store.observe(rr("GET", url, "", true, 443));
        String ifaces = store.findInterfaces("https://target.com:443", "order", null, null);
        long nodeId = extractNodeId(ifaces);

        store.addHypothesis(nodeId, "IMPLEMENTS_FUNCTION", "FUNCTION", "QueryOrderDetail", 0.87, "路径与响应特征");
        store.addHypothesis(nodeId, "OPERATES_ON", "BUSINESS_ENTITY", "Order", 0.8, "参数 id 语义");

        String sub = store.subgraph(nodeId, 1, null, true, 60);

        assertThat(sub).contains("CURRENT: GET /api/order/detail");
        assertThat(sub).contains("QueryOrderDetail");
        assertThat(sub).contains("Order");
        assertThat(sub).contains("INFERRED");
    }

    @Test
    @DisplayName("确认/推翻推测边")
    void confirm_and_reject_hypothesis() {
        String url = "https://target.com/api/order/detail?id=1";
        store.observe(rr("GET", url, "", true, 443));
        long nodeId = extractNodeId(store.findInterfaces("https://target.com:443", "order", null, null));

        String hypo = store.addHypothesis(nodeId, "RELATED_TO", "INTERFACE", "GET /api/order/update", 0.8, "CRUD 兄弟");
        long edgeId = Long.parseLong(hypo.replaceAll(".*edge=\\s*(\\d+).*", "$1").trim().split(" ")[0]);

        String confirmed = store.confirmHypothesis(edgeId, "实际请求返回 200 且更新成功", "agent");
        assertThat(confirmed).contains("CONFIRMED");

        String rejected = store.rejectHypothesis(edgeId);
        assertThat(rejected).contains("REJECTED");
    }

    @Test
    @DisplayName("指纹：响应 Server: nginx 产生技术节点")
    void fingerprint_detects_technology() {
        HttpRequestResponse mockRr = rr("GET", "https://target.com/", "", true, 443);
        HttpResponse resp = mock(HttpResponse.class);
        HttpHeader server = mock(HttpHeader.class);
        when(server.name()).thenReturn("Server");
        when(server.value()).thenReturn("nginx/1.24.0");
        HttpHeader ct = mock(HttpHeader.class);
        when(ct.name()).thenReturn("Content-Type");
        when(ct.value()).thenReturn("text/html");
        when(mockRr.response()).thenReturn(resp);
        when(resp.headers()).thenReturn(List.of(server, ct));
        when(resp.bodyToString()).thenReturn("<html>welcome</html>");

        store.observe(mockRr);

        String overview = store.overview("https://target.com:443");
        assertThat(overview).contains("Nginx");
    }

    @Test
    @DisplayName("fact 锚定：完成的扫描写入 ANALYSIS_FACT + COVERS 边")
    void record_analysis_fact_anchors_interface() {
        String url = "https://target.com/api/order/detail?id=1";
        store.observe(rr("GET", url, "", true, 443));
        store.recordAnalysisFact("GET", url, "scan #7", "scan-7-0");

        List<GraphTypes.Node> nodes = store.listNodes("https://target.com:443", 500);
        GraphTypes.Node fact = nodes.stream()
                .filter(n -> n.type() == GraphTypes.NodeType.ANALYSIS_FACT).findFirst().orElse(null);
        GraphTypes.Node iface = nodes.stream()
                .filter(n -> n.type() == GraphTypes.NodeType.INTERFACE).findFirst().orElse(null);
        assertThat(fact).isNotNull();
        assertThat(iface).isNotNull();
        assertThat(store.listEdges("https://target.com:443", 500))
                .anyMatch(e -> e.relationType() == GraphTypes.EdgeType.COVERS
                        && e.sourceNodeId() == fact.id() && e.targetNodeId() == iface.id());
    }

    @Test
    @DisplayName("Agent 指纹登记：技术/版本/证据/分类/边齐全")
    void agent_fingerprint_registration() {
        String url = "https://target.com/api/order/detail?id=1";
        store.observe(rr("GET", url, "", true, 443));
        long ifaceId = extractNodeId(store.findInterfaces("https://target.com:443", "order", null, null));

        String result = store.upsertFingerprint(ifaceId, null, "Spring Boot", "backend", "2.7.1",
                "X-Application-Context: application", 0.9);
        assertThat(result).contains("Spring Boot");

        List<GraphTypes.Node> nodes = store.listNodes("https://target.com:443", 500);
        GraphTypes.Node tech = nodes.stream()
                .filter(n -> n.type() == GraphTypes.NodeType.TECHNOLOGY && "Spring Boot".equals(n.name()))
                .findFirst().orElse(null);
        assertThat(tech).isNotNull();
        assertThat(tech.prop("category")).isEqualTo("backend");
        assertThat(nodes).anyMatch(n -> n.type() == GraphTypes.NodeType.VERSION
                && n.name().contains("2.7.1"));
        assertThat(nodes).anyMatch(n -> n.type() == GraphTypes.NodeType.EVIDENCE);

        List<GraphTypes.Edge> edges = store.listEdges("https://target.com:443", 500);
        assertThat(edges).anyMatch(e -> e.relationType() == GraphTypes.EdgeType.USES_TECHNOLOGY
                && e.targetNodeId() == tech.id());
        assertThat(edges).anyMatch(e -> e.relationType() == GraphTypes.EdgeType.HAS_VERSION);
        assertThat(edges).anyMatch(e -> e.relationType() == GraphTypes.EdgeType.INDICATES);
    }

    @Test
    @DisplayName("Agent 特征登记：FEATURE → VULN_CLASS 的 SUGGESTS 边")
    void agent_feature_registration() {
        String url = "https://target.com/api/order/detail?id=1";
        store.observe(rr("GET", url, "", true, 443));
        long ifaceId = extractNodeId(store.findInterfaces("https://target.com:443", "order", null, null));

        store.addFeature(ifaceId, null, "spring_actuator", "未授权访问",
                "GET /actuator/env 返回 200", 0.9);

        List<GraphTypes.Node> nodes = store.listNodes("https://target.com:443", 500);
        assertThat(nodes).anyMatch(n -> n.type() == GraphTypes.NodeType.FEATURE
                && "spring_actuator".equals(n.name()));
        assertThat(nodes).anyMatch(n -> n.type() == GraphTypes.NodeType.VULN_CLASS
                && "未授权访问".equals(n.name()));
        assertThat(store.listEdges("https://target.com:443", 500))
                .anyMatch(e -> e.relationType() == GraphTypes.EdgeType.SUGGESTS);
    }

    @Test
    @DisplayName("JS 资源入图：RESOURCE 节点 + LOADS + CALLS 边")
    void js_resource_creates_nodes_and_calls() {
        String api = "https://target.com/api/user/list";
        store.observe(rr("GET", api, "", true, 443));

        HttpRequestResponse jsRr = rr("GET", "https://target.com/static/app.js", "", true, 443);
        HttpResponse jsResp = mock(HttpResponse.class);
        HttpHeader ct = mock(HttpHeader.class);
        when(ct.name()).thenReturn("Content-Type");
        when(ct.value()).thenReturn("application/javascript");
        when(jsRr.response()).thenReturn(jsResp);
        when(jsResp.headers()).thenReturn(List.of(ct));
        when(jsResp.statusCode()).thenReturn((short) 200);
        when(jsResp.bodyToString()).thenReturn("fetch('/api/user/list');");
        store.observe(jsRr);

        List<GraphTypes.Node> nodes = store.listNodes("https://target.com:443", 500);
        GraphTypes.Node resource = nodes.stream()
                .filter(n -> n.type() == GraphTypes.NodeType.RESOURCE
                        && n.name().endsWith("/static/app.js"))
                .findFirst().orElse(null);
        assertThat(resource).isNotNull();
        List<GraphTypes.Edge> edges = store.listEdges("https://target.com:443", 500);
        assertThat(edges).anyMatch(e -> e.relationType() == GraphTypes.EdgeType.LOADS
                && e.targetNodeId() == resource.id());
        assertThat(edges).anyMatch(e -> e.relationType() == GraphTypes.EdgeType.CALLS
                && e.sourceNodeId() == resource.id());
    }

    private static long extractNodeId(String findInterfacesOutput) {
        String line = findInterfacesOutput.lines().findFirst().orElseThrow();
        return Long.parseLong(line.replaceAll(".*node=(\\d+).*", "$1").trim());
    }
}