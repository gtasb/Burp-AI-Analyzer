package com.ai.analyzer.ui.active;

/**
 * 主动分析页面的快捷任务预设：常见渗透测试任务一键填充提示词。
 */
public final class QuickTaskPresets {

    public record QuickTask(String label, String prompt) {}

    private QuickTaskPresets() {}

    public static QuickTask[] defaults() {
        return new QuickTask[]{
            new QuickTask("SQL注入", "检测该请求中所有参数是否存在 SQL 注入漏洞（含布尔/时间盲注、报错注入、联合查询、堆叠注入），给出测试 payload 与确认方法"),
            new QuickTask("XSS", "检测该请求及响应回显场景下的反射型/存储型/DOM XSS 可能性，给出绕过 WAF 的测试 payload 与验证方式"),
            new QuickTask("SSRF", "检测该请求中可作为 URL 输入向量的参数（url、redirect、file、image、callback 等）是否存在 SSRF，给出内网探测与 DNS 外带验证方法"),
            new QuickTask("越权/IDOR", "检测请求中 ID/对象标识参数是否存在水平越权（IDOR）与垂直越权风险，给出替换标识符的验证思路"),
            new QuickTask("文件读取", "检测路径参数与文件名参数是否存在目录穿越、任意文件读取（含编码绕过）风险，给出测试 payload"),
            new QuickTask("生成Payload", "针对该请求的认证/加密机制（如 JWT、自定义签名、加密参数）分析绕过思路并生成可利用的 Payload 与工具化脚本建议"),
        };
    }
}
