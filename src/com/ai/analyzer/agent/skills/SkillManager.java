package com.ai.analyzer.agent.skills;

import burp.api.montoya.MontoyaApi;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skills 管理器 — 基于 AgentScope {@link FileSystemSkillRepository}。
 *
 * <p>负责：
 * <ul>
 *   <li>从文件系统加载 SKILL.md 文件</li>
 *   <li>启用/禁用状态管理（UI 持久化）</li>
 *   <li>格式化可用技能目录供系统提示词使用</li>
 *   <li>SKILL.md 中自定义 {@code tools:} 段的解析</li>
 * </ul>
 */
public class SkillManager {

    static final String SKILL_FILE_NAME = "SKILL.md";

    private String skillsDirectoryPath;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Set<String> enabledSkillNames = ConcurrentHashMap.newKeySet();
    private MontoyaApi api;

    /** AgentScope FileSystemSkillRepository 实例 */
    private volatile FileSystemSkillRepository repository;
    /** 缓存的技能目录文本，随 skills 变更时失效 */
    private volatile String cachedSkillsCatalogue;

    public SkillManager() {
        this.skillsDirectoryPath = "";
    }

    public SkillManager(String skillsDirectoryPath) {
        this.skillsDirectoryPath = skillsDirectoryPath;
    }

    public void setApi(MontoyaApi api) { this.api = api; }

    public void setSkillsDirectoryPath(String path) {
        this.skillsDirectoryPath = path;
        if (path != null && !path.isEmpty()) {
            loadSkills();
        }
    }

    public String getSkillsDirectoryPath() { return skillsDirectoryPath; }

    // ======================== Loading ========================

    /**
     * 加载所有技能：使用 AgentScope {@link FileSystemSkillRepository} 解析 SKILL.md，
     * 再补充解析自定义 tools 段。
     */
    public void loadSkills() {
        if (skillsDirectoryPath == null || skillsDirectoryPath.isEmpty()) {
            logInfo("Skills 目录未配置，跳过加载");
            return;
        }

        Path dirPath = Paths.get(skillsDirectoryPath);
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            logError("Skills 目录不存在或不是目录: " + skillsDirectoryPath);
            return;
        }

        Set<String> previouslyEnabled = new HashSet<>(enabledSkillNames);
        skills.clear();

        try {
            // 使用 AgentScope FileSystemSkillRepository 加载
            repository = new FileSystemSkillRepository(dirPath, false);
            List<AgentSkill> agentSkills = repository.getAllSkills();
            logInfo("AgentScope FileSystemSkillRepository 加载了 " + agentSkills.size() + " 个技能");

            for (AgentSkill as : agentSkills) {
                String name = as.getName();
                Skill entry = new Skill(name, as.getDescription(), as.getSkillContent(), null);
                entry.setAgentSkill(as);
                entry.setEnabled(previouslyEnabled.contains(name));

                // 补充解析 SKILL.md 中的 tools: 段
                parseCustomToolsFromContent(entry, as.getSkillContent());

                skills.put(name, entry);
                logInfo("已加载 Skill: " + name
                        + " (工具:" + entry.getToolCount() + ")");
            }
        } catch (Exception e) {
            logError("FileSystemSkillRepository 加载失败: " + e.getMessage());
            fallbackManualLoad(dirPath, previouslyEnabled);
        }

        // 同步 enabledSkillNames
        enabledSkillNames.clear();
        skills.values().stream()
                .filter(Skill::isEnabled)
                .map(Skill::getName)
                .forEach(enabledSkillNames::add);

