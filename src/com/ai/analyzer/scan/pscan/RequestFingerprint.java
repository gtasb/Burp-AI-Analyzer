package com.ai.analyzer.scan.pscan;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 请求指纹：被动扫描成本控制的核心。
 *
 * <p>L1 精确去重：生成规范化去重键 {@code method|host|path|sorted-params|bodyDigest}。
 * 相比旧实现（method|host|path，完全忽略 query），新键：
 * <ul>
 *   <li>保留 query 参数名与值 —— 翻页/排序/过滤/IDOR 探测（id=1 vs id=2）不再被误去重；</li>
 *   <li>参数排序 —— 同一 URL 不同参数顺序视为同一请求；</li>
 *   <li>忽略时间戳/缓存类参数的值（timestamp、_t 等）—— 这些值变化不代表语义变化；</li>
 *   <li>POST/PUT/PATCH 附带 body 摘要，表单中 token/csrf 类参数值归一化。</li>
 * </ul>
 *
 * <p>会话归属：从 Cookie/Authorization 中提取会话标识，供 L3 会话批次分析按会话分桶。
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    /** 时间戳/缓存类参数名：值变化不代表请求语义变化，去重时忽略其值。 */
    private static final Set<String> IGNORED_PARAM_NAMES = Set.of(
            "timestamp", "ts", "_t", "nonce", "cachebust", "cachebuster", "cb", "_");

    /** 表单 token 类参数名（含子串即命中）：值每次变化，去重时归一化为 "*"。 */
    private static final String[] TOKEN_PARAM_MARKERS = {"token", "csrf", "xsrf"};

    /** 常见会话 cookie 名（小写）。 */
    private static final Set<String> SESSION_COOKIE_NAMES = Set.of(
            "jsessionid", "phpsessid", "session", "sessionid", "sid",
            "asp.net_sessionid", "connect.sid", "auth", "jwt", "token");

    /**
     * L1 规范化去重键。
     */
    public static String of(HttpRequestResponse rr) {
        if (rr == null || rr.request() == null) return "";
        HttpRequest req = rr.request();

        String url = req.url();
        if (url == null || url.isEmpty()) return "";

        String method = req.method().toUpperCase(Locale.ROOT);
        String host = hostOf(rr);

        String path = url;
        String query = null;
        int qi = url.indexOf('?');
        if (qi >= 0) {
            path = url.substring(0, qi);
            query = url.substring(qi + 1);
        }

        StringBuilder sb = new StringBuilder(method).append('|').append(host).append('|').append(path);
        String normalizedQuery = normalizeQuery(query);
        if (!normalizedQuery.isEmpty()) {
            sb.append('|').append(normalizedQuery);
        }
        String bodyDigest = bodyDigest(req);
        if (!bodyDigest.isEmpty()) {
            sb.append('|').append(bodyDigest);
        }
        return sb.toString();
    }

    /**
     * 请求所属主机。
     */
    public static String hostOf(HttpRequestResponse rr) {
        if (rr == null || rr.request() == null) return "";
        HttpService service = rr.request().httpService();
        if (service != null && service.host() != null && !service.host().isEmpty()) {
            return service.host();
        }
        return extractHost(rr.request().url());
    }

    /**
     * 会话归属 key：host + 会话标识（Cookie 中的会话值 / Authorization Bearer）。
     * 无会话标识时返回 host + "|anon"（匿名流，仍按 host 聚合）。
     */
    public static String sessionKey(HttpRequestResponse rr) {
        if (rr == null || rr.request() == null) return "|anon";
        String host = hostOf(rr);
        List<String> sessionValues = new ArrayList<>(2);
        try {
            for (HttpHeader header : rr.request().headers()) {
                String name = header.name() == null ? "" : header.name().toLowerCase(Locale.ROOT);
                String value = header.value() == null ? "" : header.value();
                if ("cookie".equals(name)) {
                    String cookieSession = sessionValueFromCookie(value);
                    if (!cookieSession.isEmpty()) sessionValues.add(cookieSession);
                } else if ("authorization".equals(name)) {
                    String auth = value.trim();
                    if (auth.regionMatches(true, 0, "bearer ", 0, 7)) {
                        sessionValues.add(auth.substring(7).trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (sessionValues.isEmpty()) return host + "|anon";
        Collections.sort(sessionValues);
        return host + "|" + String.join("&", sessionValues);
    }

    /**
     * 规范化 query：解码参数对，按名排序，时间戳/缓存类参数的值归一化为 "*"。
     * 参数值参与 key（IDOR/逻辑跳变依赖参数值变化），仅忽略无语义的时间戳值。
     */
    static String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        List<String> pairs = new ArrayList<>();
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            String name = part;
            String value = "";
            int eq = part.indexOf('=');
            if (eq >= 0) {
                name = part.substring(0, eq);
                value = part.substring(eq + 1);
            }
            if (IGNORED_PARAM_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                value = "*";
                name = name.toLowerCase(Locale.ROOT);
            }
            pairs.add(name + "=" + value);
        }
        if (pairs.isEmpty()) return "";
        Collections.sort(pairs);
        return String.join("&", pairs);
    }

    /**
     * body 摘要：表单类 body 将 token/csrf 参数值归一化后哈希，其余全文哈希。
     */
    static String bodyDigest(HttpRequest req) {
        try {
            String text = req.bodyToString();
            if (text == null || text.isEmpty()) return "";

            if (isFormBody(text)) {
                List<String> pairs = new ArrayList<>();
                for (String part : text.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) continue;
                    String name = part.substring(0, eq);
                    String lower = name.toLowerCase(Locale.ROOT);
                    boolean isToken = false;
                    for (String marker : TOKEN_PARAM_MARKERS) {
                        if (lower.contains(marker)) { isToken = true; break; }
                    }
                    pairs.add(name + "=" + (isToken ? "*" : part.substring(eq + 1)));
                }
                if (!pairs.isEmpty()) {
                    Collections.sort(pairs);
                    return "d" + Integer.toHexString(String.join("&", pairs).hashCode());
                }
            }
            return "d" + Integer.toHexString(text.hashCode());
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isFormBody(String text) {
        if (!text.contains("=")) return false;
        int eqCount = 0;
        int ampCount = 0;
        for (int i = 0; i < text.length() && i < 1024; i++) {
            char c = text.charAt(i);
            if (c == '=') eqCount++;
            else if (c == '&') ampCount++;
        }
        return eqCount >= 1 && eqCount <= 64;
    }

    private static String sessionValueFromCookie(String cookieHeader) {
        for (String part : cookieHeader.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (SESSION_COOKIE_NAMES.contains(name)) {
                return name + "=" + pair.substring(eq + 1).trim();
            }
        }
        return "";
    }

    static String extractHost(String url) {
        if (url == null) return "";
        try {
            String s = url;
            int protoEnd = s.indexOf("://");
            if (protoEnd > 0) s = s.substring(protoEnd + 3);
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(']') < colon) s = s.substring(0, colon);
            return s.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
