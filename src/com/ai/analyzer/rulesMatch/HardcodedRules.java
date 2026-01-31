package com.ai.analyzer.rulesMatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 硬编码漏洞匹配规则 - 不依赖外部 JSON 文件
 * 规则与 scanners/漏洞匹配规则库.json 一致，全部写死在代码中
 */
public final class HardcodedRules {

    private static volatile List<VulnerabilityRule> RULES;

    /**
     * 获取所有规则（懒加载，线程安全）
     */
    public static List<VulnerabilityRule> getAllRules() {
        if (RULES == null) {
            synchronized (HardcodedRules.class) {
                if (RULES == null) {
                    RULES = buildAllRules();
                }
            }
        }
        return new ArrayList<>(RULES);
    }

    private static List<VulnerabilityRule> buildAllRules() {
        List<VulnerabilityRule> rules = new ArrayList<>();

        // ========== SQL 注入（各数据库） ==========
        addSqliRules(rules);
        // ========== 命令注入 ==========
        addCommandInjectionRules(rules);
        // ========== 文件包含 / XXE / LDAP / XPath ==========
        addFileInclusionRules(rules);
        addXxeRules(rules);
        addLdapRules(rules);
        addXpathRules(rules);
        // ========== 敏感信息 / PII / CORS / 点击劫持 ==========
        addPiiAndSensitiveRules(rules);
        addCorsClickjackingRules(rules);
        // ========== 反序列化 / JNDI / JSON / 重定向 / 未授权 ==========
        addDeserializationJndiJsonRules(rules);
        // ========== 备份/源码/ViewState/敏感文件/CRLF/ReDoS/验证码 ==========
        addBackupSourceViewstateRules(rules);
        // ========== SSTI 模板注入 ==========
        addSstiRules(rules);
        // ========== Fastjson / PHP路径 / 错误消息 / HTTP走私 / XXE扩展 ==========
        addFastjsonPhpErrorRules(rules);
        // ========== SSRF 扩展 ==========
        addSsrfExtendedRules(rules);
        // ========== 目录列表 / 弱密码 / Host头 / PHPInfo / 编辑器备份 ==========
        addDirectoryWeakHostRules(rules);
        // ========== 代码注入扩展 / 参数安全 / 内容类型 ==========
        addCodeInjectionParamRules(rules);
        // ========== 扩展：服务指纹 / 文件上传 / DNS / 缓存 ==========
        addServiceFingerprintsAndExtendedRules(rules);

        return rules;
    }

    private static void addRule(List<VulnerabilityRule> rules, String type, String name, String severity, String subType, String[] patternStrs) {
        List<VulnerabilityRule.CompiledPattern> patterns = new ArrayList<>();
        for (String s : patternStrs) {
            VulnerabilityRule.CompiledPattern cp = new VulnerabilityRule.CompiledPattern(s, subType);
            if (cp.isValid()) patterns.add(cp);
        }
        if (patterns.isEmpty()) return;
        VulnerabilityRule rule = new VulnerabilityRule();
        rule.setType(type);
        rule.setName(name);
        rule.setSeverity(severity);
        rule.setPatterns(patterns);
        rules.add(rule);
    }

    private static void addSimpleRule(List<VulnerabilityRule> rules, String type, String name, String severity, String[] patternStrs) {
        addRule(rules, type, name, severity, null, patternStrs);
    }

