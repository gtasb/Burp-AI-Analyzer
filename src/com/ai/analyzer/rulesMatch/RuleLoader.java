package com.ai.analyzer.rulesMatch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import burp.api.montoya.MontoyaApi;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则加载器 - 从JSON文件加载漏洞匹配规则
 */
public class RuleLoader {
    
    private final MontoyaApi api;
    private final Gson gson;
    
    public RuleLoader(MontoyaApi api) {
        this.api = api;
        this.gson = new Gson();
    }
    
    /**
     * 从文件加载规则
     * 
     * @param filePath 规则文件路径（相对路径或绝对路径）
     * @return 规则列表
     */
    public List<VulnerabilityRule> loadRules(String filePath) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        try {
            JsonObject root = loadJsonFile(filePath);
            if (root == null) {
                api.logging().logToError("无法加载规则文件: " + filePath);
                return rules;
            }
            
            JsonObject categories = root.getAsJsonObject("categories");
            if (categories == null) {
                api.logging().logToError("规则文件格式错误：缺少 'categories' 字段");
                return rules;
            }
            
            // 解析所有漏洞类型规则
            parseCategory(categories, "sqli", "SQL注入", rules, this::parseSqlInjectionRules);
            parseCategory(categories, "command_injection", "命令注入", rules, this::parseCommandInjectionRules);
            parseCategory(categories, "file_inclusion", "文件包含", rules, this::parseSimplePatternRules);
            parseCategory(categories, "xxe", "XXE", rules, this::parseSimplePatternRules);
            parseCategory(categories, "xxe_extended", "XXE扩展", rules, this::parseSimplePatternRules);
            parseCategory(categories, "ldap_injection", "LDAP注入", rules, this::parseSimplePatternRules);
            parseCategory(categories, "xpath_injection", "XPath注入", rules, this::parseSimplePatternRules);
            parseCategory(categories, "pii_detection", "个人信息检测", rules, this::parseSimplePatternRules);
            parseCategory(categories, "sensitive_info", "敏感信息泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "cors", "CORS配置错误", rules, this::parseSimplePatternRules);
            parseCategory(categories, "clickjacking", "点击劫持", rules, this::parseSimplePatternRules);
            parseCategory(categories, "deserialization", "反序列化", rules, this::parseSimplePatternRules);
            parseCategory(categories, "jndi_injection", "JNDI注入", rules, this::parseSimplePatternRules);
            parseCategory(categories, "json_error", "JSON错误", rules, this::parseSimplePatternRules);
            parseCategory(categories, "jsonp", "JSONP信息泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "redirect", "开放重定向", rules, this::parseSimplePatternRules);
            parseCategory(categories, "unauthorized_access", "未授权访问", rules, this::parseSimplePatternRules);
            parseCategory(categories, "backup_files", "备份文件泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "source_code", "源代码泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "viewstate", "ViewState未加密", rules, this::parseSimplePatternRules);
            parseCategory(categories, "sensitive_files", "敏感文件泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "crlf_injection", "CRLF注入", rules, this::parseSimplePatternRules);
            parseCategory(categories, "redos", "正则拒绝服务", rules, this::parseSimplePatternRules);
            parseCategory(categories, "captcha_bypass", "验证码绕过", rules, this::parseSimplePatternRules);
            parseCategory(categories, "ssti", "模板注入", rules, this::parseSstiRules);
            parseCategory(categories, "fastjson", "Fastjson反序列化", rules, this::parseSimplePatternRules);
            parseCategory(categories, "php_path_leak", "PHP路径泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "error_messages", "错误消息", rules, this::parseSimplePatternRules);
            parseCategory(categories, "http_smuggling", "HTTP走私", rules, this::parseSimplePatternRules);
            parseCategory(categories, "ssrf_extended", "SSRF扩展", rules, this::parseSsrfExtendedRules);
            parseCategory(categories, "directory_listing", "目录列表", rules, this::parseSimplePatternRules);
            parseCategory(categories, "weak_password", "弱密码", rules, this::parseSimplePatternRules);
            parseCategory(categories, "host_header_injection", "Host头注入", rules, this::parseSimplePatternRules);
            parseCategory(categories, "phpinfo_disclosure", "PHPInfo泄露", rules, this::parseSimplePatternRules);
            parseCategory(categories, "editor_backup", "编辑器备份", rules, this::parseSimplePatternRules);
            parseCategory(categories, "code_injection_extended", "代码注入扩展", rules, this::parseSimplePatternRules);
            parseCategory(categories, "parameter_safety_check", "参数安全检查", rules, this::parseSimplePatternRules);
            parseCategory(categories, "content_type_detection", "内容类型检测", rules, this::parseSimplePatternRules);
            
            api.logging().logToOutput("[RuleLoader] 成功加载 " + rules.size() + " 条规则");
            
        } catch (Exception e) {
            api.logging().logToError("加载规则时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        return rules;
    }
    
    /**
     * 从文件或资源加载JSON
     */
    private JsonObject loadJsonFile(String filePath) throws IOException {
        // 尝试从文件系统加载
        File file = new File(filePath);
        if (file.exists() && file.isFile()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, JsonObject.class);
            }
        }
        
        // 尝试从classpath加载
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filePath)) {
            if (is != null) {
                try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    return gson.fromJson(reader, JsonObject.class);
                }
            }
        }
        
        return null;
    }
    
