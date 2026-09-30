package com.ai.analyzer.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shell 子进程环境补全。
 *
 * <p>Burp Suite 自身的进程环境不一定完整：部分启动方式（快捷方式 / 启动器 / 服务）会把
 * {@code PATH} 裁剪到只剩自带 JRE 的 {@code bin}。Harness 的
 * {@code LocalFilesystemWithShell.execute()} 在 {@code env} 为空时直接沿用父进程环境，
 * 于是 agent 启动的 {@code cmd.exe} 拿到的是这个残缺 {@code PATH}，
 * 导致 {@code python} / {@code py} / {@code where} / {@code curl} 全部找不到，
 * 表现为「脚本已写好却无法执行」。
 *
 * <p>这里在父进程环境之上补全 {@code PATH}：合并所有大小写写法的 PATH、
 * 追加 Windows 核心目录，并探测常见解释器与工具安装位置。
 * 不修改父进程环境，只影响我们启动的子进程。
 */
public final class ShellEnvironment {

    private ShellEnvironment() {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** PATH 分隔符：Windows 用分号，Unix 用冒号。 */
    private static final String SEP = WINDOWS ? ";" : ":";

    /**
     * 补全后的环境变量副本：父进程环境 + 修正后的 {@code PATH}。
     *
     * <p>键在 Windows 上大小写不敏感，这里同时写入 {@code PATH} 与 {@code Path}，
     * 避免子进程按另一种拼写读取时拿到未补全的旧值。
     */
    public static Map<String, String> augmentedEnv() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String path = augmentedPath();
        if (!path.isEmpty()) {
            env.put("PATH", path);
            env.put("Path", path);
        }
        return env;
    }

    /**
     * 补全 {@code PATH}：父进程 PATH 的各大小写写法 ∪ 探测到的工具目录。
     *
     * <p>父进程已有条目保持原顺序在前，探测到的新目录追加在后，
     * 既补齐缺失项，又不改变原有解析优先级。
     */
    public static String augmentedPath() {
        Set<String> inherited = new LinkedHashSet<>();
        for (String key : List.of("PATH", "Path", "path")) {
            addEntries(inherited, System.getenv(key));
        }

        Set<String> out = new LinkedHashSet<>(inherited);
        for (String dir : toolDirs()) {
            if (isDirectory(dir)) {
                out.add(dir);
            }
        }
        return String.join(SEP, out);
    }

    /**
     * 候选工具目录：Windows 核心目录（{@code System32} 承载 {@code where}/{@code curl}/
     * {@code certutil}，被裁剪的启动器常常漏掉）+ 常见解释器与工具安装位置。
     */
    private static List<String> toolDirs() {
        List<String> dirs = new ArrayList<>();
        if (!WINDOWS) {
            dirs.addAll(versionedDirs("/usr/local/bin", ""));
            dirs.addAll(versionedDirs("/usr/bin", ""));
            dirs.addAll(versionedDirs("/bin", ""));
            dirs.addAll(versionedDirs(System.getProperty("user.home") + "/.local/bin", ""));
            return dirs;
        }

        String systemRoot = firstNonBlank(System.getenv("SystemRoot"), System.getenv("windir"), "C:\\Windows");
        dirs.add(systemRoot + "\\System32");
        dirs.add(systemRoot);
        dirs.add(systemRoot + "\\System32\\Wbem");
        dirs.add(systemRoot + "\\System32\\WindowsPowerShell\\v1.0");

        // 解释器通常装在带版本号的目录（Python311 / Python312）里，需按前缀扫描一层
        dirs.addAll(versionedDirs(localAppData() + "\\Programs\\Python", "python"));
        dirs.addAll(versionedDirs(programFiles() + "\\Python", "python"));
        dirs.addAll(versionedDirs(programFilesX86() + "\\Python", "python"));

        addIfDir(dirs, localAppData() + "\\Microsoft\\WindowsApps");
        addIfDir(dirs, programFiles() + "\\Git\\cmd");
        addIfDir(dirs, programFiles() + "\\Git\\bin");
        addIfDir(dirs, programFiles() + "\\nodejs");
        addIfDir(dirs, programFilesX86() + "\\Git\\cmd");
        addIfDir(dirs, programFilesX86() + "\\nodejs");
        return dirs;
    }

    /**
     * 扫描 {@code parentDir} 下以 {@code prefix} 开头的子目录，一并收录其 {@code Scripts} 子目录。
     *
     * <p>{@code Python311} 这类带版本号的安装目录无法静态枚举，故按前缀扫描。
     */
    private static List<String> versionedDirs(String parentDir, String prefix) {
        List<String> dirs = new ArrayList<>();
        File[] children = new File(parentDir).listFiles();
        if (children == null) return dirs;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (!child.getName().toLowerCase().startsWith(prefix.toLowerCase())) continue;
            dirs.add(child.getAbsolutePath());
            File scripts = new File(child, "Scripts");
            if (scripts.isDirectory()) {
                dirs.add(scripts.getAbsolutePath());
            }
        }
        return dirs;
    }

    private static void addEntries(Set<String> dirs, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return;
        for (String part : pathValue.split(Pattern.quote(SEP))) {
            String dir = part.trim();
            if (!dir.isEmpty()) {
                dirs.add(dir);
            }
        }
    }

    private static void addIfDir(List<String> out, String dir) {
        if (dir != null && !dir.isBlank() && isDirectory(dir)) {
            out.add(dir);
        }
    }

    private static boolean isDirectory(String dir) {
        if (dir == null || dir.isBlank()) return false;
        try {
            return Files.isDirectory(Path.of(dir));
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String localAppData() {
        return firstNonBlank(System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home") + "\\AppData\\Local");
    }

    private static String programFiles() {
        return firstNonBlank(System.getenv("ProgramFiles"), "C:\\Program Files");
    }

    private static String programFilesX86() {
        return firstNonBlank(System.getenv("ProgramFiles(x86)"), "C:\\Program Files (x86)");
    }
}
