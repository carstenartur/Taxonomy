package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.RequirementCoverageService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoverageApiControllerInputTest {
    @Test
    void missingScoresAreRecordedAsAnEmptyMapWhilePreservingRequirementAndThreshold() {
        var service = mock(RequirementCoverageService.class);
        var response = new CoverageApiController(service).recordCoverage(
                new CoverageApiController.RecordCoverageRequest("REQ-17", "Requirement", null, 70));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).analyzeCoverage(Map.of(), "REQ-17", "Requirement", 70);
        verifyNoMoreInteractions(service);
    }
    @Test
    void suppliedScoresAndExplicitThresholdAreForwardedUnchanged() {
        var service = mock(RequirementCoverageService.class);
        var scores = Map.of("BP", 85, "CP", 40);
        var response = new CoverageApiController(service).recordCoverage(
                new CoverageApiController.RecordCoverageRequest("REQ-18", "Another requirement", scores, 50));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).analyzeCoverage(scores, "REQ-18", "Another requirement", 50);
        verifyNoMoreInteractions(service);
    }

}
