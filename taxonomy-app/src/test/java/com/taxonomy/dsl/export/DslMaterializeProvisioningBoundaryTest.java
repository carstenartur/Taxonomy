package com.taxonomy.dsl.export;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:hsqldb:mem:materialize-provisioning;DB_CLOSE_DELAY=-1",
        "taxonomy.git.bootstrap=false", "embedding.enabled=false", "embedding.allow-download=false",
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=", "qwen.api.key=", "llama.api.key=", "mistral.api.key="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DslMaterializeProvisioningBoundaryTest {
    private static final String ACTOR = "materialize-provision-owner";
    private static final String BRANCH = "draft";
    private static final String SOURCE = "meta { language: \"taxdsl\"; namespace: \"before\"; }";
    private static final String DSL = """
            element P type Process {
              title: "Process";
            }
            element C type Capability {
              title: "Capability";
            }
            relation P REALIZES C {
              status: provisional;
              confidence: 0.82;
            }
            """;

    @Autowired private DslMaterializeService materialization;
    @Autowired private SystemRepositoryService catalog;
    @Autowired private WorkspaceManager workspaces;
    @Autowired private UserWorkspaceRepository workspaceRows;
    @Autowired private DslGitRepositoryFactory git;
    @Autowired private ArchitectureDslDocumentRepository documents;
    @Autowired private RelationHypothesisRepository hypotheses;

    @Test
    void failedMaterializationLeavesTheFirstWorkspaceAllocationReplayable() throws Exception {
        var source = catalog.createCentralRepository("Materialization source", "materialize-" + UUID.randomUUID(),
                null, RepositoryVisibility.PRIVATE, ACTOR, BRANCH);
        String repositoryId = source.getRepositoryId();
        var upstream = git.createCentralRepository(repositoryId, source.getStorageRepositoryName());
        String base = upstream.commitDsl(BRANCH, SOURCE, ACTOR, "Selected source");
        catalog.markProvisioningReady(repositoryId);
        var workspace = workspaces.createWorkspace(ACTOR, "Materialization " + UUID.randomUUID(), null);
        String workspaceId = workspace.getWorkspaceId();
        workspace.setDefault(true);
        workspace.setSourceRepositoryId(repositoryId);
        workspaceRows.saveAndFlush(workspace);
        var previous = SecurityContextHolder.getContext();
        var authenticated = SecurityContextHolder.createEmptyContext();
        authenticated.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_ARCHITECT"))));
        SecurityContextHolder.setContext(authenticated);
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> materialization.materialize(DSL, "x".repeat(256), BRANCH, null));
            assertTrue(hypotheses.findVisibleByRepositoryAndWorkspaceAndBranch(repositoryId, workspaceId, BRANCH).isEmpty());

            var target = git.openWorkspaceRepository(workspaceId);
            String retainedHead = target.getHeadCommit(BRANCH);
            assertNotNull(retainedHead);
            var retained = workspaceRows.findByWorkspaceId(workspaceId).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, retained.getProvisioningStatus());
            assertEquals(base, retained.getBaseCommit());
            assertEquals(retainedHead, retained.getCurrentCommit());

            upstream.commitDsl(BRANCH, "meta { language: \"taxdsl\"; namespace: \"after\"; }", ACTOR, "Advanced source");
            var replay = materialization.materialize(DSL, "recovered.taxdsl", BRANCH, null);
            assertTrue(replay.valid());
            assertEquals(1, replay.hypothesesCreated());
            assertEquals(BRANCH, documents.findById(replay.documentId()).orElseThrow().getBranch());
            assertEquals(1, hypotheses.findVisibleByRepositoryAndWorkspaceAndBranch(repositoryId, workspaceId, BRANCH).size());
            assertEquals(retainedHead, target.getHeadCommit(BRANCH));
            assertEquals(base, workspaceRows.findByWorkspaceId(workspaceId).orElseThrow().getBaseCommit());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
