package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SavedAnalysisFollowUpTest {
    @Test void optionalFollowUpIdentitySurvivesSavedEvidenceRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var saved = new SavedAnalysisService(mapper, mock(TaxonomyService.class)).importFromJson("""
                {"version":1,"requirement":"requirement","scores":{"BP":0},
                 "analysisOperationId":"10000000-0000-4000-8000-000000000001",
                 "analysisProviderId":"DEMO.TIME-TRACKING-V1.R0"}
                """);
        var roundTrip = mapper.readTree(mapper.writeValueAsString(saved));
        assertThat(roundTrip.path("analysisOperationId").asString()).isEqualTo("10000000-0000-4000-8000-000000000001");
        assertThat(roundTrip.path("analysisProviderId").asString()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
    }
}
