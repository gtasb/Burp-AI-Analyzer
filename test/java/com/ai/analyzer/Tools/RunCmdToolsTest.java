package com.ai.analyzer.Tools;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RunCmdTools — CLI 工具")
class RunCmdToolsTest {

    private static final String NUCLEI_PATH = "D:\\HACKING_tools\\nuclei\\nuclei.exe";
    private static final boolean NUCLEI_AVAILABLE =
            new java.io.File("D:\\HACKING_tools\\nuclei\\nuclei.exe").exists();

    // ── 1. 精确路径匹配（原始设计） ──────────────────────────────────────────

    @Test
    @DisplayName("精确路径可以匹配并调用 cmd（Windows 内置 shell）")
    void exact_path_cmd() {
        // Windows 上 echo 是 shell 内置命令，需通过 cmd /c 调用
        RunCmdTools tools = new RunCmdTools("cmd", "", null);
        String result = tools.runCli("cmd", "/c echo hello world");
        System.out.println("[exact_path_cmd]\n" + result);
        assertThat(result).contains("hello world");
    }

    @Test
    @DisplayName("AI 用别名 'nuclei' 应能匹配白名单中的完整路径")
    void alias_match_by_exe_name() {
        RunCmdTools tools = new RunCmdTools(NUCLEI_PATH, "", null);
        // AI 可能只传 "nuclei" 或 "nuclei.exe"
        String byName    = tools.runCli("nuclei",     "-version");
        String byNameExe = tools.runCli("nuclei.exe", "-version");
        System.out.println("[alias nuclei]\n"    + byName);
        System.out.println("[alias nuclei.exe]\n" + byNameExe);
        if (NUCLEI_AVAILABLE) {
            assertThat(byName).doesNotContain("不在白名单");
            assertThat(byNameExe).doesNotContain("不在白名单");
        } else {
            // nuclei 不存在时应报 IO 错误，而非"不在白名单"
            assertThat(byName).doesNotContain("不在白名单");
        }
    }

    @Test
    @DisplayName("AI 用反斜杠/正斜杠混写路径均应能匹配")
    void slash_normalization() {
        RunCmdTools tools = new RunCmdTools(NUCLEI_PATH, "", null);
        // 正斜杠版本
        String fwd = tools.runCli("D:/HACKING_tools/nuclei/nuclei.exe", "-version");
        // 原始双反斜杠
        String bk  = tools.runCli("D:\\HACKING_tools\\nuclei\\nuclei.exe", "-version");
        System.out.println("[fwd slash]\n" + fwd);
        System.out.println("[backslash]\n" + bk);
        assertThat(fwd).doesNotContain("不在白名单");
        assertThat(bk).doesNotContain("不在白名单");
    }

    @Test
    @DisplayName("list_cli_tools 应返回可用工具列表")
    void list_cli_tools_shows_whitelist() {
        RunCmdTools tools = new RunCmdTools(
                "echo\n" + NUCLEI_PATH + "\npython --version",
                "", null);
        String list = tools.listCliTools();
        System.out.println("[list_cli_tools]\n" + list);
        assertThat(list).contains("echo");
        assertThat(list).contains("nuclei");
    }

    // ── 2. 进程输出正确读取（测死锁场景的小 proxy） ──────────────────────────

    @Test
    @DisplayName("大输出命令不应死锁，且能在超时内返回")
    void large_output_no_deadlock() {
        // dir /s 可能产生大量输出，测试不死锁
        RunCmdTools tools = new RunCmdTools(
                "cmd",
                "",
                System.getProperty("user.home"),
                java.time.Duration.ofSeconds(15));
        long start = System.currentTimeMillis();
        String result = tools.runCli("cmd", "/c dir /s /b " + System.getProperty("user.home").replace("\\", "\\\\"));
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[large_output elapsed=" + elapsed + "ms, len=" + result.length() + "]");
        assertThat(elapsed).isLessThan(15_000L);
        // 不应报执行失败
        assertThat(result).doesNotStartWith("执行失败:");
    }

    // ── 3. 索引式调用 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("AI 可以用 1-based 索引调用第一条白名单")
    void index_based_call() {
        // 白名单第 1 条是 nuclei，第 2 条是 cmd；用索引 "2" 调用 cmd
        RunCmdTools tools = new RunCmdTools(NUCLEI_PATH + "\ncmd", "", null);
        String result = tools.runCli("2", "/c echo hello index");
        System.out.println("[index_based]\n" + result);
        assertThat(result).contains("hello index");
        assertThat(result).doesNotContain("不在白名单");
    }

    // ── 4. 安全拦截 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("不在白名单的命令应被拒绝")
    void reject_not_in_whitelist() {
        RunCmdTools tools = new RunCmdTools("echo", "", null);
        String result = tools.runCli("calc.exe", "");
        System.out.println("[reject]\n" + result);
        assertThat(result).contains("不在白名单");
    }
}
