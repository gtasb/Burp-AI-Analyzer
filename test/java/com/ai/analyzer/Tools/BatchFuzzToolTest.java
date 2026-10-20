package com.ai.analyzer.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BatchFuzzTool - 参数替换")
class BatchFuzzToolTest {

    @Test
    @DisplayName("替换 JSON 字符串参数")
    void substitutes_json_string_value() {
        String req = "POST /x HTTP/1.1\r\n\r\n{\"id\":\"1\",\"name\":\"a\"}";
        assertThat(BatchFuzzTool.substituteParam(req, "id", "1' OR '1'='1"))
                .contains("\"id\":\"1' OR '1'='1\"");
    }

    @Test
    @DisplayName("替换 JSON 数字参数且保持数字类型")
    void substitutes_json_number_value() {
        String req = "POST /x HTTP/1.1\r\n\r\n{\"id\":123,\"name\":\"a\"}";
        String out = BatchFuzzTool.substituteParam(req, "id", "456");
        assertThat(out).contains("\"id\":456").doesNotContain("\"id\":\"456\"");
        // 非数字 payload 以字符串发送
        assertThat(BatchFuzzTool.substituteParam(req, "id", "1 OR 1=1"))
                .contains("\"id\":\"1 OR 1=1\"");
    }

    @Test
    @DisplayName("替换 form 参数")
    void substitutes_form_value() {
        String req = "POST /x HTTP/1.1\r\n\r\nid=1&name=a";
        assertThat(BatchFuzzTool.substituteParam(req, "id", "2"))
                .contains("id=2&name=a");
    }

    @Test
    @DisplayName("参数不存在时原样返回（调用方据此拒绝发送）")
    void returns_original_when_param_missing() {
        String req = "POST /x HTTP/1.1\r\n\r\n{\"name\":\"a\"}";
        assertThat(BatchFuzzTool.substituteParam(req, "id", "1")).isEqualTo(req);
    }
}
