package com.ai.analyzer.rulesMatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对比 HardcodedRules 与 JSON 规则库的统计是否一致。
 * 运行方式：在项目根目录执行
 *   mvn exec:java -Dexec.mainClass="com.ai.analyzer.rulesMatch.RulesConsistencyCheck"
 * 或配置 IDE 运行 RulesConsistencyCheck.main()
 */
public class RulesConsistencyCheck {

    public static void main(String[] args) {
        List<VulnerabilityRule> rules = HardcodedRules.getAllRules();

        int totalRules = rules.size();
        int totalPatterns = 0;
        Map<String, int[]> byType = new TreeMap<>(); // type -> [ruleCount, patternCount]

        for (VulnerabilityRule r : rules) {
            String type = r.getType() != null ? r.getType() : "";
            int[] counts = byType.computeIfAbsent(type, k -> new int[] { 0, 0 });
            counts[0]++;
            int n = (r.getPatterns() != null) ? r.getPatterns().size() : 0;
            counts[1] += n;
            totalPatterns += n;
        }

        System.out.println("=== HardcodedRules 统计 ===");
        System.out.println("总规则数: " + totalRules);
        System.out.println("总模式数: " + totalPatterns);
        System.out.println("\n按类型:");
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            System.out.println("  " + e.getKey() + ": rules=" + e.getValue()[0] + ", patterns=" + e.getValue()[1]);
        }

        // 校验：每条规则的 patterns 均有效
        List<String> invalid = new ArrayList<>();
        for (VulnerabilityRule r : rules) {
            if (r.getPatterns() == null || r.getPatterns().isEmpty()) {
                invalid.add(r.getType() + "/" + r.getName());
            } else {
                for (VulnerabilityRule.CompiledPattern cp : r.getPatterns()) {
                    if (!cp.isValid()) {
                        invalid.add(r.getType() + "/" + r.getName() + " pattern invalid: " + cp.getOriginalPattern());
                    }
                }
            }
        }
        if (!invalid.isEmpty()) {
            System.err.println("\n[校验] 无效规则或模式:");
            invalid.forEach(s -> System.err.println("  " + s));
        } else {
            System.out.println("\n[校验] 所有规则的 pattern 均编译有效.");
        }

        // 输出机器可读格式（便于与 Python 脚本输出 diff）
        System.out.println("\n--- 机器可读格式 ---");
        System.out.println("{\"total_rules\":" + totalRules + ",\"total_patterns\":" + totalPatterns + ",\"by_type\":{");
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            entries.add("\"" + e.getKey() + "\":{\"rules\":" + e.getValue()[0] + ",\"patterns\":" + e.getValue()[1] + "}");
        }
        System.out.println(String.join(",", entries));
        System.out.println("}}");
    }
}
