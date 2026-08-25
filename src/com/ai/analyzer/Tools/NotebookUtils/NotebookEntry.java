package com.ai.analyzer.tools.NotebookUtils;

/**
 * Notebook 中的一条已持久化记录（关键事实/发现）。
 *
 * @param version     递增版本号（每个域名独立，从 1 开始）
 * @param sourceAgent 来源 Agent 标识
 * @param type        记录类型（attack_surface / endpoint / parameter / vuln_clue / finding / behavior / note ...）
 * @param time        写入时间（ISO，形如 {@code 2026-08-25T14:23:01}）
 * @param hash        内容归一化摘要（用于去重）
 * @param content     记录正文
 */
public record NotebookEntry(int version, String sourceAgent, String type, String time, String hash, String content) {
}