    private static void addSqliRules(List<VulnerabilityRule> rules) {
        String type = "sqli";
        String severity = "high";
        String baseName = "SQL注入";

        addRule(rules, type, baseName, severity, "mysql", new String[] {
            "valid MySQL", "mysql_", "on MySQL result index", "You have an error in your SQL syntax",
            "MySQL server version for the right syntax to use", "\\[MySQL\\]\\[ODBC", "Column count doesn't match",
            "the used select statements have different number of columns", "Table '[^']+' doesn't exist",
            "DBD::mysql::st execute failed", "mysqli\\.query", "SQL syntax.*?MySQL", "Warning.*?\\Wmysqli?_",
            "MySQLSyntaxErrorException", "check the manual that (corresponds to|fits) your",
            "Unknown column '[^ ]+' in 'field list'", "MySqlClient\\.", "com\\.mysql\\.jdbc",
            "Zend_Db_(Adapter|Statement)_Mysqli_Exception", "Pdo[./_\\\\](Mysql)", "MySqlException",
            "SQLSTATE\\[\\d+\\]: Syntax error or access violation"
        });
        addRule(rules, type, baseName, severity, "mssql", new String[] {
            "System\\.Data\\.OleDb\\.OleDbException", "\\[SQL Server\\]", "\\[SQLServer JDBC Driver\\]",
            "\\[Microsoft\\]\\[ODBC SQL Server Driver\\]", "\\[SqlException", "System\\.Data\\.SqlClient\\.",
            "mssql_query\\(\\)", "Microsoft OLE DB Provider for", "Incorrect syntax near",
            "Sintaxis incorrecta cerca de", "ADODB\\.Field \\(0x800A0BCD\\)",
            "Procedure '[^']+' requires parameter '[^']+'", "ADODB\\.Recordset'", "Driver.*? SQL[\\-_\\ ]*Server",
            "OLE DB.*? SQL Server", "SQL Server[^<\"]+Driver", "SQLServerException",
            "Unclosed quotation mark after the character string"
        });
        addRule(rules, type, baseName, severity, "postgresql", new String[] {
            "PostgreSQL query failed", "pg_query\\(\\) \\[:", "pg_exec\\(\\) \\[:", "valid PostgreSQL result",
            "Npgsql", "Warning.*?\\Wpg_", "org\\.postgresql\\.util\\.PSQLException", "PostgreSQL.*?ERROR",
            "PG::SyntaxError:", "ERROR:\\s\\ssyntax error at or near", "ERROR: parser: parse error at or near",
            "org\\.postgresql\\.jdbc", "Pdo[./_\\\\]Pgsql", "PSQLException"
        });
        addRule(rules, type, baseName, severity, "oracle", new String[] {
            "(PLS|ORA)-[0-9]{4}", "Oracle error", "Oracle.*?Driver", "Oracle.*?Database",
            "Warning.*?\\W(oci|ora)_", "quoted string not properly terminated", "SQL command not properly ended",
            "oracle\\.jdbc", "OracleException"
        });
        addRule(rules, type, baseName, severity, "sqlite", new String[] {
            "SQLite/JDBCDriver", "SQLITE_ERROR", "SQLite\\.Exception", "Warning.*?sqlite_",
            "\\[SQLITE_ERROR\\]", "SQLite error \\d+:", "sqlite3\\.OperationalError:", "SQLite3::SQLException",
            "org\\.sqlite\\.JDBC", "SQLiteException"
        });
        addRule(rules, type, baseName, severity, "db2", new String[] {
            "DB2 SQL error:", "internal error \\[IBM\\]\\[CLI Driver\\]\\[DB2/6000\\]", "SQLSTATE=\\d+",
            "\\[CLI Driver\\]", "CLI Driver.*?DB2", "\\bdb2_\\w+\\(", "SQLCODE[=:\\d, -]+SQLSTATE",
            "com\\.ibm\\.db2\\.jcc", "DB2Exception"
        });
        addRule(rules, type, baseName, severity, "generic", new String[] {
            "Division by zero", "Unable to connect to database", "DB Error", "query failed",
            "Database.*?error", "SQL command.*?not properly ended", "Malformed query", "DatabaseException",
            "Driver.*?SQL", "Invalid column name", "Column.*?not found", "Table.*?not found",
            "SQL statement was not properly terminated", "Unclosed quotation mark", "java\\.sql\\.SQL",
            "QuerySyntaxException", "列 [\\\"']?[\\w]+[\\\"']? 不存在", "附近的语法不正确|附近有语法错误|后的引号不完整|未闭合"
        });
        addRule(rules, type, baseName, severity, "sybase", new String[] {
            "Sybase message:", "Sybase Driver", "\\[SYBASE\\]", "Warning.*?\\Wsybase_",
            "SybSQLException", "Sybase\\.Data\\.AseClient", "com\\.sybase\\.jdbc"
        });
        addRule(rules, type, baseName, severity, "access", new String[] {
            "Syntax error in query expression", "Data type mismatch in criteria expression",
            "Microsoft JET Database Engine", "Access Database Engine", "Microsoft Access (\\d+ )?Driver",
            "ODBC Microsoft Access", "Syntax error \\(missing operator\\) in query expression"
        });
        addRule(rules, type, baseName, severity, "informix", new String[] {
            "com\\.informix\\.jdbc", "Dynamic Page Generation Error:", "An illegal character has been found in the statement",
            "\\[Informix\\]", "Warning.*?\\Wifx_", "Exception.*?Informix", "Informix ODBC Driver",
            "weblogic\\.jdbc\\.informix", "IfxException"
        });
        addRule(rules, type, baseName, severity, "interbase", new String[] {
            "<b>Warning</b>:  ibase_", "Dynamic SQL Error", "Unexpected end of command in statement"
        });
        addRule(rules, type, baseName, severity, "frontbase", new String[] {
            "Exception (condition )?\\d+\\. Transaction rollback", "com\\.frontbase\\.jdbc",
            "Syntax error 1\\. Missing", "(Semantic|Syntax) error [1-4]\\d{2}\\."
        });
        addRule(rules, type, baseName, severity, "hsqldb", new String[] {
            "Unexpected end of command in statement \\[", "Unexpected token.*?in statement \\[",
            "org\\.hsqldb\\.jdbc"
        });
        addRule(rules, type, baseName, severity, "h2", new String[] { "org\\.h2\\.jdbc", "\\[42000-192\\]" });
        addRule(rules, type, baseName, severity, "monetdb", new String[] {
            "![0-9]{5}![^\\n]+(failed|unexpected|error|syntax|expected|violation|exception)",
            "\\[MonetDB\\]\\[ODBC Driver", "nl\\.cwi\\.monetdb\\.jdbc"
        });
        addRule(rules, type, baseName, severity, "apache_derby", new String[] {
            "Syntax error: Encountered", "org\\.apache\\.derby", "ERROR 42X01"
        });
        addRule(rules, type, baseName, severity, "vertica", new String[] {
            ", Sqlstate: (3F|42).{3}, (Routine|Hint|Position):", "/vertica/Parser/scan", "com\\.vertica\\.jdbc"
        });
    }