        cachedSkillsCatalogue = null;
        logInfo("Skills 加载完成，共 " + skills.size() + " 个技能，"
                + enabledSkillNames.size() + " 个已启用");
    }

    /**
     * 回退手动加载：当 AgentScope 的 FileSystemSkillRepository 不可用时，
     * 直接扫描子目录中的 SKILL.md 文件。
     */
    private void fallbackManualLoad(Path dirPath, Set<String> previouslyEnabled) {
        logInfo("使用手动回退模式加载 Skills");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath, Files::isDirectory)) {
            for (Path skillDir : stream) {
                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                if (!Files.exists(skillFile)) continue;

                try {
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                    String name = skillDir.getFileName().toString();
                    String description = extractDescription(content);

                    Skill entry = new Skill(name, description, content, skillFile.toString());
                    entry.setEnabled(previouslyEnabled.contains(name));
                    parseCustomToolsFromContent(entry, content);

                    skills.put(name, entry);
                    logInfo("手动加载 Skill: " + name + " (工具:" + entry.getToolCount() + ")");
                } catch (IOException e) {
                    logError("读取 SKILL.md 失败: " + skillFile + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logError("扫描 Skills 目录失败: " + e.getMessage());
        }
    }

    private String extractDescription(String content) {
        if (content == null || content.isEmpty()) return "";
        // 尝试从 YAML frontmatter 中提取 description
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                for (String line : frontmatter.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("description:") || line.startsWith("description：")) {
                        String desc = line.substring(line.indexOf(':') + 1).trim();
                        return desc.replaceAll("^[\"']|[\"']$", "");
                    }
                }
            }
        }
        // 回退：取第一行非空非标题行
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")) {
                return trimmed.length() > 100 ? trimmed.substring(0, 97) + "..." : trimmed;
            }
        }
        return "";
    }

    // ======================== Tools 解析 ========================

    private void parseCustomToolsFromContent(Skill entry, String content) {
        if (content == null || content.isEmpty()) return;
        // 简单解析 YAML frontmatter 中的 tools: 列表
        // 格式: tools:\n  - name: xxx\n    description: xxx\n    command: xxx\n    ...
        try {
            boolean inTools = false;
            String currentName = null;
            String currentDesc = null;
            String currentCommand = null;
            String currentArgs = null;
            String currentWorkDir = null;
            int currentTimeout = 120;
            List<SkillTool.ToolParameter> currentParams = new ArrayList<>();

            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("tools:")) {
                    inTools = true;
                    continue;
                }
                if (!inTools) continue;
                if (trimmed.isEmpty() || (!trimmed.startsWith("-") && !trimmed.startsWith("name:")
                        && !trimmed.startsWith("description:") && !trimmed.startsWith("command:")
                        && !trimmed.startsWith("args:") && !trimmed.startsWith("working_dir:")
                        && !trimmed.startsWith("timeout:") && !trimmed.startsWith("parameters:"))) {
                    continue;
                }

                if (trimmed.startsWith("- name:") || trimmed.startsWith("name:")) {
                    // 保存上一个工具
                    if (currentName != null) {
                        SkillTool tool = new SkillTool(currentName, currentDesc, currentCommand);
                        tool.setArgs(currentArgs);
                        tool.setWorkingDir(currentWorkDir);
                        tool.setTimeout(currentTimeout);
                        tool.setParameters(currentParams);
                        entry.addTool(tool);
                    }
                    // 开始新工具
                    currentName = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    currentDesc = null;
                    currentCommand = null;
                    currentArgs = null;
                    currentWorkDir = null;
                    currentTimeout = 120;
                    currentParams = new ArrayList<>();
                } else if (trimmed.startsWith("description:")) {
                    currentDesc = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                } else if (trimmed.startsWith("command:")) {
                    currentCommand = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                } else if (trimmed.startsWith("args:")) {
                    currentArgs = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                } else if (trimmed.startsWith("working_dir:")) {
                    currentWorkDir = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                } else if (trimmed.startsWith("timeout:")) {
                    try { currentTimeout = Integer.parseInt(trimmed.substring(trimmed.indexOf(':') + 1).trim()); } catch (NumberFormatException ignored) {}
                }
            }
            // 保存最后一个工具
            if (currentName != null) {
                SkillTool tool = new SkillTool(currentName, currentDesc, currentCommand);
                tool.setArgs(currentArgs);
                tool.setWorkingDir(currentWorkDir);
                tool.setTimeout(currentTimeout);
                tool.setParameters(currentParams);
                entry.addTool(tool);
            }
        } catch (Exception e) {
            logError("解析 tools 段失败: " + e.getMessage());
        }
    }

    // ======================== Public API ========================

    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }

    public Skill getSkill(String name) {
        return skills.get(name);
    }

    public void setSkillEnabled(String name, boolean enabled) {
        Skill skill = skills.get(name);
        if (skill != null) {
            skill.setEnabled(enabled);
            if (enabled) {
                enabledSkillNames.add(name);
            } else {
                enabledSkillNames.remove(name);
            }
            cachedSkillsCatalogue = null;
        }
    }

    public boolean isSkillEnabled(String name) {
        Skill skill = skills.get(name);
        return skill != null && skill.isEnabled();
    }

    public boolean hasEnabledSkills() {
        return skills.values().stream().anyMatch(Skill::isEnabled);
    }

    public boolean hasEnabledTools() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .anyMatch(Skill::hasTools);
    }

    public Set<String> getEnabledSkillNames() {
        return new HashSet<>(enabledSkillNames);
    }

    public void setEnabledSkillNames(Set<String> names) {
        enabledSkillNames.clear();
        if (names != null) enabledSkillNames.addAll(names);
        skills.values().forEach(s -> s.setEnabled(enabledSkillNames.contains(s.getName())));
        cachedSkillsCatalogue = null;
    }

    // ======================== Formatting ========================

    /**
     * 格式化可用技能目录，供系统提示词使用。
     */
    public String formatAvailableSkills() {
        String cached = cachedSkillsCatalogue;
        if (cached != null) return cached;

        List<Skill> enabled = getEnabledSkills();
        if (enabled.isEmpty()) {
            cachedSkillsCatalogue = "";
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Available Skills\n\n");
        sb.append("The following skills are available. Use `activate_skill` to load a skill's full instructions:\n\n");

        for (Skill skill : enabled) {
            sb.append("## ").append(skill.getName()).append("\n");
            sb.append("- **Description**: ").append(skill.getDescription()).append("\n");
            if (skill.hasTools()) {
                sb.append("- **Available Tools**:\n");
                for (SkillTool tool : skill.getTools()) {
                    sb.append("  - `").append(tool.getFullName()).append("`: ")
                            .append(tool.getDescription()).append("\n");
                }
            }
            sb.append("\n");
        }

        cachedSkillsCatalogue = sb.toString();
        return cachedSkillsCatalogue;
    }

    /**
     * 获取 AgentScope {@link FileSystemSkillRepository}，供 HarnessAgent 使用。
     */
    public FileSystemSkillRepository getRepository() {
        return repository;
    }

    // ======================== Logging ========================

    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[SkillManager] " + message);
        }
    }

    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[SkillManager] " + message);
        }
    }
}