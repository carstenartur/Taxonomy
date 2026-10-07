package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReformulationProblemAssertionsTest {
    private static final String INSTANCE =
            "/api/projects/9/requirements/12/reformulations/fb6f1014-b4bb-4229-8524-d816163c2392";
    private static final String PROBLEM = """
            {"detail":"Stored reformulation baseline does not match proposal identity",
             "instance":"%s","status":409,"title":"Portfolio state conflict",
             "type":"urn:taxonomy:portfolio:conflict"}
            """.formatted(INSTANCE);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void numericForeignIdentityMayCoincideWithTheRequestedProposalUuid() {
        // The exact UUID and numeric substring from the PostgreSQL CI failure.
        assertTrue(PROBLEM.contains("1014"));
        assertDoesNotThrow(() -> ReformulationProblemAssertions.requireIdentityConflict(mapper, PROBLEM, INSTANCE));
    }

    @Test
    void objectFieldOrderIsIrrelevant() {
        String reordered = """
                {"type":"urn:taxonomy:portfolio:conflict","title":"Portfolio state conflict",
                 "status":409,"instance":"%s",
                 "detail":"Stored reformulation baseline does not match proposal identity"}
                """.formatted(INSTANCE);
        assertDoesNotThrow(() -> ReformulationProblemAssertions.requireIdentityConflict(mapper, reordered, INSTANCE));
    }

    @Test
    void instanceRemainsBoundToTheExactContextRelativeRequest() {
        String instance = "/taxonomy" + INSTANCE;
        String body = PROBLEM.replace(INSTANCE, instance);
        assertDoesNotThrow(() -> ReformulationProblemAssertions.requireIdentityConflict(mapper, body, instance));
        assertThrows(AssertionError.class,
                () -> ReformulationProblemAssertions.requireIdentityConflict(mapper, body, INSTANCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"detail", "instance", "status", "title", "type", "missing", "extra", "numeric-leak"})
    void changedOrAdditionalProblemDataIsRejected(String mutation) {
        ObjectNode changed = (ObjectNode) mapper.readTree(PROBLEM);
        switch (mutation) {
            case "status" -> changed.put("status", 200);
            case "missing" -> changed.remove("detail");
            case "extra" -> changed.putObject("baseline").put("requirementId", 1014);
            case "numeric-leak" -> changed.put("requirementId", 1014);
            default -> changed.put(mutation, "foreign-baseline-1014");
        }
        assertThrows(AssertionError.class, () -> ReformulationProblemAssertions.requireIdentityConflict(
                mapper, mapper.writeValueAsString(changed), INSTANCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}"})
    void absentOrWrongShapedProblemIsRejected(String body) {
        assertThrows(AssertionError.class,
                () -> ReformulationProblemAssertions.requireIdentityConflict(mapper, body, INSTANCE));
    }

    @Test
    void malformedTrailingAndDuplicateJsonCannotHideAdditionalData() {
        assertThrows(JacksonException.class,
                () -> ReformulationProblemAssertions.requireIdentityConflict(mapper, "{invalid", INSTANCE));
        assertThrows(JacksonException.class, () -> ReformulationProblemAssertions.requireIdentityConflict(
                mapper, PROBLEM + " {\"requirementId\":1014}", INSTANCE));
        assertThrows(JacksonException.class, () -> ReformulationProblemAssertions.requireIdentityConflict(
                mapper, "{\"detail\":\"foreign-baseline-1014\"," + PROBLEM.strip().substring(1), INSTANCE));
    }
}
