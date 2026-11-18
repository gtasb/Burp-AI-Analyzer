package com.ai.analyzer.core;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 给 OpenCode Go 网关（opencode.ai）补齐校验头的 transport 装饰器。
 *
 * <p>该网关有两个硬性要求（实测 2026-11，https://opencode.ai/docs/go/）：
 * <ol>
 *   <li>{@code x-opencode-session}：每会话稳定的 ID，用于上游路由与 prompt 缓存；
 *       缺失直接 400 MissingSessionID。同一会话内必须保持不变（中途换 ID 会打碎缓存）。</li>
 *   <li>User-Agent：Cloudflare 拒绝无 UA 的客户端。Java HTTP 栈默认带 UA，
 *       但 AgentScope 的默认 transport 不一定带，显式补一个。</li>
 * </ol>
 * 前置条件：仅当请求 URL 的 host 是 opencode.ai（或子域）时注入——其他 provider
 * 不受影响。客户端已自带 x-opencode-session 时透传不覆盖。
 */
final class OpenCodeHeaderTransport implements HttpTransport {

    /** 会话稳定 ID：构造时生成一次，模型实例的生命周期内不变。
     *  每个角色（ACTIVE/SIDEBAR/ORGANIZER/被动）各建一个模型实例，各自独立会话，
     *  与 AgentScope 的 sessionId 语义对齐（同会话内稳定、跨角色隔离）。 */
    private final String sessionId;
    private final HttpTransport delegate;

    private OpenCodeHeaderTransport(HttpTransport delegate, String sessionId) {
        this.delegate = delegate;
        this.sessionId = sessionId;
    }

    /** 检测 URL 是否指向 OpenCode 网关。 */
    static boolean isOpenCodeUpstream(String url) {
        if (url == null || url.isBlank()) return false;
        String host = url;
        int schemeEnd = host.indexOf("://");
        if (schemeEnd >= 0) host = host.substring(schemeEnd + 3);
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("opencode.ai") || host.endsWith(".opencode.ai");
    }

    /** 包装：OpenCode 上游才包，其他直接返回原 transport。 */
    static HttpTransport wrapIfOpenCode(String baseUrl, HttpTransport delegate) {
        if (!isOpenCodeUpstream(baseUrl)) return delegate;
        String session = "burp-ai-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        com.ai.analyzer.util.AppLogBuffer.info("AgentScopeModelFactory",
                "OpenCode Go 网关头已启用: x-opencode-session=" + session);
        return new OpenCodeHeaderTransport(delegate, session);
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(applyHeaders(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(applyHeaders(request));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private HttpRequest applyHeaders(HttpRequest request) {
        Map<String, String> headers = request.getHeaders();
        // 客户端已带 x-opencode-session：透传（保持调用方语义）
        if (headers != null && headers.entrySet().stream()
                .anyMatch(e -> e.getKey() != null
                        && "x-opencode-session".equalsIgnoreCase(e.getKey())
                        && e.getValue() != null && !e.getValue().isBlank())) {
            return request;
        }
        Map<String, String> merged = new HashMap<>();
        if (headers != null) {
            merged.putAll(headers);
        }
        merged.putIfAbsent("x-opencode-session", sessionId);
        merged.putIfAbsent("User-Agent", "burp-ai-analyzer/1.4");
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(merged)
                .body(request.getBody())
                .build();
    }
}
