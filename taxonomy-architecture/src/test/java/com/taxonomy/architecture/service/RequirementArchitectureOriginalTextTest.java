package com.taxonomy.architecture.service;

import com.taxonomy.architecture.pipeline.ArchitectureViewContext;
import com.taxonomy.architecture.pipeline.ArchitectureViewPipeline;
import com.taxonomy.dto.RequirementArchitectureView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RequirementArchitectureOriginalTextTest {
    @Test
    void builtWorkingViewRetainsTheExactSubmittedSource() {
        var pipeline = mock(ArchitectureViewPipeline.class);
        var view = new RequirementArchitectureView();
        when(pipeline.execute(any(ArchitectureViewContext.class))).thenReturn(view);
        var service = new RequirementArchitectureViewService(pipeline);
        String source = "  Original submitted text\nÜnicode source and trailing spaces.  ";

        var built = service.build(Map.of("CP", 80d), source, 20);

        var serialized = new ObjectMapper().valueToTree(built);
        assertThat(serialized.path("originalRequirementText").asText()).isEqualTo(source);
        assertThat(built).isSameAs(view);
    }

    @Test
    void emptyWorkingViewStillKeepsSourceWithoutStartingThePipeline() {
        var pipeline = mock(ArchitectureViewPipeline.class);
        var service = new RequirementArchitectureViewService(pipeline);
        String source = "Original source with no successful scores";

        var built = service.build(Map.of(), source, 20);

        assertThat(new ObjectMapper().valueToTree(built).path("originalRequirementText").asText()).isEqualTo(source);
        verifyNoInteractions(pipeline);
    }
}
