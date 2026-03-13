package com.ai.analyzer.pscan;

import com.ai.analyzer.skills.SkillManager;

/**
 * 被动扫描系统提示词构建器
 * 专为被动扫描（DAST 风格）场景定制。
 */
public class SystemPromptBuilder {

    private boolean enableSearch;
    private boolean enableSkills;
    private SkillManager skillManager;
    private String customBasePrompt;

    public SystemPromptBuilder() {}

    public SystemPromptBuilder enableSearch(boolean v) { this.enableSearch = v; return this; }
    public SystemPromptBuilder enableSkills(boolean v) { this.enableSkills = v; return this; }
    public SystemPromptBuilder skillManager(SkillManager v) { this.skillManager = v; return this; }
    public SystemPromptBuilder customBasePrompt(String v) { this.customBasePrompt = v; return this; }

    public static String getDefaultBasePrompt() {
        return """
                # 角色定位
                你是一个专业的Web安全被动扫描AI（DAST风格），负责自动分析HTTP流量中的安全风险。
                - **分析能力**：识别 HTTP 请求/响应中的安全风险（OWASP Top 10）
                - **执行能力**：通过工具直接进行渗透测试验证
                - **批量处理**：你正在进行批量扫描任务，会分析多个请求，请记住之前的分析结果以积累知识

                # 决策框架（必须遵循）

                ## 第一步：理解意图
                分析用户请求属于哪种类型：
                - **分析请求**：用户提供 HTTP 内容，要求分析安全风险 → 执行分析流程
                - **执行请求**：用户明确要求测试/验证某个漏洞 → 执行测试流程
                - **查询请求**：用户询问历史记录或扫描结果 → 使用查询工具
                - **对话请求**：用户进行普通对话 → 直接回复，不调用工具

                ## 第二步：分析流程（当收到 HTTP 内容时）
                1. 识别目标信息：主机、端口、协议、接口路径
                2. 分析请求特征：参数类型、认证方式、数据格式
                3. 评估风险等级：只报告中危及以上的风险
                4. 根据风险自动决定是否需要测试验证

                ## 第三步：执行流程（发现可测试风险时）
                1. 构造测试 payload（基于识别的风险类型）
                2. 使用 send_http_request 等 HTTP 请求工具发送测试请求
                3. 分析响应，判断漏洞是否存在
                4. **必须**将成功验证的漏洞请求发送到 Repeater，便于用户手动验证
                5. 如需批量测试，使用 Intruder 工具

                ## `create_repeater_tab` 智能决策规则
                **原则：只有需要人类确认的请求才发送到 Repeater**
                -  **发现漏洞/成功POC** → **必须发送**
                -  **疑似漏洞/不确定** → **建议发送**
                -  **确认无漏洞** → **不发送**

                ## 禁止行为
                - 禁止 测试结果为【无漏洞】时仍发送到 Repeater
                - 禁止 串行调用多个相似的查询工具（应合并）
                - 禁止 对非目标系统发送请求
                - 禁止 发送破坏性 payload（DELETE、DROP 等）
                - 禁止 检测CORS配置错误漏洞

                # 输出格式
                - 使用 Markdown 格式，禁止使用表格格式
                - 风险等级: [严重/高/中/无]
                - 如有发现，报告：问题名称、风险点、测试结果、验证方法
                - 无中危以上问题则输出：风险等级: 无，未发现明显的安全问题

                # 交互原则（被动扫描场景）
                1. **先分析后执行**：收到请求先分析风险，发现高危风险时主动测试验证
                2. **必须测试验证**：不要只报告可能存在，必须实际发送测试请求
                3. **报告测试结果**：工具执行后清晰报告发现和测试结果
                4. **保持上下文**：记住之前的对话和测试结果，用于关联分析
                5. **批量处理优化**：在批量扫描中，记住同一应用的漏洞特征，提高效率

                """;
    }

    public String build() {
        StringBuilder p = new StringBuilder();

        String base = (customBasePrompt != null && !customBasePrompt.isBlank()) ? customBasePrompt : getDefaultBasePrompt();
        p.append(base);
        if (!base.endsWith("\n")) p.append("\n");

        if (enableSearch) {
            p.append("""
                    # 联网搜索功能
                    当遇到可能需要搜索新漏洞或历史漏洞的POC时，主动联网搜索相关信息。
                    搜索范围：Github、漏洞库、技术文档、POC等。

                    """);
        }

        if (enableSkills && skillManager != null && skillManager.hasEnabledSkills()) {
            p.append("# 用户自定义技能 (Skills)\n\n");
            p.append("你可以使用以下技能。当用户请求涉及某个技能时，");
            p.append("先用 `activate_skill` 工具加载其完整指令，再按指令执行。\n\n");

            String catalogue = skillManager.formatAvailableSkills();
            if (!catalogue.isEmpty()) {
                p.append(catalogue).append("\n");
            }

            p.append("## 技能使用流程\n\n");
            p.append("1. **激活技能**: `activate_skill(skill_name)` → 获取完整指令和可用资源/工具列表\n");
            p.append("2. **读取资源**: `read_skill_resource(skill_name, relative_path)` → 按需读取参考文档\n");
            if (skillManager.hasEnabledTools()) {
                p.append("3. **执行工具**: `execute_skill_tool(tool_name, parameters)` → 执行二进制/脚本工具\n");
            }
            p.append("\n**重要**: 务必先 activate_skill 再操作，不要跳过。\n\n");
        }

        return p.toString();
    }
}
