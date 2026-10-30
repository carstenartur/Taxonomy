package com.taxonomy.composition.architecture;

import com.taxonomy.versioning.service.RequirementHistoryService;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import java.io.IOException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureRequirementHistoryHttpTest {
    final RequirementHistoryService history=mock(RequirementHistoryService.class);
    final ArchitectureLifecycleService lifecycle=mock(ArchitectureLifecycleService.class);
    final WorkspaceResolver resolver=mock(WorkspaceResolver.class);
    final RepositoryContext context=RepositoryContext.workspace("architecture","copy","draft","alice");
    MockMvc mvc;
    @BeforeEach void setup() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
        mvc=standaloneSetup(new ArchitectureRequirementHistoryController(history,lifecycle,new LifecycleHttpContext(resolver)))
                .setControllerAdvice(new ArchitectureLifecycleExceptionHandler()).build();
    }
    @Test void movedHeadRequiresRestartRatherThanClaimingStorageOutage() throws Exception {
        when(history.search(eq(context),any())).thenThrow(new BranchHeadConflictException("draft","a".repeat(40),"b".repeat(40),"Restart history"));
        mvc.perform(get("/api/architecture/lifecycle/history").param("offset","25").param("expectedHead","a".repeat(40)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HISTORY_HEAD_CHANGED"));
    }
    @Test void unavailableStorageIsNotAnEmptyCompleteHistory() throws Exception {
        when(history.search(eq(context),any())).thenThrow(new IOException("Store unavailable"));
        mvc.perform(get("/api/architecture/lifecycle/history")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GIT_UNAVAILABLE"));
    }
    @Test void invalidContinuationAndPartialBusinessIdentityNeverQueryGit() throws Exception {
        mvc.perform(get("/api/architecture/lifecycle/history").param("offset","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/architecture/lifecycle/history").param("projectKey","PROJECT")).andExpect(status().isBadRequest());
        verifyNoInteractions(history);
    }
    @Test void inaccessibleOrUnselectedScopeNeverQueriesGit() throws Exception {
        mvc.perform(get("/api/architecture/lifecycle/history").param("branch","another")).andExpect(status().isNotFound());
        doThrow(ArchitectureLifecycleService.problem("NOT_FOUND","Unavailable")).when(lifecycle).authorize(context,false);
        mvc.perform(get("/api/architecture/lifecycle/history")).andExpect(status().isNotFound());
        verifyNoInteractions(history);
    }
}
