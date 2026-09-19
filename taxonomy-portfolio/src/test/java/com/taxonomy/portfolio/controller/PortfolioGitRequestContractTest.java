package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.dto.PortfolioGitDtos.*;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioGitRequestContractTest {
    final PortfolioGitApplicationService service=mock(PortfolioGitApplicationService.class);
    final WorkspaceResolver resolver=mock(WorkspaceResolver.class);
    final RepositoryStateService repositories=mock(RepositoryStateService.class);
    final WorkspaceContext context=new WorkspaceContext("alice","ws-a","current","repo-a");
    final PortfolioGitController controller=new PortfolioGitController(service,resolver,repositories);
    PortfolioGitRequestContractTest() {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");when(resolver.resolveCurrentContext()).thenReturn(context);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","*/*","application/*","application/json;q=0","invalid-content-type","text/plain"})
    void fallbackRepresentationIsTheExactPlainDsl(String accept) throws Exception {
        var value=exported();when(service.export(context)).thenReturn(value);
        var response=controller.exportPortfolio(accept);
        assertThat(response.getBody()).isEqualTo(value.dsl());
        assertThat(response.getHeaders().getContentType()).hasToString("text/plain");
        verify(repositories).ensureWorkspaceState("alice");verify(service).export(context);
    }
    @ParameterizedTest @ValueSource(strings={"application/json","text/plain, application/json;q=0.5","application/json;charset=UTF-8"})
    void explicitJsonPreservesProvenanceAndCounts(String accept) throws Exception {
        var value=exported();when(service.export(context)).thenReturn(value);
        var response=controller.exportPortfolio(accept);
        assertThat(response.getBody()).isSameAs(value);assertThat(response.getHeaders().getContentType()).hasToString("application/json");
    }
    @Test void requestBranchOverridesQueryAndCurrentBranch() throws Exception {
        controller.commit("query",new CommitPortfolioRequest(" body ","Save"));
        verify(service).commit("body","Save",context);
        controller.materialize("query",new MaterializePortfolioRequest(" body ","reviewed"));
        verify(service).materialize("body","reviewed",context);
        controller.merge("query-from","query-to",new MergePortfolioRequest(" body-from "," body-to ","Merge"));
        verify(service).merge("body-from","body-to","Merge",context);
    }
    @Test void absentRequestUsesQueryAndThenCurrentBranch() throws Exception {
        controller.commit(" query ",null);verify(service).commit("query",null,context);
        controller.materialize(null,null);verify(service).materialize("current",null,context);
        controller.merge(" source ",null,null);verify(service).merge("source","current",null,context);
        controller.previewMaterialize("preview");verify(service).previewMaterialize("preview",context);
    }
    @Test void blankBranchesFallBackToDraftAndMissingSourceRemainsInvalidForService() throws Exception {
        var blank=new WorkspaceContext("alice","ws-a"," ","repo-a");when(resolver.resolveCurrentContext()).thenReturn(blank);
        controller.commit(" ",new CommitPortfolioRequest(null,"Save"));verify(service).commit("draft","Save",blank);
        controller.materialize(" ",new MaterializePortfolioRequest(null,"head"));verify(service).materialize("draft","head",blank);
        controller.merge(null," ",new MergePortfolioRequest(" ",null,"Merge"));verify(service).merge(null,"draft","Merge",blank);
    }
    @Test void failedProvisioningCannotReachAnyGitOperation() {
        var failure=new IllegalStateException("workspace unavailable");doThrow(failure).when(repositories).ensureWorkspaceState("alice");
        assertThatThrownBy(()->controller.commit(null,null)).isSameAs(failure);verifyNoInteractions(service);
    }
    private ExportedPortfolioDsl exported() {
        return new ExportedPortfolioDsl("ws-a","alice","current","head-a","project P {}",1,2,3,4,Instant.EPOCH);
    }
}
