package com.ai.analyzer.skills;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 二进制/脚本执行安全策略
 *
 * 职责：
 * 1. 校验可执行文件路径合法性（必须真实存在）
 * 2. 校验参数值不含 shell 注入字符
 * 3. 限制路径遍历攻击
 * 4. 提供可审计的校验结果
 */
public class ExecutionPolicy {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".ps1", ".msi",
            ".sh", ".bash", ".zsh",
            ".py", ".rb", ".pl", ".php",
            ".jar", ".js", ".mjs",
            ""  // 无扩展名的二进制（如 Linux 下的 nmap, curl）
    );

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "[`$]|\\$\\(|\\$\\{");

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(^|[\\\\/])\\.\\.[\\\\/]");

    private static final int MAX_ARG_LENGTH = 4096;

    private boolean allowShellOperators = true;

    public ExecutionPolicy() {}

    /**
     * @param allowShellOperators 是否允许命令中包含管道/重定向等 shell 操作符。
     *                            设为 false 则会拒绝含有 |, &&, >, < 的命令。
     */
    public ExecutionPolicy(boolean allowShellOperators) {
        this.allowShellOperators = allowShellOperators;
    }

    public boolean isAllowShellOperators() {
        return allowShellOperators;
    }

    public void setAllowShellOperators(boolean allowShellOperators) {
        this.allowShellOperators = allowShellOperators;
    }

    /**
     * 校验 SkillTool 的命令和参数是否安全。
     *
     * @return ValidationResult，success=true 表示可执行
     */
    public ValidationResult validate(SkillTool tool, Map<String, String> paramValues) {
        if (tool == null) {
            return ValidationResult.fail("工具定义为空");
        }

        String command = tool.getCommand();
        if (command == null || command.isBlank()) {
            return ValidationResult.fail("未指定可执行命令");
        }

        // 1. 校验可执行文件存在性（仅对绝对路径做检查，相对路径依赖 PATH 解析）
        if (isAbsolutePath(command)) {
            File exe = new File(command);
            if (!exe.exists()) {
                return ValidationResult.fail("可执行文件不存在: " + command);
            }
            if (!exe.isFile()) {
                return ValidationResult.fail("路径不是文件: " + command);
            }
        }

        // 2. 校验扩展名在允许列表中
        String ext = getExtension(command).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return ValidationResult.fail("不允许执行该类型文件: " + ext);
        }

        // 3. 校验参数值安全
        if (paramValues != null) {
            for (Map.Entry<String, String> entry : paramValues.entrySet()) {
                String paramName = entry.getKey();
                String paramValue = entry.getValue();
                if (paramValue == null) continue;

                if (paramValue.length() > MAX_ARG_LENGTH) {
                    return ValidationResult.fail(
                            "参数 '" + paramName + "' 值过长（" + paramValue.length() + " > " + MAX_ARG_LENGTH + "）");
                }

                if (INJECTION_PATTERN.matcher(paramValue).find()) {
                    return ValidationResult.fail(
                            "参数 '" + paramName + "' 包含不安全字符（禁止 ` $ $( ${）");
                }

                if (PATH_TRAVERSAL_PATTERN.matcher(paramValue).find()) {
                    return ValidationResult.fail(
                            "参数 '" + paramName + "' 包含路径遍历序列 (..)");
                }
            }
        }

        // 4. 检查 shell 操作符
        if (!allowShellOperators) {
            String args = tool.getArgs();
            if (args != null && containsShellOperators(args)) {
                return ValidationResult.fail("参数模板包含 shell 操作符，但策略不允许");
            }
        }

        return ValidationResult.ok();
    }

    /**
     * 对单个参数值做清理（去除前后空白，不做截断，让 validate 去拒绝）
     */
    public String sanitizeArgValue(String value) {
        if (value == null) return null;
        return value.strip();
    }

    static boolean containsShellOperators(String s) {
        return s.contains("|") || s.contains("&&") || s.contains("||")
                || s.contains(">") || s.contains("<");
    }

    private static boolean isAbsolutePath(String path) {
        if (path == null || path.isEmpty()) return false;
        return path.startsWith("/")
                || (path.length() > 2 && path.charAt(1) == ':')
                || path.startsWith("\\\\");
    }

    private static String getExtension(String path) {
        if (path == null) return "";
        String name = Paths.get(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean success;
        private final String message;

        private ValidationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return success ? "OK" : "DENIED: " + message;
        }
    }
}
