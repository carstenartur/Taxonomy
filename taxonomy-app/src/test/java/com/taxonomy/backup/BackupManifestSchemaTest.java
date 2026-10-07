package com.taxonomy.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BackupManifestSchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BackupManifestCodec codec = new BackupManifestCodec();

    @Test void schemaAndCodecAgreeOnTextAndCollectionBoundaries() throws Exception {
        try (var schemaInput = getClass().getResourceAsStream("/backup/manifest-v1.schema.json");
             var fixtureInput = getClass().getResourceAsStream("/backup/current-state-v1.json")) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaInput);
            var base = (ObjectNode) json.readTree(fixtureInput);
            // The test fixtures use Jackson 2; pass JSON across the validator's Jackson 3 boundary.
            assertThat(schema.validate(json.writeValueAsString(base), InputFormat.JSON)).isEmpty();
            assertThat(json.readTree(codec.write(codec.read(json.writeValueAsBytes(base))))).isEqualTo(base);
            var repeatedFeatures = base.deepCopy();
            var features = repeatedFeatures.putArray("requiredFeatures");
            for (int i = 0; i < 10001; i++) features.add("component-sha256");
            assertThat(schema.validate(json.writeValueAsString(repeatedFeatures), InputFormat.JSON)).isNotEmpty();
            assertThatThrownBy(() -> codec.read(json.writeValueAsBytes(repeatedFeatures))).isInstanceOf(IllegalArgumentException.class);
            for (String field : List.of("dependencies", "omissions")) {
                for (var value : List.of("", "x".repeat(513), "x".repeat(8193))) {
                    var invalid = base.deepCopy(); invalid.putArray(field).add(value);
                    assertThat(schema.validate(json.writeValueAsString(invalid), InputFormat.JSON)).isNotEmpty();
                    assertThatThrownBy(() -> codec.read(json.writeValueAsBytes(invalid))).isInstanceOf(IllegalArgumentException.class);
                }
                var boundary = base.deepCopy(); boundary.putArray(field).add("x".repeat(512));
                assertThat(schema.validate(json.writeValueAsString(boundary), InputFormat.JSON)).isEmpty();
                var manifest = codec.read(json.writeValueAsBytes(boundary));
                assertThat(codec.read(codec.write(manifest))).isEqualTo(manifest);
                var tooMany = base.deepCopy(); var values = tooMany.putArray(field);
                for (int i = 0; i < 10001; i++) values.add("x");
                assertThat(schema.validate(json.writeValueAsString(tooMany), InputFormat.JSON)).isNotEmpty();
                assertThatThrownBy(() -> codec.read(json.writeValueAsBytes(tooMany))).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
