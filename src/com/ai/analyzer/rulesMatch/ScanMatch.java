package com.ai.analyzer.rulesMatch;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 扫描匹配结果
 */
@Data
@AllArgsConstructor
public class ScanMatch {
    /**
     * 漏洞类型（如：SQL注入、XSS等）
     */
    private String vulnerabilityType;
    
    /**
     * 匹配到的具体字符串
     */
    private String matchedString;
    
    /**
     * 危险等级（high、medium、low）
     */
    private String severity;
    
    /**
     * 匹配到的数据库类型（SQL注入特有）
     */
    private String databaseType;
    
    /**
     * 生成简短的提示信息（用于追加到UserPrompt）
     */
    public String toPromptHint() {
        if (databaseType != null && !databaseType.isEmpty()) {
            return String.format("%s-%s漏洞（检测到：%s）", 
                vulnerabilityType, databaseType, truncate(matchedString, 50));
        } else {
            return String.format("%s漏洞（检测到：%s）", 
                vulnerabilityType, truncate(matchedString, 50));
        }
    }
    
    /**
     * 生成详细的UI显示信息
     */
    public String toUiMessage() {
        if (databaseType != null && !databaseType.isEmpty()) {
            return String.format("前置扫描器匹配到疑似 %s-%s漏洞，检测到的字符串为：%s", 
                vulnerabilityType, databaseType, truncate(matchedString, 100));
        } else {
            return String.format("前置扫描器匹配到疑似 %s漏洞，检测到的字符串为：%s", 
                vulnerabilityType, truncate(matchedString, 100));
        }
    }
    
    /**
     * 截断字符串避免过长
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}
