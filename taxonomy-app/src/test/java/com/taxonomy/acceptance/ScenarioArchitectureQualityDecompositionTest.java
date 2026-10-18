package com.taxonomy.acceptance;

import com.taxonomy.dto.RelationSearchModel.Necessity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.fixture;
import static com.taxonomy.acceptance.ScenarioArchitectureQualityEvidenceTest.verify;
import static org.junit.jupiter.api.Assertions.*;

/** L04's selected F1 decomposition does not introduce a mandatory deployment. */
class ScenarioArchitectureQualityDecompositionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "The application may use a chosen environmental-picture preparation layer. This selected decomposition does not require a separate service or deployment.",
            "Only when the picture-preparation layer is selected as an architectural decomposition; no unavoidable deployment dependency is claimed.",
            "If an environmental-picture preparation and presentation layer is selected behind the public application. F1 requires the view, not this separate application-to-service decomposition."
    })
    void selectedPicturePreparationAcceptsParaphrasesAndExplicitlyNegatedDeploymentDuties(String condition) {
        assertEquals("PASS", verify(fixture("", relation(condition))).get("verdict"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Every deployment must introduce a mandatory new picture-preparation service; the decomposition is unavoidable and no selection is needed.",
            "Only if the picture-preparation decomposition is selected, use this optional view. Every deployment must introduce a mandatory new picture-preparation service; the decomposition is unavoidable and no selection is needed.",
            "Only when the picture-preparation layer is selected as an architectural decomposition; no unavoidable deployment dependency is claimed. The service, however, must be deployed in every installation.",
            "Only when the layer is selected as a decomposition. The service, however, is mandatory.",
            "Only when the layer is selected as a decomposition. This dependency, of course, is unavoidable.",
            ""
    })
    void aMandatoryDeploymentCannotHideInsideAnOptionalRelationshipOrBehindSelectionKeywords(String condition) {
        var failure = assertThrows(AssertionError.class, () -> verify(fixture("", relation(condition))));
        assertTrue(failure.getMessage().contains("L04"), failure::getMessage);
    }

    private static ScenarioArchitectureQualityEvidenceTest.Spec relation(String condition) {
        return new ScenarioArchitectureQualityEvidenceTest.Spec("L04", "UA-1580", "USES", "CI-1096", "F1", "F1",
                Necessity.OPTIONAL, condition);
    }
}