    private static void addCommandInjectionRules(List<VulnerabilityRule> rules) {
        addRule(rules, "command_injection", "命令注入", "high", "linux_basic", new String[] {
            "uid=\\d+\\([\\w]+\\)\\s+gid=\\d+\\([\\w]+\\)", "root:.*?:0:0:", "Linux version \\d+\\.\\d+", "GNU bash, version"
        });
        addRule(rules, "command_injection", "命令注入", "high", "linux_environment", new String[] {
            "Path=[\\s\\S]*?PWD=", "Path=[\\s\\S]*?PATHEXT=", "Path=[\\s\\S]*?SHELL=",
            "Path\\x3d[\\s\\S]*?PWD\\x3d", "Path\\x3d[\\s\\S]*?PATHEXT\\x3d", "Path\\x3d[\\s\\S]*?SHELL\\x3d"
        });
        addRule(rules, "command_injection", "命令注入", "high", "linux_server_info", new String[] {
            "SERVER_SIGNATURE=[\\s\\S]*?SERVER_SOFTWARE=", "SERVER_SIGNATURE\\x3d[\\s\\S]*?SERVER_SOFTWARE\\x3d"
        });
        addRule(rules, "command_injection", "命令注入", "high", "linux_network", new String[] {
            "Non-authoritative\\sanswer:\\s+Name:\\s*", "Server:\\s*.*?\\nAddress:\\s*"
        });
        addRule(rules, "command_injection", "命令注入", "high", "windows_basic", new String[] {
            "Windows IP Configuration", "Volume in drive [A-Z]", "Directory of [A-Z]:", "Microsoft Windows \\[Version"
        });
        addRule(rules, "command_injection", "命令注入", "high", "windows_set_command", new String[] {
            "Path=.*?PATHEXT=", "PROCESSOR_IDENTIFIER=", "SystemRoot=", "COMSPEC="
        });
    }

    private static void addFileInclusionRules(List<VulnerabilityRule> rules) {
        addRule(rules, "file_inclusion", "文件包含", "high", "linux", new String[] {
            "root:[x\\*]:0:0:", "root:x:0:0:root:/root:", "daemon:", "bin/bash", "/usr/bin", "/etc/passwd"
        });
        addRule(rules, "file_inclusion", "文件包含", "high", "windows", new String[] {
            "; for 16-bit app support", "\\[extensions\\]", "\\[fonts\\]", "\\[boot loader\\]", "C:\\\\Windows",
            "C:\\\\WINDOWS\\\\system32"
        });
    }

    private static void addXxeRules(List<VulnerabilityRule> rules) {
        addRule(rules, "xxe", "XXE", "high", "linux", new String[] { "root:[x\\*]:0:0:", "root:x:0:0:root:/root:" });
        addRule(rules, "xxe", "XXE", "high", "windows", new String[] { "; for 16-bit app support" });
    }

    private static void addLdapRules(List<VulnerabilityRule> rules) {
        addSimpleRule(rules, "ldap_injection", "LDAP注入", "medium", new String[] {
            "supplied argument is not a valid ldap", "javax.naming.NameNotFoundException",
            "javax.naming.directory.InvalidSearchFilterException", "Invalid DN syntax", "LDAPException|com.sun.jndi.ldap",
            "Search: Bad search filter", "Protocol error occurred", "Size limit has exceeded", "The alias is invalid",
            "Module Products.LDAPMultiPlugins", "Object does not exist", "The syntax is invalid",
            "A constraint violation occurred", "An inappropriate matching occurred", "Unknown error occurred",
            "The search filter is incorrect", "Local error occurred", "The search filter is invalid",
            "The search filter cannot be recognized", "IPWorksASP.LDAP"
        });
    }

