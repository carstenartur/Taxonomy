package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.GitNativeSyncIntegrationService;
import com.taxonomy.workspace.service.SyncIntegrationService.DivergedStrategy;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Hibernate Git refs and portfolio transactions expose failures that ORM rollback cannot repair. */
@SpringBootTest
@WithMockUser(username = "alice", roles = "ADMIN")
class GitNativeSyncPortfolioAtomicityTest {
    private static final String MODEL = """
            element recorder type Component {
              title: "Recorder";
            }
            """;

    @Autowired GitNativeSyncIntegrationService sync;
    @Autowired PortablePortfolioGitService portfolio;
    @Autowired ProjectPortfolioService projects;
    @Autowired SystemRepositoryService repositories;
    @Autowired UserWorkspaceRepository workspaces;
    @Autowired SyncStateRepository syncStates;
    @Autowired DslGitRepositoryFactory git;

    @ParameterizedTest
    @EnumSource(value = DivergedStrategy.class, names = {"TAKE_SHARED", "KEEP_MINE"})
    void immutableVersionCollisionIsRejectedBeforeModelOrTrackingRefsMove(DivergedStrategy strategy) throws Exception {
        Fixture fixture = collision(strategy);
        Snapshot before = snapshot(fixture);
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, fixture.local().workspaceId());
        var previousRequest = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThatThrownBy(() -> sync.resolveDiverged("alice", "main", strategy))
                    .isInstanceOfSatisfying(CommandProblem.class, failure -> {
                        assertThat(failure.code()).isEqualTo("PORTFOLIO_RECONCILIATION_REQUIRED");
                        assertThat(failure.getMessage()).contains("Immutable requirement version 2");
                    });
        } finally {
            RequestContextHolder.setRequestAttributes(previousRequest);
        }

        Snapshot after = snapshot(fixture);
        assertThat(after.localHead()).as("a rejected portfolio decision must not advance workspace HEAD")
                .isEqualTo(before.localHead());
        assertThat(after.centralHead()).as("a rejected portfolio decision must not publish a central HEAD")
                .isEqualTo(before.centralHead());
        assertThat(after.trackingHead()).as("a rejected portfolio decision must not become the accepted semantic base")
                .isEqualTo(before.trackingHead());
        assertThat(after.localPortfolio()).isEqualTo(before.localPortfolio());
        assertThat(after.centralPortfolio()).isEqualTo(before.centralPortfolio());
        assertThat(after.workspaceMetadata()).isEqualTo(before.workspaceMetadata());
        assertThat(after.lastPushAt()).isEqualTo(before.lastPushAt());
        assertThat(after.syncState()).isEqualTo(before.syncState());
    }

    private Fixture collision(DivergedStrategy strategy) throws Exception {
        String id = UUID.randomUUID().toString();
        var repository = repositories.createCentralRepository("Native sync immutable collision", "native-sync-" + id,
                "Real publication boundary regression", RepositoryVisibility.PRIVATE, "alice", "main");
        var local = new WorkspaceContext("alice", "native-sync-" + id, "main", repository.getRepositoryId());
        var central = new WorkspaceContext("shared", null, "main", repository.getRepositoryId());
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(local.workspaceId()); workspace.setUsername("alice"); workspace.setDisplayName("Native sync collision " + id);
        workspace.setSourceRepositoryId(repository.getRepositoryId()); workspace.setSourceBranch("main"); workspace.setCurrentBranch("main");
        workspace.setCreatedAt(Instant.now()); workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspaces.saveAndFlush(workspace);

        var project = projects.createProject(new CreateProjectRequest("ORDERS", "Orders", null, null, null, null, null, null),
                "alice", local);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("RETENTION", "Order retention",
                "Shared immutable first version", null, null, null, null, null, "alice", "Initial reviewed version", null), "alice", local);
        String base = portfolio.contributeTo(MODEL, "alice", local);
        portfolio.materialize(base, "shared", central);
        var centralProject = projects.listProjects("shared", central).getFirst();
        var centralRequirement = projects.listRequirements(centralProject.id(), "shared", central).getFirst();

        DslGitRepository source = git.getCentralRepository(repository.getRepositoryId());
        DslGitRepository working = git.openWorkspaceRepository(local.workspaceId());
        String sourceBase = source.commitDsl("main", base, "alice", "Accepted common source");
        working.commitDsl("main", base, "alice", "Accepted workspace source");
        working.commitDsl("sync-base", base, "alice", "Accepted semantic base");
        repositories.markProvisioningReady(repository.getRepositoryId());

        projects.addRequirementVersion(project.id(), requirement.id(),
                new CreateRequirementVersionRequest("Local immutable second version", "Reviewed locally", null), "alice", local);
        projects.addRequirementVersion(centralProject.id(), centralRequirement.id(),
                new CreateRequirementVersionRequest("Central immutable second version", "Reviewed centrally", null), "shared", central);
        String localDsl = portfolio.contributeTo(MODEL, "alice", local);
        String localHead = working.commitDsl("main", localDsl, "alice", "Local second version");
        if (strategy == DivergedStrategy.TAKE_SHARED) {
            source.commitDsl("main", portfolio.contributeTo(MODEL, "shared", central), "shared", "Central second version");
        } else {
            // The conflicting central v2 is retained only in the database: source-text comparison cannot find it.
            assertThat(source.getDslAtHead("main")).doesNotContain("Central immutable second version");
            assertThat(portfolio.exportPortfolio("shared", central)).contains("Central immutable second version");
        }
        workspace.setBaseCommit(sourceBase); workspace.setLastFetchedCommit(sourceBase); workspace.setLastIntegratedCommit(sourceBase);
        workspace.setCurrentCommit(localHead); workspace.setLastAccessedAt(Instant.now());
        workspaces.saveAndFlush(workspace);
        return new Fixture(local, central, working, source);
    }

    private Snapshot snapshot(Fixture fixture) throws Exception {
        var row = workspaces.findByWorkspaceId(fixture.local().workspaceId()).orElseThrow();
        var state = syncStates.findByUsername("alice").map(value -> List.of(
                String.valueOf(value.getLastSyncedCommitId()), String.valueOf(value.getLastPublishedCommitId()),
                String.valueOf(value.getLastSyncTimestamp()), String.valueOf(value.getLastPublishTimestamp()),
                String.valueOf(value.getSyncStatus()), Integer.toString(value.getUnpublishedCommitCount()),
                String.valueOf(value.getUpdatedAt()))).orElse(null);
        return new Snapshot(fixture.working().getHeadCommit("main"), fixture.source().getHeadCommit("main"),
                fixture.working().getHeadCommit("sync-base"), portfolio.exportPortfolio("alice", fixture.local()),
                portfolio.exportPortfolio("shared", fixture.central()),
                List.of(row.getCurrentCommit(), row.getLastFetchedCommit(), row.getLastIntegratedCommit(), row.getLastAccessedAt().toString()),
                repositories.getRepository(fixture.local().repositoryId()).getLastPushAt(), state);
    }

    private record Fixture(WorkspaceContext local, WorkspaceContext central, DslGitRepository working, DslGitRepository source) { }
    private record Snapshot(String localHead, String centralHead, String trackingHead, String localPortfolio,
                            String centralPortfolio, List<String> workspaceMetadata, Instant lastPushAt, List<String> syncState) { }
}
