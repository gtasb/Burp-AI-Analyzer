package com.ai.analyzer.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecutionPolicy 安全策略")
class ExecutionPolicyTest {

    private ExecutionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ExecutionPolicy();
    }

    @Nested
    @DisplayName("基本校验")
    class BasicValidation {

        @Test
        @DisplayName("should_reject_null_tool")
        void should_reject_null_tool() {
            ExecutionPolicy.ValidationResult result = policy.validate(null, null);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("工具定义为空");
        }

        @Test
        @DisplayName("should_reject_empty_command")
        void should_reject_empty_command() {
            SkillTool tool = new SkillTool("test", "desc", "");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, null);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("未指定可执行命令");
        }

        @Test
        @DisplayName("should_reject_null_command")
        void should_reject_null_command() {
            SkillTool tool = new SkillTool("test", "desc", null);
            tool.setCommand(null);
            ExecutionPolicy.ValidationResult result = policy.validate(tool, null);
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("should_accept_valid_relative_command")
        void should_accept_valid_relative_command() {
            SkillTool tool = new SkillTool("test", "desc", "python");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, Map.of());
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("路径校验")
    class PathValidation {

        @Test
        @DisplayName("should_reject_nonexistent_absolute_path")
        void should_reject_nonexistent_absolute_path() {
            String fakePath = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "C:\\nonexistent\\fake.exe"
                    : "/nonexistent/fake";
            SkillTool tool = new SkillTool("test", "desc", fakePath);
            ExecutionPolicy.ValidationResult result = policy.validate(tool, null);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("不存在");
        }

        @Test
        @DisplayName("should_accept_existing_absolute_path")
        void should_accept_existing_absolute_path() throws IOException {
            Path tempFile = Files.createTempFile("test-tool", ".bat");
            try {
                SkillTool tool = new SkillTool("test", "desc", tempFile.toString());
                ExecutionPolicy.ValidationResult result = policy.validate(tool, Map.of());
                assertThat(result.isSuccess()).isTrue();
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Nested
    @DisplayName("扩展名白名单")
    class ExtensionWhitelist {

        @Test
        @DisplayName("should_reject_disallowed_extension")
        void should_reject_disallowed_extension() throws IOException {
            Path tempFile = Files.createTempFile("test", ".dll");
            try {
                SkillTool tool = new SkillTool("test", "desc", tempFile.toString());
                ExecutionPolicy.ValidationResult result = policy.validate(tool, null);
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getMessage()).contains("不允许执行该类型文件");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("should_accept_common_script_extensions")
        void should_accept_common_script_extensions() {
            for (String ext : new String[]{".exe", ".bat", ".sh", ".py", ".rb", ".jar", ".ps1"}) {
                SkillTool tool = new SkillTool("test", "desc", "tool" + ext);
                ExecutionPolicy.ValidationResult result = policy.validate(tool, Map.of());
                assertThat(result.isSuccess())
                        .as("Extension %s should be allowed", ext)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("should_accept_extensionless_binary")
        void should_accept_extensionless_binary() {
            SkillTool tool = new SkillTool("test", "desc", "nmap");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, Map.of());
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("参数注入防护")
    class ArgumentInjection {

        @Test
        @DisplayName("should_reject_backtick_injection")
        void should_reject_backtick_injection() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            Map<String, String> params = Map.of("msg", "hello`whoami`");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("不安全字符");
        }

        @Test
        @DisplayName("should_reject_dollar_sign_injection")
        void should_reject_dollar_sign_injection() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            Map<String, String> params = Map.of("msg", "$(rm -rf /)");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("should_reject_path_traversal")
        void should_reject_path_traversal() {
            SkillTool tool = new SkillTool("test", "desc", "cat");
            Map<String, String> params = Map.of("file", "../../etc/passwd");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("路径遍历");
        }

        @Test
        @DisplayName("should_reject_oversized_argument")
        void should_reject_oversized_argument() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            Map<String, String> params = Map.of("msg", "A".repeat(5000));
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("过长");
        }

        @Test
        @DisplayName("should_accept_safe_parameters")
        void should_accept_safe_parameters() {
            SkillTool tool = new SkillTool("test", "desc", "nmap");
            Map<String, String> params = Map.of(
                    "target", "192.168.1.1",
                    "ports", "1-1000"
            );
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should_skip_null_parameter_values")
        void should_skip_null_parameter_values() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            Map<String, String> params = new HashMap<>();
            params.put("key", null);
            ExecutionPolicy.ValidationResult result = policy.validate(tool, params);
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Shell 操作符控制")
    class ShellOperators {

        @Test
        @DisplayName("should_detect_shell_operators")
        void should_detect_shell_operators() {
            assertThat(ExecutionPolicy.containsShellOperators("cmd | grep")).isTrue();
            assertThat(ExecutionPolicy.containsShellOperators("a && b")).isTrue();
            assertThat(ExecutionPolicy.containsShellOperators("a > file")).isTrue();
            assertThat(ExecutionPolicy.containsShellOperators("a < file")).isTrue();
            assertThat(ExecutionPolicy.containsShellOperators("simple cmd")).isFalse();
        }

        @Test
        @DisplayName("should_reject_shell_operators_when_disabled")
        void should_reject_shell_operators_when_disabled() {
            ExecutionPolicy strict = new ExecutionPolicy(false);
            SkillTool tool = new SkillTool("test", "desc", "echo");
            tool.setArgs("hello | tee output.txt");
            ExecutionPolicy.ValidationResult result = strict.validate(tool, Map.of());
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("shell 操作符");
        }

        @Test
        @DisplayName("should_allow_shell_operators_by_default")
        void should_allow_shell_operators_by_default() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            tool.setArgs("hello | tee output.txt");
            ExecutionPolicy.ValidationResult result = policy.validate(tool, Map.of());
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Test
    @DisplayName("should_sanitize_arg_value")
    void should_sanitize_arg_value() {
        assertThat(policy.sanitizeArgValue("  hello  ")).isEqualTo("hello");
        assertThat(policy.sanitizeArgValue(null)).isNull();
    }
}
