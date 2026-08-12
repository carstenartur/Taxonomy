package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionContextException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RelationSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationBranchProjectionRebuildServiceTest {

    private DslGitRepositoryFactory repositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
    }

    @Test
    void parsesTheExactBranchHeadBeforeReplacingProjectionRows() throws Exception {
        repositoryFactory = new DslGitRepositoryFactory(null);
        RelationBranchProjectionRebuildWriter writer =
                mock(RelationBranchProjectionRebuildWriter.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String head = repository.commitDsl(
                "review",
                """
                        element APP-1 type Application {
                          title: "Application";
                        }

                        relation APP-1 USES SVC-1 {
                          status: accepted;
                          confidence: 0.9;
                          provenance: manual;
                        }

                        relation SVC-1 DEPENDS_ON DB-1 {
                          status: proposed;
                        }
                        """,
                "alice",
                "Seed review branch");
        when(writer.replace(any(), anyString(), anyList()))
                .thenAnswer(invocation -> new RebuildResult(
                        context.repositoryId(),
                        context.workspaceId(),
                        context.branch(),
                        invocation.getArgument(1),
                        ((List<?>) invocation.getArgument(2)).size()));

        RelationBranchProjectionRebuildService service =
                new RelationBranchProjectionRebuildService(
                        repositoryFactory, writer);
        RebuildResult result = service.rebuild(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelationSnapshot>> snapshots =
                ArgumentCaptor.forClass(List.class);
        verify(writer).replace(
                org.mockito.ArgumentMatchers.eq(context),
                org.mockito.ArgumentMatchers.eq(head),
                snapshots.capture());
        assertThat(result.authoritativeCommitId()).isEqualTo(head);
        assertThat(result.relationCount()).isEqualTo(2);
        assertThat(snapshots.getValue())
                .extracting(
                        RelationSnapshot::sourceCode,
                        RelationSnapshot::relationType,
                        RelationSnapshot::targetCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "APP-1", RelationType.USES, "SVC-1"),
                        org.assertj.core.groups.Tuple.tuple(
                                "SVC-1", RelationType.DEPENDS_ON, "DB-1"));
        assertThat(snapshots.getValue().getFirst().status())
                .isEqualTo("accepted");
        assertThat(snapshots.getValue().getFirst().confidence())
                .isEqualTo(0.9);
        assertThat(snapshots.getValue().getFirst().provenance())
                .isEqualTo("manual");
    }

    @Test
    void duplicateAuthoritativeRelationsFailBeforeDatabaseAccess() throws Exception {
        repositoryFactory = new DslGitRepositoryFactory(null);
        RelationBranchProjectionRebuildWriter writer =
                mock(RelationBranchProjectionRebuildWriter.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        repositoryFactory.resolveRepository(context).commitDsl(
                "draft",
                """
                        relation APP-1 USES SVC-1 {
                          status: proposed;
                        }

                        relation APP-1 USES SVC-1 {
                          status: accepted;
                        }
                        """,
                "alice",
                "Duplicate relation fixture");

        RelationBranchProjectionRebuildService service =
                new RelationBranchProjectionRebuildService(
                        repositoryFactory, writer);

        assertThatThrownBy(() -> service.rebuild(context))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessageContaining("Duplicate relation");
        verifyNoInteractions(writer);
    }

    @Test
    void centralReadIsRejectedBeforeRepositoryOrDatabaseAccess() {
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        RelationBranchProjectionRebuildWriter writer =
                mock(RelationBranchProjectionRebuildWriter.class);
        RelationBranchProjectionRebuildService service =
                new RelationBranchProjectionRebuildService(factory, writer);
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "accepted", "reader");

        assertThatThrownBy(() -> service.rebuild(context))
                .isInstanceOf(BranchProjectionContextException.class)
                .hasMessageContaining("writable repository context");
        verifyNoInteractions(factory, writer);
    }
}
