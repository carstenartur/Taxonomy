package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.shared.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmTranscriptBoundsTest {
    @Test
    void aggregatedTranscriptsAreBoundedWithoutLosingAnyScoresOrReasons() {
        LlmService service = new LlmService(mock(LlmProviderConfig.class), mock(LlmGatewayRegistry.class),
                new ObjectMapper(), mock(TaxonomyService.class), mock(PromptTemplateService.class),
                mock(LocalEmbeddingService.class), mock(SavedAnalysisService.class));
        List<LlmCallDetail> details = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            LlmCallDetail detail = new LlmCallDetail();
            detail.setScores(Map.of("IP-" + index, 73));
            detail.setReasons(Map.of("IP-" + index, "reason " + index));
            detail.setPrompt("p".repeat(8192));
            detail.setRawResponse("r".repeat(8192));
            detail.setDurationMs(10);
            details.add(detail);
        }
        LlmCallDetail merged = ReflectionTestUtils.invokeMethod(service, "mergeDetails", details);
        assertThat(merged).isNotNull();
        assertThat(merged.getScores()).hasSize(100).containsEntry("IP-99", 73);
        assertThat(merged.getReasons()).hasSize(100).containsEntry("IP-99", "reason 99");
        assertThat(merged.getDurationMs()).isEqualTo(1000);
        assertThat(merged.getPrompt().length()).isLessThanOrEqualTo(32768);
        assertThat(merged.getRawResponse().length()).isLessThanOrEqualTo(32768);
        assertThat(merged.getPrompt()).contains("truncated");
    }
}
