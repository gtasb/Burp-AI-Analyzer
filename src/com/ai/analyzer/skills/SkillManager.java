package com.ai.analyzer.skills;

import burp.api.montoya.MontoyaApi;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.SkillResource;
import dev.langchain4j.skills.Skills;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.ai.analyzer.skills.SkillTool.ToolParameter;

/**
 * Skills 管理器 — 基于 LangChain4j 官方 Skills API (Tool Mode)
 *
 * <p>使用 {@link FileSystemSkillLoader} 加载技能（SKILL.md + 资源文件），
 * 使用 {@link Skills} 提供 {@code activate_skill} 和 {@code read_skill_resource} 工具。
 *
 * <p>在官方 API 之上扩展了：
 * <ul>
 *   <li>启用/禁用状态管理（UI 持久化）</li>
 *   <li>SKILL.md 中自定义 {@code tools:} 段的解析（二进制/脚本执行能力）</li>
 * </ul>
 *
 * @see <a href="https://docs.langchain4j.dev/tutorials/skills">LangChain4j Skills</a>
 */
public class SkillManager {

    static final String SKILL_FILE_NAME = "SKILL.md";

    private String skillsDirectoryPath;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Set<String> enabledSkillNames = ConcurrentHashMap.newKeySet();
    private MontoyaApi api;

    /** 官方 Skills 实例（Tool Mode），在 enable/disable 变更时重建 */
    private volatile Skills officialSkills;
    /** 缓存 ToolProvider，随 officialSkills 一起失效 */
    private volatile ToolProvider cachedToolProvider;
    /** 缓存 formatAvailableSkills() 输出，随 officialSkills 一起失效 */
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
     * 加载所有技能：先用官方 FileSystemSkillLoader，再补充解析自定义 tools 段。
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
            // 1) 官方 FileSystemSkillLoader 加载（子目录结构: skill_dir/SKILL.md）
            List<FileSystemSkill> loaded = FileSystemSkillLoader.loadSkills(dirPath);
            logInfo("FileSystemSkillLoader 加载了 " + loaded.size() + " 个技能");

