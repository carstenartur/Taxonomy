package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ArchitectureCommandValidationTest {
    private static final RelationKey RELATION = new RelationKey("arch-system", "CONTAINS", "arch-child");

    @Test void focusRelationPropertiesAreAnImmutableSnapshotAndTheLegacyConstructorStaysEmpty() {
        var properties = new HashMap<>(Map.of("provenance", "architecture-focus", "x-focus-rationale", "Reviewed"));
        var command = new CreateArchitectureRelation(RELATION, "accepted", properties);
        properties.put("x-focus-rationale", "Changed after construction");
        assertThat(command.properties()).containsEntry("x-focus-rationale", "Reviewed");
        assertThatThrownBy(() -> command.properties().put("x-focus-method", "LOCAL_CANDIDATES"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new CreateArchitectureRelation(RELATION, null).properties()).isEmpty();
    }

    @Test void focusRelationPropertiesRejectNullMapsKeysAndValuesWithStructuredProblems() {
        assertRelationProblem(null, "INVALID_VALUE", "properties");
        var properties = new HashMap<String, String>();
        properties.put(null, "value");
        assertRelationProblem(properties, "INVALID_VALUE", "properties");
        properties.clear();
        properties.put("x-focus-rationale", null);
        assertRelationProblem(properties, "INVALID_VALUE", "x-focus-rationale");
    }

    @Test void focusRelationPropertiesRejectUnsupportedKeysAndControlCharactersAndEnforceTheTextBound() {
        for (String key : java.util.List.of("status", "confidence", "x-focus-invented", "sourceId")) {
            assertRelationProblem(Map.of(key, "accepted"), "READ_ONLY_PROPERTY", key);
        }
        for (String value : java.util.List.of("nul\u0000", "line\nfeed", "carriage\rreturn", "tab\tvalue", "delete\u007f", "next\u0085line", "bad\ufffe")) {
            assertRelationProblem(Map.of("x-focus-rationale", value), "INVALID_VALUE", "x-focus-rationale");
        }
        assertRelationProblem(Map.of("x-focus-rationale", "r".repeat(8001)), "VALUE_TOO_LONG", "x-focus-rationale");
        assertThat(new CreateArchitectureRelation(RELATION, "accepted", Map.of("x-focus-rationale", "r".repeat(8000)))
                .properties().get("x-focus-rationale")).hasSize(8000);
    }

    private static void assertRelationProblem(Map<String, String> properties, String code, String field) {
        assertThatThrownBy(() -> new CreateArchitectureRelation(RELATION, "accepted", properties))
                .isInstanceOfSatisfying(CommandProblem.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.field()).isEqualTo(field);
                });
    }

    @Test void nullPropertyValuesProduceStructuredDomainErrorsForBothTypedElementCommands() {
        var properties = new HashMap<String, String>();
        properties.put("title", null);
        assertThatThrownBy(() -> new CreateArchitectureElement("arch-null", "System", properties))
                .isInstanceOfSatisfying(CommandProblem.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_VALUE");
                    assertThat(error.field()).isEqualTo("title");
                });
        assertThatThrownBy(() -> new UpdateArchitectureElement("arch-null", null, properties))
                .isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo("INVALID_VALUE"));
        assertThatThrownBy(() -> new UpdateArchitectureElement("arch-null", null, null))
                .isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.field()).isEqualTo("properties"));
    }
}
