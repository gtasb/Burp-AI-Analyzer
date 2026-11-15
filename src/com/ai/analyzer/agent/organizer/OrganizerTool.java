package com.ai.analyzer.agent.organizer;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 整理 agent 的专用工具：把 POC 送进 Repeater、把复测脚本写入工作区、在结束时给出决策。
 *
 * <p>Repeater 只会打开请求页，不会自动发包；真正发送仍由人类在 Repeater 中完成。
 */
public class OrganizerTool {

    public record Decision(boolean continueAnalysis, String instruction, String gaps, String report) {
    }

    private final MontoyaApi api;
    private final Path workspace;
    private final AtomicReference<Decision> decision = new AtomicReference<>();

    public OrganizerTool(MontoyaApi api, Path workspace) {
        this.api = api;
        this.workspace = workspace;
    }

    public Decision getDecision() {
        return decision.get();
    }

    public void reset() {
        decision.set(null);
    }

    @Tool(name = "organizer_send_to_repeater", description =
            "把一个已验证的 POC 请求发送到 Burp Repeater（只打开请求页，不会自动发包，人类确认后再发送）。")
    public String sendToRepeater(
            @ToolParam(name = "method", description = "HTTP 方法，如 GET/POST") String method,
            @ToolParam(name = "url", description = "完整 URL") String url,
            @ToolParam(name = "headers", description = "请求头，每行一个 K: V，可留空", required = false) String headers,
            @ToolParam(name = "body", description = "请求体，可留空", required = false) String body,
            @ToolParam(name = "name", description = "Repeater 标签名，可留空", required = false) String name) {
        if (api == null) return "Repeater 不可用：MontoyaApi 为空";
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean secure = "https".equals(scheme);
            int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return "URL 不合法: " + url;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                path += "?" + uri.getRawQuery();
            }
            StringBuilder raw = new StringBuilder();
            raw.append(method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT))
                    .append(' ').append(path).append(" HTTP/1.1\r\n");
            raw.append("Host: ").append(host);
            if ((secure && port != 443) || (!secure && port != 80)) {
                raw.append(':').append(port);
            }
            raw.append("\r\n");
            if (headers != null && !headers.isBlank()) {
                for (String line : headers.split("\\R")) {
                    if (!line.isBlank()) raw.append(line.trim()).append("\r\n");
                }
            }
            String b = body == null ? "" : body;
            if (!b.isEmpty() && !containsHeader(headers, "content-length")) {
                raw.append("Content-Length: ").append(b.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
            }
            raw.append("\r\n").append(b);
            HttpService service = HttpService.httpService(host, port, secure);
            HttpRequest request = HttpRequest.httpRequest(service, raw.toString());
            api.repeater().sendToRepeater(request, name == null || name.isBlank() ? "AI-POC" : name.trim());
            return "已发送到 Repeater: " + name;
        } catch (Exception e) {
            return "发送 Repeater 失败: " + e.getMessage();
        }
    }

    @Tool(name = "organizer_save_script", description =
            "把复测脚本/POC 保存到工作区的 pocs/ 目录（不会执行）。filename 只允许文件名，不允许路径。")
    public String saveScript(
            @ToolParam(name = "filename", description = "文件名，如 poc_idor.py") String filename,
            @ToolParam(name = "content", description = "脚本内容") String content) {
        try {
            String safe = sanitizeFilename(filename);
            if (safe.isEmpty()) return "文件名不合法";
            Path dir = workspace != null ? workspace.resolve("pocs") : Path.of("pocs");
            Files.createDirectories(dir);
            Path target = dir.resolve(safe);
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return "脚本已保存: " + target.toAbsolutePath();
        } catch (Exception e) {
            return "保存脚本失败: " + e.getMessage();
        }
    }

    @Tool(name = "organizer_finish", description =
            "本轮整理结束。continue_analysis=true 表示还有未覆盖的攻击面，需要主动 agent 继续；"
                    + "否则给出完整渗透测试报告。必须且只能调用一次。")
    public String finish(
            @ToolParam(name = "continue_analysis", description = "是否让主动 agent 继续分析") boolean continueAnalysis,
            @ToolParam(name = "instruction", description = "继续时给主动 agent 的具体指令；结束时留空", required = false) String instruction,
            @ToolParam(name = "gaps", description = "未覆盖点/新攻击向量，简短列表", required = false) String gaps,
            @ToolParam(name = "report_markdown", description = "结束时输出完整渗透测试报告（Markdown）；继续时留空", required = false) String report) {
        decision.set(new Decision(continueAnalysis,
                instruction == null ? "" : instruction.trim(),
                gaps == null ? "" : gaps.trim(),
                report == null ? "" : report));
        return continueAnalysis ? "已记录：继续分析" : "已记录：结束并出报告";
    }

    private static boolean containsHeader(String headers, String name) {
        if (headers == null) return false;
        String lower = headers.toLowerCase(Locale.ROOT);
        return lower.contains(name.toLowerCase(Locale.ROOT) + ":");
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null) return "";
        String f = filename.trim().replace('\\', '/');
        int slash = f.lastIndexOf('/');
        if (slash >= 0) f = f.substring(slash + 1);
        f = f.replaceAll("[^a-zA-Z0-9._-]", "_");
        return f.length() > 120 ? f.substring(0, 120) : f;
    }
}
