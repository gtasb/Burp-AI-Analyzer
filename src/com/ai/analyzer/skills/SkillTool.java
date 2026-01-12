package com.ai.analyzer.skills;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 表示一个 Skill 中定义的可执行工具
 * 
 * 在 SKILL.md 中定义格式：
 * ---
 * name: network-scanner
 * description: 网络扫描技能
 * tools:
 *   - name: nmap_scan
 *     description: 使用 nmap 进行端口扫描
 *     command: "C:/Tools/nmap/nmap.exe"
 *     args: "-sV -p {ports} {target}"
 *     working_dir: "C:/Tools/nmap"
 *     timeout: 300
 *     parameters:
 *       - name: target
 *         type: string
 *         description: 目标IP或域名
 *         required: true
 *       - name: ports
 *         type: string
 *         description: 端口范围
 *         required: false
 *         default: "1-1000"
 * ---
 */
public class SkillTool implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String name;           // 工具名称（AI调用时使用）
    private String description;    // 工具描述（告诉AI这个工具做什么）
    private String command;        // 可执行文件路径或命令
    private String args;           // 参数模板，使用 {param_name} 作为占位符
    private String workingDir;     // 工作目录（可选）
    private int timeout = 120;     // 执行超时（秒），默认120秒
    private List<ToolParameter> parameters = new ArrayList<>(); // 参数定义
    private String skillName;      // 所属技能名称
    
    public SkillTool() {}
    
    public SkillTool(String name, String description, String command) {
        this.name = name;
        this.description = description;
        this.command = command;
    }
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getCommand() {
        return command;
    }
    
    public void setCommand(String command) {
        this.command = command;
    }
    
    public String getArgs() {
        return args;
    }
    
    public void setArgs(String args) {
        this.args = args;
    }
    
    public String getWorkingDir() {
        return workingDir;
    }
    
    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }
    
    public int getTimeout() {
        return timeout;
    }
    
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
    
    public List<ToolParameter> getParameters() {
        return parameters;
    }
    
    public void setParameters(List<ToolParameter> parameters) {
        this.parameters = parameters;
    }
    
    public void addParameter(ToolParameter param) {
        this.parameters.add(param);
    }
    
    public String getSkillName() {
        return skillName;
    }
    
    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }
    
    /**
     * 获取完整的工具名称（包含技能前缀，避免冲突）
     */
    public String getFullName() {
        if (skillName != null && !skillName.isEmpty()) {
            return "skill_" + skillName + "_" + name;
        }
        return "skill_" + name;
    }
    
    /**
     * 构建实际执行的命令行参数
     * 将参数模板中的占位符替换为实际值
     */
    public String buildArgs(Map<String, String> paramValues) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        
        String result = args;
        
        // 替换占位符
        for (ToolParameter param : parameters) {
            String placeholder = "{" + param.getName() + "}";
            String value = paramValues.get(param.getName());
            
            if (value == null || value.isEmpty()) {
                // 使用默认值
                value = param.getDefaultValue();
            }
            
            if (value != null) {
                result = result.replace(placeholder, value);
            } else if (param.isRequired()) {
                throw new IllegalArgumentException("缺少必需参数: " + param.getName());
            } else {
                // 移除未提供的可选参数占位符
                result = result.replace(placeholder, "");
            }
        }
        
        // 清理多余空格
        return result.replaceAll("\\s+", " ").trim();
    }
    
    /**
     * 验证参数是否完整
     */
    public void validateParameters(Map<String, String> paramValues) {
        for (ToolParameter param : parameters) {
            if (param.isRequired()) {
                String value = paramValues.get(param.getName());
                if (value == null || value.trim().isEmpty()) {
                    if (param.getDefaultValue() == null || param.getDefaultValue().isEmpty()) {
                        throw new IllegalArgumentException("缺少必需参数: " + param.getName());
                    }
                }
            }
        }
    }
    
    @Override
    public String toString() {
        return "SkillTool{" +
                "name='" + name + '\'' +
                ", command='" + command + '\'' +
                ", parameters=" + parameters.size() +
                '}';
    }
    
    /**
     * 工具参数定义
     */
    public static class ToolParameter implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String name;           // 参数名
        private String type = "string"; // 参数类型（string, number, boolean）
        private String description;    // 参数描述
        private boolean required = true; // 是否必需
        private String defaultValue;   // 默认值
        
        public ToolParameter() {}
        
        public ToolParameter(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public ToolParameter(String name, String description, boolean required) {
            this.name = name;
            this.description = description;
            this.required = required;
        }
        
        // Getters and Setters
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public boolean isRequired() {
            return required;
        }
        
        public void setRequired(boolean required) {
            this.required = required;
        }
        
        public String getDefaultValue() {
            return defaultValue;
        }
        
        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }
        
        @Override
        public String toString() {
            return name + (required ? "*" : "") + ":" + type;
        }
    }
}
