package com.ai.analyzer.util;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 侧栏「待分析报文」收藏列表的共享存储（静态单例）。
 * 多个侧栏编辑器（Request/Response、不同工具类型）通过同一存储协同：
 * 在一处收藏的报文，其他侧栏立即可见并可批量发送。
 */
public final class MessageCollectionStore {

    /** 收藏上限，超出时拒绝加入（提示用户清理） */
    public static final int MAX_ITEMS = 200;

    public static final class StoreEntry {
        private final HttpRequestResponse requestResponse;
        private final long timestamp;
        private final String timeText;

        StoreEntry(HttpRequestResponse requestResponse, long timestamp) {
            this.requestResponse = requestResponse;
            this.timestamp = timestamp;
            this.timeText = java.time.LocalDateTime
                    .ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }

        public HttpRequestResponse getRequestResponse() {
            return requestResponse;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getTimeText() {
            return timeText;
        }
    }

    private static final List<StoreEntry> ENTRIES = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Path storageFile;

    /** 收藏列表持久化文件名（位于工作区根目录） */
    public static final String STORAGE_FILE_NAME = ".message_collection.json";

    private MessageCollectionStore() {
    }

    public static synchronized boolean add(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return false;
        }
        if (ENTRIES.size() >= MAX_ITEMS) {
            return false;
        }
        if (indexOf(requestResponse) >= 0) {
            return false;
        }
        ENTRIES.add(new StoreEntry(requestResponse, System.currentTimeMillis()));
        saveToDisk();
        notifyChanged();
        return true;
    }

    public static synchronized boolean remove(HttpRequestResponse requestResponse) {
        int index = indexOf(requestResponse);
        if (index < 0) {
            return false;
        }
        ENTRIES.remove(index);
        saveToDisk();
        notifyChanged();
        return true;
    }

    public static synchronized int removeAll(List<HttpRequestResponse> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (HttpRequestResponse item : items) {
            if (removeInternal(item)) {
                removed++;
            }
        }
        if (removed > 0) {
            saveToDisk();
            notifyChanged();
        }
        return removed;
    }

    private static boolean removeInternal(HttpRequestResponse requestResponse) {
        int index = indexOf(requestResponse);
        if (index < 0) {
            return false;
        }
        ENTRIES.remove(index);
        return true;
    }

    public static synchronized void clear() {
        if (ENTRIES.isEmpty()) {
            return;
        }
        ENTRIES.clear();
        saveToDisk();
        notifyChanged();
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    /** 返回条目快照（新列表，调用方可自由修改） */
    public static synchronized List<StoreEntry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    private static synchronized int indexOf(HttpRequestResponse requestResponse) {
        String fingerprint = fingerprint(requestResponse);
        if (fingerprint == null) {
            return -1;
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            if (fingerprint.equals(fingerprint(ENTRIES.get(i).getRequestResponse()))) {
                return i;
            }
        }
        return -1;
    }

    private static String fingerprint(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(requestResponse.request().toByteArray().getBytes());
            if (requestResponse.response() != null) {
                digest.update(requestResponse.response().toByteArray().getBytes());
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (Exception e) {
            return requestResponse.request().toString();
        }
    }

    /**
     * 设置工作区目录：收藏列表持久化到 工作区/.message_collection.json。
     * 首次设置且内存为空时尝试从磁盘恢复；恢复失败（文件损坏等）静默忽略。
     */
    public static synchronized void setWorkplaceDirectory(String workplaceDirectory) {
        if (workplaceDirectory == null || workplaceDirectory.trim().isEmpty()) {
            storageFile = null;
            return;
        }
        storageFile = Path.of(workplaceDirectory.trim())
                .toAbsolutePath().normalize().resolve(STORAGE_FILE_NAME);
        if (ENTRIES.isEmpty()) {
            loadFromDisk();
        }
    }

    /** 是否已配置持久化目标 */
    public static synchronized boolean isPersistent() {
        return storageFile != null;
    }

    /** 磁盘恢复条目数（测试/诊断用） */
    public static synchronized int restoredFromDiskCount() {
        return restoredCount;
    }

    private static int restoredCount = 0;

    private static void saveToDisk() {
        Path file = storageFile;
        if (file == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(ENTRIES.size() * 160);
            sb.append("{\"version\":1,\"entries\":[");
            for (int i = 0; i < ENTRIES.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                StoreEntry entry = ENTRIES.get(i);
                sb.append("{\"requestB64\":\"")
                        .append(escapeJson(Base64.getEncoder().encodeToString(
                                entry.getRequestResponse().request().toByteArray().getBytes())))
                        .append("\",\"responseB64\":\"");
                if (entry.getRequestResponse().response() != null) {
                    sb.append(escapeJson(Base64.getEncoder().encodeToString(
                            entry.getRequestResponse().response().toByteArray().getBytes())));
                }
                sb.append("\",\"ts\":").append(entry.getTimestamp()).append('}');
            }
            sb.append("]}");
            file.getParent().toFile().mkdirs();
            // 原子写：先写临时文件再原子替换，避免写入中途崩溃导致 JSON 半截损坏、整列表丢失
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try {
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ignored) {
            // 持久化失败不阻断功能，下次变更时重试
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadFromDisk() {
        Path file = storageFile;
        if (file == null || !Files.exists(file)) {
            return;
        }
        restoredCount = 0;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseJsonToMap(json);
            Object entriesObj = root != null ? root.get("entries") : null;
            if (!(entriesObj instanceof List)) {
                return;
            }
            for (Object itemObj : (List<Object>) entriesObj) {
                if (!(itemObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) itemObj;
                Object requestB64 = item.get("requestB64");
                if (!(requestB64 instanceof String)) {
                    continue;
                }
                try {
                    byte[] requestBytes = Base64.getDecoder().decode((String) requestB64);
                    HttpRequestResponse rr = HttpRequestResponse.httpRequestResponse(
                            HttpRequest.httpRequest(ByteArray.byteArray(requestBytes)),
                            item.get("responseB64") instanceof String
                                    ? HttpResponse.httpResponse(ByteArray.byteArray(
                                            Base64.getDecoder().decode((String) item.get("responseB64"))))
                                    : null);
                    if (ENTRIES.size() < MAX_ITEMS && indexOf(rr) < 0) {
                        Object ts = item.get("ts");
                        StoreEntry entry = new StoreEntry(rr,
                                ts instanceof Number ? ((Number) ts).longValue() : System.currentTimeMillis());
                        ENTRIES.add(entry);
                        restoredCount++;
                    }
                } catch (Exception ignored) {
                    // 单条恢复失败跳过，不影响其余条目
                }
            }
            if (restoredCount > 0) {
                notifyChanged();
            }
        } catch (Exception ignored) {
            // 文件损坏等：忽略，等待下一次保存覆盖
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // 单个监听器异常不影响其他监听器
            }
        }
    }

    /** 仅测试用：清空全部条目与监听器，重置静态状态 */
    public static synchronized void resetForTest() {
        ENTRIES.clear();
        LISTENERS.clear();
        storageFile = null;
        restoredCount = 0;
    }
}
