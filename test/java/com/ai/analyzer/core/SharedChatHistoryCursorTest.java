package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享聊天历史的游标推进契约。
 *
 * <p>回归：{@code ChatPanel.addToSharedHistory} 曾在写入共享历史后**同步**把
 * {@code lastSyncedHistorySize} 推到最新，而该字段是「已渲染到哪」的游标。
 * 于是紧随其后的 {@code syncChatAreaFromSharedHistory()} 命中
 * {@code currentSize <= lastSyncedHistorySize} 提前 return，
 * 消息已写入共享历史与历史文件、却从不渲染——只有重载插件后
 * {@code loadChatHistory()} 读文件才能看到。
 */
@DisplayName("共享聊天历史游标契约")
class SharedChatHistoryCursorTest {

    private static final String CHAT_PANEL = "com.ai.analyzer.ui.ChatPanel";

    @Test
    @DisplayName("sent_user_message_should_be_rendered_immediately")
    void sentUserMessageIsRenderedImmediately() throws Exception {
        // 真实复现回归：写入共享历史后立刻渲染，不得因为游标被提前推进而跳过
        burp.api.montoya.MontoyaApi mockApi = org.mockito.Mockito.mock(burp.api.montoya.MontoyaApi.class);
        org.mockito.Mockito.when(mockApi.logging())
                .thenReturn(org.mockito.Mockito.mock(burp.api.montoya.logging.Logging.class));

        AgentApiClient client = new AgentApiClient(mockApi, "http://localhost", "k");
        com.ai.analyzer.ui.ChatPanel panel = new com.ai.analyzer.ui.ChatPanel(mockApi, client);

        // ChatPanel 构造时会 loadChatHistory() 读本机历史文件，基线不固定，按增量断言
        int sizeBefore = client.getSharedChatUiHistorySize();

        Method appendToChatAndShare = com.ai.analyzer.ui.ChatPanel.class
                .getDeclaredMethod("appendToChatAndShare", String.class, String.class, boolean.class);
        appendToChatAndShare.setAccessible(true);
        appendToChatAndShare.invoke(panel, "你", "这条消息必须立刻可见", true);

        // 关键断言：已发送的用户消息在同一次调用后就能从 UI 文本里读到，
        // 而不需要重载插件后由 loadChatHistory() 重放
        javax.swing.JTextPane chatArea = (javax.swing.JTextPane) readField(panel, "chatArea");
        assertThat(chatArea.getText())
                .as("已发送的用户消息必须立即渲染到聊天区")
                .contains("这条消息必须立刻可见");

        // 同时历史里也确实多了一条，供其它侧栏同步
        assertThat(client.getSharedChatUiHistorySize()).isEqualTo(sizeBefore + 1);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    @DisplayName("cursor_field_must_exist_and_be_private")
    void cursorFieldIsPrivate() throws Exception {
        Class<?> chatPanel = Class.forName(CHAT_PANEL);
        Field cursor = chatPanel.getDeclaredField("lastSyncedHistorySize");
        assertThat(java.lang.reflect.Modifier.isPrivate(cursor.getModifiers())).isTrue();
        assertThat(cursor.getType()).isEqualTo(int.class);
    }

    @Test
    @DisplayName("shared_history_entry_should_notify_listeners")
    void addingEntryNotifiesListeners() {
        AgentApiClient client = new AgentApiClient("http://localhost", "k");
        final int[] notified = {0};
        client.addChatUiListener(() -> notified[0]++);

        client.addChatUiEntry("你", "hello", true);

        assertThat(client.getSharedChatUiHistorySize()).isEqualTo(1);
        assertThat(notified[0])
                .as("写入共享历史后必须通知监听者，各侧栏才能实时同步")
                .isEqualTo(1);
        Object[] entry = client.getSharedChatUiHistoryEntry(0);
        assertThat(entry[0]).isEqualTo("你");
        assertThat(entry[1]).isEqualTo("hello");
        assertThat(entry[2]).isEqualTo(true);
    }

    @Test
    @DisplayName("clearing_history_should_notify_listeners")
    void clearingNotifiesListeners() {
        AgentApiClient client = new AgentApiClient("http://localhost", "k");
        client.addChatUiEntry("你", "hi", true);
        final int[] notified = {0};
        client.addChatUiListener(() -> notified[0]++);

        client.clearSharedChatUiHistory();

        assertThat(client.getSharedChatUiHistorySize()).isZero();
        assertThat(notified[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("history_should_be_capped_to_max_entries")
    void historyIsCapped() throws Exception {
        AgentApiClient client = new AgentApiClient("http://localhost", "k");
        Field maxField = AgentApiClient.class.getDeclaredField("MAX_SHARED_UI_HISTORY");
        maxField.setAccessible(true);
        int max = maxField.getInt(null);

        for (int i = 0; i < max + 25; i++) {
            client.addChatUiEntry("你", "m" + i, true);
        }
        assertThat(client.getSharedChatUiHistorySize()).isEqualTo(max);
    }
}
