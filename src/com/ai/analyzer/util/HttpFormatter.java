package com.ai.analyzer.util;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HTTP 请求/响应格式化工具类
 * 统一处理 HTTP 内容的格式化，避免代码重复
 */
public class HttpFormatter {
    
    public static final int DEFAULT_MAX_LENGTH = 15000;
    /** 进入 user prompt 的 HTTP 预览上限（完整内容落盘缓存） */
    public static final int PROMPT_PREVIEW_MAX_LENGTH = 4000;

    /** 超长 HTTP 内容落盘目录（工作区 .cache/ 下），agent 用原生 read_file 读回 */
    private static volatile Path cacheDir;

    /**
     * HTTP内容压缩结果
     */
    public static class CompressResult {
        public final String content;
        public final boolean wasCompressed;
        public final int originalLength;
        public final int compressedLength;
        
        public CompressResult(String content, boolean wasCompressed, int originalLength, int compressedLength) {
            this.content = content;
            this.wasCompressed = wasCompressed;
            this.originalLength = originalLength;
            this.compressedLength = compressedLength;
        }
    }

    /**
     * 组装 user prompt 前的 HTTP 内容处理结果
     */
    public static class PromptPrepareResult {
        public final String promptText;
        public final boolean cached;
        public final int originalLength;

        public PromptPrepareResult(String promptText, boolean cached, int originalLength) {
            this.promptText = promptText != null ? promptText : "";
            this.cached = cached;
            this.originalLength = originalLength;
        }
    }

    /**
     * 设置超长 HTTP 内容落盘目录（工作区目录，实际写入其 .cache/ 子目录）。
     * 未设置时超长内容退化为截断。
     */
    public static void setWorkplaceDirectory(String workplaceDirectory) {
        if (workplaceDirectory == null || workplaceDirectory.trim().isEmpty()) {
            cacheDir = null;
            return;
        }
        cacheDir = Path.of(workplaceDirectory.trim()).toAbsolutePath().normalize().resolve(".cache").normalize();
    }

    /**
     * 组装 user prompt 前的 HTTP 内容处理：未超长则原样进入提示词；
     * 超长则将完整报文写入工作区 .cache/，提示词中仅保留预览与 read_file 路径提示。
     */
    public static PromptPrepareResult prepareForPrompt(String httpContent) {
        return prepareForPrompt(httpContent, "http-content");
    }

    public static PromptPrepareResult prepareForPrompt(String httpContent, String cacheLabel) {
        if (httpContent == null || httpContent.isEmpty()) {
            return new PromptPrepareResult("", false, 0);
        }
        int originalLength = httpContent.length();
        if (originalLength <= DEFAULT_MAX_LENGTH) {
            return new PromptPrepareResult(httpContent, false, originalLength);
        }
        if (cacheDir != null) {
            try {
                Files.createDirectories(cacheDir);
                String safeLabel = cacheLabel == null ? "" : cacheLabel.trim().replaceAll("[^a-zA-Z0-9._-]+", "-");
                if (safeLabel.length() > 48) safeLabel = safeLabel.substring(0, 48);
                Path file = cacheDir.resolve(System.currentTimeMillis() + (safeLabel.isEmpty() ? "" : "-" + safeLabel) + ".txt");
                Files.writeString(file, httpContent, StandardCharsets.UTF_8);

                CompressResult preview = compressIfTooLong(httpContent, PROMPT_PREVIEW_MAX_LENGTH);
                String inline = preview.content
                        + "\n\n...[HTTP 报文较长（约 " + originalLength + " 字符），完整内容已写入工作区，未直接进入上下文]...\n\n"
                        + "完整内容: read_file path=\"" + file.toAbsolutePath() + "\"";
                return new PromptPrepareResult(inline, true, originalLength);
            } catch (IOException e) {
                /* fall through to truncation */
            }
        }
        CompressResult compressed = compressIfTooLong(httpContent);
        return new PromptPrepareResult(compressed.content, false, originalLength);
    }
    
    /**
     * 如果HTTP内容过长则压缩，保留请求头完整，截断请求体和响应体
     */
    public static CompressResult compressIfTooLong(String httpContent) {
        return compressIfTooLong(httpContent, DEFAULT_MAX_LENGTH);
    }
    
    public static CompressResult compressIfTooLong(String httpContent, int maxTotalLength) {
        if (httpContent == null) {
            return new CompressResult("", false, 0, 0);
        }
        if (httpContent.length() <= maxTotalLength) {
            return new CompressResult(httpContent, false, httpContent.length(), httpContent.length());
        }
        
        int originalLength = httpContent.length();
        
        String requestSection = httpContent;
        String responseSection = null;
        
        int respIdx = httpContent.indexOf("=== HTTP响应 ===");
        if (respIdx > 0) {
            requestSection = httpContent.substring(0, respIdx).trim();
            responseSection = httpContent.substring(respIdx);
        }
        
        int halfLimit = maxTotalLength / 2;
        StringBuilder result = new StringBuilder();
        
        result.append(truncateSection(requestSection, responseSection != null ? halfLimit : maxTotalLength, "请求"));
        
        if (responseSection != null) {
            result.append("\n\n");
            result.append(truncateSection(responseSection, halfLimit, "响应"));
        }
        
        String compressed = result.toString();
        return new CompressResult(compressed, true, originalLength, compressed.length());
    }
    
