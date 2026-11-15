package com.ai.analyzer.graph;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashSet;
import java.util.Set;

import com.ai.analyzer.graph.GraphTypes.EdgeType;

/**
 * 只读知识图谱工具：给主动扫描 agent 使用。
 *
 * <p>主动 agent 不再写图（证据整理由 organizer agent 负责），但仍需要读取
 * 资产概览、接口上下文、节点评估和人类笔记来指导测试。
 */
public class GraphReadTool {

    private final GraphStore store;

    public GraphReadTool(GraphStore store, String agentName) {
        this.store = store != null ? store : GraphStore.getInstance();
    }

    @Tool(name = "graph_get_root", description = "查询目标站点根节点（Origin），确认该目标是否已在知识图谱中建档。")
    public String getRoot(
            @ToolParam(name = "origin", description = "目标 Origin，如 https://target.com 或 http://target.com:8080") String origin) {
        try {
            return store.getRootText(origin);
        } catch (Exception e) {
            return "查询根节点失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_overview", description = "查看目标站点图谱概览：已积累的接口/参数/技术指纹/模块清单与最近的站点笔记。开始分析一个站点前先调用。")
    public String overview(
            @ToolParam(name = "root_id", description = "目标 Origin（root id），如 https://target.com:443") String rootId) {
        try {
            return store.overview(rootId);
        } catch (Exception e) {
            return "查询概览失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_get_subgraph", description =
            "查询某节点附近的局部知识子图。返回参数、功能、业务实体、相关接口、模块/服务、技术与 N-day 关联。")
    public String getSubgraph(
            @ToolParam(name = "node_id", description = "起始节点 id") long nodeId,
            @ToolParam(name = "depth", description = "遍历深度（0~3，默认 1）", required = false) Integer depth,
            @ToolParam(name = "relation_types", description = "可选：只包含这些关系类型", required = false) String relationTypes,
            @ToolParam(name = "include_inferred", description = "是否包含推测内容，默认 true", required = false) Boolean includeInferred) {
        try {
            Set<EdgeType> types = parseTypes(relationTypes);
            return store.subgraph(nodeId, depth != null ? depth : 1, types,
                    includeInferred == null || includeInferred, 60);
        } catch (Exception e) {
            return "查询子图失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_find_interfaces", description = "按关键词/模块/业务实体查找目标站点下的接口。")
    public String findInterfaces(
            @ToolParam(name = "root_id", description = "目标 Origin（root id）") String rootId,
            @ToolParam(name = "keyword", description = "关键词（匹配路径/名称），可选", required = false) String keyword,
            @ToolParam(name = "module", description = "模块名过滤，可选", required = false) String module,
            @ToolParam(name = "entity", description = "业务实体过滤，可选", required = false) String entity) {
        try {
            return store.findInterfaces(rootId, keyword, module, entity);
        } catch (Exception e) {
            return "查找接口失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_get_interface", description = "查看某个接口的完整上下文：参数、功能推测、业务实体、相关接口、技术关联与节点评估。")
    public String getInterface(
            @ToolParam(name = "interface_id", description = "接口节点 id") long interfaceId) {
        try {
            return store.subgraph(interfaceId, 1, null, true, 80);
        } catch (Exception e) {
            return "查询接口失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_get_notes", description =
            "读取某 Origin 的笔记：人类的指令/牵引、其他 agent 的发现与结论。since_version 传 0 读全部。")
    public String getNotes(
            @ToolParam(name = "root_id", description = "目标 Origin（root id）") String root,
            @ToolParam(name = "since_version", description = "只返回版本号大于此值的笔记；0 表示全部", required = false) Integer sinceVersion) {
        try {
            int since = sinceVersion == null ? 0 : sinceVersion;
            java.util.List<GraphTypes.Node> notes = store.listNotes(root, since);
            if (notes.isEmpty()) {
                return "无笔记。root=" + root + ", since_version=" + since;
            }
            StringBuilder sb = new StringBuilder("笔记 root=").append(root)
                    .append(", since_version=").append(since)
                    .append(", 共 ").append(notes.size()).append(" 条：\n");
            for (var n : notes) {
                sb.append("\n--- [v").append(GraphDatabase.noteVersion(n))
                        .append(" · ").append(n.prop("kind"))
                        .append(" · ").append(n.prop("author")).append("] ---\n")
                        .append(n.prop("content")).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取笔记失败: " + e.getMessage();
        }
    }

    private static Set<EdgeType> parseTypes(String relationTypes) {
        if (relationTypes == null || relationTypes.isBlank()) {
            return null;
        }
        Set<EdgeType> set = new LinkedHashSet<>();
        for (String part : relationTypes.split("[,; ]")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                set.add(EdgeType.valueOf(t.toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        return set.isEmpty() ? null : set;
    }
}
