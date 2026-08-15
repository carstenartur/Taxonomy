package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlatJsonTest {

    @Test
    void deterministicWriterRoundTripsNestedUnicodeMetadata() {
        String source = """
                {
                  "name": "Größe & Qualität",
                  "active": true,
                  "score": 1.25,
                  "items": [
                    {
                      "id": 7,
                      "text": "line one\\nline two"
                    },
                    null
                  ]
                }
                """;

        Map<String, Object> parsed = FlatJson.parseObject(source);
        String rendered = FlatJson.pretty(parsed);

        assertThat(rendered)
                .contains("\"name\": \"Größe & Qualität\"")
                .contains("\"text\": \"line one\\nline two\"")
                .doesNotContain("\\u00f6");
        assertThat(FlatJson.parseObject(rendered)).isEqualTo(parsed);
        assertThat(FlatJson.pretty(FlatJson.parseObject(rendered)))
                .isEqualTo(rendered);
    }
}
