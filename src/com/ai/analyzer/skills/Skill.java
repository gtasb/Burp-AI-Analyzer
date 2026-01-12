package com.ai.analyzer.skills;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 表示一个用户自定义的 Skill（技能）
 * 
 * Skills 是包含指令、脚本和资源的文件夹，AI 动态加载以提高特定任务的性能。
 * 每个 skill 是一个独立的文件夹，包含一个 SKILL.md 文件。
 * 
 * SKILL.md 格式（支持可执行工具定义）：
 * ---
 * name: skill-name
 * description: A clear description of what this skill does
 * tools:
 *   - name: nmap_scan
 *     description: 使用 nmap 进行端口扫描
 *     command: "C:/Tools/nmap/nmap.exe"
 *     args: "-sV -p {ports} {target}"
 *     timeout: 300
 *     parameters:
 *       - name: target
 *         description: 目标IP或域名
 *         required: true
 *       - name: ports
 *         description: 端口范围
 *         default: "1-1000"
 * ---
 * # Skill Name
 * [Instructions content...]
 * 
 * 参考: https://github.com/anthropics/skills
 */
public class Skill implements Serializable {
    private static final long serialVersionUID = 2L; // 版本升级
    
    private String name;           // 技能名称（从frontmatter读取）
    private String description;    // 技能描述（从frontmatter读取）
    private String content;        // 技能指令内容（markdown部分）
    private String filePath;       // SKILL.md 文件路径
    private String folderPath;     // 技能文件夹路径
    private boolean enabled;       // 是否启用
    private long lastModified;     // 文件最后修改时间
    private List<SkillTool> tools = new ArrayList<>(); // 可执行工具列表
    
    public Skill() {
        this.enabled = false;
    }
    
    public Skill(String name, String description, String content, String filePath) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.filePath = filePath;
        this.enabled = false;
        
        // 从文件路径提取文件夹路径
        if (filePath != null) {
            File file = new File(filePath);
            this.folderPath = file.getParent();
            this.lastModified = file.lastModified();
        }
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
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
        if (filePath != null) {
            File file = new File(filePath);
            this.folderPath = file.getParent();
            this.lastModified = file.lastModified();
        }
    }
    
    public String getFolderPath() {
        return folderPath;
    }
    
    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
    
    public List<SkillTool> getTools() {
        return tools;
    }
    
    public void setTools(List<SkillTool> tools) {
        this.tools = tools != null ? tools : new ArrayList<>();
    }
    
    public void addTool(SkillTool tool) {
        if (tool != null) {
            tool.setSkillName(this.name);
            this.tools.add(tool);
        }
    }
    
    /**
     * 检查是否有可执行工具
     */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
    
    /**
     * 获取工具数量
     */
    public int getToolCount() {
        return tools != null ? tools.size() : 0;
    }
    
    /**
     * 检查技能文件是否已更新
     */
    public boolean isFileUpdated() {
        if (filePath == null) return false;
        File file = new File(filePath);
        return file.exists() && file.lastModified() > lastModified;
    }
    
    /**
     * 获取用于显示的简短描述（截断过长的描述）
     */
    public String getShortDescription() {
        if (description == null) return "";
        if (description.length() <= 100) return description;
        return description.substring(0, 97) + "...";
    }
    
    @Override
    public String toString() {
        return "Skill{" +
                "name='" + name + '\'' +
                ", enabled=" + enabled +
                ", tools=" + getToolCount() +
                ", description='" + getShortDescription() + '\'' +
                '}';
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
