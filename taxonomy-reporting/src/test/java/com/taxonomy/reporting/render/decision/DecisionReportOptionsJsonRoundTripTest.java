package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.api.decision.DecisionReportOptions;

import com.taxonomy.reporting.api.decision.DecisionReportOptions.Contents;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.Profile;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionReportOptionsJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(Profile.class)
    void allAnalysisRootsSurviveJsonRoundTrip(Profile profile) {
        var options = new DecisionReportOptions(profile, null, null, null, null);
        assertEquals(options, mapper.readValue(mapper.writeValueAsString(options), DecisionReportOptions.class));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void implicitAllRootsAreOmittedRatherThanWrittenAsAnInvalidEmptySelection(Profile profile) {
        var options = new DecisionReportOptions(profile, null, null, null, null);
        assertFalse(mapper.readTree(mapper.writeValueAsString(options)).has("taxonomyRoots"));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void explicitSelectedRootsRemainPresentAndSurviveJsonRoundTrip(Profile profile) {
        var options = new DecisionReportOptions(profile, Set.of("BP", "CP"), null, null, null);
        String json = mapper.writeValueAsString(options);
        assertEquals(2, mapper.readTree(json).path("taxonomyRoots").size());
        assertEquals(options, mapper.readValue(json, DecisionReportOptions.class));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void emptySectionsAreNotOmittedOrReplacedByProfileDefaults(Profile profile) {
        var options = new DecisionReportOptions(profile, null, Contents.NONE, TreeLayout.AUTO, Set.of());
        String json = mapper.writeValueAsString(options);
        assertTrue(mapper.readTree(json).has("sections"));
        assertEquals(options, mapper.readValue(json, DecisionReportOptions.class));
    }

    @Test
    void omittedRootsKeepFullDefaults() {
        assertEquals(DecisionReportOptions.full(), mapper.readValue("{}", DecisionReportOptions.class));
    }

    @Test
    void explicitNullRootsKeepFullDefaults() {
        assertEquals(DecisionReportOptions.full(), mapper.readValue("{\"taxonomyRoots\":null}", DecisionReportOptions.class));
    }

    @Test
    void explicitEmptyJsonSelectionIsStillRejected() {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> mapper.readValue("{\"taxonomyRoots\":[]}", DecisionReportOptions.class));
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("explicit export selection"));
    }

    @Test
    void explicitEmptyConstructorSelectionIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionReportOptions(null, Set.of(), null, null, null));
    }

    @Test
    void blankRootIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionReportOptions(null, Set.of(" "), null, null, null));
    }

    @Test
    void whitespacePaddedRootIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionReportOptions(null, Set.of(" BP"), null, null, null));
    }

    @Test
    void overlongRootIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionReportOptions(null, Set.of("x".repeat(257)), null, null, null));
    }

    @Test
    void excessiveRootCountIsStillRejected() {
        var roots = new HashSet<String>();
        for (int i = 0; i < 101; i++) roots.add("ROOT-" + i);
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionReportOptions(null, roots, null, null, null));
    }

    @Test
    void optionsSurviveRoundTripInsideTheirContainingDocument() {
        var envelope = new ExportEnvelope(DecisionReportOptions.full());
        assertEquals(envelope, mapper.readValue(mapper.writeValueAsString(envelope), ExportEnvelope.class));
    }

    public record ExportEnvelope(DecisionReportOptions options) { }
}
