package com.taxonomy.preferences;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferencesSchemaTest {

    private final PreferencesSchema schema = new PreferencesSchema();

    @Test
    void acceptsTheDocumentedAdministrationValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("llm.rpm", 5);
        values.put("llm.timeout.seconds", 30);
        values.put("rate-limit.per-minute", 10);
        values.put("analysis.min-relevance-score", 70);
        values.put("dsl.default-branch", "draft");
        values.put("dsl.project-name", "Taxonomy Architecture");
        values.put("dsl.auto-save.interval-seconds", 0);
        values.put("dsl.remote.url", "https://example.invalid/architecture.git");
        values.put("dsl.remote.token", "");
        values.put("dsl.remote.push-on-commit", false);
        values.put("limits.max-business-text", 5_000);
        values.put("limits.max-architecture-nodes", 50);
        values.put("limits.max-export-nodes", 200);
        values.put("diagram.policy", "defaultImpact");

        assertThatCode(() -> schema.validateSnapshot(values)).doesNotThrowAnyException();
        assertThatCode(() -> schema.validateChanges(Map.of(
                "rate-limit.per-minute", 0,
                "diagram.policy", "trace")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownKeysAndEmptyMutations() {
        assertThatThrownBy(() -> schema.validateChanges(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> schema.validateChanges(Map.of("llm.rpn", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown preference key")
                .hasMessageContaining("llm.rpn");
    }

    @Test
    void rejectsWrongTypesAndOutOfRangeNumbers() {
        assertThatThrownBy(() -> schema.validateChanges(Map.of("llm.rpm", "5")))
                .hasMessageContaining("llm.rpm must be an integer");
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "rate-limit.per-minute", -1)))
                .hasMessageContaining("between 0 and 1000");
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "limits.max-architecture-nodes", 1.5)))
                .hasMessageContaining("between 1 and 1000");
    }

    @Test
    void rejectsInvalidBranchPolicyAndMaskedSecretValues() {
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "dsl.default-branch", "invalid..branch")))
                .hasMessageContaining("valid Git branch name");
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "diagram.policy", "unknown")))
                .hasMessageContaining("not supported");
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "dsl.remote.token", "****1234")))
                .hasMessageContaining("masked display value")
                .hasMessageNotContaining("1234");
    }

    @Test
    void rejectsControlCharactersWithoutEchoingTheValue() {
        assertThatThrownBy(() -> schema.validateChanges(Map.of(
                "dsl.remote.url", "https://example.invalid/repo\nsecret")))
                .hasMessageContaining("control characters")
                .hasMessageNotContaining("secret");
    }
}
