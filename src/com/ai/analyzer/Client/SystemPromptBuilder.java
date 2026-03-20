package com.ai.analyzer.Client;

import com.ai.analyzer.skills.SkillManager;

public class SystemPromptBuilder {
    
    private boolean enableSearch;
    private boolean enableMcp;
    private boolean enableRagMcp;
    private boolean enableChromeMcp;
    private boolean enableFileSystemAccess;
    private boolean enableSkills;
    private String ragMcpDocumentsPath;
    private SkillManager skillManager;
    private String customBasePrompt;
    
    public SystemPromptBuilder() {}
    
    public SystemPromptBuilder enableSearch(boolean v) { this.enableSearch = v; return this; }
    public SystemPromptBuilder enableMcp(boolean v) { this.enableMcp = v; return this; }
    public SystemPromptBuilder enableRagMcp(boolean v) { this.enableRagMcp = v; return this; }
    public SystemPromptBuilder enableChromeMcp(boolean v) { this.enableChromeMcp = v; return this; }
    public SystemPromptBuilder enableFileSystemAccess(boolean v) { this.enableFileSystemAccess = v; return this; }
    public SystemPromptBuilder enableSkills(boolean v) { this.enableSkills = v; return this; }
    public SystemPromptBuilder ragMcpDocumentsPath(String v) { this.ragMcpDocumentsPath = v; return this; }
    public SystemPromptBuilder skillManager(SkillManager v) { this.skillManager = v; return this; }
    public SystemPromptBuilder customBasePrompt(String v) { this.customBasePrompt = v; return this; }
    
    public static String getDefaultBasePrompt() {
        return """
                # 角色定位
                你是一个专业的 Web 渗透测试大师，同时是Burpsuite当中的插件，具备以下能力：
                - **分析能力**：识别 HTTP 请求/响应中的安全风险（OWASP Top 10）
                - **执行能力**：通过工具直接进行渗透测试验证
                - **辅助能力**：为渗透测试工程师提供测试建议和 POC

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
                - 不使用 # 标题语法
                - 简洁明了，只报告中危及以上风险

                # 交互原则
                1. **先分析后执行**：收到请求先分析风险，发现高危风险时主动测试验证
                2. **解释决策**：调用工具前简要说明原因
                3. **报告结果**：工具执行后清晰报告发现
                4. **保持上下文**：记住之前的对话和测试结果

                """;
    }
    
    public String build() {
        StringBuilder p = new StringBuilder();

        String base = (customBasePrompt != null && !customBasePrompt.isBlank()) ? customBasePrompt : getDefaultBasePrompt();
        p.append(base);
        if (!base.endsWith("\n")) p.append("\n");

        return p.toString();
    }
}
