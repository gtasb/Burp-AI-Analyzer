package com.ai.analyzer.skills;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

/**
 * Skill 工具提供者
 * 将 SkillTool 定义转换为 LangChain4j 可调用的工具
 * 
 * 由于 LangChain4j 的 @Tool 注解是静态的，我们需要动态地将 SkillTool 
 * 转换为可调用的方法。这个类提供了一个通用的执行入口。
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
    
    public void setApi(MontoyaApi api) {
        this.api = api;
        this.executor.setApi(api);
    }
    
    /**
     * 执行指定的 Skill 工具
     * 
     * @param toolName 工具名称（可以是短名称或完整名称 skill_xxx_yyy）
     * @param parameters JSON 格式的参数字符串，如 {"target": "192.168.1.1", "ports": "1-1000"}
     * @return 工具执行结果
     */
    @Tool(name = "execute_skill_tool", value = "执行用户自定义的 Skill 工具。" +
            "首先使用 list_skill_tools 查看可用的工具列表，然后调用此工具执行。" +
            "参数 parameters 是 JSON 格式的参数映射。")
    public String executeSkillTool(
            @P("工具名称，从 list_skill_tools 获取") String toolName,
            @P("JSON 格式的参数，如 {\"target\": \"192.168.1.1\", \"ports\": \"1-1000\"}") String parameters) {
        
        if (toolName == null || toolName.trim().isEmpty()) {
            return "错误：未指定工具名称";
        }
        
        SkillTool tool = skillManager.getToolByName(toolName.trim());
        if (tool == null) {
            return "错误：未找到工具 '" + toolName + "'。请先使用 list_skill_tools 查看可用的工具。";
        }
        
        // 解析参数
        Map<String, String> paramMap = parseJsonParameters(parameters);
        
        // 执行工具
        try {
            SkillToolExecutor.ExecutionResult result = executor.execute(tool, paramMap);
            return result.toAIReadableFormat();
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 列出所有可用的 Skill 工具
     */
    @Tool(name = "list_skill_tools", value = "列出所有用户自定义的可执行 Skill 工具及其参数。" +
            "在调用 execute_skill_tool 之前，先使用此工具了解有哪些可用的工具。")
    public String listSkillTools() {
        List<SkillTool> tools = skillManager.getAllEnabledTools();
        
        if (tools.isEmpty()) {
            return "当前没有可用的 Skill 工具。\n" +
                   "提示：用户需要在 Skills 配置中启用包含工具定义的技能。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用的 Skill 工具\n\n");
        
        for (SkillTool tool : tools) {
            sb.append("### ").append(tool.getFullName()).append("\n");
            sb.append("**描述**: ").append(tool.getDescription()).append("\n");
            sb.append("**命令**: `").append(tool.getCommand()).append("`\n");
            
            if (tool.getArgs() != null && !tool.getArgs().isEmpty()) {
                sb.append("**参数模板**: `").append(tool.getArgs()).append("`\n");
            }
            
            if (!tool.getParameters().isEmpty()) {
                sb.append("**参数**:\n");
                for (SkillTool.ToolParameter param : tool.getParameters()) {
                    sb.append("- `").append(param.getName()).append("`");
                    if (param.isRequired()) {
                        sb.append(" (必需)");
                    } else if (param.getDefaultValue() != null) {
                        sb.append(" (默认: ").append(param.getDefaultValue()).append(")");
                    } else {
                        sb.append(" (可选)");
                    }
                    sb.append(": ").append(param.getDescription()).append("\n");
                }
            }
            
            sb.append("\n");
        }
        
        sb.append("---\n");
        sb.append("使用方法: 调用 `execute_skill_tool` 并传入工具名称和 JSON 格式的参数。\n");
        sb.append("示例: execute_skill_tool(\"skill_network_nmap_scan\", \"{\\\"target\\\": \\\"192.168.1.1\\\", \\\"ports\\\": \\\"1-1000\\\"}\")\n");
        
        return sb.toString();
    }
    
    /**
     * 解析 JSON 格式的参数字符串
     */
    private Map<String, String> parseJsonParameters(String jsonParams) {
        Map<String, String> params = new HashMap<>();
        
        if (jsonParams == null || jsonParams.trim().isEmpty()) {
            return params;
        }
        
        String json = jsonParams.trim();
        
        // 移除外层花括号
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
        }
        
        // 简单的 JSON 解析（不依赖外部库）
        // 支持格式: "key1": "value1", "key2": "value2"
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                
                // 移除引号
                if (key.startsWith("\"") && key.endsWith("\"")) {
                    key = key.substring(1, key.length() - 1);
                }
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                
                // 处理转义字符
                value = value.replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t");
                
                params.put(key, value);
            }
        }
        
        return params;
    }
    
    /**
     * 获取执行器实例（用于外部直接调用）
     */
    public SkillToolExecutor getExecutor() {
        return executor;
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
