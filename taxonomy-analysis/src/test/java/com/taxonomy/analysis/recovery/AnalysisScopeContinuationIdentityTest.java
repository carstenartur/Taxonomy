package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalysisScopeContinuationIdentityTest {
    @Test
    void scopeIsCanonicalAndFrozenWithoutChangingLegacyIdentity() throws Exception {
        var llm = mock(LlmService.class);
        when(llm.recoveryPolicyFingerprint(null)).thenReturn("policy");
        var service = new AnalysisContinuationService(null, null, llm, null, new ObjectMapper());
        var method = AnalysisContinuationService.class.getDeclaredMethod("signature", AnalysisRequest.class, List.class);
        method.setAccessible(true);
        var request = new AnalysisRequest(); request.setBusinessText("requirement");
        String legacy = AnalysisCheckpointSession.digest("resumable-scoring-v1", "requirement", "false", "null", "policy", "[]", "");
        assertThat(method.invoke(service, request, List.of())).isEqualTo(legacy);
        request.setAnalysisScope(new AnalysisScope(new LinkedHashSet<>(List.of("CP", "BP")), AnalysisMode.FULL));
        Object selected = method.invoke(service, request, List.of());
        request.setAnalysisScope(new AnalysisScope(new LinkedHashSet<>(List.of("BP", "CP")), AnalysisMode.FULL));
        assertThat(method.invoke(service, request, List.of())).isEqualTo(selected);
        request.setAnalysisScope(new AnalysisScope(Set.of("BP"), AnalysisMode.FULL));
        assertThat(method.invoke(service, request, List.of())).isNotEqualTo(selected);
        request.setAnalysisScope(new AnalysisScope(Set.of("BP", "CP"), AnalysisMode.TAXONOMIES_ONLY));
        assertThat(method.invoke(service, request, List.of())).isNotEqualTo(selected);
    }
}
