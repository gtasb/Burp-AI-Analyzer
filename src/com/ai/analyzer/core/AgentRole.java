package com.ai.analyzer.core;

import java.util.Locale;

/**
 * 交互式 agent 的角色。每个角色拥有独立的运行时、session、单航班锁和（除侧栏外的）状态目录。
 */
public enum AgentRole {
    /** 请求分析页的主动扫描 agent：只读图谱，产出交给整理 agent */
    ACTIVE,
    /** Burp 侧栏聊天 agent：行为保持原样，拥有长期记忆 */
    SIDEBAR,
    /** 证据整理与攻击向量指引 agent：写图谱、出报告/POC，不与其它 agent 共享上下文 */
    ORGANIZER;

    /** Harness agent 名，同时决定状态键与 workspace/agents/&lt;name&gt; 目录。 */
    public String agentName() {
        return "burp-ai-" + name().toLowerCase(Locale.ROOT);
    }

    public String sessionPrefix() {
        return name().toLowerCase(Locale.ROOT) + "-session-";
    }
}
