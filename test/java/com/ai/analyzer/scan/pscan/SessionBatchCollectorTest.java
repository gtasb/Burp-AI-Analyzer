package com.ai.analyzer.scan.pscan;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SessionBatchCollector - L3 会话批次收集器")
class SessionBatchCollectorTest {

    private HttpRequestResponse rr(String host, String session) {
        HttpService service = mock(HttpService.class);
        when(service.host()).thenReturn(host);
        HttpRequest request = mock(HttpRequest.class);
        when(request.httpService()).thenReturn(service);
        when(request.url()).thenReturn("https://" + host + "/api/x");
        when(request.method()).thenReturn("GET");
        HttpHeader cookie = mock(HttpHeader.class);
        when(cookie.name()).thenReturn("Cookie");
        when(cookie.value()).thenReturn("session=" + session);
        when(request.headers()).thenReturn(List.of(cookie));
        HttpRequestResponse rr = mock(HttpRequestResponse.class);
        when(rr.request()).thenReturn(request);
        return rr;
    }

    @Nested
    @DisplayName("add - 满批产出")
    class Add {

        @Test
        @DisplayName("未满时不产出，满时产出完整批次并移除")
        void batch_emitted_when_full() {
            SessionBatchCollector collector = new SessionBatchCollector(3, 10_000);
            HttpRequestResponse r1 = rr("a.com", "s1");
            HttpRequestResponse r2 = rr("a.com", "s1");
            HttpRequestResponse r3 = rr("a.com", "s1");

            assertThat(collector.add(r1)).isNull();
            assertThat(collector.add(r2)).isNull();
            SessionBatchCollector.Batch batch = collector.add(r3);
            assertThat(batch).isNotNull();
            assertThat(batch.requests).hasSize(3);
            assertThat(batch.requests.get(0)).isSameAs(r1);
            assertThat(batch.requests.get(2)).isSameAs(r3);
            assertThat(collector.pendingBatchCount()).isZero();
        }

        @Test
        @DisplayName("maxRequests=1 时每次 add 立即产出")
        void max_one_emits_every_add() {
            SessionBatchCollector collector = new SessionBatchCollector(1, 10_000);
            SessionBatchCollector.Batch batch = collector.add(rr("a.com", "s1"));
            assertThat(batch).isNotNull();
            assertThat(batch.requests).hasSize(1);
        }

        @Test
        @DisplayName("不同会话各自聚合互不干扰")
        void sessions_are_independent() {
            SessionBatchCollector collector = new SessionBatchCollector(3, 10_000);
            collector.add(rr("a.com", "s1"));
            collector.add(rr("a.com", "s2"));
            collector.add(rr("a.com", "s1"));
            assertThat(collector.pendingBatchCount()).isEqualTo(2);
            assertThat(collector.pendingRequestCount()).isEqualTo(3);
            SessionBatchCollector.Batch batch = collector.add(rr("a.com", "s1"));
            assertThat(batch).isNotNull();
            assertThat(batch.sessionKey).contains("s1");
            assertThat(batch.requests).hasSize(3);
            assertThat(collector.pendingBatchCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("expire - 窗口过期产出")
    class Expire {

        @Test
        @DisplayName("超窗批次被移出，未超窗保留")
        void expired_only_removed() {
            SessionBatchCollector collector = new SessionBatchCollector(10, 1_000);
            collector.add(rr("a.com", "s1"));
            collector.add(rr("b.com", "s1"));
            long now = System.currentTimeMillis();
            assertThat(collector.expire(now - 1)).isEmpty();
            assertThat(collector.pendingBatchCount()).isEqualTo(2);
            List<SessionBatchCollector.Batch> expired = collector.expire(now + 1_001);
            assertThat(expired).hasSize(2);
            assertThat(collector.pendingBatchCount()).isZero();
        }

        @Test
        @DisplayName("仅清除空闲超窗的会话")
        void only_idle_sessions_expire() throws Exception {
            SessionBatchCollector collector = new SessionBatchCollector(10, 1_000);
            collector.add(rr("a.com", "s1"));
            collector.add(rr("b.com", "s1"));
            Thread.sleep(20);
            long active = System.currentTimeMillis();
            collector.add(rr("a.com", "s1"));
            collector.add(rr("a.com", "s1"));
            List<SessionBatchCollector.Batch> expired = collector.expire(active + 1_000);
            assertThat(expired).hasSize(1);
            assertThat(expired.get(0).sessionKey).contains("b.com");
            assertThat(collector.pendingBatchCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("clear - 清理残留")
    class Clear {

        @Test
        @DisplayName("清空所有未提交批次")
        void clear_removes_pending() {
            SessionBatchCollector collector = new SessionBatchCollector(10, 1_000);
            collector.add(rr("a.com", "s1"));
            collector.add(rr("a.com", "s2"));
            assertThat(collector.pendingBatchCount()).isEqualTo(2);
            collector.clear();
            assertThat(collector.pendingBatchCount()).isZero();
            assertThat(collector.pendingRequestCount()).isZero();
        }
    }

    @Nested
    @DisplayName("构造参数保护")
    class Constructor {

        @Test
        @DisplayName("非法参数被钳制为最小值")
        void invalid_params_clamped() {
            SessionBatchCollector collector = new SessionBatchCollector(0, 0);
            assertThat(collector.maxRequests()).isEqualTo(1);
            SessionBatchCollector.Batch batch = collector.add(rr("a.com", "s1"));
            assertThat(batch).isNotNull();
        }
    }
}
