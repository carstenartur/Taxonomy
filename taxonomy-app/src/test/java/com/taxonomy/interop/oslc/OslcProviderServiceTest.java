package com.taxonomy.interop.oslc;

import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceAccessService;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort;
import com.taxonomy.workspace.service.WorkspaceArchitectureReadPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OslcProviderServiceTest {

    @Test
    void historicalArchitectureReadUsesExactCommitAndSerializesReturnedState() throws IOException {
        WorkspaceArchitectureReadPort architecture = mock(WorkspaceArchitectureReadPort.class);
        OslcProviderService service = new OslcProviderService(
                mock(ProjectPortfolioService.class),
                architecture,
                mock(SystemRepositoryService.class),
                mock(RepositoryMembershipService.class),
                mock(WorkspaceAccessService.class));
        RepositoryContext context = RepositoryContext.workspace(
                "repository", "workspace", "draft", "alice");
        String requestedCommit = "0123456789abcdef0123456789abcdef01234567";
        String canonicalDsl = "element arch-a type System";
        var document = new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(
                new WorkspaceArchitectureIntegrationPort.State("workspace-scope", requestedCommit, 7),
                canonicalDsl);
        when(architecture.read(context, requestedCommit)).thenReturn(document);

        OslcRdf rdf = service.architectureVersion(
                context,
                requestedCommit,
                path -> "https://taxonomy.example/oslc" + path);

        verify(architecture).read(context, requestedCommit);
        String resource = "https://taxonomy.example/oslc/architecture/versions/" + requestedCommit;
        assertThat(rdf.triples()).contains(
                new OslcRdf.Triple(resource, OslcRdf.DCT + "identifier", requestedCommit, false),
                new OslcRdf.Triple(resource, OslcRdf.TAX + "canonicalDsl", canonicalDsl, false));

        String serialized = new String(rdf.turtle(), StandardCharsets.UTF_8);
        assertThat(serialized)
                .contains(requestedCommit)
                .contains(canonicalDsl);
    }
}
