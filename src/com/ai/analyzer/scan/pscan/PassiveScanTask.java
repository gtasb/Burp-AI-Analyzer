package com.ai.analyzer.scan.pscan;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 被动扫描入口过滤 / 成本控制（静态配置 API）。
 *
 * <p>历史版本中本类还包含一个 {@code Callable<ScanResult>} 的实例执行路径，已移除：
 * 实际扫描执行已改由 {@link PassiveScanManager#scanRequest} 直接调用
 * {@link PassiveScanApiClient#analyzeRequest} 完成，本类不再承担任务执行职责。
 *
 * <p>当前职责：
 * <ul>
 *   <li>L0 过滤 {@link #shouldSkipRequest}：静态资源 / 二进制响应 / 域名黑名单；</li>
 *   <li>可配置的跳过扩展名与域名黑名单（供 {@code SettingsPanel} 读写）。</li>
 * </ul>
 */
public final class PassiveScanTask {

    private PassiveScanTask() {}

    // ========== 可配置的过滤规则（静态字段，供全局共享） ==========

    private static final String[] DEFAULT_STATIC_EXTENSIONS = {
        ".js", ".css", ".map",
        ".png", ".jpg", ".jpeg", ".gif", ".ico", ".bmp", ".webp",
        ".svg", ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".mp3", ".mp4", ".webm", ".wav", ".avi", ".flv",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".rar", ".7z", ".tar", ".gz",
        ".xml", ".rss", ".atom", ".sitemap"
    };

    private static final String[] DEFAULT_STATIC_PATHS = {
        "/images/", "/img/", "/css/", "/js/", "/fonts/", "/static/", "/assets/"
    };

    /** 默认域名黑名单（统计/广告/遥测等对安全测试无价值的域名，可自行增删） */
    private static final String DEFAULT_DOMAIN_BLACKLIST_TEXT = String.join("\n", new String[] {
        "# 统计/分析",
        "*.google-analytics.com",
        "*.googletagmanager.com",
        "*.googleadservices.com",
        "*.doubleclick.net",
        "*.analytics.qq.com",
        "*.hm.baidu.com",
        "*.cnzz.com",
        "*.umeng.com",
        "# 广告",
        "*.adservice.google.com",
        "# 公共静态/遥测",
        "*.gstatic.com",
        "*.cloudflareinsights.com",
        "fonts.googleapis.com",
        "recaptcha.net"
    });

    private static volatile Set<String> customExtensions = null;
    private static volatile String[] customStaticPaths = null;
    private static final CopyOnWriteArrayList<Pattern> domainBlacklist = new CopyOnWriteArrayList<>();

    /**
     * 获取默认的域名黑名单文本（供 UI 显示）
     */
    public static String getDefaultDomainBlacklistText() {
        return DEFAULT_DOMAIN_BLACKLIST_TEXT;
    }

    /**
     * 获取默认的跳过扩展名列表（供 UI 显示）
     */
    public static String getDefaultSkipExtensionsText() {
        return String.join(", ", DEFAULT_STATIC_EXTENSIONS);
    }

    /**
     * 设置自定义的跳过扩展名（逗号或换行分隔，如 ".js, .css, .png"）。
     * 传入 null 或空字符串则恢复默认。
     */
    public static void setCustomSkipExtensions(String text) {
        if (text == null || text.isBlank()) {
            customExtensions = null;
            customStaticPaths = null;
            return;
        }
        Set<String> exts = Arrays.stream(text.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.toLowerCase() : ("." + s.toLowerCase()))
                .collect(Collectors.toSet());

        List<String> paths = exts.stream()
                .filter(s -> s.startsWith("/") && s.endsWith("/"))
                .collect(Collectors.toList());
        exts.removeAll(paths);

        customExtensions = exts.isEmpty() ? null : exts;
        customStaticPaths = paths.isEmpty() ? null : paths.toArray(new String[0]);
    }

    /**
     * 设置域名黑名单（每行一个模式，支持通配符如 *.google.com）。
     * 传入 null 或空字符串则清空。
     */
    public static void setDomainBlacklist(String text) {
        domainBlacklist.clear();
        if (text == null || text.isBlank()) return;

        for (String line : text.split("[\\r\\n]+")) {
            String pattern = line.trim();
            if (pattern.isEmpty() || pattern.startsWith("#")) continue;
            try {
                String regex = globToRegex(pattern);
                domainBlacklist.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 检查域名是否在黑名单中
     */
    public static boolean isDomainBlacklisted(String host) {
        if (host == null || host.isEmpty() || domainBlacklist.isEmpty()) return false;
        for (Pattern p : domainBlacklist) {
            if (p.matcher(host).matches()) return true;
        }
        return false;
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': sb.append("\\."); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }

    /**
     * 静态方法：检查请求是否应该跳过（供外部调用）
     */
    public static boolean shouldSkipRequest(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return true;
        }

        String url = requestResponse.request().url();
        if (url == null) return true;

        // 域名黑名单检查
        String host = RequestFingerprint.extractHost(url);
        if (isDomainBlacklisted(host)) return true;

        String lowerUrl = url.toLowerCase();
        if (isStaticResourceStatic(lowerUrl)) return true;

        // 仅对 GET 请求跳过二进制响应（POST/PUT 等可能涉及文件上传漏洞，必须保留）
        String method = requestResponse.request().method().toUpperCase();
        if ("GET".equals(method) && requestResponse.response() != null) {
            try {
                String respStr = requestResponse.response().toString();
                if (respStr != null) {
                    String headerPart = respStr;
                    int sep = respStr.indexOf("\r\n\r\n");
                    if (sep < 0) sep = respStr.indexOf("\n\n");
                    if (sep > 0) headerPart = respStr.substring(0, sep);
                    String lowerHeaders = headerPart.toLowerCase();

                    if (lowerHeaders.contains("content-type:")) {
                        if (lowerHeaders.contains("image/") ||
                            lowerHeaders.contains("font/") ||
                            lowerHeaders.contains("audio/") ||
                            lowerHeaders.contains("video/") ||
                            lowerHeaders.contains("application/octet-stream") ||
                            lowerHeaders.contains("application/zip") ||
                            lowerHeaders.contains("application/gzip") ||
                            lowerHeaders.contains("application/pdf") ||
                            lowerHeaders.contains("application/wasm")) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    private static boolean isStaticResourceStatic(String url) {
        String path = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) path = url.substring(0, queryIndex);
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex > 0) path = path.substring(0, fragmentIndex);

        Set<String> exts = customExtensions;
        if (exts != null) {
            for (String ext : exts) {
                if (path.endsWith(ext)) return true;
            }
        } else {
            for (String ext : DEFAULT_STATIC_EXTENSIONS) {
                if (path.endsWith(ext)) return true;
            }
        }

        String[] paths = customStaticPaths;
        if (paths != null) {
            for (String sp : paths) {
                if (url.contains(sp)) return true;
            }
        } else {
            for (String sp : DEFAULT_STATIC_PATHS) {
                if (url.contains(sp)) return true;
            }
        }

        return false;
    }
}