            for (FileSystemSkill fsSkill : loaded) {
                String name = fsSkill.name();
                Skill entry = new Skill(name, fsSkill.description(), fsSkill.content(), null);
                entry.setFileSystemSkill(fsSkill);
                entry.setEnabled(previouslyEnabled.contains(name));

                // 补充解析 SKILL.md 中的 tools: 段
                parseCustomToolsFromContent(entry, fsSkill);

                skills.put(name, entry);
                logInfo("已加载 Skill: " + name
                        + " (工具:" + entry.getToolCount()
                        + ", 资源:" + fsSkill.resources().size() + ")");
            }
        } catch (Exception e) {
            String rootCause = e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                rootCause = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            logInfo("FileSystemSkillLoader 加载失败（将使用安全手工加载）: " + rootCause);
            fallbackManualLoad(dirPath, previouslyEnabled);
        }

        // 同步 enabledSkillNames
        enabledSkillNames.clear();
        skills.values().stream()
                .filter(Skill::isEnabled)
                .forEach(s -> enabledSkillNames.add(s.getName()));

        // 重建官方 Skills 实例
        rebuildOfficialSkills();

        logInfo("Skills 加载完成，共 " + skills.size() + " 个，已启用 " + enabledSkillNames.size() + " 个");
    }

    /**
     * 如果 FileSystemSkillLoader 失败（例如目录含 .git 等二进制文件），
     * 退回到手工扫描，同时安全加载资源文件以支持 {@code read_skill_resource}。
     */
    private void fallbackManualLoad(Path dirPath, Set<String> previouslyEnabled) {
        logInfo("尝试手工加载 Skills（含安全资源扫描）...");
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(dirPath, Files::isDirectory)) {
            for (Path skillDir : dirs) {
                if (skillDir.getFileName().toString().startsWith(".")) continue;
                Path skillMd = skillDir.resolve(SKILL_FILE_NAME);
                if (!Files.exists(skillMd)) continue;

                try {
                    String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
                    String name = extractFrontmatterValue(raw, "name");
                    String desc = extractFrontmatterValue(raw, "description");
                    if (name == null || name.isEmpty()) {
                        name = skillDir.getFileName().toString();
                    }
                    String content = extractContent(raw);

                    List<SkillResource> resources = loadResourcesSafely(skillDir);

                    dev.langchain4j.skills.Skill officialSkill = dev.langchain4j.skills.Skill.builder()
                            .name(name)
                            .description(desc != null ? desc : "")
                            .content(content != null ? content : "")
                            .resources(resources)
                            .build();

                    Skill entry = new Skill(name, desc, content, skillMd.toString());
                    entry.setFolderPath(skillDir.toString());
                    entry.setBuiltOfficialSkill(officialSkill);
                    entry.setEnabled(previouslyEnabled.contains(name));
                    parseCustomToolsFromRaw(entry, raw, skillDir.toString());
                    skills.put(name, entry);
                    logInfo("(手工) 已加载 Skill: " + name + " (资源: " + resources.size() + ")");
                } catch (IOException e) {
                    logError("手工加载失败: " + skillDir + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logError("手工遍历目录失败: " + e.getMessage());
        }
    }

    /**
     * 安全加载技能目录下的资源文件，跳过隐藏目录（.git）和无法读取的二进制文件。
     */
    private List<SkillResource> loadResourcesSafely(Path skillDir) {
        List<SkillResource> resources = new ArrayList<>();
        try {
            Files.walk(skillDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase(SKILL_FILE_NAME))
                    .forEach(p -> {
                        Path rel = skillDir.relativize(p);
                        for (Path part : rel) {
                            if (part.toString().startsWith(".")) return;
                        }
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            resources.add(SkillResource.builder()
                                    .relativePath(rel.toString().replace('\\', '/'))
                                    .content(text)
                                    .build());
                        } catch (Exception ignored) {
                            // MalformedInputException etc. — skip binary/unreadable files
                        }
                    });
        } catch (IOException e) {
            logError("扫描资源文件失败: " + skillDir + " - " + e.getMessage());
        }
        return resources;
    }

    // ======================== Custom tools parsing ========================

    /**
     * 从 FileSystemSkill 对应的 SKILL.md 原始内容中解析自定义 tools: 段。
     * 官方 Skill 不会解析 tools: 前端信息，我们自己做。
     */
    private void parseCustomToolsFromContent(Skill entry, FileSystemSkill fsSkill) {
        // FileSystemSkill 只暴露 content（frontmatter 之后），
        // 需要找到原始 SKILL.md 文件来读 frontmatter。
        // 尝试从 resources 里推断目录路径，或直接读磁盘。
        try {
            Path dir = Paths.get(skillsDirectoryPath);
            Path skillMd = dir.resolve(fsSkill.name()).resolve(SKILL_FILE_NAME);
            if (Files.exists(skillMd)) {
                String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
                parseCustomToolsFromRaw(entry, raw, skillMd.getParent().toString());
                entry.setFilePath(skillMd.toString());
                entry.setFolderPath(skillMd.getParent().toString());
            }
        } catch (IOException e) {
            logError("解析自定义 tools 失败: " + entry.getName() + " - " + e.getMessage());
        }
    }

    private void parseCustomToolsFromRaw(Skill entry, String raw, String skillDir) {
        String frontmatter = extractFrontmatter(raw);
        if (frontmatter != null && frontmatter.contains("tools:")) {
            List<SkillTool> tools = parseToolsFromYaml(frontmatter, entry.getName(), skillDir);
            for (SkillTool tool : tools) {
                entry.addTool(tool);
            }
        }
    }

    // ======================== Frontmatter helpers ========================

    private static String extractFrontmatter(String raw) {
        if (raw == null || !raw.startsWith("---")) return null;
        int end = raw.indexOf("\n---", 3);
        if (end < 0) return null;
        return raw.substring(3, end).trim();
    }

    private static String extractContent(String raw) {
        if (raw == null) return "";
        if (!raw.startsWith("---")) return raw;
        int end = raw.indexOf("\n---", 3);
        if (end < 0) return raw;
        return raw.substring(end + 4).trim();
    }

    static String extractFrontmatterValue(String raw, String key) {
        String fm = extractFrontmatter(raw);
        if (fm == null) return null;
        for (String line : fm.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String val = trimmed.substring(key.length() + 1).trim();
                return removeQuotes(val);
            }
        }
        return null;
    }

    // ======================== YAML tools parser ========================

    private List<SkillTool> parseToolsFromYaml(String yaml, String skillName, String skillDir) {
        List<SkillTool> tools = new ArrayList<>();
        String[] lines = yaml.split("\n");
        SkillTool currentTool = null;
        ToolParameter currentParam = null;
        boolean inTools = false;
        boolean inParameters = false;
        int toolIndent = -1;  // 记录工具级 `- name:` 的缩进深度

        for (String line : lines) {
            String trimmed = line.trim();
            int indent = line.length() - line.stripLeading().length();

            if (trimmed.equals("tools:")) { inTools = true; continue; }
            if (!inTools) continue;

            if (trimmed.startsWith("- name:")) {
                boolean isToolLevel = (toolIndent < 0 || indent <= toolIndent);
                if (isToolLevel) {
                    // 工具级 `- name:`
                    toolIndent = indent;
                    if (currentTool != null) {
                        if (currentParam != null) { currentTool.addParameter(currentParam); currentParam = null; }
                        tools.add(currentTool);
                    }
                    currentTool = new SkillTool();
                    currentTool.setSkillName(skillName);
                    currentTool.setName(yamlVal(trimmed.substring(7)));
                    inParameters = false;
                } else if (inParameters && currentTool != null) {
                    // 参数级 `- name:`（缩进更深）
                    if (currentParam != null) currentTool.addParameter(currentParam);
                    currentParam = new ToolParameter();
                    currentParam.setName(yamlVal(trimmed.substring(7)));
                }
                continue;
            }
            if (currentTool == null) continue;

            if (trimmed.startsWith("description:") && !inParameters) {
                currentTool.setDescription(yamlVal(trimmed.substring(12)));
            } else if (trimmed.startsWith("command:")) {
                String cmd = yamlVal(trimmed.substring(8));
                if (cmd != null && !cmd.isEmpty() && !isAbsolutePath(cmd) && isRelativeFilePath(cmd)) {
                    cmd = skillDir + File.separator + cmd;
                }
                currentTool.setCommand(cmd);
            } else if (trimmed.startsWith("args:")) {
                currentTool.setArgs(yamlVal(trimmed.substring(5)));
            } else if (trimmed.startsWith("working_dir:")) {
                String wd = yamlVal(trimmed.substring(12));
                if (wd != null && !wd.isEmpty() && !isAbsolutePath(wd)) wd = skillDir + File.separator + wd;
                currentTool.setWorkingDir(wd);
            } else if (trimmed.startsWith("timeout:")) {
                try { currentTool.setTimeout(Integer.parseInt(yamlVal(trimmed.substring(8)))); } catch (NumberFormatException ignored) {}
            } else if (trimmed.equals("parameters:")) {
                inParameters = true;
            } else if (inParameters && currentParam != null) {
                if (trimmed.startsWith("description:")) currentParam.setDescription(yamlVal(trimmed.substring(12)));
                else if (trimmed.startsWith("type:")) currentParam.setType(yamlVal(trimmed.substring(5)));
                else if (trimmed.startsWith("required:")) currentParam.setRequired("true".equalsIgnoreCase(yamlVal(trimmed.substring(9))));
                else if (trimmed.startsWith("default:")) { currentParam.setDefaultValue(yamlVal(trimmed.substring(8))); currentParam.setRequired(false); }
            }
        }
        if (currentTool != null) {
            if (currentParam != null) currentTool.addParameter(currentParam);
            tools.add(currentTool);
        }
        return tools;
    }

    private static String yamlVal(String v) { return v == null ? null : removeQuotes(v.trim()); }

    private static String removeQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }

    static boolean isAbsolutePath(String p) {
        if (p == null || p.isEmpty()) return false;
        return p.startsWith("/") || (p.length() > 2 && p.charAt(1) == ':') || p.startsWith("\\\\");
    }

    /**
     * 判断命令是否是相对文件路径（含路径分隔符或 . 前缀）。
     * 裸命令名如 "ping"、"cmd"、"python" 应通过 PATH 解析，不拼接 skillDir。
     */
    private static boolean isRelativeFilePath(String cmd) {
        return cmd.contains("/") || cmd.contains("\\") || cmd.startsWith(".");
    }

    // ======================== Official Skills (Tool Mode) ========================

    /**
     * 根据当前启用的技能重建官方 {@link Skills} 实例。
     * 必须在 enable/disable 变更后调用。
     */
    private void rebuildOfficialSkills() {
        List<dev.langchain4j.skills.Skill> enabled = skills.values().stream()
                .filter(Skill::isEnabled)
                .map(this::toOfficialSkill)
                .collect(Collectors.toList());

        if (!enabled.isEmpty()) {
            this.officialSkills = Skills.from(enabled);
        } else {
            this.officialSkills = null;
        }
        this.cachedToolProvider = null;
        this.cachedSkillsCatalogue = null;
    }

    /**
     * 将内部 Skill 转换为官方 Skill 对象。
     * 优先级：FileSystemSkill（官方加载）→ builtOfficialSkill（手工加载，含资源）→ 最简构建。
     */
    private dev.langchain4j.skills.Skill toOfficialSkill(Skill entry) {
        if (entry.getFileSystemSkill() != null) return entry.getFileSystemSkill();
        if (entry.getBuiltOfficialSkill() != null) return entry.getBuiltOfficialSkill();
        return dev.langchain4j.skills.Skill.builder()
                .name(entry.getName())
                .description(entry.getDescription() != null ? entry.getDescription() : "")
                .content(entry.getContent() != null ? entry.getContent() : "")
                .build();
    }

    /**
     * 获取官方 Skills 的 ToolProvider（提供 activate_skill + read_skill_resource）。
     * 返回缓存实例，随 {@link #rebuildOfficialSkills()} 一起失效。
     */
    public ToolProvider getSkillsToolProvider() {
        if (officialSkills == null) return null;
        ToolProvider tp = cachedToolProvider;
        if (tp == null) {
            tp = officialSkills.toolProvider();
            cachedToolProvider = tp;
        }
        return tp;
    }

    /**
     * 获取官方 formatAvailableSkills() 生成的 XML 目录。
     * 返回缓存字符串，随 {@link #rebuildOfficialSkills()} 一起失效。
     */
    public String formatAvailableSkills() {
        if (officialSkills == null) return "";
        String cat = cachedSkillsCatalogue;
        if (cat == null) {
            cat = officialSkills.formatAvailableSkills();
            cachedSkillsCatalogue = cat;
        }
        return cat;
    }

    // ======================== Enable / Disable ========================

    public void enableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill != null) {
            skill.setEnabled(true);
            enabledSkillNames.add(name);
            rebuildOfficialSkills();
            logInfo("已启用 Skill: " + name);
        }
    }

    public void disableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill != null) {
            skill.setEnabled(false);
            enabledSkillNames.remove(name);
            rebuildOfficialSkills();
            logInfo("已禁用 Skill: " + name);
        }
    }

    public void toggleSkill(String name) {
        Skill s = skills.get(name);
        if (s != null) { if (s.isEnabled()) disableSkill(name); else enableSkill(name); }
    }

    public void setSkillEnabled(String name, boolean enabled) {
        if (enabled) enableSkill(name); else disableSkill(name);
    }

    // ======================== Accessors ========================

    public List<Skill> getAllSkills() { return new ArrayList<>(skills.values()); }

    public List<Skill> getEnabledSkills() {
        return skills.values().stream().filter(Skill::isEnabled).collect(Collectors.toList());
    }

    public Skill getSkill(String name) { return skills.get(name); }
    public boolean hasEnabledSkills() { return !enabledSkillNames.isEmpty(); }
    public int getEnabledSkillCount() { return enabledSkillNames.size(); }

    public List<String> getEnabledSkillNames() { return new ArrayList<>(enabledSkillNames); }

    public void setEnabledSkillNames(List<String> names) {
        enabledSkillNames.clear();
        if (names != null) {
            enabledSkillNames.addAll(names);
            for (Skill s : skills.values()) s.setEnabled(enabledSkillNames.contains(s.getName()));
        }
        rebuildOfficialSkills();
    }

    // ======================== Tool Accessors ========================

    public List<SkillTool> getAllEnabledTools() {
        List<SkillTool> all = new ArrayList<>();
        for (Skill s : getEnabledSkills()) if (s.hasTools()) all.addAll(s.getTools());
        return all;
    }

    public SkillTool getToolByName(String toolName) {
        for (Skill s : skills.values())
            for (SkillTool t : s.getTools())
                if (t.getName().equals(toolName) || t.getFullName().equals(toolName)) return t;
        return null;
    }

    public boolean hasEnabledTools() { return getEnabledSkills().stream().anyMatch(Skill::hasTools); }
    public int getEnabledToolCount() { return getEnabledSkills().stream().mapToInt(Skill::getToolCount).sum(); }

    /**
     * 旧接口兼容 — 全量注入 skills 提示词（不推荐，请用 formatAvailableSkills）。
     */
    public String buildSkillsPrompt() {
        List<Skill> enabled = getEnabledSkills();
        if (enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n# 用户自定义技能指令\n\n");
        for (Skill s : enabled) {
            sb.append("## Skill: ").append(s.getName()).append("\n");
            if (s.getDescription() != null) sb.append("**描述**: ").append(s.getDescription()).append("\n\n");
            if (s.hasTools()) {
                sb.append("**可用工具**:\n");
                for (SkillTool t : s.getTools())
                    sb.append("- `").append(t.getFullName()).append("`: ").append(t.getDescription()).append("\n");
                sb.append("\n");
            }
            sb.append(s.getContent()).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    // ======================== Refresh / Create ========================

    public void refreshUpdatedSkills() {
        loadSkills();
    }

    public void createExampleSkill(String dirPath) {
        Path skillDir = Paths.get(dirPath, "example-skill");
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve(SKILL_FILE_NAME), """
                    ---
                    name: example-skill
                    description: 示例技能 — 演示如何创建自定义技能
                    ---
                    
                    # Example Skill
                    
                    这是一个示例技能，你可以参考这个格式创建自己的技能。
                    
                    ## 指令
                    1. 执行步骤 A
                    2. 根据结果执行步骤 B
                    3. 生成报告
                    """, StandardCharsets.UTF_8);

            Path refs = skillDir.resolve("references");
            Files.createDirectories(refs);
            Files.writeString(refs.resolve("checklist.md"),
                    "# 检查清单\n\n- [ ] 参数校验\n- [ ] 认证检查\n", StandardCharsets.UTF_8);
            logInfo("已创建示例 Skill: " + skillDir);
        } catch (IOException e) { logError("创建示例 Skill 失败: " + e.getMessage()); }

        Path toolDir = Paths.get(dirPath, "network-scanner");
        try {
            Files.createDirectories(toolDir);
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            Files.writeString(toolDir.resolve(SKILL_FILE_NAME), """
                    ---
                    name: network-scanner
                    description: 网络扫描技能，包含常用网络诊断工具
                    tools:
                      - name: ping_host
                        description: 使用 ping 测试目标主机连通性
                        command: "%s"
                        args: "%s"
                        timeout: 30
                        parameters:
                          - name: target
                            description: 目标 IP 或域名
                            required: true
                    ---
                    
                    # Network Scanner
                    
                    当用户需要进行网络诊断时：
                    1. 先用 activate_skill 加载此技能
                    2. 用 execute_skill_tool 执行工具
                    3. 分析输出并报告
                    """.formatted(
                    win ? "ping" : "/bin/ping",
                    win ? "-n 4 {target}" : "-c 4 {target}"
            ), StandardCharsets.UTF_8);
            logInfo("已创建带工具的示例 Skill: " + toolDir);
        } catch (IOException e) { logError("创建带工具的示例 Skill 失败: " + e.getMessage()); }
    }

    // ======================== Logging ========================

    private void logInfo(String msg) {
        if (api != null) api.logging().logToOutput("[SkillManager] " + msg);
        else System.out.println("[SkillManager] " + msg);
    }

    private void logError(String msg) {
        if (api != null) api.logging().logToError("[SkillManager] " + msg);
        else System.err.println("[SkillManager] " + msg);
    }
}
