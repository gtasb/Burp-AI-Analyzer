package com.ai.analyzer.Tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import dev.langchain4j.web.search.google.customsearch.GoogleCustomWebSearchEngine;
import dev.langchain4j.community.web.search.duckduckgo.DuckDuckGoWebSearchEngine;
import com.ai.analyzer.utils.ArtifactCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private final WebSearchEngine searchEngine;

    public static WebSearchTools tavily(String apiKey, String baseUrl) {
        var builder = TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .timeout(Duration.ofSeconds(30));
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.baseUrl(baseUrl.trim());
        }
        return new WebSearchTools(builder.build());
    }

    public static WebSearchTools google(String apiKey, String csi) {
        return new WebSearchTools(GoogleCustomWebSearchEngine.builder()
                .apiKey(apiKey)
                .csi(csi)
                .timeout(Duration.ofSeconds(30))
                .build());
    }

    public static WebSearchTools duckDuckGo() {
        return new WebSearchTools(DuckDuckGoWebSearchEngine.builder()
                .duration(Duration.ofSeconds(30))
                .build());
    }

    public WebSearchTools(String apiKey) {
        this(apiKey, null);
    }

    public WebSearchTools(String apiKey, String baseUrl) {
        this(buildTavily(apiKey, baseUrl));
    }

    private WebSearchTools(WebSearchEngine engine) {
        this.searchEngine = engine;
    }

    private static WebSearchEngine buildTavily(String apiKey, String baseUrl) {
        var builder = TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .timeout(Duration.ofSeconds(30));
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    @Tool(name = "web_search", value = {
            "在互联网上搜索信息，用于查询最新漏洞(CVE)、技术文档、安全公告、资产信息等。",
            "参数 query 为搜索关键词或自然语言问题。"
    })
    public String searchWeb(@P("搜索关键词或自然语言问题") String query) {
        try {
            WebSearchRequest request = WebSearchRequest.builder()
                    .searchTerms(query)
                    .maxResults(5)
                    .build();
            WebSearchResults results = searchEngine.search(request);
            if (results == null || results.results() == null || results.results().isEmpty()) {
                return "未找到相关搜索结果";
            }
            return results.results().stream()
                    .map(r -> {
                        StringBuilder sb = new StringBuilder();
                        if (r.title() != null) sb.append("### ").append(r.title()).append("\n");
                        if (r.url() != null) sb.append("URL: ").append(r.url()).append("\n");
                        if (r.snippet() != null) sb.append(r.snippet()).append("\n");
                        return sb.toString();
                    })
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    @Tool(name = "fetch_url", value = {
            "GET 抓取指定 URL 的正文内容（用于阅读搜索结果中的网页详情）。",
            "仅支持 http/https；返回纯文本（HTML 会剥离标签）。内容过长会自动截断。",
            "典型用法：web_search 得到链接后，用本工具读取 CVE 详情页、公告正文等。"
    })
    public String fetchUrl(@P("完整的 http 或 https URL") String url) {
        if (url == null || url.trim().isEmpty()) {
            return "抓取失败: URL 不能为空";
        }
        String raw = url.trim();
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
            return "抓取失败: 出于安全考虑，不允许访问该主机（内网/元数据等地址）";
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
                return "抓取失败: HTTP " + status;
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
            String header = "[来源] " + uri + "\n[HTTP " + status + "]\n\n";
            int fullLen = text.length();
            if (fullLen > MAX_FETCH_INLINE_CHARS) {
                ArtifactCache.ArtifactRef ref = ArtifactCache.saveText(header + text, "fetch-url");
                String preview = text.substring(0, Math.min(FETCH_PREVIEW_CHARS, text.length()));
                return header
                        + preview
                        + "\n\n...[网页正文较长，完整内容未直接进入上下文]...\n\n"
                        + ref.toPromptText();
            }
            return header + text;
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
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