    /**
     * 解析 SQL 注入规则
     */
    private List<VulnerabilityRule> parseSqlInjectionRules(JsonObject sqliObj, String type, String defaultName) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        String name = sqliObj.has("name") ? sqliObj.get("name").getAsString() : defaultName;
        String severity = sqliObj.has("severity") ? sqliObj.get("severity").getAsString() : "high";
        
        JsonObject databases = sqliObj.getAsJsonObject("databases");
        if (databases != null) {
            for (Map.Entry<String, JsonElement> entry : databases.entrySet()) {
                String dbType = entry.getKey();
                JsonObject dbObj = entry.getValue().getAsJsonObject();
                
                VulnerabilityRule rule = new VulnerabilityRule();
                rule.setType("sqli");
                rule.setName(name);
                rule.setSeverity(severity);
                rule.setPatterns(extractPatterns(dbObj, dbType));
                
                if (!rule.getPatterns().isEmpty()) {
                    rules.add(rule);
                }
            }
        }
        
        return rules;
    }
    
    /**
     * 解析命令注入规则
     */
    private List<VulnerabilityRule> parseCommandInjectionRules(JsonObject cmdObj, String type, String defaultName) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        String name = cmdObj.has("name") ? cmdObj.get("name").getAsString() : defaultName;
        String severity = cmdObj.has("severity") ? cmdObj.get("severity").getAsString() : "high";
        
        JsonObject patterns = cmdObj.getAsJsonObject("patterns");
        if (patterns != null) {
            for (Map.Entry<String, JsonElement> entry : patterns.entrySet()) {
                String osType = entry.getKey(); // linux, windows, etc.
                JsonElement patternElem = entry.getValue();
                
                List<VulnerabilityRule.CompiledPattern> compiledPatterns = new ArrayList<>();
                
                if (patternElem.isJsonObject()) {
                    JsonObject patternObj = patternElem.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> subEntry : patternObj.entrySet()) {
                        String subType = osType + "_" + subEntry.getKey();
                        if (subEntry.getValue().isJsonArray()) {
                            JsonArray arr = subEntry.getValue().getAsJsonArray();
                            for (JsonElement elem : arr) {
                                String pattern = elem.getAsString();
                                VulnerabilityRule.CompiledPattern cp = 
                                    new VulnerabilityRule.CompiledPattern(pattern, subType);
                                if (cp.isValid()) {
                                    compiledPatterns.add(cp);
                                }
                            }
                        }
                    }
                } else if (patternElem.isJsonArray()) {
                    JsonArray arr = patternElem.getAsJsonArray();
                    for (JsonElement elem : arr) {
                        String pattern = elem.getAsString();
                        VulnerabilityRule.CompiledPattern cp = 
                            new VulnerabilityRule.CompiledPattern(pattern, osType);
                        if (cp.isValid()) {
                            compiledPatterns.add(cp);
                        }
                    }
                }
                
                if (!compiledPatterns.isEmpty()) {
                    VulnerabilityRule rule = new VulnerabilityRule();
                    rule.setType("command_injection");
                    rule.setName(name);
                    rule.setSeverity(severity);
                    rule.setPatterns(compiledPatterns);
                    rules.add(rule);
                }
            }
        }
        
        return rules;
    }
    
    /**
     * 统一的类别解析方法
     */
    @FunctionalInterface
    private interface CategoryParser {
        List<VulnerabilityRule> parse(JsonObject obj, String type, String defaultName);
    }
    
    private void parseCategory(JsonObject categories, String key, String defaultName, 
                               List<VulnerabilityRule> rules, CategoryParser parser) {
        if (categories.has(key)) {
            try {
                List<VulnerabilityRule> parsed = parser.parse(categories.getAsJsonObject(key), key, defaultName);
                rules.addAll(parsed);
            } catch (Exception e) {
                api.logging().logToError("[RuleLoader] 解析 " + key + " 规则失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 解析 SSTI (服务端模板注入) 规则
     */
    private List<VulnerabilityRule> parseSstiRules(JsonObject sstiObj, String type, String defaultName) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        String name = sstiObj.has("name") ? sstiObj.get("name").getAsString() : defaultName;
        String severity = sstiObj.has("severity") ? sstiObj.get("severity").getAsString() : "high";
        
        // 解析模板引擎规则
        if (sstiObj.has("templates")) {
            JsonObject templates = sstiObj.getAsJsonObject("templates");
            for (Map.Entry<String, JsonElement> entry : templates.entrySet()) {
                String engine = entry.getKey();
                JsonObject engineObj = entry.getValue().getAsJsonObject();
                
                // 提取测试payload作为匹配模式
                if (engineObj.has("test_payloads")) {
                    List<VulnerabilityRule.CompiledPattern> patterns = new ArrayList<>();
                    JsonArray payloads = engineObj.getAsJsonArray("test_payloads");
                    for (JsonElement payload : payloads) {
                        String pattern = Pattern.quote(payload.getAsString()); // 精确匹配
                        VulnerabilityRule.CompiledPattern cp = 
                            new VulnerabilityRule.CompiledPattern(pattern, engine);
                        if (cp.isValid()) {
                            patterns.add(cp);
                        }
                    }
                    
                    if (!patterns.isEmpty()) {
                        VulnerabilityRule rule = new VulnerabilityRule();
                        rule.setType(type);
                        rule.setName(name + "-" + engine);
                        rule.setSeverity(severity);
                        rule.setPatterns(patterns);
                        rules.add(rule);
                    }
                }
            }
        }
        
        return rules;
    }
    
    /**
     * 解析 SSRF 扩展规则
     */
    private List<VulnerabilityRule> parseSsrfExtendedRules(JsonObject ssrfObj, String type, String defaultName) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        String name = ssrfObj.has("name") ? ssrfObj.get("name").getAsString() : defaultName;
        String severity = ssrfObj.has("severity") ? ssrfObj.get("severity").getAsString() : "high";
        
        // 解析错误模式
        if (ssrfObj.has("error_patterns")) {
            List<VulnerabilityRule.CompiledPattern> patterns = 
                extractPatternsFromElement(ssrfObj.get("error_patterns"), "error");
            
            if (!patterns.isEmpty()) {
                VulnerabilityRule rule = new VulnerabilityRule();
                rule.setType(type);
                rule.setName(name);
                rule.setSeverity(severity);
                rule.setPatterns(patterns);
                rules.add(rule);
            }
        }
        
        // 解析响应指示器
        if (ssrfObj.has("response_indicators")) {
            List<VulnerabilityRule.CompiledPattern> patterns = 
                extractPatternsFromElement(ssrfObj.get("response_indicators"), "response");
            
            if (!patterns.isEmpty()) {
                VulnerabilityRule rule = new VulnerabilityRule();
                rule.setType(type);
                rule.setName(name + "-响应特征");
                rule.setSeverity(severity);
                rule.setPatterns(patterns);
                rules.add(rule);
            }
        }
        
        return rules;
    }
    
    /**
     * 解析简单模式规则（通用方法）
     */
    private List<VulnerabilityRule> parseSimplePatternRules(JsonObject obj, String type, String defaultName) {
        List<VulnerabilityRule> rules = new ArrayList<>();
        
        String name = obj.has("name") ? obj.get("name").getAsString() : defaultName;
        String severity = obj.has("severity") ? obj.get("severity").getAsString() : "medium";
        
        // 查找 patterns 字段
        JsonElement patternsElem = obj.get("patterns");
        if (patternsElem != null) {
            List<VulnerabilityRule.CompiledPattern> compiledPatterns = extractPatternsFromElement(patternsElem, null);
            
            if (!compiledPatterns.isEmpty()) {
                VulnerabilityRule rule = new VulnerabilityRule();
                rule.setType(type);
                rule.setName(name);
                rule.setSeverity(severity);
                rule.setPatterns(compiledPatterns);
                rules.add(rule);
            }
        }
        
        return rules;
    }
    
    /**
     * 从JsonObject提取patterns数组
     */
    private List<VulnerabilityRule.CompiledPattern> extractPatterns(JsonObject obj, String subType) {
        List<VulnerabilityRule.CompiledPattern> result = new ArrayList<>();
        
        JsonArray patterns = obj.getAsJsonArray("patterns");
        if (patterns != null) {
            for (JsonElement elem : patterns) {
                String pattern = elem.getAsString();
                VulnerabilityRule.CompiledPattern cp = 
                    new VulnerabilityRule.CompiledPattern(pattern, subType);
                if (cp.isValid()) {
                    result.add(cp);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 从JsonElement提取patterns（递归处理嵌套结构）
     */
    private List<VulnerabilityRule.CompiledPattern> extractPatternsFromElement(JsonElement elem, String subType) {
        List<VulnerabilityRule.CompiledPattern> result = new ArrayList<>();
        
        if (elem.isJsonArray()) {
            JsonArray arr = elem.getAsJsonArray();
            for (JsonElement item : arr) {
                if (item.isJsonPrimitive()) {
                    String pattern = item.getAsString();
                    VulnerabilityRule.CompiledPattern cp = 
                        new VulnerabilityRule.CompiledPattern(pattern, subType);
                    if (cp.isValid()) {
                        result.add(cp);
                    }
                }
            }
        } else if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String newSubType = entry.getKey();
                result.addAll(extractPatternsFromElement(entry.getValue(), newSubType));
            }
        }
        
        return result;
    }
}
