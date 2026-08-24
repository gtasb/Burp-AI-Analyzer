package com.ai.analyzer.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器渲染工具。在本地安装的 Chrome/Chromium 中渲染 URL 并返回 JS 执行后的 DOM。
 * 适用于 SPA、DOM XSS 验证、hash 路由等 curl 无法处理的场景。
 */
public class BrowserRenderTool {

    private static final int MAX_DOM_CHARS = 100_000;

    @Tool(name = "browser_render",
          description = "在本地 Chrome(无头模式)中渲染指定的 HTTP/HTTPS URL，返回 JavaScript 执行完成后的 DOM 内容。" +
                        "适用于 SPA/单页应用、DOM XSS 验证、hash/fragment 路由等 curl 无法处理的场景。" +
                        "每个调用都会创建一个临时 Chrome 用户目录并在执行后清理。")
    public String render(
            @ToolParam(name = "url", description = "要渲染的完整 URL（http 或 https）") String url,
            @ToolParam(name = "waitMs", description = "等待页面渲染的毫秒数（可选，默认 5000，最大 30000）", required = false) Integer waitMs,
            @ToolParam(name = "maxChars", description = "返回 DOM 的最大字符数（可选，默认 20000，最大 100000）", required = false) Integer maxChars) {

        if (url == null || url.trim().isEmpty()) return "渲染失败: URL 不能为空";
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "渲染失败: 仅支持 http 和 https 协议";
        }

        int wait = (waitMs != null) ? Math.min(Math.max(500, waitMs), 30000) : 5000;
        int maxDom = (maxChars != null) ? Math.min(Math.max(1000, maxChars), MAX_DOM_CHARS) : 20000;

        String chromePath = findChrome();
        if (chromePath == null) {
            return "渲染失败: 未找到 Chrome/Chromium，请安装 Chrome 或设置 CHROME_PATH 环境变量。";
        }

        Path profileDir = null;
        try {
            profileDir = Files.createTempDirectory("browser-render-");
            List<String> cmd = new ArrayList<>();
            cmd.add(chromePath);
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
            cmd.add("--no-sandbox");
            cmd.add("--disable-extensions");
            cmd.add("--disable-background-networking");
            cmd.add("--disable-dev-shm-usage");
            cmd.add("--no-first-run");
            cmd.add("--no-default-browser-check");
            cmd.add("--mute-audio");
            cmd.add("--user-data-dir=" + profileDir.toAbsolutePath());
            cmd.add("--virtual-time-budget=" + wait);
            cmd.add("--dump-dom");
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() + 1 > maxDom) {
                        output.append(line, 0, Math.max(0, maxDom - output.length() - 1));
                        output.append("\n...[DOM 已截断]");
                        break;
                    }
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(wait + 5000, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "渲染超时（等待 " + wait + "ms），页面可能加载过慢或存在死循环。";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && output.length() == 0) {
                return "Chrome 退出码 " + exitCode + "，未产生 DOM 输出。可能 URL 不可达或 Chrome 版本不兼容。";
            }

            String dom = output.toString().trim();
            if (dom.isEmpty()) {
                return "Chrome 无 DOM 输出（退出码 " + exitCode + "），页面可能空白或需要登录。";
            }

            return "[浏览器渲染结果]\n"
                    + "URL: " + url + "\n"
                    + "DOM 长度: " + dom.length() + " 字符\n"
                    + (dom.length() >= maxDom ? "[DOM 已截断]\n" : "")
                    + "\n" + dom;

        } catch (Exception e) {
            return "渲染失败: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (profileDir != null) {
                try { deleteDir(profileDir); } catch (Exception ignored) { }
            }
        }
    }

    private static String findChrome() {
        String envPath = System.getenv("CHROME_PATH");
        if (envPath != null && !envPath.isEmpty() && java.nio.file.Files.exists(java.nio.file.Paths.get(envPath))) {
            return envPath;
        }
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String[] candidates = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Chromium\\Application\\chrome.exe"
            };
            for (String c : candidates) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(c))) return c;
            }
            // Check PATH
            try {
                Process which = new ProcessBuilder("where", "chrome.exe")
                        .redirectErrorStream(true).start();
                String line = new BufferedReader(new InputStreamReader(which.getInputStream())).readLine();
                if (line != null && !line.isEmpty()) return line.trim();
            } catch (Exception ignored) { }
        } else {
            String[] candidates = {
                "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome-stable"
            };
            for (String c : candidates) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(c))) return c;
            }
        }
        return null;
    }

    private static void deleteDir(Path dir) {
        try {
            java.nio.file.Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }
}
