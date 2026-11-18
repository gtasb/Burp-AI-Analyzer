package com.ai.analyzer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各 provider 的「模型列表」接口适配。
 *
 * <p>各家 base URL 的形态不统一（有的带 /v1、有的带 /api/v1、用户可以粘贴完整 endpoint），
 * 这里统一归一化，避免出现 {@code /v1/v1/models} 这种必然 404 的拼接。
 * Anthropic 的 /v1/models 使用 x-api-key + anthropic-version，而不是 Bearer。
 */
public final class ModelListFetcher {

    private ModelListFetcher() {
    }

    /** 根据 provider 与用户填写的 base URL 计算模型列表 endpoint。 */
    public static String endpoint(String provider, String apiUrl) {
        String base = trimTrailingSlashes(apiUrl == null ? "" : apiUrl.trim());
        boolean anthropic = "Anthropic兼容".equals(provider);
        boolean dashscope = "DashScope".equals(provider);

        if (base.isEmpty()) {
            base = anthropic ? "https://api.anthropic.com"
                    : dashscope ? "https://dashscope.aliyuncs.com/api/v1"
                    : "https://api.openai.com/v1";
        }
        if (base.endsWith("/models")) {
            return base;
        }
        if (base.endsWith("/api/v1") || base.endsWith("/v1")) {
            return base + "/models";
        }
        if (dashscope) {
            return base + "/api/v1/models";
        }
        return base + "/v1/models";
    }

    /** 请求头：Anthropic 官方域名只发 x-api-key；兼容网关额外保留 Authorization。
     *  OpenCode Go（opencode.ai）在 Cloudflare 后面，无 UA 会被拒——显式补。 */
    static Map<String, String> headers(String provider, String apiUrl, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if ("Anthropic兼容".equals(provider)) {
            headers.put("anthropic-version", "2023-06-01");
            headers.put("x-api-key", apiKey);
            if (!isNativeAnthropic(apiUrl)) {
                headers.put("Authorization", "Bearer " + apiKey);
            }
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        if ("OpenCode Go".equals(provider) || isOpenCodeUrl(apiUrl)) {
            headers.put("User-Agent", "burp-ai-analyzer/1.4");
        }
        return headers;
    }

    /** URL host 是否指向 OpenCode 网关（opencode.ai）。 */
    static boolean isOpenCodeUrl(String apiUrl) {
        try {
            if (apiUrl == null || apiUrl.isBlank()) return false;
            String host = URI.create(trimTrailingSlashes(apiUrl.trim())).getHost();
            return host != null && (host.equalsIgnoreCase("opencode.ai") || host.toLowerCase().endsWith(".opencode.ai"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNativeAnthropic(String apiUrl) {
        try {
            if (apiUrl == null || apiUrl.isBlank()) return true;
            String host = URI.create(trimTrailingSlashes(apiUrl.trim())).getHost();
            return host != null && (host.equals("api.anthropic.com") || host.endsWith(".anthropic.com"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 拉取模型列表。返回按名称排序的模型 id；网络/HTTP 错误抛出异常（由调用方记录日志）。
     */
    public static List<String> fetch(String provider, String apiUrl, String apiKey)
            throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        String endpoint = endpoint(provider, apiUrl);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .GET();
        headers(provider, apiUrl, apiKey).forEach(rb::header);

        HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("模型列表接口 HTTP " + resp.statusCode() + " @ " + endpoint
                    + "；响应: " + truncate(resp.body(), 500));
        }
        return parseModels(resp.body());
    }

    /** 兼容 {data:[{id}]}（OpenAI/Anthropic）与 {models:[{name|id}]}（部分网关）。 */
    static List<String> parseModels(String body) {
        List<String> models = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return models;
        }
        JsonElement root = JsonParser.parseString(body);
        // 部分网关直接返回顶层数组：[{id:...}] 或 ["model-a","model-b"]
        if (root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    addIfPresent(models, el.getAsString());
                } else if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    addIfPresent(models, firstString(o, "id", "name"));
                }
            }
            models.sort(String::compareTo);
            return models;
        }
        if (!root.isJsonObject()) {
            return models;
        }
        JsonObject obj = root.getAsJsonObject();
        JsonArray arr = null;
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            arr = obj.getAsJsonArray("data");
        } else if (obj.has("models") && obj.get("models").isJsonArray()) {
            arr = obj.getAsJsonArray("models");
        }
        if (arr == null) {
            return models;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            addIfPresent(models, firstString(o, "id", "name"));
        }
        models.sort(String::compareTo);
        return models;
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && !o.get(k).isJsonNull()) {
                return o.get(k).getAsString();
            }
        }
        return null;
    }

    private static void addIfPresent(List<String> models, String id) {
        if (id != null && !id.isBlank() && !models.contains(id)) {
            models.add(id);
        }
    }

    private static String trimTrailingSlashes(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
