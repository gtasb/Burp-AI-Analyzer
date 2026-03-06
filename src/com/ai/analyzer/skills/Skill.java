package com.ai.analyzer.skills;

import dev.langchain4j.skills.FileSystemSkill;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 项目内部的 Skill 包装类 — 在官方 LangChain4j {@link dev.langchain4j.skills.Skill} 之上
 * 增加 enable/disable 状态管理 和 可执行工具（二进制/脚本）定义。
 *
 * <ul>
 *   <li>官方 Skills API 负责：内容加载、资源读取、activate_skill / read_skill_resource</li>
 *   <li>本类负责：启用/禁用、SkillTool（二进制执行）、UI 持久化</li>
 * </ul>
 *
 * @see <a href="https://docs.langchain4j.dev/tutorials/skills">LangChain4j Skills</a>
 */
public class Skill implements Serializable {
    private static final long serialVersionUID = 4L;

    private String name;
    private String description;
    private String content;
    private String filePath;
    private String folderPath;
    private boolean enabled;
    private long lastModified;
    private List<SkillTool> tools = new ArrayList<>();

    /** 官方 FileSystemSkill 引用（transient，不序列化） */
    private transient FileSystemSkill fileSystemSkill;

    /** 手工构建的官方 Skill（含资源），fallback 加载时使用（transient，不序列化） */
    private transient dev.langchain4j.skills.Skill builtOfficialSkill;

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
            File file = new File(filePath);
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
        public Builder fileSystemSkill(FileSystemSkill fs) { skill.fileSystemSkill = fs; return this; }
        public Builder builtOfficialSkill(dev.langchain4j.skills.Skill s) { skill.builtOfficialSkill = s; return this; }

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
            File file = new File(filePath);
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

    // -------- Official FileSystemSkill --------

    public FileSystemSkill getFileSystemSkill() { return fileSystemSkill; }
    public void setFileSystemSkill(FileSystemSkill fileSystemSkill) { this.fileSystemSkill = fileSystemSkill; }

    public dev.langchain4j.skills.Skill getBuiltOfficialSkill() { return builtOfficialSkill; }
    public void setBuiltOfficialSkill(dev.langchain4j.skills.Skill s) { this.builtOfficialSkill = s; }

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
        File file = new File(filePath);
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
