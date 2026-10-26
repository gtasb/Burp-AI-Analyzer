package com.ai.analyzer.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * URL-Centric 资产知识图谱的类型定义（TODO2 §4/§5/§6）。
 *
 * <p>核心约定：程序事实与 Agent 推测严格区分（status + confidence），
 * canonical key 一律由程序生成，LLM 只提供 name/relation/置信度。
 */
public final class GraphTypes {

    private GraphTypes() {}

    /** 事实等级（§6）：观察 / 确定性推导 / Agent 推测 / 验证确认 / 推翻。 */
    public enum FactStatus {
        OBSERVED(5), CONFIRMED(4), DERIVED(3), INFERRED(2), REJECTED(1);

        public final int rank;

        FactStatus(int rank) {
            this.rank = rank;
        }

        /** 状态推进：取更高等级；REJECTED 只能由更高事实覆盖（如后续 OBSERVED）。 */
        public static FactStatus promote(FactStatus existing, FactStatus incoming) {
            if (existing == null) {
                return incoming == null ? INFERRED : incoming;
            }
            if (existing == REJECTED && incoming != OBSERVED && incoming != CONFIRMED) {
                return existing; // 已推翻的推测不再被低置信度覆盖
            }
            return incoming.rank > existing.rank ? incoming : existing;
        }
    }

    /** 节点类型（§4）。 */
    public enum NodeType {
        ORIGIN, INTERFACE, PARAMETER, FUNCTION, BUSINESS_ENTITY, BUSINESS_LOGIC,
        TECHNOLOGY, VERSION, MODULE, SERVICE, VULNERABILITY,
        /** 前端资源：JS / CSS / source map / API 文档等 */
        RESOURCE,
        /** 指纹证据：命中的 header / cookie / HTML / JS / 错误页片段 */
        EVIDENCE,
        /** 可被漏洞化的能力特征：actuator / swagger / upload / jwt ... */
        FEATURE,
        /** 后端实现推测：Spring Boot / Laravel / Node+Express ... */
        BACKEND_HYPOTHESIS,
        /** 漏洞类别：SQLi / IDOR / 未授权 ... */
        VULN_CLASS,
        /** 已测试但未确认的候选漏洞 */
        CANDIDATE,
        /** 分析事实锚点：一次完成的扫描/验证在资产上的落点（沿用 ARTEX fact-anchor 语义） */
        ANALYSIS_FACT
    }

    /** 边类型（§5），第一版只实现以下集合。 */
    public enum EdgeType {
        HAS_INTERFACE, HAS_PARAMETER, IMPLEMENTS_FUNCTION, OPERATES_ON,
        BELONGS_TO, CONTAINS, PAID_BY,
        RELATED_TO, SAME_MODULE, SAME_ENTITY, SAME_FUNCTION, PRECEDES, FOLLOWS,
        ALTERNATIVE, CRUD_SIBLING,
        RUNS_ON, USES_TECHNOLOGY, HAS_VERSION, AFFECTED_BY, SUPPORTED_BY,
        /** Origin → 前端资源 */
        LOADS,
        /** 前端资源 → 它调用的接口（从 JS 中提取） */
        CALLS,
        /** 证据 → 技术/特征/假设 */
        INDICATES,
        /** 证据 → 假设（支持） */
        SUPPORTS,
        /** 证据/测试结果 → 假设（推翻） */
        REFUTES,
        /** 特征/技术 → 漏洞类别 */
        SUGGESTS,
        /** 假设 → 支撑它的技术/证据 */
        DERIVED_FROM,
        /** 测试结果 → 假设/候选漏洞（确认） */
        CONFIRMS,
        /** 测试结果 → 假设/候选漏洞（否定） */
        REJECTS,
        /** 分析事实 → 资产（覆盖锚定） */
        COVERS,
        /** 域名 → IP */
        RESOLVES_TO,
        /** IP/服务 → 应用 */
        HOSTS,
        /** 应用/服务 → 接口 */
        EXPOSES
    }

    /** 图谱节点。 */
    public record Node(
            long id,
            String rootId,
            NodeType type,
            String name,
            String canonicalKey,
            FactStatus status,
            double confidence,
            String sourceType,
            String sourceId,
            Map<String, Object> properties,
            String createdAt,
            String updatedAt) {

        public Node withStatus(FactStatus s, double c) {
            LinkedHashMap<String, Object> p = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
            return new Node(id, rootId, type, name, canonicalKey, s, c, sourceType, sourceId, p, createdAt, updatedAt);
        }

        public Object prop(String key) {
            return properties == null ? null : properties.get(key);
        }
    }

    /** 图谱边。 */
    public record Edge(
            long id,
            String rootId,
            long sourceNodeId,
            long targetNodeId,
            EdgeType relationType,
            FactStatus status,
            double confidence,
            String sourceType,
            String sourceId,
            Map<String, Object> properties,
            String createdAt,
            String updatedAt) {

        public Edge withStatus(FactStatus s, double c) {
            LinkedHashMap<String, Object> p = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
            return new Edge(id, rootId, sourceNodeId, targetNodeId, relationType, s, c, sourceType, sourceId, p, createdAt, updatedAt);
        }
    }

    /** 轻量证据（§4.12）：只存来源引用与一句话摘要，不复制完整报文。 */
    public record Evidence(
            long id,
            String rootId,
            Long nodeId,
            String sourceType,
            String sourceId,
            String summary,
            Map<String, Object> metadata,
            String createdAt) {
    }
}