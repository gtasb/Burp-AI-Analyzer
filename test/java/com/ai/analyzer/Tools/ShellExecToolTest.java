package com.ai.analyzer.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShellExecTool - 命令执行工具")
class ShellExecToolTest {

    @Test
    @DisplayName("should return exit code for echo command")
    void echo() {
        ShellExecTool tool = new ShellExecTool(null, null, true, false);
        String result = tool.execute("echo hello", null, 10);
        assertThat(result).contains("退出码: 0");
        assertThat(result).contains("hello");
    }

    @Test
    @DisplayName("should return non-zero exit code for failing command")
    void failingCommand() {
        ShellExecTool tool = new ShellExecTool(null, null, true, false);
        String result = tool.execute("exit 42", null, 10);
        assertThat(result).contains("退出码: 42");
    }

    @Test
    @DisplayName("should timeout for long-running command")
    void timeout() {
        ShellExecTool tool = new ShellExecTool(null, null, true, false);
        String result = tool.execute("ping -n 60 127.0.0.1", null, 2);
        assertThat(result).contains("超时");
    }

    @Test
    @DisplayName("should handle Chinese output encoding")
    void chineseOutput() {
        ShellExecTool tool = new ShellExecTool(null, null, true, false);
        // chcp 65001 强制 cmd.exe UTF-8 输出
        String result = tool.execute("echo 中文测试", null, 10);
        assertThat(result).contains("退出码: 0");
        assertThat(result).contains("中文测试");
    }
}
