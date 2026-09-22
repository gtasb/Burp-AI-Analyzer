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

    private static long extractNodeId(String findInterfacesOutput) {
        String line = findInterfacesOutput.lines().findFirst().orElseThrow();
        return Long.parseLong(line.replaceAll(".*node=(\\d+).*", "$1").trim());
    }
}