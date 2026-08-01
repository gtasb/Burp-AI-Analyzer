package com.ai.analyzer.agent.skills;

import io.agentscope.core.skill.AgentSkill;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Burp AI Analyzer 的 Skill 包装类。
 *
 * <p>在 AgentScope 的 {@link AgentSkill} 之上增加：
 * <ul>
 *   <li>启用/禁用状态管理（UI 持久化）</li>
 *   <li>{@link SkillTool} 可执行工具定义（二进制/脚本）</li>
 * </ul>
 *
 * <p>AgentScope 的 {@code FileSystemSkillRepository} 负责 SKILL.md 的加载和解析，
 * 本类负责项目特有的 UI 状态和工具扩展。
 */
public class Skill implements Serializable {
    private static final long serialVersionUID = 5L;

    private String name;
    private String description;
    private String content;
    private String filePath;
    private String folderPath;
    private boolean enabled;
    private long lastModified;
    private List<SkillTool> tools = new ArrayList<>();

    /** AgentScope AgentSkill 引用（transient，不序列化） */
    private transient AgentSkill agentSkill;

    public Skill() {
        this.enabled = false;
    }

    public Skill(String name, String description, String content, String filePath) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.filePath = filePath;
        this.enabled = false;
        if (filePath != null) {
            java.io.File file = new java.io.File(filePath);
            this.folderPath = file.getParent();
            this.lastModified = file.lastModified();
        }
    }

    // ======================== Builder ========================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Skill skill = new Skill();

        public Builder name(String name) { skill.name = name; return this; }
        public Builder description(String description) { skill.description = description; return this; }
        public Builder content(String content) { skill.content = content; return this; }
        public Builder filePath(String filePath) { skill.setFilePath(filePath); return this; }
        public Builder enabled(boolean enabled) { skill.enabled = enabled; return this; }
        public Builder tools(List<SkillTool> tools) { skill.setTools(tools); return this; }
        public Builder agentSkill(AgentSkill as) { skill.agentSkill = as; return this; }

        public Skill build() { return skill; }
    }

    // ======================== Getters & Setters ========================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) {
        this.filePath = filePath;
        if (filePath != null) {
            java.io.File file = new java.io.File(filePath);
            this.folderPath = file.getParent();
            this.lastModified = file.lastModified();
        }
    }

    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String folderPath) { this.folderPath = folderPath; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    // -------- AgentScope AgentSkill --------

    public AgentSkill getAgentSkill() { return agentSkill; }
    public void setAgentSkill(AgentSkill agentSkill) { this.agentSkill = agentSkill; }

    // -------- Tools (binary/script execution) --------

    public List<SkillTool> getTools() { return tools; }
    public void setTools(List<SkillTool> tools) { this.tools = tools != null ? tools : new ArrayList<>(); }

    public void addTool(SkillTool tool) {
        if (tool != null) {
            tool.setSkillName(this.name);
            this.tools.add(tool);
        }
    }

    public boolean hasTools() { return tools != null && !tools.isEmpty(); }
    public int getToolCount() { return tools != null ? tools.size() : 0; }

    // ======================== Utility ========================

    public boolean isFileUpdated() {
        if (filePath == null) return false;
        java.io.File file = new java.io.File(filePath);
        return file.exists() && file.lastModified() > lastModified;
    }

    public String getShortDescription() {
        if (description == null) return "";
        if (description.length() <= 100) return description;
        return description.substring(0, 97) + "...";
    }

    @Override
    public String toString() {
        return "Skill{name='" + name + "', enabled=" + enabled + ", tools=" + getToolCount() + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Skill skill = (Skill) o;
        return name != null ? name.equals(skill.name) : skill.name == null;
    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }
}