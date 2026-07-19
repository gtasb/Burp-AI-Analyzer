package com.ai.analyzer.skills;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 二进制/脚本执行工具提供者
 *
 * <p>仅负责 SKILL.md 中自定义 {@code tools:} 段定义的可执行工具。
 * {@code activate_skill} 和 {@code read_skill_resource} 由官方
 * {@link dev.langchain4j.skills.Skills#toolProvider()} 提供。
 *
 * <p>通过 {@code @Tool} 注解注册到 AiServices.builder().tools(...)，
 * 与官方 Skills ToolProvider 并行工作。
 *
 * @see <a href="https://docs.langchain4j.dev/tutorials/skills">LangChain4j Skills</a>
 */
public class SkillToolsProvider {

    private final SkillManager skillManager;
    private final SkillToolExecutor executor;
    private MontoyaApi api;

    public SkillToolsProvider(SkillManager skillManager) {
        this.skillManager = skillManager;
        this.executor = new SkillToolExecutor();
    }

    public SkillToolsProvider(SkillManager skillManager, MontoyaApi api) {
        this.skillManager = skillManager;
        this.executor = new SkillToolExecutor(api);
        this.api = api;
    }

    public SkillToolsProvider(SkillManager skillManager, MontoyaApi api, ExecutionPolicy policy) {
        this.skillManager = skillManager;
        this.executor = new SkillToolExecutor(api, policy);
        this.api = api;
    }

    public void setApi(MontoyaApi api) {
        this.api = api;
        this.executor.setApi(api);
    }

    public SkillToolExecutor getExecutor() { return executor; }

    // ======================== execute_skill_tool ========================

    /**
     * 执行用户自定义的 Skill 工具（二进制文件、脚本等）。
     * 所有执行均经过 {@link ExecutionPolicy} 安全校验。
     */
    @Tool(name = "execute_skill_tool", value = "执行用户自定义的 Skill 工具（可执行文件、脚本等）。" +
            "先用 activate_skill 了解工具列表和参数，再调用此工具执行。" +
            "所有执行均经过安全策略校验。")
    public String executeSkillTool(
            @P("工具名称（从 activate_skill 或 list_skill_tools 获取）") String toolName,
            @P("JSON 格式的参数，如 {\"target\": \"192.168.1.1\", \"ports\": \"1-1000\"}") String parameters) {

        if (toolName == null || toolName.isBlank()) {
            return "错误：未指定工具名称。";
        }

        SkillTool tool = skillManager.getToolByName(toolName.trim());
        if (tool == null) {
            return "错误：未找到工具 '" + toolName + "'。请先使用 activate_skill 或 list_skill_tools 查看可用工具。";
        }

        Map<String, String> paramMap = parseJsonParameters(parameters);

        try {
            SkillToolExecutor.ExecutionResult result = executor.execute(tool, paramMap);
            return result.toAIReadableFormat();
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }

    // ======================== list_skill_tools ========================

    /**
     * 列出所有已启用技能中定义的可执行工具。
     */
    @Tool(name = "list_skill_tools", value = "列出所有已启用技能中定义的可执行工具及其参数说明。")
    public String listSkillTools() {
        List<SkillTool> tools = skillManager.getAllEnabledTools();

        if (tools.isEmpty()) {
            return "当前没有可用的 Skill 工具。请先用 activate_skill 激活包含工具定义的技能。";
        }

        StringBuilder sb = new StringBuilder("## 可用的 Skill 工具\n\n");
        for (SkillTool tool : tools) {
            sb.append("### ").append(tool.getFullName()).append("\n");
            sb.append("**描述**: ").append(tool.getDescription()).append("\n");
            sb.append("**命令**: `").append(tool.getCommand()).append("`\n");
            if (tool.getArgs() != null && !tool.getArgs().isEmpty()) {
                sb.append("**参数模板**: `").append(tool.getArgs()).append("`\n");
            }
            if (!tool.getParameters().isEmpty()) {
                sb.append("**参数**:\n");
                for (SkillTool.ToolParameter p : tool.getParameters()) {
                    sb.append("- `").append(p.getName()).append("`");
                    if (p.isRequired()) sb.append(" (必需)");
                    else if (p.getDefaultValue() != null) sb.append(" (默认: ").append(p.getDefaultValue()).append(")");
                    else sb.append(" (可选)");
                    sb.append(": ").append(p.getDescription()).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("---\n");
        sb.append("使用: 调用 `execute_skill_tool` 并传入工具名称和 JSON 参数。\n");
        return sb.toString();
    }

    // ======================== JSON Parsing ========================

    Map<String, String> parseJsonParameters(String jsonParams) {
        Map<String, String> params = new HashMap<>();
        if (jsonParams == null || jsonParams.isBlank()) return params;
        String json = jsonParams.trim();
        if (json.startsWith("{") && json.endsWith("}")) json = json.substring(1, json.length() - 1);
        for (String pair : json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = stripQuotes(kv[0]);
                String value = stripQuotes(kv[1]);
                value = value.replace("\\\"", "\"").replace("\\\\", "\\")
                        .replace("\\n", "\n").replace("\\t", "\t");
                params.put(key, value);
            }
        }
        return params;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    public void shutdown() { /* no-op */ }
}
