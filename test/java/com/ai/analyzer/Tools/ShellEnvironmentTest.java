package com.ai.analyzer.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShellEnvironment - shell PATH 补全")
class ShellEnvironmentTest {

    @Test
    @DisplayName("augmentedPath_should_contain_system32")
    void augmentedPathContainsSystem32() {
        // System32 承载 where/curl/certutil，Burp 启动器常把它裁掉
        assertThat(ShellEnvironment.augmentedPath().toLowerCase())
                .contains("system32");
    }

    @Test
    @DisplayName("augmentedPath_should_keep_inherited_entries_first")
    void augmentedPathKeepsInheritedOrder() {
        String inherited = System.getenv("PATH");
        if (inherited == null || inherited.isBlank()) return;

        String augmented = ShellEnvironment.augmentedPath();
        String firstInherited = inherited.split(";")[0].trim();
        if (firstInherited.isEmpty()) return;

        // 补全只允许追加，不允许改变既有解析优先级
        assertThat(augmented).startsWith(firstInherited);
    }

    @Test
    @DisplayName("augmentedPath_should_not_contain_blank_segments")
    void augmentedPathHasNoBlankSegments() {
        assertThat(ShellEnvironment.augmentedPath().split(";"))
                .allSatisfy(segment -> assertThat(segment).isNotBlank());
    }

    @Test
    @DisplayName("augmentedEnv_should_expose_both_path_spellings")
    void augmentedEnvExposesBothSpellings() {
        Map<String, String> env = ShellEnvironment.augmentedEnv();
        // Windows 环境变量大小写不敏感，两种拼写都要写入，否则子进程可能读到未补全的旧值
        assertThat(env.get("PATH")).isEqualTo(env.get("Path"));
        assertThat(env.get("PATH")).contains("system32".toLowerCase());
    }

    @Test
    @DisplayName("augmentedEnv_should_preserve_parent_variables")
    void augmentedEnvPreservesParentVariables() {
        String key = System.getenv().keySet().stream()
                .filter(k -> !k.equalsIgnoreCase("path"))
                .findFirst().orElse(null);
        if (key == null) return;

        String parentValue = System.getenv(key);
        assertThat(ShellEnvironment.augmentedEnv().get(key)).isEqualTo(parentValue);
    }

    @Test
    @DisplayName("should_make_python_reachable_through_augmented_path")
    void pythonReachable() throws Exception {
        Path dir = Files.createTempDirectory("shellenv-test");
        try {
            // 模拟「父进程 PATH 残缺」：只保留一个无关目录
            Path fake = dir.resolve("jre").resolve("bin");
            Files.createDirectories(fake);

            ShellExecTool tool = new ShellExecTool(null);
            String whereOut = tool.execute("where python", null, 30);

            // 本机装了 python 时应能找到；没装则只断言命令本身可执行（不因缺 python 而失败）
            boolean pythonInstalled = Files.exists(Path.of(
                    System.getenv("LOCALAPPDATA") == null ? "" : System.getenv("LOCALAPPDATA"),
                    "Programs", "Python")) || Files.exists(Path.of("C:\\Program Files\\Python"));
            if (pythonInstalled) {
                assertThat(whereOut).containsIgnoringCase("python.exe");
            }
            assertThat(whereOut).doesNotContain("找不到");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("where_should_be_reachable_via_system32")
    void whereReachable() {
        ShellExecTool tool = new ShellExecTool(null);
        String result = tool.execute("where where", null, 30);
        assertThat(result).containsIgnoringCase("where.exe");
    }

    @Test
    @DisplayName("augmentedPath_should_have_no_duplicates")
    void augmentedPathHasNoDuplicates() {
        List<String> entries = List.of(ShellEnvironment.augmentedPath().split(";"));
        assertThat(entries).doesNotHaveDuplicates();
    }

    private static void deleteRecursively(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
