package com.ai.analyzer.graph;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashSet;
import java.util.Set;

import com.ai.analyzer.graph.GraphTypes.EdgeType;

/**
 * 知识图谱 Agent 工具（TODO2 §13）。
 *
 * <p>Agent 通过这 8 个只读/受控写接口访问图谱；锁、SQLite、去重、
 * 事实等级推进全部由 {@link GraphStore}/{@link GraphDatabase} 封装，
 * Agent 不需要直接管理数据库。
 */
public class GraphTool {

    private final GraphStore store;
    private final String defaultSourceAgent;

    public GraphTool(GraphStore store, String defaultSourceAgent) {
        this.store = store != null ? store : GraphStore.getInstance();
        this.defaultSourceAgent = defaultSourceAgent == null || defaultSourceAgent.isBlank()
                ? "agent" : defaultSourceAgent;
    }

    @Tool(name = "graph_get_root", description = "查询目标站点根节点（Origin），确认该目标是否已在知识图谱中建档。返回 Origin 信息。")
    public String getRoot(
            @ToolParam(name = "origin", description = "目标 Origin，如 https://target.com 或 http://target.com:8080") String origin) {
        try {
            return store.getRootText(origin);
        } catch (Exception e) {
            return "查询根节点失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_overview", description = "查看目标站点图谱概览：已积累的接口/参数/技术指纹/模块清单。开始分析一个站点前先调用它了解已知知识。")
    public String overview(
            @ToolParam(name = "root_id", description = "目标 Origin（root id），如 https://target.com:443") String rootId) {
        try {
            return store.overview(rootId);
        } catch (Exception e) {
            return "查询概览失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_get_subgraph", description =
            "查询某节点附近的局部知识子图（最核心的查询工具）。只返回与当前节点相关的局部上下文，不包含整张图。用于查看一个接口周围的参数、功能、业务实体、相关接口、模块/服务、技术与可能的 N-day。")
    public String getSubgraph(
            @ToolParam(name = "node_id", description = "起始节点 id（如接口/参数/技术节点的数字 id，可从 graph_find_interfaces / graph_get_interface 获得）") long nodeId,
            @ToolParam(name = "depth", description = "遍历深度（0~3，默认 1）", required = false) Integer depth,
            @ToolParam(name = "relation_types", description = "可选：只包含这些关系类型（如 CRUD_SIBLING,SAME_ENTITY,RELATED_TO）；留空为全部", required = false) String relationTypes,
            @ToolParam(name = "include_inferred", description = "是否包含 Agent 推测(INFERRED)内容，默认 true", required = false) Boolean includeInferred) {
        try {
            Set<EdgeType> types = parseTypes(relationTypes);
            return store.subgraph(nodeId, depth != null ? depth : 1, types,
                    includeInferred == null || includeInferred, 60);
        } catch (Exception e) {
            return "查询子图失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_find_interfaces", description = "按关键词/模块/业务实体查找目标站点下的接口。用于探索站点时发现相关接口。")
    public String findInterfaces(
            @ToolParam(name = "root_id", description = "目标 Origin（即 root id，如 https://target.com:443）") String rootId,
            @ToolParam(name = "keyword", description = "关键词（匹配路径/名称），可选", required = false) String keyword,
            @ToolParam(name = "module", description = "模块名过滤（如 order/user/admin），可选", required = false) String module,
            @ToolParam(name = "entity", description = "业务实体过滤（如 Order/User），可选", required = false) String entity) {
        try {
            return store.findInterfaces(rootId, keyword, module, entity);
        } catch (Exception e) {
            return "查找接口失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_get_interface", description = "查看某个接口的完整上下文：参数、功能推测、业务实体、相关接口、模块/服务、技术与 N-day 关联。")
    public String getInterface(
            @ToolParam(name = "interface_id", description = "接口节点 id") long interfaceId) {
        try {
            return store.subgraph(interfaceId, 1, null, true, 80);
        } catch (Exception e) {
            return "查询接口失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_upsert_observation", description =
            "把确定观察到的事实写入图谱（OBSERVED）。url 形式可自动创建 Origin+接口；或提供 root + type/name 创建其它类型节点。source 默认当前 Agent。")
    public String upsertObservation(
            @ToolParam(name = "type", description = "节点类型：INTERFACE / PARAMETER / FUNCTION / BUSINESS_ENTITY / BUSINESS_LOGIC / MODULE / SERVICE / TECHNOLOGY / VERSION / VULNERABILITY") String type,
            @ToolParam(name = "name", description = "节点名（INTERFACE 时可用完整 URL 自动解析）") String name,
            @ToolParam(name = "root", description = "目标 Origin（root id）或包含协议的完整 URL") String root,
            @ToolParam(name = "source", description = "来源标识，可留空（默认当前 Agent）", required = false) String source) {
        try {
            String src = source == null || source.isBlank() ? defaultSourceAgent : source;
            return store.upsertAgentObservation(type, name, root, src, null);
        } catch (Exception e) {
            return "写入观察失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_add_hypothesis", description =
            "为某节点添加一条 Agent 推测（INFERRED，必须标记推测而非事实）：subject →relation→ target。target 不存在会自动创建为推测节点。")
    public String addHypothesis(
            @ToolParam(name = "subject_node_id", description = "主体节点 id（如接口节点）") long subjectNodeId,
            @ToolParam(name = "relation", description = "关系类型：IMPLEMENTS_FUNCTION/OPERATES_ON/BELONGS_TO/RELATED_TO/SAME_ENTITY/CRUD_SIBLING/USES_TECHNOLOGY 等") String relation,
            @ToolParam(name = "target_type", description = "目标节点类型：FUNCTION/BUSINESS_ENTITY/MODULE/SERVICE/TECHNOLOGY/VULNERABILITY 等") String targetType,
            @ToolParam(name = "target_name", description = "目标节点名（如 QueryOrderDetail / Order）") String targetName,
            @ToolParam(name = "confidence", description = "置信度 0~1，默认 0.7", required = false) Double confidence,
            @ToolParam(name = "reason", description = "推测依据（简短）", required = false) String reason) {
        try {
            return store.addHypothesis(subjectNodeId, relation, targetType, targetName,
                    confidence == null ? 0.7 : Math.max(0, Math.min(1, confidence)), reason);
        } catch (Exception e) {
            return "添加推测失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_confirm_hypothesis", description = "把一条 INFERRED 推测边标记为 CONFIRMED（已用实际请求验证），会附加证据。")
    public String confirmHypothesis(
            @ToolParam(name = "edge_id", description = "推测边的 id（graph_add_hypothesis 返回）") long edgeId,
            @ToolParam(name = "evidence", description = "验证证据摘要（如返回的数据/响应特征）", required = false) String evidence,
            @ToolParam(name = "source", description = "来源标识，可留空", required = false) String source) {
        try {
            String src = source == null || source.isBlank() ? defaultSourceAgent : source;
            return store.confirmHypothesis(edgeId, evidence, src);
        } catch (Exception e) {
            return "确认推测失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_reject_hypothesis", description = "推翻一条推测（标记 REJECTED 而不是删除，保留推理历史）。")
    public String rejectHypothesis(
            @ToolParam(name = "edge_id", description = "要推翻的边 id") long edgeId) {
        try {
            return store.rejectHypothesis(edgeId);
        } catch (Exception e) {
            return "推翻推测失败: " + e.getMessage();
        }
    }

    private static Set<EdgeType> parseTypes(String relationTypes) {
        if (relationTypes == null || relationTypes.isBlank()) {
            return null;
        }
        Set<EdgeType> set = new LinkedHashSet<>();
        for (String part : relationTypes.split("[,; ]")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                set.add(EdgeType.valueOf(t.toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        return set.isEmpty() ? null : set;
    }
}