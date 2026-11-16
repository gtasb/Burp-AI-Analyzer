package com.ai.analyzer.core;

import java.util.List;
import java.util.Objects;

/**
 * 一次需要分发给多个 agent 客户端的配置快照。
 *
 * <p>历史上「把同一份配置推给主动/侧栏/整理/被动」靠 4 处手抄的 setter 序列
 * （syncApiConfigToPassiveScan / applyConfigTo / updateApiClientConfigForAnalysis /
 * applySelectedApiProfile），每处 30-56 行、共 71 次手写 setter 调用——审计中已发现
 * 两处漂移 bug（applySettings 与 saveSettings 不对称、passive 侧漏字段）。
 * 本类把「配置的完整形态」收敛为一个值对象：UI 只需构建快照，再由各客户端的
 * {@code applySnapshot} 统一消费，新增字段只改两处（此处 + apply 实现），
 * 不可能在第 3 个客户端上漏抄。
 */
public record AgentConnectionConfig(
        String apiUrl,
        String apiKey,
        String model,
        String maxTokens,
        AgentConfig.ApiProvider apiProvider,
        boolean enableSearch,
        String searchMode,
        String tavilyApiKey,
        String tavilyBaseUrl,
        String googleSearchApiKey,
        String googleSearchCsi,
        boolean enableMcp,
        boolean enableBurpMcpSendTools,
        String burpMcpUrl,
        String burpMcpAuthorization,
        boolean enableRagMcp,
        String ragMcpUrl,
        String ragMcpDocumentsPath,
        boolean enableChromeMcp,
        String chromeMcpUrl,
        boolean enableFileSystemAccess,
        String customMcpConfigJson,
        boolean enableCliTool,
        boolean enableFileSystemSandbox,
        String cliWhitelist,
        String cliToolPrompt,
        String activeMaxIters,
        boolean enablePlanMode,
        boolean enableSkills,
        String skillsDirectoryPath,
        String customActiveSystemPrompt,
        String workplaceDirectoryPath) {

    /** 从 AgentConfig 生成快照（单一事实来源）。 */
    public static AgentConnectionConfig from(AgentConfig config) {
        Objects.requireNonNull(config, "config");
        return new AgentConnectionConfig(
                config.getApiUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getMaxTokens(),
                config.getApiProvider(),
                config.isEnableSearch(),
                config.getSearchMode(),
                config.getTavilyApiKey(),
                config.getTavilyBaseUrl(),
                config.getGoogleSearchApiKey(),
                config.getGoogleSearchCsi(),
                config.isEnableMcp(),
                config.isEnableBurpMcpSendTools(),
                config.getBurpMcpUrl(),
                config.getBurpMcpAuthorization(),
                config.isEnableRagMcp(),
                config.getRagMcpUrl(),
                config.getRagMcpDocumentsPath(),
                config.isEnableChromeMcp(),
                config.getChromeMcpUrl(),
                config.isEnableFileSystemAccess(),
                config.getCustomMcpConfigJson(),
                config.isEnableCliTool(),
                config.isEnableFileSystemSandbox(),
                config.getCliWhitelist(),
                config.getCliToolPrompt(),
                String.valueOf(config.getActiveMaxIters()),
                config.isEnablePlanMode(),
                config.isEnableSkills(),
                config.getSkillsDirectoryPath(),
                config.getCustomSystemPrompt(),
                config.getWorkplaceDirectoryPath());
    }

    public boolean differsFrom(AgentConnectionConfig other) {
        return other == null || !equals(other);
    }
}
