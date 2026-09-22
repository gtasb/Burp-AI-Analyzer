package com.ai.analyzer.tools;

import com.ai.analyzer.tools.NotebookUtils.NotebookEntry;
import com.ai.analyzer.tools.NotebookUtils.NotebookStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;

/**
 * Notebook 工具：主动扫描 Agent 与多个被动扫描 Agent 之间共享关键发现的轻量级知识黑板。
 *
 * <p>Agent 只需调用下面这 4 个工具，锁、版本管理、广播、并发控制、原子写、去重全部由
 * {@link NotebookStore} 在底层封装，无需自己管理锁或消息队列。
 *
 * <ul>
 *   <li>{@code notebook_read} — 读取某域名全部关键发现</li>
 *   <li>{@code notebook_write} — 追加一条关键发现（自动去重、版本号 +1、广播其他 Agent）</li>
 *   <li>{@code notebook_get_updates} — 按版本号拉取增量（补齐错过的广播 / 重启前的更新）</li>
 *   <li>{@code notebook_list} — 列出所有已存在 Notebook 的域名</li>
 * </ul>
 */
public class NotebookTool {

    private static final int MAX_READ_CHARS = 40_000;

    private final NotebookStore store;
    private final String defaultSourceAgent;

    public NotebookTool() {
        this(NotebookStore.getInstance(), "agent");
    }

    /**
     * @param store              共享的黑板引擎（主动/被动必须指向同一实例）
     * @param defaultSourceAgent 未显式指定 {@code source_agent} 时使用的默认来源标识
     */
    public NotebookTool(NotebookStore store, String defaultSourceAgent) {
        this.store = store != null ? store : NotebookStore.getInstance();
        this.defaultSourceAgent = defaultSourceAgent == null || defaultSourceAgent.isBlank()
                ? "agent" : defaultSourceAgent;
    }

    @Tool(name = "notebook_read", description =
            "读取某个目标域名的 Notebook（共享知识黑板），返回该域名下所有已知的攻击面、接口、参数、漏洞线索、关键行为等关键发现。开始分析某域名前建议先读取，避免重复劳动。")
    public String read(
            @ToolParam(name = "domain", description = "目标域名，例如 example.com") String domain) {
        try {
            String d = NotebookStore.normalizeDomain(domain);
            NotebookStore.ReadResult r = store.readWithMeta(d);
            if (!r.exists()) {
                return "Notebook 为空: " + d + "（尚无任何记录，latest_version=0）";
            }
            String body = NotebookStore.stripEntryMarkers(r.content());
            if (body.length() > MAX_READ_CHARS) {
                body = body.substring(0, MAX_READ_CHARS)
                        + "\n...[内容已截断，请用 notebook_get_updates(domain, since_version) 按版本增量读取剩余内容]";
            }
            return "Notebook: " + d + ".md\nlatest_version: " + r.version() + "\n\n" + body;
        } catch (Exception e) {
            return "Notebook 读取失败: " + e.getMessage();
        }
    }

    @Tool(name = "notebook_write", description =
            "向某个目标域名追加一条关键发现（攻击面/接口/参数/漏洞线索/验证结论等）。写入是原子操作：自动加文件锁、去重、版本号 +1，并向正在工作的其他 Agent 广播‘该域名 Notebook 有新内容’。重复内容会被跳过。")
    public String write(
            @ToolParam(name = "domain", description = "目标域名，例如 example.com") String domain,
            @ToolParam(name = "type", description = "记录类型，如 attack_surface / endpoint / parameter / vuln_clue / finding / behavior / note") String type,
            @ToolParam(name = "content", description = "要写入的关键发现内容（Markdown）") String content,
            @ToolParam(name = "source_agent", description = "来源 Agent 标识；可留空，自动使用当前 Agent 名称") String sourceAgent) {
        try {
            String d = NotebookStore.normalizeDomain(domain);
            String agent = sourceAgent == null || sourceAgent.isBlank() ? defaultSourceAgent : sourceAgent;
            NotebookStore.WriteResult r = store.write(d, type, content, agent);
            if (r.duplicate()) {
                return "内容与已有记录重复，已跳过写入（未新增版本、未广播）。domain=" + d
                        + ", 当前 latest_version=" + r.version();
            }
            if (!r.appended()) {
                return "未写入: " + (r.error() != null ? r.error() : "未知原因") + " domain=" + d;
            }
            return "已写入 Notebook。domain=" + d + ", new_version=" + r.version()
                    + ", 已向其他 Agent 广播增量通知。";
        } catch (Exception e) {
            return "Notebook 写入失败: " + e.getMessage();
        }
    }

    @Tool(name = "notebook_get_updates", description =
            "按版本号增量读取某域名 Notebook 的新增内容：只返回 version > since_version 的条目。用于补齐错过的广播、当时未运行或重启后缺失的更新。since_version 传 0 表示读取全部。")
    public String getUpdates(
            @ToolParam(name = "domain", description = "目标域名，例如 example.com") String domain,
            @ToolParam(name = "since_version", description = "只返回版本号大于此值的增量记录；传 0 返回全部") int sinceVersion) {
        try {
            String d = NotebookStore.normalizeDomain(domain);
            List<NotebookEntry> updates = store.getUpdates(d, sinceVersion);
            int latest = store.latestVersion(d);
            if (updates.isEmpty()) {
                return "无新增内容。domain=" + d + ", since_version=" + sinceVersion
                        + ", latest_version=" + latest;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Notebook 增量更新: ").append(d).append('\n');
            sb.append("since_version=").append(sinceVersion)
                    .append(", latest_version=").append(latest)
                    .append(", 新增 ").append(updates.size()).append(" 条\n");
            for (NotebookEntry e : updates) {
                sb.append("\n--- [v").append(e.version()).append("] ").append(e.type())
                        .append(" · ").append(e.sourceAgent())
                        .append(" · ").append(e.time().replace('T', ' ')).append(" ---\n")
                        .append(e.content()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "Notebook 增量读取失败: " + e.getMessage();
        }
    }

    @Tool(name = "notebook_list", description =
            "列出所有已存在 Notebook 的域名及其最新版本号和条目数，用于了解当前已积累了哪些目标的知识。")
    public String list() {
        try {
            List<NotebookStore.DomainSummary> domains = store.listDomains();
            if (domains.isEmpty()) {
                return "notebooks 目录为空：尚无任何域名的 Notebook。";
            }
            StringBuilder sb = new StringBuilder("已存在的域名 Notebook:\n");
            for (NotebookStore.DomainSummary s : domains) {
                sb.append("- ").append(s.domain())
                        .append("（v").append(s.version())
                        .append(", ").append(s.entryCount()).append(" 条）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Notebook 列表失败: " + e.getMessage();
        }
    }
}