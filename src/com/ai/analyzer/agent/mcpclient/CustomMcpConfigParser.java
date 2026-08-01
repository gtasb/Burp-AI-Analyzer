package com.ai.analyzer.agent.mcpclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析用户在 UI 中填写的自定义 MCP 配置 JSON（简版数组格式）。
 */
public final class CustomMcpConfigParser {

    private CustomMcpConfigParser() {
    }

    public static List<CustomMcpConfig> parse(String json) throws JsonParseException {
        List<CustomMcpConfig> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }

        JsonElement root = JsonParser.parseString(json);
        if (root == null || root.isJsonNull()) {
            return result;
        }

        if (!root.isJsonArray()) {
            throw new JsonParseException("自定义 MCP 配置必须是一个 JSON 数组");
        }

        JsonArray array = root.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            CustomMcpConfig config = parseOne(element.getAsJsonObject());
            result.add(config);
        }

        return result;
    }

    private static CustomMcpConfig parseOne(JsonObject obj) {
        CustomMcpConfig config = new CustomMcpConfig();
        if (obj.has("name")) {
            config.setName(getString(obj, "name"));
        }
        if (obj.has("enabled")) {
            config.setEnabled(getBoolean(obj, "enabled", true));
        }
        if (obj.has("type")) {
            String rawType = getString(obj, "type");
            config.setRawType(rawType);
            CustomMcpConfig.TransportType parsedType = CustomMcpConfig.TransportType.from(rawType);
            boolean recognized = rawType == null || rawType.trim().isEmpty()
                    || parsedType.getValue().equalsIgnoreCase(rawType.trim())
                    || parsedType.name().equalsIgnoreCase(rawType.trim());
            config.setTypeRecognized(recognized);
            config.setType(parsedType);
        }
        if (obj.has("url")) {
            config.setUrl(getString(obj, "url"));
        }
        if (obj.has("authorization")) {
            config.setAuthorization(getString(obj, "authorization"));
        }
        if (obj.has("command")) {
            config.setCommand(getStringList(obj, "command"));
        }
        if (obj.has("env")) {
            config.setEnv(getStringMap(obj, "env"));
        }
        if (obj.has("toolWhitelist")) {
            config.setToolWhitelist(getStringList(obj, "toolWhitelist"));
        }
        return config;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) return "";
        return element.getAsString();
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean defaultValue) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) return defaultValue;
        return element.getAsBoolean();
    }

    private static List<String> getStringList(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonArray()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) continue;
            list.add(item.getAsString());
        }
        return list;
    }

    private static Map<String, String> getStringMap(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonObject()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) continue;
            map.put(entry.getKey(), value.getAsString());
        }
        return map;
    }

    public static String defaultConfigJson() {
        return """
                [
                  {
                    "name": "fetch",
                    "enabled": false,
                    "type": "streamableHttp",
                    "url": "http://127.0.0.1:3001/mcp",
                    "authorization": "Bearer your-token-here",
                    "toolWhitelist": ["fetch", "fetch_url"]
                  }
                ]
                """.stripIndent();
    }

    public static String toJson(List<CustomMcpConfig> configs) {
        JsonArray array = new JsonArray();
        if (configs == null) return array.toString();

        for (CustomMcpConfig config : configs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", config.getName());
            obj.addProperty("enabled", config.isEnabled());
            obj.addProperty("type", config.getType().getValue());
            obj.addProperty("url", config.getUrl());
            obj.addProperty("authorization", config.getAuthorization());

            JsonArray commandArray = new JsonArray();
            for (String item : config.getCommand()) {
                commandArray.add(item);
            }
            obj.add("command", commandArray);

            JsonObject envObj = new JsonObject();
            for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
                envObj.addProperty(entry.getKey(), entry.getValue());
            }
            obj.add("env", envObj);

            JsonArray whitelistArray = new JsonArray();
            for (String item : config.getToolWhitelist()) {
                whitelistArray.add(item);
            }
            obj.add("toolWhitelist", whitelistArray);

            array.add(obj);
        }

        return array.toString();
    }
}
