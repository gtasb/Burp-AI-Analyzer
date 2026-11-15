package com.ai.analyzer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 单向上下文共享：侧栏 agent 每轮完成的结论摘要，供主动扫描 agent 在下一轮读取。
 *
 * <p>只保存 assistant 的纯文本结论（不含工具调用轨迹、不含 reasoning），
 * 避免把 tool_calls 历史注入另一个 agent 造成工具配对/DeepSeek reasoning 问题。
 */
public final class AgentDigestStore {

    private static final AgentDigestStore INSTANCE = new AgentDigestStore();
    private static final int MAX_ENTRIES = 30;
    private static final int MAX_ENTRY_CHARS = 4_000;
    private static final int MAX_RENDER_CHARS = 6_000;

    private final List<String> entries = new ArrayList<>();

    private AgentDigestStore() {
    }

    public static AgentDigestStore getInstance() {
        return INSTANCE;
    }

    /** 记录一条侧栏结论摘要（重复内容跳过）。 */
    public synchronized void record(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return;
        if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(normalized)) {
            return;
        }
        entries.add(normalized);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** 渲染最近若干条摘要，总长受 maxChars 限制；无内容时返回空串。 */
    public synchronized String renderForPrompt(int maxChars) {
        int budget = maxChars > 0 ? maxChars : MAX_RENDER_CHARS;
        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            String block = "- " + entry + "\n";
            if (sb.length() + block.length() > budget) {
                if (sb.length() == 0) {
                    sb.append(block, 0, Math.min(block.length(), budget));
                }
                break;
            }
            sb.insert(0, block);
        }
        return sb.toString().trim();
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_ENTRY_CHARS) {
            t = t.substring(0, MAX_ENTRY_CHARS) + "…";
        }
        return t;
    }
}
