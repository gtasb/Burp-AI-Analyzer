package com.ai.analyzer.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SkillToolExecutor")
class SkillToolExecutorTest {

    private SkillToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SkillToolExecutor();
    }

    @Nested
    @DisplayName("安全策略集成")
    class SecurityPolicyIntegration {

        @Test
        @DisplayName("should_reject_execution_when_policy_denies")
        void should_reject_execution_when_policy_denies() {
            SkillTool tool = new SkillTool("test", "desc", "");
            SkillToolExecutor.ExecutionResult result = executor.execute(tool, Map.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("安全策略拒绝");
        }

        @Test
        @DisplayName("should_reject_injection_in_parameters")
        void should_reject_injection_in_parameters() {
            SkillTool tool = new SkillTool("test", "desc", "echo");
            Map<String, String> params = Map.of("msg", "$(whoami)");
            SkillToolExecutor.ExecutionResult result = executor.execute(tool, params);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("安全策略拒绝");
        }

        @Test
        @DisplayName("should_use_custom_execution_policy")
        void should_use_custom_execution_policy() {
            ExecutionPolicy strict = new ExecutionPolicy(false);
            SkillToolExecutor strictExecutor = new SkillToolExecutor(null, strict);

            SkillTool tool = new SkillTool("test", "desc", "echo");
            tool.setArgs("hello | tee file.txt");
            SkillToolExecutor.ExecutionResult result = strictExecutor.execute(tool, Map.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("安全策略拒绝");
        }
    }

    @Nested
    @DisplayName("实际执行")
    class ActualExecution {

        @Test
        @DisplayName("should_execute_echo_command_successfully")
        void should_execute_echo_command_successfully() {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            SkillTool tool;
            if (isWindows) {
                tool = new SkillTool("echo", "Echo test", "cmd");
                tool.setArgs("/c echo hello-skills-test");
            } else {
                tool = new SkillTool("echo", "Echo test", "/bin/echo");
                tool.setArgs("hello-skills-test");
            }
            tool.setTimeout(10);

            SkillToolExecutor.ExecutionResult result = executor.execute(tool, Map.of());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("hello-skills-test");
            assertThat(result.getExitCode()).isEqualTo(0);
            assertThat(result.getDuration()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should_report_nonzero_exit_code")
        void should_report_nonzero_exit_code() {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            SkillTool tool;
            if (isWindows) {
                tool = new SkillTool("fail", "Fail test", "cmd");
                tool.setArgs("/c exit 42");
            } else {
                tool = new SkillTool("fail", "Fail test", "/bin/sh");
                tool.setArgs("-c \"exit 42\"");
            }
            tool.setTimeout(10);

            SkillToolExecutor.ExecutionResult result = executor.execute(tool, Map.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getExitCode()).isEqualTo(42);
        }

        @Test
        @DisplayName("should_handle_missing_executable_gracefully")
        void should_handle_missing_executable_gracefully() {
            SkillTool tool = new SkillTool("missing", "desc", "nonexistent_binary_xyz_123");
            tool.setTimeout(5);

            SkillToolExecutor.ExecutionResult result = executor.execute(tool, Map.of());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).isNotNull();
        }

        @Test
        @DisplayName("should_substitute_parameters_in_execution")
        void should_substitute_parameters_in_execution() {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            SkillTool tool;
            if (isWindows) {
                tool = new SkillTool("paramecho", "Echo with params", "cmd");
                tool.setArgs("/c echo {message}");
            } else {
                tool = new SkillTool("paramecho", "Echo with params", "/bin/echo");
                tool.setArgs("{message}");
            }

            SkillTool.ToolParameter msgParam = new SkillTool.ToolParameter("message", "The message", true);
            tool.setParameters(java.util.List.of(msgParam));
            tool.setTimeout(10);

            Map<String, String> params = new HashMap<>();
            params.put("message", "hello-from-param");
            SkillToolExecutor.ExecutionResult result = executor.execute(tool, params);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("hello-from-param");
        }
    }

    @Nested
    @DisplayName("ExecutionResult 格式化")
    class ResultFormatting {

        @Test
        @DisplayName("should_format_success_result")
        void should_format_success_result() {
            SkillToolExecutor.ExecutionResult result = new SkillToolExecutor.ExecutionResult();
            result.setToolName("test");
            result.setSuccess(true);
            result.setOutput("hello");
            result.setDuration(100);

            String formatted = result.toAIReadableFormat();
            assertThat(formatted).contains("test");
            assertThat(formatted).contains("成功");
            assertThat(formatted).contains("hello");
            assertThat(formatted).contains("100ms");
        }

        @Test
        @DisplayName("should_format_failure_result_with_error")
        void should_format_failure_result_with_error() {
            SkillToolExecutor.ExecutionResult result = new SkillToolExecutor.ExecutionResult();
            result.setToolName("fail");
            result.setSuccess(false);
            result.setError("Something went wrong");
            result.setExitCode(1);
            result.setDuration(50);

            String formatted = result.toAIReadableFormat();
            assertThat(formatted).contains("失败");
            assertThat(formatted).contains("Something went wrong");
            assertThat(formatted).contains("退出码");
        }
    }
}
