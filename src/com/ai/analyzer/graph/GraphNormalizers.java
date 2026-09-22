package com.ai.analyzer.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Origin / URL / Interface / Parameter 归一化（TODO2 §11 / §10）。
 *
 * <p>canonical key 一律由程序生成：INTERFACE = {@code origin|METHOD|path}，
 * PARAMETER = {@code interfaceId|location|name}，MODULE/SERVICE/ENTITY 等按 root 内规范化名。
 */
public final class GraphNormalizers {

    private GraphNormalizers() {}

    /** URL 中的参数。 */
    public record Param(String name, String location) {
    }

    /** 解析结果（不含完整报文）。 */
    public record ParsedRequest(
            String origin, String scheme, String host, int port,
            String method, String path, List<Param> params) {
    }

    /**
     * 从 URL + method + 可能含表单参数的请求体解析。
     * 无端口信息时按 scheme 推断（http:80 / https:443）。
     */
    public static ParsedRequest parse(String method, String url, String body) {
        String m = method == null ? "GET" : method.toUpperCase().trim();
        String s = url == null ? "" : url.trim();
        String scheme = "http";
        int protoEnd = s.indexOf("://");
        if (protoEnd > 0) {
            scheme = s.substring(0, protoEnd).toLowerCase();
            s = s.substring(protoEnd + 3);
        }
        int slash = s.indexOf('/');
        String hostPort = slash >= 0 ? s.substring(0, slash) : s;
        String path = slash >= 0 ? s.substring(slash) : "/";
        int q = path.indexOf('?');
        String query = "";
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        int frag = path.indexOf('#');
        if (frag >= 0) {
            path = path.substring(0, frag);
        }
        String host = hostPort;
        int port = scheme.equalsIgnoreCase("https") ? 443 : 80;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(']') < colon) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        String origin = scheme.toLowerCase() + "://" + (host.isEmpty() ? "unknown" : host) + ":" + port;
        List<Param> params = new ArrayList<>();
        if (!query.isEmpty()) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String name = eq >= 0 ? part.substring(0, eq) : part;
                if (!name.isEmpty()) {
                    params.add(new Param(decode(name), "query"));
                }
            }
        }
        if (body != null && !body.isBlank() && (body.contains("=") || body.startsWith("{"))) {
            // 表单体：a=1&b=2
            if (!body.trim().startsWith("{")) {
                for (String part : body.split("&")) {
                    int eq = part.indexOf('=');
                    String name = eq >= 0 ? part.substring(0, eq) : part;
                    if (!name.isEmpty()) {
                        params.add(new Param(decode(name), "body"));
                    }
                }
            }
        }
        return new ParsedRequest(origin, scheme, host.equals("unknown") ? "" : host, port,
                m, normalizePath(path), dedupeParams(params));
    }

    private static List<Param> dedupeParams(List<Param> params) {
        List<Param> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Param p : params) {
            String key = p.location() + "|" + p.name();
            if (seen.add(key)) {
                out.add(p);
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 路径规范化：去尾部斜杠（根路径除外）。 */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** INTERFACE canonical：origin|METHOD|path。 */
    public static String interfaceKey(String origin, String method, String path) {
        return origin + "|" + (method == null ? "GET" : method.toUpperCase().trim()) + "|" + normalizePath(path);
    }

    /** MODULE canonical（root 内）：root|module:<名>。 */
    public static String moduleKey(String rootId, String moduleName) {
        return rootId + "|module:" + normalizeName(moduleName);
    }

    public static String entityKey(String rootId, String entityName) {
        return rootId + "|entity:" + normalizeName(entityName);
    }

    public static String functionKey(String rootId, String functionName) {
        return rootId + "|function:" + normalizeName(functionName);
    }

    public static String techKey(String rootId, String techName) {
        return rootId + "|tech:" + toStringKey(techName);
    }

    /** 从 path 猜测模块名（首个有意义的路径段）。 */
    public static String guessModule(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        String first = slash >= 0 ? p.substring(0, slash) : p;
        if (first.isEmpty() || first.equalsIgnoreCase("api") || first.equalsIgnoreCase("v1") || first.equalsIgnoreCase("v2")) {
            int second = p.indexOf('/', slash >= 0 ? slash + 1 : 0);
            if (second >= 0) {
                first = p.substring(slash + 1, second);
            }
        }
        return normalizeName(first);
    }

    private static String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        String n = s.trim().toLowerCase(java.util.Locale.ROOT);
        return n.replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    }

    /** 对外暴露的名称归一化（用于查询匹配）。 */
    public static String normalizeForMatch(String s) {
        return normalizeName(s);
    }

    private static String toStringKey(String s) {
        if (s == null) {
            return "";
        }
        String n = s.trim().toLowerCase(java.util.Locale.ROOT);
        return n.replaceAll("[^a-z0-9.+-]+", "-").replaceAll("^-+|-+$", "");
    }

    /** 从一组观察中枚举出现的参数名（供 INTERFACE 列表展示）。 */
    public static List<String> paramNames(List<Param> params) {
        List<String> out = new ArrayList<>();
        for (Param p : params) {
            out.add(p.name() + "(" + p.location() + ")");
        }
        return out;
    }

    /** 安全的分页截断帮助函数。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}