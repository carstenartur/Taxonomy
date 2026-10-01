package com.taxonomy.interop.backup;

import java.io.IOException;
import com.taxonomy.exchange.backup.PortableRows;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;

/** Explicit contract binding, with no raw JSON forwarding or source-bearing exceptions. */
final class IntegrationBackupJson {
    private static final int MAX_CHARS = PortableRows.MAX_RECORD_BYTES / 2;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_CHARS)
                            .maxStringLength(MAX_CHARS).maxNestingDepth(64).maxNameLength(2048)
                            .maxNumberLength(32).maxTokenCount(1_000_000).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private IntegrationBackupJson() { }
    static <T> T read(String value, Class<T> type) throws IOException {
        if (value == null) return null;
        try {
            if (value.length() > MAX_CHARS) throw new IllegalArgumentException();
            return JSON.readValue(value, type);
        }
        catch (RuntimeException failure) { throw new IOException("Invalid or unsupported integration backup evidence"); }
    }
}