    private static String truncateSection(String section, int maxLength, String sectionName) {
        if (section.length() <= maxLength) {
            return section;
        }
        
        int headerEnd = findHeaderBodySeparator(section);
        
        if (headerEnd > 0 && headerEnd < maxLength) {
            String headers = section.substring(0, headerEnd);
            String body = section.substring(headerEnd);
            int bodyLimit = maxLength - headers.length();
            
            if (body.length() > bodyLimit && bodyLimit > 0) {
                return headers + body.substring(0, bodyLimit)
                    + "\n\n...[" + sectionName + "体已截断，原始 " + body.length() + " 字符，保留前 " + bodyLimit + " 字符]";
            }
            return section;
        }
        
        return section.substring(0, maxLength)
            + "\n\n...[" + sectionName + "内容已截断，原始 " + section.length() + " 字符，保留前 " + maxLength + " 字符]";
    }
    
    private static int findHeaderBodySeparator(String content) {
        int firstLine = content.indexOf('\n');
        if (firstLine < 0) return -1;
        
        int dblNewline = content.indexOf("\r\n\r\n", firstLine);
        if (dblNewline > 0) return dblNewline + 4;
        
        dblNewline = content.indexOf("\n\n", firstLine);
        if (dblNewline > 0) return dblNewline + 2;
        
        return -1;
    }

    /**
     * 格式化 HttpRequestResponse 为字符串
     * 格式：=== HTTP请求 ===\n[请求内容]\n\n=== HTTP响应 ===\n[响应内容]
     * 
     * @param requestResponse Burp 的 HttpRequestResponse 对象
     * @return 格式化后的字符串，如果没有请求则返回空字符串
     */
    public static String formatHttpRequestResponse(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== HTTP请求 ===\n");
        
        byte[] requestBytes = requestResponse.request().toByteArray().getBytes();
        String requestStr = new String(requestBytes, StandardCharsets.UTF_8);
        sb.append(sanitizeForApi(requestStr));
        
        if (requestResponse.response() != null) {
            sb.append("\n\n=== HTTP响应 ===\n");
            byte[] responseBytes = requestResponse.response().toByteArray().getBytes();
            String responseStr = new String(responseBytes, StandardCharsets.UTF_8);
            
            if (isBinaryContent(responseStr)) {
                int headerEnd = findHeaderBodySeparator(responseStr);
                if (headerEnd > 0) {
                    sb.append(PromptInjectionGuard.guard(sanitizeForApi(responseStr.substring(0, headerEnd))));
                    sb.append("[二进制响应体已省略]");
                } else {
                    sb.append("[二进制响应，已省略]");
                }
            } else {
                sb.append(PromptInjectionGuard.guard(sanitizeForApi(responseStr)));
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 清洗内容，使其可安全传递给 LLM API。
     * - 移除 NULL 字节和大部分控制字符（保留 \t \n \r）
     * - 将 JS 风格的 \xNN 转义（会破坏 Jackson JSON 解析）替换为 [hex:NN]
     * - 替换其他不可打印的 Unicode 字符
     */
    public static String sanitizeForApi(String content) {
        if (content == null || content.isEmpty()) return content;
        
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            
            if (c == '\0') continue;
            
            // 保留常见空白符
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(c);
                continue;
            }
            
            // 移除其他控制字符 (0x01-0x08, 0x0B, 0x0C, 0x0E-0x1F)
            if (c < 0x20) continue;
            
            // 移除 Unicode replacement char 和特殊不可见字符
            if (c == '\uFFFD' || c == '\uFFFE' || c == '\uFFFF') continue;
            
            sb.append(c);
        }
        
        String cleaned = sb.toString();
        // 将 JS hex escapes \xNN 替换为安全文本，避免 Jackson "Unrecognized character escape 'x'"
        cleaned = cleaned.replaceAll("\\\\x([0-9a-fA-F]{2})", "[hex:$1]");
        
        return cleaned;
    }
    
    /**
     * 检测内容是否为二进制数据（不适合发送给 LLM）
     * 当不可打印字符比例超过 15% 时判定为二进制
     */
    public static boolean isBinaryContent(String content) {
        if (content == null || content.isEmpty()) return false;
        
        int sampleLen = Math.min(content.length(), 2000);
        int headerEnd = findHeaderBodySeparator(content);
        int checkStart = headerEnd > 0 ? headerEnd : 0;
        if (checkStart >= sampleLen) return false;
        
        int nonPrintable = 0;
        int checked = 0;
        for (int i = checkStart; i < sampleLen; i++) {
            char c = content.charAt(i);
            checked++;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                nonPrintable++;
            } else if (c == '\uFFFD') {
                nonPrintable++;
            }
        }
        
        if (checked == 0) return false;
        return (double) nonPrintable / checked > 0.15;
    }
    
    /**
     * 格式化字符串格式的请求和响应
     * 格式：=== HTTP请求 ===\n[请求内容]\n\n=== HTTP响应 ===\n[响应内容]
     * 
     * @param request 请求字符串
     * @param response 响应字符串（可为 null）
     * @return 格式化后的字符串
     */
    public static String formatHttpRequestResponse(String request, String response) {
        if (request == null || request.trim().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== HTTP请求 ===\n");
        sb.append(request);
        
        if (response != null && !response.trim().isEmpty()) {
            sb.append("\n\n=== HTTP响应 ===\n");
            sb.append(PromptInjectionGuard.guard(response));
        }
        
        return sb.toString();
    }
}

