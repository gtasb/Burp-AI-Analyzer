package com.ai.analyzer.util;

import com.ai.analyzer.agent.mcpclient.CustomMcpConfig;
import com.ai.analyzer.agent.mcpclient.CustomMcpConfigParser;

import java.awt.Color;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.UIManager;

/**
 * 自定义 MCP 配置校验工具。
 * 统一处理简版数组 JSON 的语法检查、字段缺失检查、URL 格式检查、name 重复检查等，
 * 并生成供 UI 展示的校验结果。
 */
public final class McpConfigValidator {

    private McpConfigValidator() {
    }

    /**
     * 自定义 MCP 配置校验结果。
     */
    public static class McpConfigValidationResult {
        private final boolean success;
        private final boolean active;
        private final String statusText;
        private final String summaryText;
        private final Color statusColor;
        private final List<String> errors;

        public McpConfigValidationResult(boolean success, boolean active, String statusText,
                                         String summaryText, Color statusColor, List<String> errors) {
            this.success = success;
            this.active = active;
            this.statusText = statusText;
            this.summaryText = summaryText;
            this.statusColor = statusColor;
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isActive() {
            return active;
        }

        public String getStatusText() {
            return statusText;
        }

        public String getSummaryText() {
            return summaryText;
        }

        public Color getStatusColor() {
            return statusColor;
        }

        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }
    }

    /**
     * 校验自定义 MCP 配置 JSON 字符串。
     *
     * @param json               用户填写的 JSON 数组字符串
     * @param masterSwitchOn     UI 总开关是否开启
     * @param defaultStatusColor 默认状态标签颜色（可为 null，使用系统默认灰色）
     * @return 校验结果，包含状态文本、颜色、是否可生效等
     */
    public static McpConfigValidationResult validate(String json, boolean masterSwitchOn, Color defaultStatusColor) {
        Color disabledColor = defaultStatusColor != null ? defaultStatusColor
                : UIManager.getColor("Label.disabledForeground");

        // 未启用总开关时一律认为空配置状态
        if (!masterSwitchOn) {
            return new McpConfigValidationResult(true, false, "状态：自定义 MCP 已禁用",
                    "", disabledColor, new ArrayList<>());
        }

        if (json == null || json.trim().isEmpty()) {
            return new McpConfigValidationResult(true, false, "状态：空配置，未启用任何自定义 MCP",
                    "", disabledColor, new ArrayList<>());
        }

        List<CustomMcpConfig> configs;
        try {
            configs = CustomMcpConfigParser.parse(json);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
            return new McpConfigValidationResult(false, false, "状态：JSON 解析失败 - " + message,
                    "请检查 JSON 语法（括号、逗号、引号是否匹配）。", Color.RED, Collections.singletonList(message));
        }

        List<String> errors = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        int enabledCount = 0;
        int validCount = 0;

        for (int i = 0; i < configs.size(); i++) {
            CustomMcpConfig config = configs.get(i);
            String prefix = "第 " + (i + 1) + " 项";
            String name = config.getName().trim();

            if (name.isEmpty()) {
                errors.add(prefix + "：缺少 name（服务器名称必填）");
            } else if (!seenNames.add(name)) {
                errors.add(prefix + "：name '" + name + "' 与其他服务器重复");
            }

            if (!config.isEnabled()) {
                continue;
            }
            enabledCount++;

            if (!config.isTypeRecognized()) {
                errors.add(prefix + " ('" + name + "')：不支持的 type 值：" + config.getRawType().trim()
                        + "（仅支持 sse / streamableHttp / stdio / websocket）");
                continue;
            }

            if (config.isValid()) {
                validCount++;
            } else {
                // 补充更人性化的错误描述
                CustomMcpConfig.TransportType type = config.getType();
                switch (type) {
                    case SSE, STREAMABLE_HTTP, WEBSOCKET -> {
                        if (config.getUrl().trim().isEmpty()) {
                            errors.add(prefix + " ('" + name + "')：type 为 " + type.getValue()
                                    + " 时必须提供 url");
                        } else if (!isValidUrl(config.getUrl().trim())) {
                            errors.add(prefix + " ('" + name + "')：url 格式不合法：" + config.getUrl().trim());
                        }
                    }
                    case STDIO -> {
                        if (config.getCommand().isEmpty()) {
                            errors.add(prefix + " ('" + name + "')：type 为 stdio 时必须提供 command 数组");
                        } else {
                            for (int j = 0; j < config.getCommand().size(); j++) {
                                String arg = config.getCommand().get(j);
                                if (arg == null || arg.trim().isEmpty()) {
                                    errors.add(prefix + " ('" + name + "')：command 第 " + (j + 1) + " 个参数为空");
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            String first = errors.get(0);
            String summary = "发现 " + errors.size() + " 个问题：" + first;
            if (errors.size() > 1) summary += " 等";
            return new McpConfigValidationResult(false, false, "状态：配置校验未通过",
                    summary, Color.RED, errors);
        }

        String statusText = "状态：解析成功，共 " + configs.size() + " 项，已启用 " + enabledCount
                + " 项，可生效 " + validCount + " 项";
        return new McpConfigValidationResult(true, true, statusText,
                "配置合法，保存后将在下次分析时加载。", new Color(34, 139, 34), new ArrayList<>());
    }

    /**
     * 校验 URL 是否合法（要求 http/https/ws/wss 协议，并可解析出服务器地址）。
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = new URI(url);
            uri.parseServerAuthority();
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "ws".equalsIgnoreCase(scheme)
                    || "wss".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 统计当前 JSON 中有效且已启用的自定义 MCP 配置数量。
     * 解析失败时返回 0。
     */
    public static int countEnabledCustomMcpConfigs(String json) {
        try {
            return (int) CustomMcpConfigParser.parse(json != null ? json : "")
                    .stream()
                    .filter(CustomMcpConfig::isValid)
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }
}
