package com.ai.analyzer.agent.mcpclient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CustomMcpConfigParser - 自定义 MCP 配置")
class CustomMcpConfigParserTest {

    @Test
    @DisplayName("单个服务器对象（用户常见写法）也能解析")
    void accepts_single_object() {
        String json = """
                {
                  "name": "preview-mcp",
                  "enabled": true,
                  "type": "streamableHttp",
                  "url": "https://mcp.preview.is/mcp",
                  "header": {"Authorization": "Bearer rk_test"}
                }""";

        List<CustomMcpConfig> configs = CustomMcpConfigParser.parse(json);

        assertThat(configs).hasSize(1);
        CustomMcpConfig config = configs.get(0);
        assertThat(config.getName()).isEqualTo("preview-mcp");
        assertThat(config.getUrl()).isEqualTo("https://mcp.preview.is/mcp");
        assertThat(config.getAuthorization()).isEqualTo("Bearer rk_test");
        assertThat(config.isValid()).isTrue();
    }

    @Test
    @DisplayName("headers 对象：Authorization 抽出，其余作为额外请求头")
    void headers_map_is_split() {
        String json = """
                [{"name":"x","type":"sse","url":"https://a/mcp",
                  "headers":{"Authorization":"Bearer t","X-Api-Key":"k"}}]""";

        CustomMcpConfig config = CustomMcpConfigParser.parse(json).get(0);

        assertThat(config.getAuthorization()).isEqualTo("Bearer t");
        assertThat(config.getHeaders()).containsEntry("X-Api-Key", "k")
                .doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("顶层 authorization 优先于 headers 里的 Authorization")
    void explicit_authorization_wins() {
        String json = """
                [{"name":"x","url":"https://a/mcp",
                  "headers":{"Authorization":"Bearer from-header"},
                  "authorization":"Bearer from-field"}]""";

        assertThat(CustomMcpConfigParser.parse(json).get(0).getAuthorization())
                .isEqualTo("Bearer from-field");
    }

    @Test
    @DisplayName("toJson 往返保留 headers")
    void to_json_round_trip_keeps_headers() {
        CustomMcpConfig config = new CustomMcpConfig();
        config.setName("x");
        config.setUrl("https://a/mcp");
        config.setType(CustomMcpConfig.TransportType.STREAMABLE_HTTP);
        config.setAuthorization("Bearer t");
        config.setHeaders(java.util.Map.of("X-Api-Key", "k"));

        List<CustomMcpConfig> back = CustomMcpConfigParser.parse(
                CustomMcpConfigParser.toJson(List.of(config)));

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getAuthorization()).isEqualTo("Bearer t");
        assertThat(back.get(0).getHeaders()).containsEntry("X-Api-Key", "k");
    }

    @Test
    @DisplayName("非对象/数组的 JSON 仍然报错")
    void rejects_scalar_json() {
        assertThatThrownBy(() -> CustomMcpConfigParser.parse("\"just-a-string\""))
                .isInstanceOf(com.google.gson.JsonParseException.class);
    }
}
