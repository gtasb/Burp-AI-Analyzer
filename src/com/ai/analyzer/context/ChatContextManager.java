package com.ai.analyzer.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 统一的聊天上下文管理器。
 *
 * 目标是把“按消息数”管理改成“按 token/内容体积”管理，并在超长时自动压缩/摘要。
 * 当前依赖版本中没有直接提供 TokenWindowChatMemory，因此这里先提供一个可插拔的实现，
 * 兼容 LangChain4j 的 ChatMemory 接口，并保留压缩回退能力。
 */
public class ChatContextManager implements ChatMemory {
    private final String name;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final int maxMessages;
    private final int maxTokens;
    private final int maxCharacters;
    private final Supplier<String> summarySupplier;

    public ChatContextManager(String name) {
        this(name, 80, 128_000, null);
    }

    public ChatContextManager(String name, int maxMessages, int maxTokens, Supplier<String> summarySupplier) {
        this.name = Objects.requireNonNullElse(name, "chat-context");
        this.maxMessages = Math.max(1, maxMessages);
        this.maxTokens = Math.max(1_000, maxTokens);
        this.maxCharacters = Math.max(4_000, this.maxTokens * 3);
        this.summarySupplier = summarySupplier;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    @Override
    public Object id() {
        return name;
    }

    @Override
    public void add(ChatMessage message) {
        if (message == null) return;
        messages.add(message);
        enforceLimits();
    }

    @Override
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public void clear() {
        messages.clear();
    }

    public void compactIfNeeded(String latestUserContent) {
        enforceLimits(latestUserContent);
    }

    public void compactToMinimal(String latestUserContent) {
        messages.clear();
        if (latestUserContent != null && !latestUserContent.isBlank()) {
            messages.add(new UserMessage("[轻量模式] 当前请求已压缩，优先输出结论与建议。\n\n" + latestUserContent));
        }
    }

    public String buildSummary() {
        if (messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("已保留最近对话要点（压缩版）:\n");
        int startIndex = Math.max(0, messages.size() - Math.min(10, messages.size()));
        for (int i = startIndex; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String text = String.valueOf(msg);
            text = text.replace('\r', ' ').replace('\n', ' ');
            if (text.length() > 240) {
                text = text.substring(0, 240) + "...";
            }
            sb.append("- ").append(msg.getClass().getSimpleName()).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private void enforceLimits() {
        enforceLimits(null);
    }

    private void enforceLimits(String latestUserContent) {
        if (messages.size() <= maxMessages && estimateTokens() <= maxTokens && estimateCharacters() <= maxCharacters) {
            return;
        }

        List<ChatMessage> retained = new ArrayList<>();
        if (messages.size() > maxMessages) {
            int start = Math.max(0, messages.size() - maxMessages);
            retained.addAll(messages.subList(start, messages.size()));
        } else {
            retained.addAll(messages);
        }

        if (estimateTokens(retained) > maxTokens || estimateCharacters(retained) > maxCharacters) {
            retained = compactHistory(retained, latestUserContent);
        }

        messages.clear();
        messages.addAll(retained);
    }

    private List<ChatMessage> compactHistory(List<ChatMessage> history, String latestUserContent) {
        List<ChatMessage> compacted = new ArrayList<>();
        if (history.isEmpty()) {
            return compacted;
        }

        String summary = summarySupplier != null ? summarySupplier.get() : buildSummary(history);
        if (summary != null && !summary.isBlank()) {
            compacted.add(new UserMessage("[系统自动压缩的历史上下文摘要]\n" + summary));
        }
        if (latestUserContent != null && !latestUserContent.isBlank()) {
            compacted.add(new UserMessage("[当前请求摘要]\n" + truncate(latestUserContent, 4000)));
        }
        if (compacted.isEmpty()) {
            compacted.addAll(history.subList(Math.max(0, history.size() - 2), history.size()));
        }
        return compacted;
    }

    private String buildSummary(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("已保留最近对话要点（压缩版）:\n");
        int startIndex = Math.max(0, history.size() - Math.min(8, history.size()));
        for (int i = startIndex; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String text = String.valueOf(msg).replace('\r', ' ').replace('\n', ' ');
            if (text.length() > 240) {
                text = text.substring(0, 240) + "...";
            }
            sb.append("- ").append(msg.getClass().getSimpleName()).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private int estimateTokens() {
        return estimateTokens(messages);
    }

    private int estimateTokens(List<ChatMessage> history) {
        int total = 0;
        for (ChatMessage message : history) {
            total += estimateTokens(String.valueOf(message));
        }
        return total;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private int estimateCharacters() {
        return estimateCharacters(messages);
    }

    private int estimateCharacters(List<ChatMessage> history) {
        int total = 0;
        for (ChatMessage message : history) {
            total += String.valueOf(message).length();
        }
        return total;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
