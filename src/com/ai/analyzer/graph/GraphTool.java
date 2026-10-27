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
            "把确定观察到的事实写入图谱（OBSERVED）。INTERFACE 时 name 传完整 URL 或 \"METHOD /path\"，root 传 Origin；"
                    + "其它类型用 root + type/name 创建节点。source 默认当前 Agent。")
    public String upsertObservation(
            @ToolParam(name = "type", description = "节点类型：INTERFACE / PARAMETER / FUNCTION / BUSINESS_ENTITY / BUSINESS_LOGIC / MODULE / SERVICE / TECHNOLOGY / VERSION / FEATURE / BACKEND_HYPOTHESIS / RESOURCE / VULNERABILITY") String type,
            @ToolParam(name = "name", description = "节点名；INTERFACE 用完整 URL（如 https://target.com/api/x）或 \"POST /api/x\"") String name,
            @ToolParam(name = "root", description = "目标 Origin（root id，如 https://target.com:443）；INTERFACE 也可直接传完整 URL") String root,
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

    @Tool(name = "graph_upsert_fingerprint", description =
            "登记一条技术指纹（OBSERVED）。适用于从响应头/Cookie/HTML/JS/错误页识别出的前端框架、后端框架、服务器、中间件。"
                    + "必须尽量提供 evidence（命中的原文片段），便于后续回溯；可选记录版本号。category 推荐 frontend/backend/server/middleware/library。")
    public String upsertFingerprint(
            @ToolParam(name = "technology", description = "技术名称，如 Nginx / Spring Boot / Vue / Laravel") String technology,
            @ToolParam(name = "root", description = "目标 Origin（root id），可选；若提供 interface_id 可留空", required = false) String root,
            @ToolParam(name = "interface_id", description = "关联接口节点 id，可选", required = false) Long interfaceId,
            @ToolParam(name = "category", description = "分类：frontend/backend/server/middleware/library，可选", required = false) String category,
            @ToolParam(name = "version", description = "版本号，可选", required = false) String version,
            @ToolParam(name = "evidence", description = "指纹证据（命中的 header/Cookie/HTML/JS 片段），强烈建议填写", required = false) String evidence,
            @ToolParam(name = "confidence", description = "置信度 0~1，默认 0.8", required = false) Double confidence) {
        try {
            return store.upsertFingerprint(interfaceId == null ? 0 : interfaceId, root, technology,
                    category, version, evidence, confidence == null ? 0.8 : confidence);
        } catch (Exception e) {
            return "登记指纹失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_add_feature", description =
            "登记一个可被漏洞化的能力特征（如 actuator/swagger/graphql/upload/jwt/oauth/websocket），"
                    + "可关联接口并映射到漏洞类别（SUGGESTS）。用于“特征 → 漏洞分析”。")
    public String addFeature(
            @ToolParam(name = "feature", description = "特征名，如 spring_actuator / swagger_ui / graphql / file_upload") String feature,
            @ToolParam(name = "root", description = "目标 Origin（root id），可选；若提供 interface_id 可留空", required = false) String root,
            @ToolParam(name = "interface_id", description = "关联接口节点 id，可选", required = false) Long interfaceId,
            @ToolParam(name = "vuln_class", description = "候选漏洞类别，如 未授权访问 / 信息泄露 / 文件上传 / IDOR，可选", required = false) String vulnClass,
            @ToolParam(name = "evidence", description = "特征证据（命中的路径/页面片段）", required = false) String evidence,
            @ToolParam(name = "confidence", description = "置信度 0~1，默认 0.7", required = false) Double confidence) {
        try {
            return store.addFeature(interfaceId == null ? 0 : interfaceId, root, feature, vulnClass,
                    evidence, confidence == null ? 0.7 : confidence);
        } catch (Exception e) {
            return "登记特征失败: " + e.getMessage();
        }
    }

    @Tool(name = "graph_add_backend_hypothesis", description =
            "登记一条后端实现推测（INFERRED），如 Spring Boot / Laravel / ThinkPHP / Node+Express。"
                    + "必须提供 evidence（路径形态、错误串、Cookie 名、JS SDK 等依据），便于后续确认或推翻。")
    public String addBackendHypothesis(
            @ToolParam(name = "hypothesis", description = "后端实现猜测，如 Spring Boot / Laravel / Node+Express") String hypothesis,
            @ToolParam(name = "root", description = "目标 Origin（root id），可选；若提供 interface_id 可留空", required = false) String root,
            @ToolParam(name = "interface_id", description = "关联接口节点 id，可选", required = false) Long interfaceId,
            @ToolParam(name = "evidence", description = "推断依据（路径/错误串/Cookie/JS 片段）", required = false) String evidence,
            @ToolParam(name = "confidence", description = "置信度 0~1，默认 0.6", required = false) Double confidence) {
        try {
            return store.addBackendHypothesis(interfaceId == null ? 0 : interfaceId, root, hypothesis,
                    evidence, confidence == null ? 0.6 : confidence);
        } catch (Exception e) {
            return "登记后端推测失败: " + e.getMessage();
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