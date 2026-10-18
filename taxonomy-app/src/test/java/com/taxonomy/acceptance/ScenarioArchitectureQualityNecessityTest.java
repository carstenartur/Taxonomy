package com.taxonomy.acceptance;

import com.taxonomy.dto.RelationSearchModel.Necessity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.fixture;
import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.verify;
import static org.junit.jupiter.api.Assertions.*;

/** Independent functional examples separate required channel support from an optional extra view. */
class ScenarioArchitectureQualityNecessityTest {
    @ParameterizedTest @ValueSource(strings = {"L05", "L06"})
    void requiredChannelFulfillmentIsAcceptedWithItsBoundedFunction(String id) {
        assertEquals("PASS", verify(fixture("", relation(id, Necessity.REQUIRED))).get("verdict"));
    }

    @ParameterizedTest @ValueSource(strings = {"L05", "L06"})
    void requiredChannelFulfillmentCannotBeDemotedToAnOptionalCapability(String id) {
        assertRejected(id, Necessity.OPTIONAL);
    }

    @ParameterizedTest @ValueSource(strings = {"L04", "L14", "L15", "L16"})
    void explicitlySelectedDecompositionOrInverseViewRemainsOptional(String id) {
        assertEquals("PASS", verify(fixture("", relation(id, Necessity.OPTIONAL))).get("verdict"));
    }

    @ParameterizedTest @ValueSource(strings = {"L04", "L14", "L15", "L16"})
    void optionalDecompositionOrInverseViewCannotBecomeARequiredDependency(String id) {
        assertRejected(id, Necessity.REQUIRED);
    }

    private static void assertRejected(String id, Necessity necessity) {
        var failure = assertThrows(AssertionError.class, () -> verify(fixture("", relation(id, necessity))));
        assertTrue(failure.getMessage().contains(id), failure::getMessage);
    }

    private static ScenarioArchitectureQualityEvidenceTest.Spec relation(String id, Necessity necessity) {
        String channel = "Channel capability is required; each delivery uses this channel only when selected by the subscriber.";
        String view = "Only if an additional capability-facing view is selected; it describes the same bounded function and adds no independent obligation.";
        return switch (id) {
            case "L04" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "UA-1580", "USES", "CI-1096", "F1", "F1", necessity,
                    "Only when the picture-preparation layer is selected as an architectural decomposition; no unavoidable deployment dependency is claimed.");
            case "L05" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "CR-1037", "FULFILLS", "CP-1041", "F3", "F3", necessity, channel);
            case "L06" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "CR-1060", "FULFILLS", "CP-1041", "F3", "F3", necessity, channel);
            case "L14" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "CP-1041", "REALIZES", "CR-1037", "F3", "F3", necessity, view);
            case "L15" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "CP-1041", "REALIZES", "CR-1060", "F3", "F3", necessity, view);
            case "L16" -> new ScenarioArchitectureQualityEvidenceTest.Spec(id, "CP-1123", "REALIZES", "CR-1007", "A1", "A1", necessity, view + " Within test design assumption A1.");
            default -> throw new AssertionError("Unknown handwritten test relation " + id);
        };
    }
}
