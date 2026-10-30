package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.portfolio.service.RequirementIntakeService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RequirementIntakeControllerTest {
    private final RequirementIntakeService intake=mock(RequirementIntakeService.class);
    private final WorkspaceResolver resolver=mock(WorkspaceResolver.class);
    private final MockMvc mvc=standaloneSetup(new RequirementIntakeController(intake,resolver))
            .setControllerAdvice(new PortfolioExceptionHandler()).build();

    @ParameterizedTest @ValueSource(strings={"repositoryId","workspaceId","workspaceScopeKey","branch"})
    void mismatchedScopePinCannotCaptureCandidatesOrInvokeProvider(String pin) throws Exception {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice","work","draft","repo"));
        mvc.perform(post("/api/architecture/lifecycle/intake-review").param(pin,"other")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Incoming\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(intake);
    }

    @Test void exactPrivateScopePinsReachTheReviewUnchanged() throws Exception {
        var context=new WorkspaceContext("alice","work","draft","repo");
        response(context,new Scope("repo","work","work","draft"));
        mvc.perform(post("/api/architecture/lifecycle/intake-review")
                        .param("repositoryId","repo").param("workspaceId","work").param("workspaceScopeKey","work").param("branch","draft")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Incoming\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope.workspaceScopeKey").value("work"));
        verify(intake).review(new Request(null,null,"Incoming",null),"alice",context);
    }

    @Test void sharedScopePinUsesCanonicalSharedIdentity() throws Exception {
        var context=new WorkspaceContext("alice",null,"main","repo");
        response(context,new Scope("repo",null,"__shared__","main"));
        mvc.perform(post("/api/architecture/lifecycle/intake-review")
                        .param("repositoryId","repo").param("workspaceId","").param("workspaceScopeKey","__shared__").param("branch","main")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Incoming\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope.workspaceScopeKey").value("__shared__"));
        verify(intake).review(new Request(null,null,"Incoming",null),"alice",context);
    }

    private void response(WorkspaceContext context,Scope scope) {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(context);
        when(intake.review(any(),eq("alice"),eq(context))).thenReturn(new Response(Status.UNAVAILABLE,
                "PROVIDER_NOT_CONFIGURED",RequirementIntakeService.inputHash("Incoming"),scope,null,
                new Coverage(0,0,false),List.of(),true));
    }
}
