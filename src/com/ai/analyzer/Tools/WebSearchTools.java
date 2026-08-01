package com.ai.analyzer.tools;

import com.ai.analyzer.util.ArtifactCache;
import com.ai.analyzer.util.PromptInjectionGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSearchTools {
    private static final int MAX_FETCH_BYTES = 512 * 1024;
    private static final int MAX_FETCH_INLINE_CHARS = 18_000;
    private static final int FETCH_PREVIEW_CHARS = 2_000;

    /** 常见桌面 Chrome（Windows）导航请求，避免特征明显的爬虫/工具 UA */
    private static final String FETCH_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final HttpClient FETCH_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 支持的搜索后端 */
    private enum SearchEngine { TAVILY, GOOGLE, DUCKDUCKGO }

    private final SearchEngine engine;
    private final String apiKey;
    private final String baseUrl; // Tavily 自定义端点（代理等场景）
    private final String csi;     // Google Custom Search Engine ID

    public static WebSearchTools tavily(String apiKey, String baseUrl) {
        return new WebSearchTools(SearchEngine.TAVILY, apiKey, baseUrl, null);
    }

    public static WebSearchTools google(String apiKey, String csi) {
        return new WebSearchTools(SearchEngine.GOOGLE, apiKey, null, csi);
    }

    public static WebSearchTools duckDuckGo() {
        return new WebSearchTools(SearchEngine.DUCKDUCKGO, null, null, null);
    }

    public WebSearchTools(String apiKey) {
        this(apiKey, null);
    }

    public WebSearchTools(String apiKey, String baseUrl) {
        this(SearchEngine.TAVILY, apiKey, baseUrl, null);
    }

    private WebSearchTools(SearchEngine engine, String apiKey, String baseUrl, String csi) {
        this.engine = engine;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.csi = csi;
    }

    @Tool(name = "web_search", description =
            "在互联网上搜索信息，用于查询最新漏洞(CVE)、技术文档、安全公告、资产信息等。"
            + "参数 query 为搜索关键词或自然语言问题。"
            + "返回结果包含引擎提供的直接答案（如果有）以及若干条网页摘要和链接。"
            + "若需要查看某条链接的完整内容，可继续调用 fetch_url。")
    public String searchWeb(@ToolParam(name = "query", description = "搜索关键词或自然语言问题") String query) {
        if (query == null || query.trim().isEmpty()) {
            return "搜索失败: query 不能为空，请提供搜索关键词或自然语言问题。";
        }
        String q = query.trim();

        // 对不稳定搜索引擎进行 2 次重试
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String text = switch (engine) {
                    case TAVILY -> searchTavily(q);
                    case GOOGLE -> searchGoogle(q);
                    case DUCKDUCKGO -> searchDuckDuckGo(q);
                };
                if (text == null) {
                    return "未找到相关搜索结果，可尝试换用更具体的关键词。";
                }
                return text;
            } catch (Exception e) {
                lastError = e;
                if (attempt == 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }

        return "搜索失败: " + (lastError != null && lastError.getMessage() != null
                ? lastError.getMessage()
                : "未知错误")
                + "。建议稍后重试或换用其他关键词。";
    }

    /**
     * Tavily REST API：POST /search，自带直接答案字段。
     */
    private String searchTavily(String query) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("Tavily API Key 未配置");
        }
        String endpoint = (baseUrl != null && !baseUrl.trim().isEmpty() ? baseUrl.trim() : "https://api.tavily.com") + "/search";
        String body = "{\"api_key\":\"" + escapeJson(apiKey) + "\",\"query\":\"" + escapeJson(query)
                + "\",\"max_results\":5,\"include_answer\":true}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = FETCH_HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Tavily 搜索失败: HTTP " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        if (root.has("answer")) {
            String answer = root.get("answer").getAsString().trim();
            if (!answer.isEmpty()) {
                sb.append("【搜索直接答案】\n").append(answer).append("\n\n");
            }
        }
        sb.append("【相关网页】\n");
        JsonArray results = root.has("results") ? root.getAsJsonArray("results") : new JsonArray();
        if (results.isEmpty()) return null;

        int idx = 1;
        for (JsonElement el : results) {
            JsonObject r = el.getAsJsonObject();
            sb.append("[").append(idx++).append("] ");
            if (r.has("title") && !r.get("title").isJsonNull()) sb.append(r.get("title").getAsString()).append("\n");
            if (r.has("url") && !r.get("url").isJsonNull()) sb.append("URL: ").append(r.get("url").getAsString()).append("\n");
            if (r.has("content") && !r.get("content").isJsonNull()) {
                String c = r.get("content").getAsString().trim();
                if (!c.isEmpty()) sb.append(c).append("\n");
            }
            sb.append("\n");
        }
        sb.append("如需阅读某条链接的完整正文，请调用 fetch_url(url=\"...\")。");
        return sb.toString().trim();
    }

    /**
     * Google Custom Search JSON API：GET /customsearch/v1。
     */
    private String searchGoogle(String query) throws Exception {
        if (apiKey == null || apiKey.isEmpty() || csi == null || csi.isEmpty()) {
            throw new IOException("Google 搜索 API Key / CX 未配置");
        }
        String url = "https://www.googleapis.com/customsearch/v1?key=" + urlEncode(apiKey)
                + "&cx=" + urlEncode(csi)
                + "&q=" + urlEncode(query)
                + "&num=5";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = FETCH_HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Google 搜索失败: HTTP " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!root.has("items")) return null;

        StringBuilder sb = new StringBuilder("【相关网页】\n");
        JsonArray items = root.getAsJsonArray("items");
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
    }

    /**
     * DuckDuckGo HTML 端点：解析 result__a / result__snippet。
     */
    private String searchDuckDuckGo(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + urlEncode(query);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", FETCH_UA)
                .header("Accept", "text/html")
                .GET()
                .build();
        HttpResponse<String> resp = FETCH_HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("DuckDuckGo 搜索失败: HTTP " + resp.statusCode());
        }
        String html = resp.body();

        List<String[]> items = new ArrayList<>();
        Matcher linkM = Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (linkM.find() && items.size() < 5) {
            items.add(new String[]{extractDdgHref(linkM.group(1)), stripHtmlTags(linkM.group(2))});
        }
        if (items.isEmpty()) return null;

        Matcher snipM = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snipM.find() && snippets.size() < 5) {
            snippets.add(stripHtmlTags(snipM.group(1)).trim());
        }

        StringBuilder sb = new StringBuilder("【相关网页】\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(items.get(i)[1]).append("\n");
            sb.append("URL: ").append(items.get(i)[0]).append("\n");
            if (i < snippets.size() && !snippets.get(i).isEmpty()) {
                sb.append(snippets.get(i)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("如需阅读某条链接的完整正文，请调用 fetch_url(url=\"...\")。");
        return sb.toString().trim();
    }

    /**
     * 从 DDG 的 /l/?uddg= 跳转链接中还原真实 URL。
     */
    private static String extractDdgHref(String href) {
        int i = href.indexOf("uddg=");
        if (i >= 0) {
            int j = href.indexOf('&', i);
            String enc = j < 0 ? href.substring(i + 5) : href.substring(i + 5, j);
            try {
                return URLDecoder.decode(enc, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return href.replace("&amp;", "&");
    }

    private static String stripHtmlTags(String s) {
        if (s == null) return "";
        return decodeBasicEntities(s.replaceAll("<[^>]+>", "").replace("&amp;", "&"));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlEncode(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Tool(name = "fetch_url", description =
            "GET 抓取指定 URL 的正文内容（用于阅读搜索结果中的网页详情）。"
            + "仅支持 http/https；返回纯文本（HTML 会剥离标签）。内容过长会自动截断并缓存。"
            + "典型用法：web_search 得到链接后，用本工具读取 CVE 详情页、公告正文等。")
    public String fetchUrl(@ToolParam(name = "url", description = "完整的 http 或 https URL") String url) {
        if (url == null || url.trim().isEmpty()) {
            return "抓取失败: URL 不能为空，请提供完整的 http/https URL。";
        }
        String raw = url.trim();
        // 如果模型传入了 Markdown 链接或多余空格，简单清洗
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
        if (isBlockedHost(host)) {
            return "抓取失败: 出于安全考虑，不允许访问该主机（内网/元数据等地址）。如需访问，请调整安全策略。";
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(uri.normalize())
                    .timeout(Duration.ofSeconds(35))
                    .header("User-Agent", FETCH_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Ch-Ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = FETCH_HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
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
            if (ctLower.contains("html") || text.stripLeading().startsWith("<")) {
                text = htmlToPlainText(text);
            }

            text = normalizeWs(text);
            // 提示注入防护：网页内容为不可信外部数据，进入 LLM 上下文前检测并包裹警告标记
            text = PromptInjectionGuard.guard(text);
            String header = "[来源] " + uri + "\n[HTTP " + status + "]\n\n";
            int fullLen = text.length();
            if (fullLen == 0) {
                return header + "（页面正文为空）";
            }
            if (fullLen > MAX_FETCH_INLINE_CHARS) {
                ArtifactCache.ArtifactRef ref = ArtifactCache.saveText(header + text, "fetch-url");
                String preview = text.substring(0, Math.min(FETCH_PREVIEW_CHARS, text.length()));
                return header
                        + preview
                        + "\n\n...[网页正文较长，完整内容未直接进入上下文，可通过缓存读取]...\n\n"
                        + ref.toPromptText();
            }
            return header + text;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return "抓取失败: " + msg
                    + "\n提示：可尝试用 web_search 搜索该 URL 的镜像/缓存版本，或检查 URL 是否可访问。";
        }
    }

    private static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost")) return true;
        if ("metadata.google.internal".equals(h)) return true;
        if (h.equals("0.0.0.0") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]")) return true;
        if (h.equals("169.254.169.254") || h.startsWith("169.254.")) return true;
        // 常见私网 IPv4 字面量
        if (h.startsWith("10.")) return true;
        if (h.startsWith("192.168.")) return true;
        if (h.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..+")) return true;
        return false;
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
                /* fall through */
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String htmlToPlainText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("(?i)</div>", "\n");
        s = s.replaceAll("(?i)</tr>", "\n");
        s = s.replaceAll("(?i)</li>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = decodeBasicEntities(s);
        return s;
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
