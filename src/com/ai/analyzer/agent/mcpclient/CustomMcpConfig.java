package com.ai.analyzer.agent.mcpclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单个自定义 MCP 服务器配置（简版数组格式）。
 *
 * JSON 示例：
 * <pre>
 * [
 *   {
 *     "name": "fetch",
 *     "enabled": true,
 *     "type": "streamableHttp",
 *     "url": "http://127.0.0.1:3001/mcp",
 *     "toolWhitelist": ["fetch_url"]
 *   },
 *   {
 *     "name": "local-everything",
 *     "enabled": false,
 *     "type": "stdio",
 *     "command": ["uvx", "mcp-server-everything"]
 *   }
 * ]
 * </pre>
 */
public class CustomMcpConfig {

    public enum TransportType {
        SSE("sse"),
        STREAMABLE_HTTP("streamableHttp"),
        STDIO("stdio"),
        WEBSOCKET("websocket");

        private final String value;

        TransportType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static TransportType from(String value) {
            if (value == null) return STREAMABLE_HTTP;
            String normalized = value.trim().toLowerCase();
            for (TransportType t : values()) {
                if (t.value.equals(normalized) || t.name().toLowerCase().equals(normalized)) {
                    return t;
                }
            }
            return STREAMABLE_HTTP;
        }
    }

    private String name = "";
    private boolean enabled = true;
    private TransportType type = TransportType.STREAMABLE_HTTP;
    private String url = "";
    private String authorization = "";
    private List<String> command = new ArrayList<>();
    private Map<String, String> env = Collections.emptyMap();
    private List<String> toolWhitelist = new ArrayList<>();
    private String rawType = TransportType.STREAMABLE_HTTP.getValue();
    private boolean typeRecognized = true;

    public CustomMcpConfig() {
    }

    public String getName() {
        return name != null ? name : "";
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TransportType getType() {
        return type != null ? type : TransportType.STREAMABLE_HTTP;
    }

    public void setType(TransportType type) {
        this.type = type != null ? type : TransportType.STREAMABLE_HTTP;
    }

    public String getRawType() {
        return rawType != null ? rawType : "";
    }

    public void setRawType(String rawType) {
        this.rawType = rawType;
    }

    public boolean isTypeRecognized() {
        return typeRecognized;
    }

    public void setTypeRecognized(boolean typeRecognized) {
        this.typeRecognized = typeRecognized;
    }

    public String getUrl() {
        return url != null ? url : "";
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAuthorization() {
        return authorization != null ? authorization : "";
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }

    public List<String> getCommand() {
        return command != null ? command : new ArrayList<>();
    }

    public void setCommand(List<String> command) {
        if (command == null) {
            this.command = new ArrayList<>();
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String item : command) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        this.command = normalized;
    }

    public Map<String, String> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    public void setEnv(Map<String, String> env) {
        this.env = env != null ? env : Collections.emptyMap();
    }

    public List<String> getToolWhitelist() {
        return toolWhitelist != null ? toolWhitelist : new ArrayList<>();
    }

    public void setToolWhitelist(List<String> toolWhitelist) {
        if (toolWhitelist == null) {
            this.toolWhitelist = new ArrayList<>();
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String item : toolWhitelist) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        this.toolWhitelist = normalized;
    }

    public boolean isValid() {
        if (!enabled || getName().trim().isEmpty()) return false;
        if (!typeRecognized) return false;
        return switch (getType()) {
            case SSE, STREAMABLE_HTTP, WEBSOCKET -> !getUrl().trim().isEmpty();
            case STDIO -> !getCommand().isEmpty();
        };
    }
}
