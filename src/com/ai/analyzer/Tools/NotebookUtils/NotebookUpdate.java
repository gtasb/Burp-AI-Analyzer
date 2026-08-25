package com.ai.analyzer.tools.NotebookUtils;

/**
 * 广播通知：某个域名的 Notebook 有新内容被写入。
 *
 * <p>广播<b>只负责提醒</b>，不承载完整数据。当前正在工作的其他 Agent 收到后，
 * 自行调用 {@link com.ai.analyzer.tools.NotebookTool} 的 {@code notebook_read} 或 {@code notebook_get_updates}
 * 拉取新增内容；错过广播、当时未运行或重启后的 Agent，则通过版本号用
 * {@code notebook_get_updates(since_version)} 补齐。
 */
public record NotebookUpdate(String domain, int version, String sourceAgent, String type, String time) {

    public String summarize() {
        return String.format("domain=%s version=%d source=%s type=%s time=%s",
                domain, version, sourceAgent, type, time);
    }
}