package com.ai.analyzer.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelContextLimitResolverTest {

    @Test
    void extractsContextLimitFromOpenAiStyleMetadata() {
        String metadata = """
                {
                  "data": [
                    {
                      "id": "gpt-4o-mini",
                      "max_model_len": 128000
                    }
                  ]
                }
                """;

        Integer resolved = ModelContextLimitResolver.extractContextLimitFromMetadata(metadata);

        assertEquals(128000, resolved);
    }

    @Test
    void extractsContextLimitFromNestedMetadataPayload() {
        String metadata = """
                {
                  "models": [
                    {
                      "id": "claude-3-5-sonnet",
                      "max_tokens": 200000
                    }
                  ]
                }
                """;

        Integer resolved = ModelContextLimitResolver.extractContextLimitFromMetadata(metadata);

        assertEquals(200000, resolved);
    }
}
