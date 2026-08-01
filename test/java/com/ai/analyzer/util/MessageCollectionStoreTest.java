package com.ai.analyzer.util;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MessageCollectionStore - 侧栏收藏列表共享存储")
class MessageCollectionStoreTest {

    private HttpRequestResponse rr;
    private HttpRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpRequest.class);
        burp.api.montoya.core.ByteArray requestBytes = mockBytes("GET /a HTTP/1.1\r\nHost: t\r\n\r\n");
        when(request.toByteArray()).thenReturn(requestBytes);
        rr = mock(HttpRequestResponse.class);
        when(rr.request()).thenReturn(request);
    }

    @AfterEach
    void tearDown() {
        MessageCollectionStore.resetForTest();
    }

    private static burp.api.montoya.core.ByteArray mockBytes(String content) {
        burp.api.montoya.core.ByteArray bytes = mock(burp.api.montoya.core.ByteArray.class);
        when(bytes.getBytes()).thenReturn(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytes;
    }

    private HttpRequestResponse another(String path) {
        HttpRequest req = mock(HttpRequest.class);
        burp.api.montoya.core.ByteArray bytes = mockBytes("GET " + path + " HTTP/1.1\r\nHost: t\r\n\r\n");
        when(req.toByteArray()).thenReturn(bytes);
        HttpRequestResponse item = mock(HttpRequestResponse.class);
        when(item.request()).thenReturn(req);
        return item;
    }

    @Test
    @DisplayName("add 增加条目，snapshot 返回独立副本")
    void add_increases_size_and_snapshot_is_copy() {
        assertThat(MessageCollectionStore.add(rr)).isTrue();
        assertThat(MessageCollectionStore.size()).isEqualTo(1);
        List<MessageCollectionStore.StoreEntry> snapshot = MessageCollectionStore.snapshot();
        snapshot.clear();
        assertThat(MessageCollectionStore.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("相同报文去重，重复 add 返回 false")
    void duplicate_add_is_rejected() {
        assertThat(MessageCollectionStore.add(rr)).isTrue();
        assertThat(MessageCollectionStore.add(rr)).isFalse();
        assertThat(MessageCollectionStore.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("null 与无请求的报文拒绝加入")
    void null_or_empty_rejected() {
        assertThat(MessageCollectionStore.add(null)).isFalse();
        HttpRequestResponse noRequest = mock(HttpRequestResponse.class);
        when(noRequest.request()).thenReturn(null);
        assertThat(MessageCollectionStore.add(noRequest)).isFalse();
        assertThat(MessageCollectionStore.size()).isZero();
    }

    @Test
    @DisplayName("remove/removeAll/clear 正确删除")
    void remove_operations() {
        HttpRequestResponse r2 = another("/b");
        HttpRequestResponse r3 = another("/c");
        MessageCollectionStore.add(rr);
        MessageCollectionStore.add(r2);
        MessageCollectionStore.add(r3);
        assertThat(MessageCollectionStore.remove(rr)).isTrue();
        assertThat(MessageCollectionStore.size()).isEqualTo(2);
        assertThat(MessageCollectionStore.removeAll(List.of(r2, r3))).isEqualTo(2);
        assertThat(MessageCollectionStore.size()).isZero();
        MessageCollectionStore.add(rr);
        MessageCollectionStore.clear();
        assertThat(MessageCollectionStore.size()).isZero();
    }

    @Test
    @DisplayName("listener 在数据变化时收到通知")
    void listener_notified_on_change() {
        AtomicInteger count = new AtomicInteger(0);
        MessageCollectionStore.addListener(count::incrementAndGet);
        MessageCollectionStore.add(rr);
        assertThat(count.get()).isEqualTo(1);
        MessageCollectionStore.add(rr);
        assertThat(count.get()).isEqualTo(1);
        MessageCollectionStore.clear();
        assertThat(count.get()).isEqualTo(2);
    }

    @Nested
    @DisplayName("持久化到工作区")
    class Persistence {

        @org.junit.jupiter.api.io.TempDir
        java.nio.file.Path tempDir;

        @Test
        @DisplayName("add 后落盘，文件包含条目")
        void add_persists_to_disk() throws Exception {
            MessageCollectionStore.setWorkplaceDirectory(tempDir.toString());
            MessageCollectionStore.add(rr);
            java.nio.file.Path file = tempDir.resolve(MessageCollectionStore.STORAGE_FILE_NAME);
            assertThat(java.nio.file.Files.exists(file)).isTrue();
            String content = java.nio.file.Files.readString(file);
            assertThat(content).contains("requestB64");
            assertThat(content).contains("version");
        }

        @Test
        @DisplayName("remove/clear 同步更新磁盘")
        void remove_and_clear_update_disk() throws Exception {
            MessageCollectionStore.setWorkplaceDirectory(tempDir.toString());
            MessageCollectionStore.add(rr);
            MessageCollectionStore.remove(rr);
            String contentAfterRemove = java.nio.file.Files.readString(
                    tempDir.resolve(MessageCollectionStore.STORAGE_FILE_NAME));
            assertThat(contentAfterRemove).doesNotContain("requestB64");
            MessageCollectionStore.add(rr);
            MessageCollectionStore.clear();
            String contentAfterClear = java.nio.file.Files.readString(
                    tempDir.resolve(MessageCollectionStore.STORAGE_FILE_NAME));
            assertThat(contentAfterClear).contains("\"entries\":[]");
        }

        @Test
        @DisplayName("未配置工作区时不写盘")
        void no_workplace_means_no_file() throws Exception {
            MessageCollectionStore.add(rr);
            assertThat(java.nio.file.Files.exists(tempDir.resolve(MessageCollectionStore.STORAGE_FILE_NAME))).isFalse();
        }

        @Test
        @DisplayName("损坏的持久化文件被静默忽略")
        void corrupted_file_ignored() {
            java.nio.file.Path file = tempDir.resolve(MessageCollectionStore.STORAGE_FILE_NAME);
            try {
                java.nio.file.Files.writeString(file, "{{{ not json");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            MessageCollectionStore.setWorkplaceDirectory(tempDir.toString());
            assertThat(MessageCollectionStore.size()).isZero();
            assertThat(MessageCollectionStore.restoredFromDiskCount()).isZero();
        }
    }
}
