package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortfolioImportRoutingContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void importQueuesOnlyReturnedRequirementIdentitiesWhenExplicitlyRequested(boolean analyze) {
        var projects=mock(ProjectPortfolioService.class);
        var analysis=mock(ProjectRequirementAnalysisService.class);
        var resolver=mock(WorkspaceResolver.class);
        var context=new WorkspaceContext("alice","ws-a","draft","repo-a");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");when(resolver.resolveCurrentContext()).thenReturn(context);
        var controller=new ProjectPortfolioController(projects,analysis,resolver,100,500000L);
        var candidate=mock(ImportRequirementCandidate.class,RETURNS_DEEP_STUBS);
        when(candidate.text()).thenReturn(analyze ? "requirement" : null);
        when(candidate.source().originalText()).thenReturn(null);
        var request=mock(ImportRequirementsRequest.class);
        when(request.requirements()).thenReturn(List.of(candidate));when(request.analyzeAfterImport()).thenReturn(analyze);
        when(request.provider()).thenReturn("mock");when(request.maxArchitectureNodes()).thenReturn(17);when(request.idempotencyKey()).thenReturn("import-1");
        var created=mock(RequirementView.class);when(created.id()).thenReturn(22L);
        when(projects.importRequirements(1L,List.of(candidate),"alice",context)).thenReturn(List.of(created));
        var response=controller.importRequirements(1L,request);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        verify(projects).importRequirements(1L,List.of(candidate),"alice",context);
        if(analyze) {
            var command=ArgumentCaptor.forClass(AnalyzeProjectRequest.class);
            verify(analysis).enqueueProject(eq(1L),command.capture(),eq("alice"),eq(context));
            assertThat(command.getValue().requirementIds()).containsExactly(22L);
            assertThat(command.getValue().provider()).isEqualTo("mock");
            assertThat(command.getValue().maxArchitectureNodes()).isEqualTo(17);
            assertThat(command.getValue().idempotencyKey()).isEqualTo("import-1");
        } else verifyNoInteractions(analysis);
    }
}