    private static void addXpathRules(List<VulnerabilityRule> rules) {
        addRule(rules, "xpath_injection", "XPath注入", "medium", "exact", new String[] {
            "MS.Internal.Xml.", "org.apache.xpath.XPath", "Expression must evaluate to a node-set",
            "System.Xml.XPath.XPathException", "javax.xml.xpath.XPathException", "XPath evaluation exception",
            "Invalid XPath expression", "Failed to evaluate XPath expression", "Empty Path Expression",
            "Invalid predicate", "Invalid expression", "msxml4.dll", "msxml3.dll"
        });
        addRule(rules, "xpath_injection", "XPath注入", "medium", "regex", new String[] {
            "XPath(?:EvalError|Syntax\\s+Error|Compile\\s+Error)\\b",
            "XPath(?:Exception|Error|EvalException):\\s*['\"](?P<detail>.+?)['\"]",
            "XPath\\s+[Ee]rror\\s*:\\s*(?P<detail>.+?)(?:\\n|$)", "XPathEvalError:\\s*(?P<detail>.+?)(?:\\n|$)",
            "Line\\s+\\d+:\\s*(?:Invalid|Illegal)\\s+XPath\\s+expression"
        });
    }

    private static void addPiiAndSensitiveRules(List<VulnerabilityRule> rules) {
        addRule(rules, "pii_detection", "个人信息检测", "high", "bankcard", new String[] { "\\D(6\\d{14,18})\\D" });
        addRule(rules, "pii_detection", "个人信息检测", "high", "idcard", new String[] {
            "\\D([123456789]\\d{5}((19)|(20))\\d{2}((0[123456789])|(1[012]))((0[123456789])|([12][0-9])|(3[01]))\\d{3}[Xx0-9])\\D"
        });
        addRule(rules, "pii_detection", "个人信息检测", "high", "phone", new String[] { "\\D(1[3578]\\d{9})\\D" });
        addRule(rules, "pii_detection", "个人信息检测", "high", "email", new String[] {
            "(([a-zA-Z0-9]+[_|\\-|\\.]?)*[a-zA-Z0-9]+\\@([a-zA-Z0-9]+[_|\\-|\\.]?)*[a-zA-Z0-9]+(\\.[a-zA-Z]{2,3})+)"
        });
        // 敏感信息 - API Keys / Tokens / Credentials
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "api_keys", new String[] {
            "access_key.*?[\"'](.*?)[\"']", "accesskeyid.*?[\"'](.*?)[\"']", "AIza[0-9A-Za-z-_]{35}",
            "A[SK]IA[0-9A-Z]{16}", "api[key|_key|\\s+]+[a-zA-Z0-9_\\-]{5,100}"
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "tokens", new String[] {
            "ey[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.?[A-Za-z0-9-_.+/=]*$", "bearer [a-z0-9_.=:_\\+\\/-]{5,100}",
            "\"api_token\":\"(xox[a-zA-Z]-[a-zA-Z0-9-]+)\""
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "credentials", new String[] {
            "(password\\s*[`=:\\\\\"]+\\s*[^\\s]+|password is\\s*[`=:\\\\\"]*\\s*[^\\s]+|pwd\\s*[`=:\\\\\"]*\\s*[^\\s]+|passwd\\s*[`=:\\\\\"]+\\s*[^\\s]+)"
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "private_keys", new String[] {
            "-----BEGIN RSA PRIVATE KEY-----", "-----BEGIN DSA PRIVATE KEY-----", "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN PGP PRIVATE KEY BLOCK-----", "([-]+BEGIN [^\\s]+ PRIVATE KEY[-]+[\\s]*[^-]*[-]+END [^\\s]+ PRIVATE KEY[-]+)"
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "database", new String[] {
            "jdbc:(mysql|h2|oracle|sqlserver|jtds:sqlserver):", "(jdbc:[a-z:]+://[a-z0-9\\.\\-_:;=/@?,&]+)"
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "cloud", new String[] {
            "[\\w\\-.]*s3[\\w\\-.]*\\.?amazonaws\\.com\\/?[\\w\\-.]*", "(storage\\.cloud\\.google\\.com\\/[\\w\\-.]+)",
            "[\\w]+\\.cloudfront\\.net"
        });
        addRule(rules, "sensitive_info", "敏感信息泄露", "low", "app_specific", new String[] {
            "(?i)(?:app(?:[\\-_]?)(?:id|secret)).?[=:].*?(wx[a-fA-F0-9]{16,18})", "[a-zA-Z0-9_-]*:[a-zA-Z0-9_\\-]+@github\\.com*"
        });
    }

    private static void addCorsClickjackingRules(List<VulnerabilityRule> rules) {
        addSimpleRule(rules, "cors", "CORS配置错误", "medium", new String[] {
            "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"
        });
        addSimpleRule(rules, "clickjacking", "点击劫持", "low", new String[] {
            "x-frame-options", "content-security-policy", "frame-ancestors"
        });
    }

    private static void addDeserializationJndiJsonRules(List<VulnerabilityRule> rules) {
        addRule(rules, "deserialization", "反序列化", "high", "java", new String[] { "\\\\xac\\\\xed\\\\x00\\\\x05", "rO0" });
        addRule(rules, "deserialization", "反序列化", "high", "php", new String[] { "O:\\d+:\"", "a:\\d+:{", "s:\\d+:\"" });
        addRule(rules, "deserialization", "反序列化", "high", "python", new String[] { "\\\\x80\\\\x03", "\\\\x80\\\\x04", "c__builtin__" });
        addSimpleRule(rules, "jndi_injection", "JNDI注入", "high", new String[] {
            "javax.naming.NamingException", "JNDI", "javax.naming"
        });
        addSimpleRule(rules, "json_error", "JSON处理错误", "medium", new String[] { "jackson", "fastjson", "autotype" });
        // JSONP 规则过于宽泛（Callback 匹配太多），需要更精确的检测，暂时移除
        // addSimpleRule(rules, "jsonp", "JSONP信息泄露", "medium", new String[] {
        //     "_callback", "_cb", "callback", "cb", "jsonp", "jsonpcallback", "username", "memberid", "userid", "email"
        // });
        addSimpleRule(rules, "redirect", "开放重定向", "medium", new String[] {
            "Location", "<meta[^>]*?url[\\s]*?=[\\s'\"]*?([^>]*?)['\"]?>",
            "(location|window\\.location|document\\.location)(\\.href|\\.replace|\\.assign)\\("
        });
        // 未授权访问规则太宽泛（200 OK 匹配所有成功响应），已移除
        // addSimpleRule(rules, "unauthorized_access", "未授权访问", "high", new String[] {
        //     "200 OK", "401 Unauthorized", "403 Forbidden"
        // });
    }

    private static void addBackupSourceViewstateRules(List<VulnerabilityRule> rules) {
        addSimpleRule(rules, "backup_files", "备份文件泄露", "medium", new String[] {
            "\\x50\\x4b\\x03\\x04", "\\x52\\x61\\x72\\x21", "\\x2d\\x2d\\x20\\x4d", "\\x2d\\x2d\\x20\\x70\\x68", "\\x2f\\x2a\\x0a\\x20\\x4e"
        });
        addRule(rules, "source_code", "源代码泄露", "high", "php", new String[] { "<\\?php", "\\<\\?php[\\x20-\\x7f]+" });
        addRule(rules, "source_code", "源代码泄露", "high", "asp", new String[] { "<%.*Response\\.Write", "%>.*%<" });
        addRule(rules, "source_code", "源代码泄露", "high", "perl", new String[] { "^#!\\\\/.*\\\\/perl" });
        addRule(rules, "source_code", "源代码泄露", "high", "python", new String[] { "^#!\\/.*?\\/python" });
        addRule(rules, "source_code", "源代码泄露", "high", "dotnet", new String[] { "using\\sSystem[\\s\\S]*?class\\s[\\s\\S]*?\\s?\\{[\\s\\S]*}" });
        addSimpleRule(rules, "viewstate", "ViewState未加密", "medium", new String[] {
            "<input[^>]+__VIEWSTATE[\"' ][^>]*value=[\"']([^\"']+)", "<input[^>]+value=[\"']([^\"']+)[\"' ][^>]+__VIEWSTATE",
            "__VIEWSTATE=([A-Za-z0-9+/=]+)"
        });
        // 敏感文件 - 与 JSON 中 files 一一对应
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "phpinfo", new String[] { "PHP Extension|<title>phpinfo\\(\\)|php_version" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "svn", new String[] { "\\s+dir\\s*\\d+\\s*", "svn:wc:ra_dav:version-url" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "git", new String[] { "repositoryformatversion[\\s\\S]*" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "bzr", new String[] { "This is a Bazaar[\\s\\S]" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "cvs", new String[] { ":pserver:[\\s\\S]*?:[\\s\\S]*" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "hg", new String[] { "^revlogv1.*" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "ds_store", new String[] { "\\x42\\x75\\x64\\x31" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "idea", new String[] { "<project version=\"\\w+\">" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "swagger", new String[] { "<title>Swagger UI</title>" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "htaccess", new String[] {
            "(RewriteEngine|RewriteCond|RewriteRule|AuthType|AuthName|AuthUserFile|ErrorDocument|deny from|AddType|AddHandler|IndexIgnore|ContentDigest|AddOutputFilterByType|php_flag|php_value)\\s"
        });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "sftp_config", new String[] {
            "(\"type\":[\\s\\S]*?\"host\":[\\s\\S]*?\"user\":[\\s\\S]*?\"password\":[\\s\\S]*\")"
        });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "filezilla", new String[] { "filezilla" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "web_xml", new String[] { "<web-app" });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "tomcat", new String[] {
            "^<\\?xml version.*?Licensed to the Apache Software Foundation"
        });
        addRule(rules, "sensitive_files", "敏感文件泄露", "medium", "django", new String[] { "\\sTEMPLATES\\s?=\\s?\\[" });
        addSimpleRule(rules, "crlf_injection", "CRLF注入", "medium", new String[] {
            "\\\\r\\\\n\\\\t", "%0a%0a", "%0d%0a", "%250a", "%3f%23%0d%0a%09", "%25%30%61", "%u000d%u000a", "%25250a"
        });
        addSimpleRule(rules, "redos", "正则拒绝服务", "medium", new String[] {
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\.\\."
        });
        addRule(rules, "captcha_bypass", "验证码绕过", "low", "frontend", new String[] {
            "(var|let|const)\\s+(code|vcode|verifyCode|captcha)\\s*?;?\\s*?(//.*?验证码)?[\\s\\S]{0,50}?function\\s+?(create|generate|make|init)(Code|VCode|VerifyCode|Captcha)[\\s\\S]{0,200}?=\\s*?[\"'`]?[\\s\\S]{0,50}?codeLength\\s*?="
        });
        addSimpleRule(rules, "captcha_bypass", "验证码绕过", "low", new String[] {
            "验证码错误", "验证码不正确", "captcha\\s*error", "invalid\\s*code", "验证码已过期", "请重新输入验证码", "code\\s*error", "验证码无效"
        });
    }

    private static void addSstiRules(List<VulnerabilityRule> rules) {
        String type = "ssti";
        String name = "模板注入";
        String severity = "high";
        addRule(rules, type, name + "-jinja2", severity, "jinja2", new String[] {
            "\\Q{{7*7}}\\E", "\\Q{{config}}\\E", "49", "dict_items"
        });
        // Mako: 去掉 "ab" 过于宽泛的规则，只保留 payload 和计算结果
        addRule(rules, type, name + "-mako", severity, "mako", new String[] { "\\Q${7*7}\\E" });
        addRule(rules, type, name + "-tornado", severity, "tornado", new String[] { "\\Q{{7*7}}\\E", "\\Q{{handler.settings}}\\E", "49" });
        addRule(rules, type, name + "-twig", severity, "twig", new String[] { "\\Q{{7*7}}\\E", "\\Q{{_self}}\\E", "\\Q{{dump(app)}}\\E", "49" });
        addRule(rules, type, name + "-freemarker", severity, "freemarker", new String[] { "\\Q${7*7}\\E", "49", "7777777" });
        addRule(rules, type, name + "-velocity", severity, "velocity", new String[] { "\\Q#set($x=7*7)$x\\E", "49" });
        addRule(rules, type, name + "-erb", severity, "erb", new String[] { "\\Q<%= 7*7 %>\\E", "49" });
        addRule(rules, type, name + "-slim", severity, "slim", new String[] { "= 7*7", "49" });
        addRule(rules, type, name + "-pug", severity, "pug", new String[] { "\\Q#{7*7}\\E", "49" });
        addRule(rules, type, name + "-nunjucks", severity, "nunjucks", new String[] { "\\Q{{7*7}}\\E", "49" });
        addRule(rules, type, name + "-ejs", severity, "ejs", new String[] { "\\Q<%=7*7%>\\E", "49" });
        addRule(rules, type, name + "-smarty", severity, "smarty", new String[] { "\\Q{7*7}\\E", "\\Q{$smarty.version}\\E", "49" });
        addRule(rules, type, name + "-angularjs", severity, "angularjs", new String[] { "ng-app", "angular\\.js", "X-Powered-By: AngularJS" });
    }

    private static void addFastjsonPhpErrorRules(List<VulnerabilityRule> rules) {
        addSimpleRule(rules, "fastjson", "Fastjson反序列化", "high", new String[] {
            "@type", "java.net.Inet4Address", "java.net.URL"
        });
        addRule(rules, "php_path_leak", "PHP路径泄露", "low", "array_error", new String[] {
            "Warning.*?array given in (.*?) on line"
        });
        addRule(rules, "php_path_leak", "PHP路径泄露", "low", "realpath", new String[] {
            "realpath\\(\\): open_basedir restriction in effect\\. File\\((.*?)\\) is not within the allowed path\\(s\\)"
        });
        addRule(rules, "error_messages", "错误消息", "info", "stack_trace", new String[] {
            "Stack trace:", "at .*?\\(.*?:\\d+\\)", "Traceback \\(most recent call last\\):", "Exception in thread", "Caused by:"
        });
        addRule(rules, "error_messages", "错误消息", "info", "debug_info", new String[] {
            "DEBUG:", "VERBOSE:", "TRACE:", "Stack Trace", "Call Stack"
        });
        addRule(rules, "error_messages", "错误消息", "info", "asp_net", new String[] {
            "\"Message\":\"Invalid web service call", "Exception of type", "--- End of inner exception stack trace ---",
            "Microsoft OLE DB Provider", "Error ([\\d-]+) \\([\\dA-Fa-f]+\\)", "([A-Za-z]+[.])+[A-Za-z]*Exception: ",
            "in [A-Za-z]:\\([A-Za-z0-9_]+\\)+[A-Za-z0-9_\\-]+(\\.aspx)?\\.cs:line [\\d]+", "Syntax error in string in query expression"
        });
        addRule(rules, "error_messages", "错误消息", "info", "java", new String[] {
            "\\.java:[0-9]+", "\\.java\\((Inlined )?Compiled Code\\)", "\\.invoke\\(Unknown Source\\)", "nested exception is"
        });
        addRule(rules, "error_messages", "错误消息", "info", "php", new String[] {
            "\\.php on line [0-9]+", "\\.php</b> on line <b>[0-9]+", "Fatal error:", "\\.php:[0-9]+"
        });
        addRule(rules, "error_messages", "错误消息", "info", "python", new String[] {
            "Traceback \\(most recent call last\\):", "File \"[A-Za-z0-9\\-_\\./]*\", line [0-9]+, in"
        });
        addRule(rules, "error_messages", "错误消息", "info", "django", new String[] {
            "You're seeing this error because you have <code>DEBUG = True</code> in", "TemplateSyntaxError", "Django Version"
        });
        addRule(rules, "error_messages", "错误消息", "info", "laravel", new String[] {
            "Whoops, looks like something went wrong", "Illuminate\\\\", "Laravel"
        });
        addRule(rules, "error_messages", "错误消息", "info", "spring", new String[] {
            "Whitelabel Error Page", "org.springframework", "DispatcherServlet"
        });
        addRule(rules, "error_messages", "错误消息", "info", "flask", new String[] {
            "Werkzeug Debugger", "flask.app", "jinja2.exceptions"
        });
        addRule(rules, "error_messages", "错误消息", "info", "express", new String[] {
            "Express", "at Layer.handle", "node_modules/express"
        });
        addRule(rules, "error_messages", "错误消息", "info", "other", new String[] {
            "\\.js:[0-9]+:[0-9]+", "JBWEB[0-9]{6}:", "((dn|dc|cn|ou|uid|o|c)=[\\w\\d]*,\\s?){2,}",
            "at (\\/[A-Za-z0-9\\.]+)*\\.pm line [0-9]+", "\\.rb:[0-9]+:in", "\\.scala:[0-9]+",
            "\\(generated by waitress\\)", "You are seeing this page because development mode is enabled",
            "<title>Action Controller: Exception caught</title>", "<p class=\"face\">:\\(</p>",
            "class='xdebug-error xe-fatal-error'", "Required\\s\\w+\\sparameter\\s'([^']+?)'\\sis\\snot\\spresent"
        });
        // HTTP走私规则太宽泛（HTTP/1.1 匹配所有请求），已移除
        // 真正的HTTP走私需要检测双重 Content-Length、Transfer-Encoding 冲突等，简单正则无法准确判断
        // addSimpleRule(rules, "http_smuggling", "HTTP走私", "high", new String[] {
        //     "HTTP/1.1.*?\\r\\n.*?HTTP/1.1", "Content-Length", "Transfer-Encoding"
        // });
        addRule(rules, "xxe_extended", "XXE扩展", "high", "xxe_errors", new String[] {
            "XML parsing error", "Undeclared entity", "Failed to load external entity",
            "XML External Entity", "XML error", "Invalid XML", "EntityRef: expecting ';'", "XML_ERR_EXT_ENTITY_HANDLING"
        });
        addRule(rules, "xxe_extended", "XXE扩展", "high", "entity_expansion", new String[] {
            "entity expansion limit", "entity reference loop", "recursive entity reference"
        });
    }

    private static void addSsrfExtendedRules(List<VulnerabilityRule> rules) {
        addRule(rules, "ssrf_extended", "SSRF扩展特征", "high", "connection_errors", new String[] {
            "Connection refused", "Connection timed out", "Unable to connect", "Failed to connect",
            "Network is unreachable", "No route to host", "getaddrinfo failed", "Could not resolve host",
            "connect ECONNREFUSED", "connect ETIMEDOUT"
        });
        addRule(rules, "ssrf_extended", "SSRF扩展特征", "high", "protocol_errors", new String[] {
            "Protocol not supported", "Unsupported protocol", "Unknown protocol"
        });
        addRule(rules, "ssrf_extended", "SSRF扩展特征", "high", "dns_errors", new String[] {
            "Name or service not known", "nodename nor servname provided", "Temporary failure in name resolution"
        });
        addRule(rules, "ssrf_extended", "SSRF扩展特征-响应", "high", "response", new String[] {
            "redis_version", "MongoDB", "cluster_name", "X-Jenkins", "Server: Docker", "X-Consul-", "etcd-version",
            "ami-id", "instance-id", "public-hostname", "project-id", "zone", "compute", "network", "subscriptionId"
        });
    }

    private static void addDirectoryWeakHostRules(List<VulnerabilityRule> rules) {
        addSimpleRule(rules, "directory_listing", "目录列表", "low", new String[] {
            "Index of /", "<title>Index of", "Parent Directory", "Apache.*?Server at", "nginx/[0-9.]+",
            "\\[To Parent Directory\\]", "<pre>", "<a href=\"", "Last modified", "Size</th>"
        });
        addRule(rules, "weak_password", "弱密码特征", "high", "error", new String[] {
            "用户名或密码错误", "incorrect username or password", "invalid credentials", "authentication failed", "登录失败"
        });
        addRule(rules, "weak_password", "弱密码特征", "high", "success", new String[] {
            "登录成功", "login success", "welcome", "dashboard", "redirect"
        });
        addSimpleRule(rules, "host_header_injection", "Host头注入", "medium", new String[] {
            "reflected_in_response", "password_reset", "cache_poisoning", "注入的Host值出现在响应中"
        });
        addRule(rules, "phpinfo_disclosure", "PHPInfo泄露", "medium", "dangerous", new String[] {
            "<td class=\"e\">allow_url_fopen</td><td class=\"v\">On</td>",
            "<td class=\"e\">allow_url_include</td><td class=\"v\">On</td>",
            "<td class=\"e\">asp_tags</td><td class=\"v\">On</td>",
            "<td class=\"e\">register_globals</td><td class=\"v\">On</td>",
            "<td class=\"e\">enable_dl</td><td class=\"v\">On</td>",
            "<td class=\"e\">display_errors</td><td class=\"v\">On</td>",
            "short_open_tag</td><td class=\"v\">On</td>"
        });
        addRule(rules, "phpinfo_disclosure", "PHPInfo泄露", "medium", "info", new String[] {
            "PHP Extension", "php_version", "<td class=\"e\">System </td>", "SCRIPT_FILENAME", "SERVER_ADDR",
            "disable_functions", "open_basedir", "PATH\"]</td>"
        });
        addSimpleRule(rules, "editor_backup", "编辑器备份", "medium", new String[] {
            "\\.swp", "\\.bak", "~\\.", "filezilla", "recentservers"
        });
    }

    private static void addCodeInjectionParamRules(List<VulnerabilityRule> rules) {
        // code_injection_extended 规则过于宽泛，已移除
        // parameter_safety_check 和 content_type_detection 不是漏洞，已移除
    }

    private static void addServiceFingerprintsAndExtendedRules(List<VulnerabilityRule> rules) {
        addRule(rules, "service_fingerprints", "服务指纹", "low", "redis", new String[] {
            "redis_version", "-ERR unknown command", "-ERR wrong number of arguments", "-DENIED Redis is running", "PONG"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "mongodb", new String[] {
            "MongoDB", "Mongodb", "ismaster", "buildinfo"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "elasticsearch", new String[] {
            "\"cluster_name\"", "\"version\"", "\"number\"", "\"build_hash\"", "\"tagline\" : \"You Know, for Search\""
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "jenkins", new String[] {
            "Jenkins.instance.pluginManager.plugins", "Jenkins", "X-Jenkins"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "docker", new String[] {
            "\"Containers\"", "\"Images\"", "\"ServerVersion\"", "\"ApiVersion\""
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "zookeeper", new String[] { "zookeeper", "Environment:", "conf" });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "memcache", new String[] { "STAT pid", "STAT version", "END" });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "solr", new String[] {
            "\"responseHeader\"", "\"status\":0", "\"QTime\"", "Apache Solr"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "rsync", new String[] { "@RSYNCD:", "RSYNCD" });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "ftp", new String[] {
            "220", "FTP", "Anonymous login", "230 Login successful"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "ldap", new String[] { "LDAP", "rootDSE", "supportedLDAPVersion" });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "mysql", new String[] {
            "mysql_native_password", "Access denied for user", "Host.*is not allowed to connect"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "postgresql", new String[] {
            "PostgreSQL", "FATAL:  password authentication failed", "FATAL:  no pg_hba.conf entry"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "mssql", new String[] {
            "Microsoft SQL Server", "Login failed", "MSSQL"
        });
        addRule(rules, "service_fingerprints", "服务指纹", "low", "activemq", new String[] {
            "Apache ActiveMQ", "Welcome to the Apache ActiveMQ"
        });
        addSimpleRule(rules, "file_upload", "文件上传", "high", new String[] {
            "upload success", "上传成功", "file saved", "文件保存成功"
        });
        addSimpleRule(rules, "dns_zone_transfer", "DNS区域传送", "medium", new String[] {
            "Transfer failed", "AXFR", "SOA record"
        });
        // 缓存投毒：只保留明确的缓存头，去掉 Age（太常见）
        addSimpleRule(rules, "cache_poisoning", "缓存投毒", "high", new String[] {
            "X-Cache", "X-Cache-Hits", "CF-Cache-Status", "X-Varnish", "X-Cache-Lookup"
        });
    }

    private HardcodedRules() {}
}
