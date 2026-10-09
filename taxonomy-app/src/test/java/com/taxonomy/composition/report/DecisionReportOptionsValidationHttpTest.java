package com.taxonomy.composition.report;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportController;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportService;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionReportOptionsValidationHttpTest {
    @Test void invalidTypedOptionsRemainClientErrorsWithTheApplicationAdvice() throws Exception {
        var controller = new DecisionRationaleReportController(mock(DecisionRationaleReportService.class),
                mock(ReportRendererRegistry.class), mock(RepositoryStateService.class), mock(WorkspaceResolver.class));
        var saved = new DecisionRationaleSnapshotReportController(mock(DecisionRationaleSnapshotReportService.class),
                mock(ReportRendererRegistry.class), mock(WorkspaceResolver.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller, saved)
                .setControllerAdvice(new GlobalExceptionHandler(mock(MessageSource.class))).build();
        mvc.perform(post("/api/decision-report/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"exportOptions\":{\"profile\":\"WRONG\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/decision-report/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"exportOptions\":{\"taxonomyRoots\":[]}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/1/snapshots/saved/decision-report/json").param("profile","WRONG"))
                .andExpect(status().isBadRequest());
    }
}
