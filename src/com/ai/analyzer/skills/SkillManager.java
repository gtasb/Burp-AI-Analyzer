package com.ai.analyzer.skills;

import burp.api.montoya.MontoyaApi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ai.analyzer.skills.SkillTool.ToolParameter;

/**
 * Skills 管理器
 * 
 * 负责：
 * 1. 从指定目录加载 SKILL.md 文件
 * 2. 解析 YAML frontmatter 和 markdown 内容
 * 3. 管理 skill 的启用/禁用状态
 * 4. 提供启用的 skills 内容供系统提示词使用
 * 
 * 支持两种目录结构：
 * - 单层: skills_dir/skill1/SKILL.md
 * - 扁平: skills_dir/skill1.md (文件名作为skill名称)
 */
public class SkillManager {
    
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", 
            Pattern.DOTALL
    );
    private static final Pattern YAML_NAME_PATTERN = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern YAML_DESCRIPTION_PATTERN = Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE);
    
    private String skillsDirectoryPath;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Set<String> enabledSkillNames = ConcurrentHashMap.newKeySet();
    private MontoyaApi api;
    
    public SkillManager() {
        this.skillsDirectoryPath = "";
    }
    
    public SkillManager(String skillsDirectoryPath) {
        this.skillsDirectoryPath = skillsDirectoryPath;
    }
    
    public void setApi(MontoyaApi api) {
        this.api = api;
    }
    
    /**
     * 设置 skills 目录路径并重新加载
     */
    public void setSkillsDirectoryPath(String path) {
        this.skillsDirectoryPath = path;
        if (path != null && !path.isEmpty()) {
            loadSkills();
        }
    }
    
    public String getSkillsDirectoryPath() {
        return skillsDirectoryPath;
    }
    
    /**
     * 从配置的目录加载所有 skills
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
        
        // 保存当前启用状态
        Set<String> previouslyEnabled = new HashSet<>(enabledSkillNames);
        
        // 清空当前 skills（保留启用状态）
        skills.clear();
        
        try (Stream<Path> paths = Files.walk(dirPath, 2)) { // 最多遍历2层
            paths.filter(Files::isRegularFile)
                 .filter(this::isSkillFile)
                 .forEach(path -> {
                     try {
                         Skill skill = parseSkillFile(path);
                         if (skill != null && skill.getName() != null) {
                             // 恢复之前的启用状态
                             skill.setEnabled(previouslyEnabled.contains(skill.getName()));
                             skills.put(skill.getName(), skill);
                             logInfo("已加载 Skill: " + skill.getName());
                         }
                     } catch (Exception e) {
                         logError("加载 Skill 文件失败: " + path + " - " + e.getMessage());
                     }
                 });
        } catch (IOException e) {
            logError("遍历 Skills 目录失败: " + e.getMessage());
        }
        
        // 更新启用的 skills 集合
        enabledSkillNames.clear();
        skills.values().stream()
              .filter(Skill::isEnabled)
              .forEach(s -> enabledSkillNames.add(s.getName()));
        
        logInfo("Skills 加载完成，共 " + skills.size() + " 个，已启用 " + enabledSkillNames.size() + " 个");
    }
    
    /**
     * 判断是否是 skill 文件
     */
    private boolean isSkillFile(Path path) {
        String fileName = path.getFileName().toString();
        // 支持 SKILL.md 或直接 .md 文件
        return fileName.equalsIgnoreCase(SKILL_FILE_NAME) || 
               (fileName.endsWith(".md") && !fileName.startsWith("."));
    }
    
    /**
     * 解析 SKILL.md 文件
     */
    private Skill parseSkillFile(Path path) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        
        Matcher frontmatterMatcher = FRONTMATTER_PATTERN.matcher(content);
        
        String name = null;
        String description = null;
        String markdownContent = content;
        String frontmatter = null;
        
        if (frontmatterMatcher.matches()) {
            frontmatter = frontmatterMatcher.group(1);
            markdownContent = frontmatterMatcher.group(2).trim();
            
            // 解析 YAML frontmatter
            Matcher nameMatcher = YAML_NAME_PATTERN.matcher(frontmatter);
            if (nameMatcher.find()) {
                name = nameMatcher.group(1).trim();
                // 移除可能的引号
                name = removeQuotes(name);
            }
            
            Matcher descMatcher = YAML_DESCRIPTION_PATTERN.matcher(frontmatter);
            if (descMatcher.find()) {
                description = descMatcher.group(1).trim();
                description = removeQuotes(description);
            }
        }
        
        // 如果没有从 frontmatter 获取到 name，使用文件名或目录名
        if (name == null || name.isEmpty()) {
            String fileName = path.getFileName().toString();
            if (fileName.equalsIgnoreCase(SKILL_FILE_NAME)) {
                // 使用父目录名作为 skill 名称
                name = path.getParent().getFileName().toString();
            } else {
                // 使用文件名（去掉 .md 扩展名）
                name = fileName.replaceAll("\\.md$", "");
            }
        }
        
        // 如果没有描述，尝试从内容的第一段提取
        if (description == null || description.isEmpty()) {
            description = extractFirstParagraph(markdownContent);
        }
        
        Skill skill = new Skill(name, description, markdownContent, path.toString());
        
        // 解析工具定义
        if (frontmatter != null && frontmatter.contains("tools:")) {
            List<SkillTool> tools = parseToolsFromYaml(frontmatter, name, path.getParent().toString());
            for (SkillTool tool : tools) {
                skill.addTool(tool);
            }
            if (!tools.isEmpty()) {
                logInfo("Skill " + name + " 包含 " + tools.size() + " 个可执行工具");
            }
        }
        
        return skill;
    }
    
    /**
     * 从 YAML frontmatter 中解析工具定义
     * 
     * 支持的格式：
     * tools:
     *   - name: tool_name
     *     description: 工具描述
     *     command: "/path/to/executable"
     *     args: "-flag {param1} {param2}"
     *     working_dir: "/work/dir"
     *     timeout: 120
     *     parameters:
     *       - name: param1
     *         description: 参数描述
     *         required: true
     *       - name: param2
     *         description: 参数描述
     *         default: "default_value"
     */
    private List<SkillTool> parseToolsFromYaml(String yaml, String skillName, String skillDir) {
        List<SkillTool> tools = new ArrayList<>();
        
        // 简单的 YAML 解析（不依赖外部库）
        String[] lines = yaml.split("\n");
        SkillTool currentTool = null;
        SkillTool.ToolParameter currentParam = null;
        boolean inTools = false;
        boolean inParameters = false;
        int toolIndent = -1;
        int paramIndent = -1;
        
        for (String line : lines) {
            String trimmed = line.trim();
            int indent = line.length() - line.stripLeading().length();
            
            // 检测 tools: 开始
            if (trimmed.equals("tools:")) {
                inTools = true;
                toolIndent = indent;
                continue;
            }
            
            if (!inTools) continue;
            
            // 检测工具列表项开始 (- name: ...)
            if (trimmed.startsWith("- name:")) {
                // 保存之前的工具
                if (currentTool != null) {
                    // 保存之前的参数
                    if (currentParam != null) {
                        currentTool.addParameter(currentParam);
                        currentParam = null;
                    }
                    tools.add(currentTool);
                }
                
                currentTool = new SkillTool();
                currentTool.setSkillName(skillName);
                currentTool.setName(extractYamlValue(trimmed.substring(7)));
                inParameters = false;
                paramIndent = -1;
                continue;
            }
            
            if (currentTool == null) continue;
            
            // 解析工具属性
            if (trimmed.startsWith("description:") && !inParameters) {
                currentTool.setDescription(extractYamlValue(trimmed.substring(12)));
            } else if (trimmed.startsWith("command:")) {
                String cmd = extractYamlValue(trimmed.substring(8));
                // 处理相对路径
                if (cmd != null && !cmd.isEmpty() && !isAbsolutePath(cmd)) {
                    cmd = skillDir + File.separator + cmd;
                }
                currentTool.setCommand(cmd);
            } else if (trimmed.startsWith("args:")) {
                currentTool.setArgs(extractYamlValue(trimmed.substring(5)));
            } else if (trimmed.startsWith("working_dir:")) {
                String workDir = extractYamlValue(trimmed.substring(12));
                if (workDir != null && !workDir.isEmpty() && !isAbsolutePath(workDir)) {
                    workDir = skillDir + File.separator + workDir;
                }
                currentTool.setWorkingDir(workDir);
            } else if (trimmed.startsWith("timeout:")) {
                try {
                    currentTool.setTimeout(Integer.parseInt(extractYamlValue(trimmed.substring(8))));
                } catch (NumberFormatException e) {
                    // 忽略无效的超时值
                }
            } else if (trimmed.equals("parameters:")) {
                inParameters = true;
                paramIndent = indent;
            } else if (inParameters && trimmed.startsWith("- name:")) {
                // 保存之前的参数
                if (currentParam != null) {
                    currentTool.addParameter(currentParam);
                }
                currentParam = new SkillTool.ToolParameter();
                currentParam.setName(extractYamlValue(trimmed.substring(7)));
            } else if (inParameters && currentParam != null) {
                // 解析参数属性
                if (trimmed.startsWith("description:")) {
                    currentParam.setDescription(extractYamlValue(trimmed.substring(12)));
                } else if (trimmed.startsWith("type:")) {
                    currentParam.setType(extractYamlValue(trimmed.substring(5)));
                } else if (trimmed.startsWith("required:")) {
                    currentParam.setRequired("true".equalsIgnoreCase(extractYamlValue(trimmed.substring(9))));
                } else if (trimmed.startsWith("default:")) {
                    currentParam.setDefaultValue(extractYamlValue(trimmed.substring(8)));
                    currentParam.setRequired(false); // 有默认值则非必需
                }
            }
        }
        
        // 保存最后的工具和参数
        if (currentTool != null) {
            if (currentParam != null) {
                currentTool.addParameter(currentParam);
            }
            tools.add(currentTool);
        }
        
        return tools;
    }
    
    /**
     * 提取 YAML 值（去除引号和空格）
     */
    private String extractYamlValue(String value) {
        if (value == null) return null;
        value = value.trim();
        return removeQuotes(value);
    }
    
    /**
     * 判断是否是绝对路径
     */
    private boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) return false;
        // Windows 绝对路径: C:\ 或 \\
        // Unix 绝对路径: /
        return path.startsWith("/") || 
               (path.length() > 2 && path.charAt(1) == ':') ||
               path.startsWith("\\\\");
    }
    
    /**
     * 移除字符串两端的引号
     */
    private String removeQuotes(String str) {
        if (str == null) return null;
        str = str.trim();
        if ((str.startsWith("\"") && str.endsWith("\"")) ||
            (str.startsWith("'") && str.endsWith("'"))) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }
    
    /**
     * 从 markdown 内容中提取第一段作为描述
     */
    private String extractFirstParagraph(String content) {
        if (content == null || content.isEmpty()) return "";
        
        // 跳过标题行
        String[] lines = content.split("\n");
        StringBuilder paragraph = new StringBuilder();
        boolean foundContent = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (foundContent) break; // 遇到空行，段落结束
                continue;
            }
            if (trimmed.startsWith("#")) {
                continue; // 跳过标题
            }
            foundContent = true;
            paragraph.append(trimmed).append(" ");
        }
        
        String result = paragraph.toString().trim();
        if (result.length() > 200) {
            result = result.substring(0, 197) + "...";
        }
        return result;
    }
    
    /**
     * 启用指定的 skill
     */
    public void enableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill != null) {
            skill.setEnabled(true);
            enabledSkillNames.add(name);
            logInfo("已启用 Skill: " + name);
        }
    }
    
    /**
     * 禁用指定的 skill
     */
    public void disableSkill(String name) {
        Skill skill = skills.get(name);
        if (skill != null) {
            skill.setEnabled(false);
            enabledSkillNames.remove(name);
            logInfo("已禁用 Skill: " + name);
        }
    }
    
    /**
     * 切换 skill 的启用状态
     */
    public void toggleSkill(String name) {
        Skill skill = skills.get(name);
        if (skill != null) {
            if (skill.isEnabled()) {
                disableSkill(name);
            } else {
                enableSkill(name);
            }
        }
    }
    
    /**
     * 设置 skill 的启用状态
     */
    public void setSkillEnabled(String name, boolean enabled) {
        if (enabled) {
            enableSkill(name);
        } else {
            disableSkill(name);
        }
    }
    
    /**
     * 获取所有已加载的 skills
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }
    
    /**
     * 获取所有已启用的 skills
     */
    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取指定名称的 skill
     */
    public Skill getSkill(String name) {
        return skills.get(name);
    }
    
    /**
     * 检查是否有任何 skill 被启用
     */
    public boolean hasEnabledSkills() {
        return !enabledSkillNames.isEmpty();
    }
    
    /**
     * 获取已启用 skills 的数量
     */
    public int getEnabledSkillCount() {
        return enabledSkillNames.size();
    }
    
    /**
     * 获取已启用的 skill 名称列表（用于持久化）
     */
    public List<String> getEnabledSkillNames() {
        return new ArrayList<>(enabledSkillNames);
    }
    
    /**
     * 设置已启用的 skill 名称列表（用于从持久化恢复）
     */
    public void setEnabledSkillNames(List<String> names) {
        enabledSkillNames.clear();
        if (names != null) {
            enabledSkillNames.addAll(names);
            // 更新 skill 对象的启用状态
            for (Skill skill : skills.values()) {
                skill.setEnabled(enabledSkillNames.contains(skill.getName()));
            }
        }
    }
    
    /**
     * 生成已启用 skills 的系统提示词内容
     * 格式化为适合添加到系统提示词的形式
     */
    public String buildSkillsPrompt() {
        List<Skill> enabledSkills = getEnabledSkills();
        if (enabledSkills.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n# 用户自定义技能指令\n\n");
        sb.append("以下是用户加载的自定义技能，请在相关场景中应用这些指令：\n\n");
        
        for (Skill skill : enabledSkills) {
            sb.append("## Skill: ").append(skill.getName()).append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append("**描述**: ").append(skill.getDescription()).append("\n\n");
            }
            
            // 如果有可执行工具，列出它们
            if (skill.hasTools()) {
                sb.append("**可用工具**:\n");
                for (SkillTool tool : skill.getTools()) {
                    sb.append("- `").append(tool.getFullName()).append("`: ").append(tool.getDescription()).append("\n");
                }
                sb.append("\n");
            }
            
            sb.append(skill.getContent()).append("\n\n");
            sb.append("---\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取所有已启用技能中的工具
     */
    public List<SkillTool> getAllEnabledTools() {
        List<SkillTool> allTools = new ArrayList<>();
        for (Skill skill : getEnabledSkills()) {
            if (skill.hasTools()) {
                allTools.addAll(skill.getTools());
            }
        }
        return allTools;
    }
    
    /**
     * 根据名称获取工具
     */
    public SkillTool getToolByName(String toolName) {
        for (Skill skill : skills.values()) {
            for (SkillTool tool : skill.getTools()) {
                if (tool.getName().equals(toolName) || tool.getFullName().equals(toolName)) {
                    return tool;
                }
            }
        }
        return null;
    }
    
    /**
     * 检查是否有可执行工具
     */
    public boolean hasEnabledTools() {
        return getEnabledSkills().stream()
                .anyMatch(Skill::hasTools);
    }
    
    /**
     * 获取已启用工具的数量
     */
    public int getEnabledToolCount() {
        return getEnabledSkills().stream()
                .mapToInt(Skill::getToolCount)
                .sum();
    }
    
    /**
     * 刷新已更新的 skill 文件
     */
    public void refreshUpdatedSkills() {
        for (Skill skill : skills.values()) {
            if (skill.isFileUpdated()) {
                try {
                    Path path = Paths.get(skill.getFilePath());
                    Skill updated = parseSkillFile(path);
                    if (updated != null) {
                        updated.setEnabled(skill.isEnabled());
                        skills.put(skill.getName(), updated);
                        logInfo("已刷新 Skill: " + skill.getName());
                    }
                } catch (IOException e) {
                    logError("刷新 Skill 失败: " + skill.getName() + " - " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 创建示例 skill 文件
     */
    public void createExampleSkill(String dirPath) {
        // 创建基础示例（无工具）
        Path skillDir = Paths.get(dirPath, "example-skill");
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        
        try {
            Files.createDirectories(skillDir);
            
            String exampleContent = """
                    ---
                    name: example-skill
                    description: 这是一个示例技能，演示如何创建自定义技能
                    ---
                    
                    # Example Skill
                    
                    这是一个示例技能，你可以参考这个格式创建自己的技能。
                    
                    ## 使用场景
                    - 当用户询问某种特定类型的问题时
                    - 当需要执行特定的分析流程时
                    
                    ## 指令
                    1. 首先执行步骤 A
                    2. 然后根据结果执行步骤 B
                    3. 最后生成报告
                    
                    ## 输出格式
                    - 使用 Markdown 格式
                    - 包含清晰的标题和列表
                    
                    ## 示例
                    用户: "请使用示例技能分析这个请求"
                    AI: 好的，我将按照示例技能的指令进行分析...
                    """;
            
            Files.write(skillFile, exampleContent.getBytes(StandardCharsets.UTF_8));
            logInfo("已创建示例 Skill: " + skillFile);
        } catch (IOException e) {
            logError("创建示例 Skill 失败: " + e.getMessage());
        }
        
        // 创建带工具的示例
        Path toolSkillDir = Paths.get(dirPath, "network-scanner");
        Path toolSkillFile = toolSkillDir.resolve(SKILL_FILE_NAME);
        
        try {
            Files.createDirectories(toolSkillDir);
            
            // 根据操作系统选择示例命令
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String pingCmd = isWindows ? "ping" : "/bin/ping";
            String pingArgs = isWindows ? "-n 4 {target}" : "-c 4 {target}";
            
            String toolExampleContent = """
                    ---
                    name: network-scanner
                    description: 网络扫描技能，包含常用的网络诊断工具
                    tools:
                      - name: ping_host
                        description: 使用 ping 测试目标主机的连通性
                        command: "%s"
                        args: "%s"
                        timeout: 30
                        parameters:
                          - name: target
                            description: 目标 IP 地址或域名
                            required: true
                      - name: echo_test
                        description: 简单的回显测试工具
                        command: "%s"
                        args: "%s"
                        timeout: 10
                        parameters:
                          - name: message
                            description: 要回显的消息
                            required: true
                    ---
                    
                    # Network Scanner Skill
                    
                    这是一个网络扫描技能示例，演示如何定义可执行工具。
                    
                    ## 可用工具
                    
                    1. **ping_host**: 测试目标主机的网络连通性
                       - 使用场景：验证目标是否在线
                       - 参数：target（目标 IP 或域名）
                    
                    2. **echo_test**: 简单的回显测试
                       - 使用场景：测试工具执行功能
                       - 参数：message（要回显的内容）
                    
                    ## 使用指南
                    
                    当用户需要进行网络诊断时：
                    1. 首先使用 list_skill_tools 查看可用工具
                    2. 然后使用 execute_skill_tool 执行相应工具
                    3. 分析工具输出并报告结果
                    
                    ## 注意事项
                    
                    - 确保目标主机的合法性
                    - 某些工具可能需要管理员权限
                    - 遵守网络安全法规
                    """.formatted(
                        pingCmd, pingArgs,
                        isWindows ? "cmd" : "/bin/echo",
                        isWindows ? "/c echo {message}" : "{message}"
                    );
            
            Files.write(toolSkillFile, toolExampleContent.getBytes(StandardCharsets.UTF_8));
            logInfo("已创建带工具的示例 Skill: " + toolSkillFile);
        } catch (IOException e) {
            logError("创建带工具的示例 Skill 失败: " + e.getMessage());
        }
    }
    
    private void logInfo(String message) {
        if (api != null) {
            api.logging().logToOutput("[SkillManager] " + message);
        } else {
            System.out.println("[SkillManager] " + message);
        }
    }
    
    private void logError(String message) {
        if (api != null) {
            api.logging().logToError("[SkillManager] " + message);
        } else {
            System.err.println("[SkillManager] " + message);
        }
    }
}
