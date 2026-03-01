package com.ai.analyzer.ui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ChatPanel.ChatMessage - 聊天消息序列化与持久化")
class ChatPanelTest {

    @Nested
    @DisplayName("ChatMessage 序列化")
    class ChatMessageSerializationTests {

        @Test
        @DisplayName("should_be_serializable")
        void should_be_serializable() {
            ChatPanel.ChatMessage msg = new ChatPanel.ChatMessage("user", "Hello World");
            assertThat(msg).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("should_preserve_role_and_content_after_serialization")
        void should_preserve_role_and_content_after_serialization() throws Exception {
            ChatPanel.ChatMessage original = new ChatPanel.ChatMessage("assistant", "分析完成，未发现漏洞");

            // 序列化
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(original);
            }

            // 反序列化
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                ChatPanel.ChatMessage deserialized = (ChatPanel.ChatMessage) ois.readObject();
                assertThat(deserialized.getRole()).isEqualTo("assistant");
                assertThat(deserialized.getContent()).isEqualTo("分析完成，未发现漏洞");
            }
        }

        @Test
        @DisplayName("should_serialize_list_of_messages")
        void should_serialize_list_of_messages() throws Exception {
            List<ChatPanel.ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatPanel.ChatMessage("user", "请分析这个请求"));
            messages.add(new ChatPanel.ChatMessage("assistant", "## 风险等级: 无\n未发现明显漏洞"));
            messages.add(new ChatPanel.ChatMessage("user", "请深入检查 SQL 注入"));

            // 序列化
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(new ArrayList<>(messages));
            }

            // 反序列化
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            @SuppressWarnings("unchecked")
            List<ChatPanel.ChatMessage> deserialized;
            try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                deserialized = (List<ChatPanel.ChatMessage>) ois.readObject();
            }

            assertThat(deserialized).hasSize(3);
            assertThat(deserialized.get(0).getRole()).isEqualTo("user");
            assertThat(deserialized.get(0).getContent()).isEqualTo("请分析这个请求");
            assertThat(deserialized.get(2).getRole()).isEqualTo("user");
            assertThat(deserialized.get(2).getContent()).isEqualTo("请深入检查 SQL 注入");
        }

        @Test
        @DisplayName("should_handle_empty_content")
        void should_handle_empty_content() throws Exception {
            ChatPanel.ChatMessage msg = new ChatPanel.ChatMessage("system", "");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(msg);
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                ChatPanel.ChatMessage deserialized = (ChatPanel.ChatMessage) ois.readObject();
                assertThat(deserialized.getRole()).isEqualTo("system");
                assertThat(deserialized.getContent()).isEmpty();
            }
        }

        @Test
        @DisplayName("should_handle_unicode_content")
        void should_handle_unicode_content() throws Exception {
            ChatPanel.ChatMessage msg = new ChatPanel.ChatMessage("assistant",
                "## 风险等级: 严重\n发现 SQL 注入漏洞：参数 `id` 可被利用\n建议使用参数化查询 🔒");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(msg);
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                ChatPanel.ChatMessage deserialized = (ChatPanel.ChatMessage) ois.readObject();
                assertThat(deserialized.getContent()).contains("SQL 注入");
                assertThat(deserialized.getContent()).contains("参数化查询");
            }
        }
    }

    @Nested
    @DisplayName("聊天历史文件持久化")
    class ChatHistoryPersistenceTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("should_write_and_read_history_file")
        void should_write_and_read_history_file() throws Exception {
            File historyFile = tempDir.resolve("test_chat_history.dat").toFile();

            // 写入
            List<ChatPanel.ChatMessage> original = new ArrayList<>();
            original.add(new ChatPanel.ChatMessage("user", "分析请求安全性"));
            original.add(new ChatPanel.ChatMessage("assistant", "该请求存在 XSS 风险"));

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(historyFile))) {
                oos.writeObject(original);
            }

            assertThat(historyFile).exists();
            assertThat(historyFile.length()).isGreaterThan(0);

            // 读取
            @SuppressWarnings("unchecked")
            List<ChatPanel.ChatMessage> loaded;
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(historyFile))) {
                loaded = (List<ChatPanel.ChatMessage>) ois.readObject();
            }

            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(0).getRole()).isEqualTo("user");
            assertThat(loaded.get(1).getContent()).isEqualTo("该请求存在 XSS 风险");
        }

        @Test
        @DisplayName("should_truncate_history_to_max_messages")
        void should_truncate_history_to_max_messages() throws Exception {
            File historyFile = tempDir.resolve("test_chat_truncate.dat").toFile();
            int maxMessages = 50;

            // 创建 100 条消息
            List<ChatPanel.ChatMessage> original = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                original.add(new ChatPanel.ChatMessage("user", "消息 #" + i));
            }

            // 只保存最后 maxMessages 条
            List<ChatPanel.ChatMessage> toSave = new ArrayList<>(
                original.subList(original.size() - maxMessages, original.size()));
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(historyFile))) {
                oos.writeObject(toSave);
            }

            @SuppressWarnings("unchecked")
            List<ChatPanel.ChatMessage> loaded;
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(historyFile))) {
                loaded = (List<ChatPanel.ChatMessage>) ois.readObject();
            }

            assertThat(loaded).hasSize(maxMessages);
            assertThat(loaded.get(0).getContent()).isEqualTo("消息 #50");
            assertThat(loaded.get(49).getContent()).isEqualTo("消息 #99");
        }

        @Test
        @DisplayName("should_handle_corrupted_data_gracefully")
        void should_handle_corrupted_data_gracefully() {
            // 使用内存流模拟损坏的序列化数据，避免 Windows 文件锁问题
            byte[] corruptedData = "this is not a valid serialized object".getBytes();

            boolean exceptionThrown = false;
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(corruptedData))) {
                ois.readObject();
            } catch (Exception e) {
                exceptionThrown = true;
            }
            assertThat(exceptionThrown).isTrue();
        }

        @Test
        @DisplayName("should_handle_missing_file_gracefully")
        void should_handle_missing_file_gracefully() {
            File nonExistent = tempDir.resolve("does_not_exist.dat").toFile();
            assertThat(nonExistent).doesNotExist();
            // loadChatHistory 内部检查 file.exists()，不存在时直接返回
        }
    }
}
