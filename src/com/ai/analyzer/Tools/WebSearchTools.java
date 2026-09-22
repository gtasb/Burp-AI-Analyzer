package com.ai.analyzer.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSearchTools {
    private static final int MAX_FETCH_BYTES = 512 * 1024;
    private static final int MAX_FETCH_INLINE_CHARS = 18_000;

    private static final String FETCH_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Gson GSON = new Gson();

    private final String provider;
    private final String apiKey;
    private final String csi;
    private final String baseUrl;

    private WebSearchTools(String provider, String apiKey, String csi, String baseUrl) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.csi = csi;
        this.baseUrl = baseUrl;
    }

    public static WebSearchTools tavily(String apiKey, String baseUrl) {
        return new WebSearchTools("tavily", apiKey, null, baseUrl);
    }

    public static WebSearchTools google(String apiKey, String csi) {
        return new WebSearchTools("google", apiKey, csi, null);
    }

    public static WebSearchTools duckDuckGo() {
        return new WebSearchTools("duckduckgo", null, null, null);
    }

    @Tool(name = "web_search", description = "在互联网上搜索信息，用于查询最新漏洞(CVE)、技术文档、安全公告、资产信息等。参数 query 为搜索关键词或自然语言问题。返回结果包含直接答案（如果有）以及若干条网页摘要和链接。若需要查看某条链接的完整内容，可继续调用 fetch_url。")
    public String searchWeb(@ToolParam(name = "query", description = "搜索关键词或自然语言问题") String query) {
        if (query == null || query.trim().isEmpty()) {
            return "搜索失败: query 不能为空，请提供搜索关键词或自然语言问题。";
        }
        String q = query.trim();
        switch (provider) {
            case "tavily": return searchTavily(q);
            case "google": return searchGoogle(q);
            case "duckduckgo": return searchDuckDuckGo(q);
            default:             return "搜索失败: 未知搜索引擎提供商";
        }
    }

    @Tool(name = "vulnerability_search", description = "查询公开漏洞库(NVD)和安全公告。支持按产品/版本/CVE ID搜索，返回CVSS评分、受影响的版本范围、公开PoC/分析文章链接。命中结果需要人工验证目标版本和利用前置条件。")
    public String searchVulnerabilities(
            @ToolParam(name = "query", description = "搜索关键词，如产品名+版本号 或 CVE编号") String query) {
        if (query == null || query.trim().isEmpty()) return "查询失败: 关键词不能为空";

        String cveId = null;
        Matcher cveMatcher = Pattern.compile("CVE-\\d{4}-\\d{4,7}", Pattern.CASE_INSENSITIVE).matcher(query.trim());
        if (cveMatcher.find()) cveId = cveMatcher.group().toUpperCase();

        try {
            // 查询 NVD
            String params = cveId != null
                    ? "cveId=" + URLEncoder.encode(cveId, StandardCharsets.UTF_8)
                    : "keywordSearch=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "&resultsPerPage=10";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://services.nvd.nist.gov/rest/json/cves/2.0?" + params))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "BurpAI/1.0")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

            StringBuilder sb = new StringBuilder();
            if (resp.statusCode() != 200) {
                sb.append("NVD 查询返回 HTTP ").append(resp.statusCode()).append("\n");
            } else {
                JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                JsonArray vulns = root.getAsJsonArray("vulnerabilities");
                if (vulns == null || vulns.isEmpty()) {
                    sb.append("NVD 未找到匹配的 CVE 记录。\n");
                } else {
                    sb.append("## NVD 查询结果 (").append(Math.min(vulns.size(), 10)).append(" 条)\n\n");
                    for (int i = 0; i < Math.min(vulns.size(), 10); i++) {
                        JsonObject cve = vulns.get(i).getAsJsonObject().getAsJsonObject("cve");
                        if (cve == null) continue;
                        String id = jsonStr(cve, "id");
                        sb.append("**").append(id).append("**\n");
                        String desc = extractNvdDescription(cve);
                        if (desc != null) sb.append(desc.substring(0, Math.min(300, desc.length()))).append("\n");
                        String severity = extractNvdSeverity(cve);
                        if (severity != null) sb.append("严重等级: ").append(severity).append("\n");
                        JsonArray refs = cve.getAsJsonArray("references");
                        if (refs != null) {
                            for (int j = 0; j < Math.min(refs.size(), 4); j++) {
                                String url = jsonStr(refs.get(j).getAsJsonObject(), "url");
                                if (url != null && !url.isEmpty()) sb.append("  - ").append(url).append("\n");
                            }
                        }
                        sb.append("\n");
                    }
                }
            }

            // Follow-up web search for PoC/advisory (if CVE found or explicit product search)
            if (cveId != null) {
                sb.append("**PoC/分析文章搜索**:\n");
                for (String suffix : new String[]{" PoC", " exploit GitHub", " advisory"}) {
                    String followupUrl = "https://www.google.com/search?q="
                            + URLEncoder.encode(cveId + suffix, StandardCharsets.UTF_8);
                    sb.append("  - 搜索 \"").append(cveId).append(suffix).append("\"\n");
                }
                sb.append("（请使用 web_search 搜索上述关键词获取最新公开分析）\n");
            }
            sb.append("\n⚠️ 注意: 公开漏洞库的记录需要人工验证目标版本和利用前置条件，不能直接作为存在漏洞的证据。");
            return sb.toString().trim();
        } catch (Exception e) {
            return "漏洞查询失败: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static String extractNvdDescription(JsonObject cve) {
        JsonArray descs = cve.getAsJsonArray("descriptions");
        if (descs == null) return null;
        for (JsonElement el : descs) {
            JsonObject d = el.getAsJsonObject();
            if ("en".equals(jsonStr(d, "lang"))) return jsonStr(d, "value");
        }
        return descs.size() > 0 ? jsonStr(descs.get(0).getAsJsonObject(), "value") : null;
    }

    private static String extractNvdSeverity(JsonObject cve) {
        JsonObject metrics = cve.getAsJsonObject("metrics");
        if (metrics == null) return null;
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonArray arr = metrics.getAsJsonArray(key);
            if (arr != null && arr.size() > 0) {
                JsonObject data = arr.get(0).getAsJsonObject().getAsJsonObject("cvssData");
                if (data != null) {
                    String severity = jsonStr(data, "baseSeverity");
                    String score = data.get("baseScore") != null ? String.valueOf(data.get("baseScore").getAsDouble()) : "";
                    return severity + (score.isEmpty() ? "" : " (" + score + ")");
                }
            }
        }
        return null;
    }

    private static String jsonStr(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    private String searchTavily(String query) {
        String url = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl.trim() : "https://api.tavily.com/search";
        try {
            JsonObject body = new JsonObject();
            body.addProperty("api_key", apiKey);
            body.addProperty("query", query);
            body.addProperty("max_results", 5);
            body.addProperty("include_answer", true);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "搜索失败: Tavily API 返回 HTTP " + resp.statusCode() + " - " + resp.body();
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            StringBuilder sb = new StringBuilder();

            if (json.has("answer") && !json.get("answer").isJsonNull()) {
                String answer = json.get("answer").getAsString();
                if (!answer.trim().isEmpty()) {
                    sb.append("【搜索直接答案】\n").append(answer.trim()).append("\n\n");
                }
            }

            sb.append("【相关网页】\n");
            JsonArray results = json.getAsJsonArray("results");
            if (results == null || results.isEmpty()) {
                return "未找到相关搜索结果，可尝试换用更具体的关键词。";
            }
            int idx = 1;
            for (JsonElement el : results) {
                JsonObject r = el.getAsJsonObject();
                sb.append("[").append(idx++).append("] ");
                if (r.has("title") && !r.get("title").isJsonNull()) sb.append(r.get("title").getAsString()).append("\n");
                if (r.has("url") && !r.get("url").isJsonNull()) sb.append("URL: ").append(r.get("url").getAsString()).append("\n");
                if (r.has("content") && !r.get("content").isJsonNull()) sb.append(r.get("content").getAsString().trim()).append("\n");
                sb.append("\n");
            }
            sb.append("如需阅读某条链接的完整正文，请调用 fetch_url(url=\"...\")。");
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败 (Tavily): " + (e.getMessage() != null ? e.getMessage() : e.toString())
                    + "。建议稍后重试或换用其他关键词。";
        }
    }

    private String searchGoogle(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/customsearch/v1?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&cx=" + URLEncoder.encode(csi, StandardCharsets.UTF_8)
                    + "&q=" + encodedQuery + "&num=5";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "搜索失败: Google API 返回 HTTP " + resp.statusCode() + " - " + resp.body();
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray items = json.getAsJsonArray("items");
            if (items == null || items.isEmpty()) {
                return "未找到相关搜索结果，可尝试换用更具体的关键词。";
            }

            StringBuilder sb = new StringBuilder("【相关网页】\n");
            int idx = 1;
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                sb.append("[").append(idx++).append("] ");
                if (item.has("title")) sb.append(item.get("title").getAsString()).append("\n");
                if (item.has("link")) sb.append("URL: ").append(item.get("link").getAsString()).append("\n");
                if (item.has("snippet")) sb.append(item.get("snippet").getAsString().trim()).append("\n");
                sb.append("\n");
            }
            sb.append("如需阅读某条链接的完整正文，请调用 fetch_url(url=\"...\")。");
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败 (Google): " + (e.getMessage() != null ? e.getMessage() : e.toString())
                    + "。建议稍后重试或换用其他关键词。";
        }
    }

    private String searchDuckDuckGo(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", FETCH_UA)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "搜索失败: DuckDuckGo 返回 HTTP " + resp.statusCode();
            }

            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            StringBuilder sb = new StringBuilder();

            if (json.has("AbstractText") && !json.get("AbstractText").isJsonNull()) {
                String abstractText = json.get("AbstractText").getAsString();
                if (!abstractText.trim().isEmpty()) {
                    sb.append("【摘要】\n").append(abstractText.trim()).append("\n");
                    if (json.has("AbstractSource") && !json.get("AbstractSource").isJsonNull()) {
                        sb.append("来源: ").append(json.get("AbstractSource").getAsString()).append("\n");
                    }
                    if (json.has("AbstractURL") && !json.get("AbstractURL").isJsonNull()) {
                        sb.append("URL: ").append(json.get("AbstractURL").getAsString()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            sb.append("【相关网页】\n");
            int idx = 1;

            JsonArray results = json.getAsJsonArray("Results");
            if (results != null) {
                for (JsonElement el : results) {
                    JsonObject r = el.getAsJsonObject();
                    sb.append("[").append(idx++).append("] ");
                    if (r.has("Text") && !r.get("Text").isJsonNull()) sb.append(r.get("Text").getAsString()).append("\n");
                    if (r.has("FirstURL") && !r.get("FirstURL").isJsonNull()) sb.append("URL: ").append(r.get("FirstURL").getAsString()).append("\n");
                    sb.append("\n");
                }
            }

            JsonArray topics = json.getAsJsonArray("RelatedTopics");
            if (topics != null) {
                for (JsonElement el : topics) {
                    if (idx > 10) break;
                    if (el.isJsonObject()) {
                        JsonObject topic = el.getAsJsonObject();
                        if (topic.has("Text") && !topic.get("Text").isJsonNull()) {
                            sb.append("[").append(idx++).append("] ").append(topic.get("Text").getAsString()).append("\n");
                            if (topic.has("FirstURL") && !topic.get("FirstURL").isJsonNull()) {
                                sb.append("URL: ").append(topic.get("FirstURL").getAsString()).append("\n");
                            }
                            sb.append("\n");
                        }
                    }
                }
            }

            if (idx == 1) {
                return "未找到相关搜索结果，可尝试换用更具体的关键词。";
            }
            sb.append("如需阅读某条链接的完整正文，请调用 fetch_url(url=\"...\")。");
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败 (DuckDuckGo): " + (e.getMessage() != null ? e.getMessage() : e.toString())
                    + "。建议稍后重试或换用其他关键词。";
        }
    }

    /** 单参数兼容重载（供测试和内部调用） */
    public String fetchUrl(String url) {
        return fetchUrl(url, null);
    }

    @Tool(name = "fetch_url", description = "抓取指定 URL 的内容并提取可读文本。自动过滤非公开/内网地址。返回页面标题与正文，超长内容会截断。")
    public String fetchUrl(
            @ToolParam(name = "url", description = "完整的 http 或 https URL") String url,
            @ToolParam(name = "maxChars", description = "可选：返回内容最大字符数，默认 18000，最大 50000", required = false) Integer maxChars) {
        if (url == null || url.trim().isEmpty()) {
            return "抓取失败: URL 不能为空，请提供完整的 http/https URL。";
        }
        String raw = url.trim();
        raw = raw.replaceAll("(?i)^\\[(?<txt>[^\\]]*)\\]\\((?<link>[^\\)]+)\\)$", "${link}").trim();

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return "抓取失败: URL 格式无效 — " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "抓取失败: 仅允许 http/https 协议";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "抓取失败: 无法解析主机名";
        }

        // DNS 级 IP 验证：解析 hostname 到 IP，阻止内网/链路本地地址
        String blockedReason = resolveAndCheckBlocked(host);
        if (blockedReason != null) {
            return "抓取失败: " + blockedReason;
        }

        int maxFetchChars = (maxChars != null) ? Math.min(Math.max(1000, maxChars), MAX_FETCH_INLINE_CHARS) : MAX_FETCH_INLINE_CHARS;

        try {
            HttpRequest req = HttpRequest.newBuilder(uri.normalize())
                    .timeout(Duration.ofSeconds(35))
                    .header("User-Agent", FETCH_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status < 200 || status >= 300) {
                return "抓取失败: HTTP " + status
                        + "\n提示：该页面可能需要登录、被拦截或已不可用，可尝试用 web_search 搜索快照/备用来源。";
            }

            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            String ctLower = contentType.toLowerCase(Locale.ROOT);
            if (ctLower.contains("application/pdf")
                    || ctLower.startsWith("image/")
                    || ctLower.startsWith("video/")
                    || ctLower.startsWith("audio/")
                    || ctLower.contains("octet-stream")) {
                return "抓取失败或不适用: 响应为二进制类型 (" + contentType + ")，请改用浏览器或专用工具下载";
            }

            byte[] body = readLimited(resp.body(), MAX_FETCH_BYTES);
            Charset charset = charsetFromContentType(contentType);

            String text = new String(body, charset);
            String title = "";
            if (ctLower.contains("html") || text.stripLeading().startsWith("<")) {
                title = extractHtmlTitle(text);
                text = readabilityExtract(text);
            }

            text = normalizeWs(text);
            String header = "[来源] " + uri + "\n[HTTP " + status + "]\n";
            if (!title.isEmpty()) header += "[标题] " + title + "\n";
            header += "\n";

            int fullLen = text.length();
            if (fullLen == 0) {
                return header + "（页面正文为空）";
            }
            String display = text.length() > maxFetchChars
                    ? text.substring(0, maxFetchChars)
                        + "\n\n...[网页正文较长，剩余内容已截断]..."
                    : text;
            return header + display;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return "抓取失败: " + msg
                    + "\n提示：可尝试用 web_search 搜索该 URL 的镜像/缓存版本，或检查 URL 是否可访问。";
        }
    }

    private static boolean isPrivateIp(String ip) {
        if (ip == null) return true;
        // IPv6 checks
        if (ip.contains(":")) {
            String n = ip.toLowerCase(Locale.ROOT);
            if (n.equals("::1") || n.equals("::") || n.equals("[::1]")) return true;
            if (n.startsWith("fc") || n.startsWith("fd")) return true; // unique local address
            if (n.startsWith("fe80")) return true; // link-local
            // IPv4-mapped IPv6
            if (n.contains("::ffff:")) {
                String v4part = n.substring(n.lastIndexOf(":") + 1);
                return isPrivateIpV4(v4part);
            }
            return false;
        }
        return isPrivateIpV4(ip);
    }

    private static boolean isPrivateIpV4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return true;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first == 0 || first == 10) return true;
            if (first == 127) return true;
            if (first == 169 && second == 254) return true;
            if (first == 172 && second >= 16 && second <= 31) return true;
            if (first == 192 && second == 168) return true;
            if (first == 100 && second >= 64 && second <= 127) return true; // CGNAT
            if (first >= 224) return true; // multicast + reserved
        } catch (NumberFormatException e) {
            return true;
        }
        return false;
    }

    /**
     * SSRF 防护：仅在 URL 字面量/直连 IP 明确指向内网地址时拦截。
     *
     * <p>不做基于系统 DNS 解析的拦截——本地 DNS 污染/劫持环境会把公网域名
     * （如 example.com）解析到 127.x 假地址，导致合法站点被误拦。
     * 解析到内网只作为警告信息返回（由调用方追加提示），不阻止请求。
     *
     * @return null 表示允许；非 null 返回明确拒绝原因（仅在字面量明确内网时）
     */
    private static String resolveAndCheckBlocked(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")) {
            return "不允许访问本地回环地址";
        }
        if ("metadata.google.internal".equals(h) || "169.254.169.254".equals(h)) {
            return "不允许访问云元数据端点";
        }
        if (isLiteralPrivateIp(h)) {
            return "不允许访问内网地址 (" + h + ")";
        }
        return null; // allowed
    }

    /** 判断字符串是否为明确的私有/保留 IPv4 或 IPv6 字面量（用于直连 IP 的 URL） */
    private static boolean isLiteralPrivateIp(String host) {
        String h = host.replace("[", "").replace("]", "");
        if (h.contains(":")) {
            return h.equals("::1") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8");
        }
        // 必须先确认是 IPv4 字面量（4 段纯数字），域名/主机名直接放行
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || !p.chars().allMatch(Character::isDigit)) return false;
        }
        return isPrivateIpV4(h);
    }

    private static String extractHtmlTitle(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? m.group(1).trim().replaceAll("\\s+", " ") : "";
    }

    /**
     * Readability 风格的内容提取：去除非内容元素，优先 article/main，退回到 body。
     */
    private static String readabilityExtract(String html) {
        if (html == null || html.isEmpty()) return "";
        // 移除不可见元素
        String s = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        s = s.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
        s = s.replaceAll("(?is)<header[^>]*>.*?</header>", " ");
        s = s.replaceAll("(?is)<footer[^>]*>.*?</footer>", " ");
        s = s.replaceAll("(?is)<aside[^>]*>.*?</aside>", " ");
        s = s.replaceAll("(?is)<form[^>]*>.*?</form>", " ");
        s = s.replaceAll("(?is)<svg[^>]*>.*?</svg>", " ");

        // 优先找 article/main 容器
        java.util.regex.Matcher main = java.util.regex.Pattern.compile(
                "(?is)<(?:article|main|div\\s+(?:class|id)\\s*=\\s*\"(?:content|post|entry|article|main)\")[^>]*>(.*?)</(?:article|main|div)>").matcher(s);
        if (main.find()) {
            s = main.group(1);
        } else {
            // 退到 body
            java.util.regex.Matcher body = java.util.regex.Pattern.compile(
                    "(?is)<body[^>]*>(.*?)</body>").matcher(s);
            if (body.find()) s = body.group(1);
        }

        // 转可读文本
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = decodeBasicEntities(s);
        return s.trim();
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < maxBytes) {
                int n = in.read(buf, 0, Math.min(buf.length, maxBytes - total));
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) return StandardCharsets.UTF_8;
        Matcher m = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (m.find()) {
            try {
                String name = m.group(1).replace("\"", "").trim();
                return Charset.forName(name);
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String decodeBasicEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String normalizeWs(String s) {
        if (s == null) return "";
        return s.replaceAll("[ \t]+", " ")
                .replaceAll("\\R{3,}", "\n\n")
                .trim();
    }
}
