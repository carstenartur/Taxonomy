package com.taxonomy.relations.command;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ArchitectureRelationGitCommandServiceTest {

    private DslGitRepositoryFactory repositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
    }

    @Test
    void workspaceCommandCommitsOnlyToTheExplicitSelectedWorkspace()
            throws Exception {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext workspaceA = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        RepositoryContext workspaceB = RepositoryContext.workspace(
                "repo-a", "workspace-b", "draft", "bob");
        DslGitRepository repositoryA = repositoryFactory.resolveRepository(workspaceA);
        DslGitRepository repositoryB = repositoryFactory.resolveRepository(workspaceB);
        String seed = """
                element APP-1 type Application {
                  title: "Payments";
                }

                element SVC-1 type Service {
                  title: "Payment service";
                }
                """;
        String headA = repositoryA.commitDsl(
                "draft", seed, "system", "Seed workspace A");
        String headB = repositoryB.commitDsl(
                "draft", seed, "system", "Seed workspace B");

        var result = service.execute(
                workspaceA,
                headA,
                new UpsertRelation(
                        definition("accepted", 0.9, "manual"),
                        new CommandMetadata(
                                "proposal-17",
                                " Accepted after architecture review. ")));

        assertThat(result.repositoryId()).isEqualTo("repo-a");
        assertThat(result.workspaceId()).isEqualTo("workspace-a");
        assertThat(result.branch()).isEqualTo("draft");
        assertThat(result.scope()).isEqualTo(RepositoryScope.WORKSPACE);
        assertThat(result.previousHeadCommit()).isEqualTo(headA);
        assertThat(result.authoritativeCommitId())
                .isEqualTo(repositoryA.getHeadCommit("draft"));
        assertThat(result.changeKind()).isEqualTo(ChangeKind.ADDED);
        assertThat(result.commitCreated()).isTrue();
        assertThat(result.causationId()).isEqualTo("proposal-17");
        assertThat(repositoryA.getDslAtHead("draft"))
                .contains("relation APP-1 USES SVC-1")
                .contains("status: accepted;");
        assertThat(repositoryA.getHeadCommitInfo("draft").message())
                .contains("relation: added APP-1 USES SVC-1")
                .contains("Causation-Id: proposal-17")
                .contains("Rationale: Accepted after architecture review.");

        assertThat(repositoryB.getHeadCommit("draft")).isEqualTo(headB);
        assertThat(repositoryB.getDslAtHead("draft"))
                .doesNotContain("relation APP-1 USES SVC-1");
        assertThat(repositoryB.getCommitCount("draft")).isEqualTo(1);
    }

    @Test
    void centralReadIsRejectedBeforeRepositoryResolution() {
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        ArchitectureRelationGitCommandService service =
                new ArchitectureRelationGitCommandService(
                        factory,
                        new ArchitectureRelationDslTransformer(),
                        new ExpectedHeadDslCommitter());
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "accepted", "reader");

        assertThatThrownBy(() -> service.execute(
                context,
                null,
                new UpsertRelation(
                        definition("accepted", 0.9, "manual"),
                        new CommandMetadata("manual-1"))))
                .isInstanceOf(ReadOnlyRepositoryContextException.class)
                .hasMessageContaining("CENTRAL_WRITE, WORKSPACE or FORK");
        verifyNoInteractions(factory);
    }

    @Test
    void centralWriteCreatesTheInitialAuthoritativeCommit() throws Exception {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext context = RepositoryContext.centralWrite(
                "repo-central", "accepted", "maintainer");

        var result = service.execute(
                context,
                null,
                new UpsertRelation(
                        definition("accepted", 1.0, "manual"),
                        new CommandMetadata("manual-central")));

        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        assertThat(result.previousHeadCommit()).isNull();
        assertThat(result.authoritativeCommitId())
                .isEqualTo(repository.getHeadCommit("accepted"));
        assertThat(result.commitCreated()).isTrue();
        assertThat(repository.getCommitCount("accepted")).isEqualTo(1);
        assertThat(repository.getDslAtHead("accepted"))
                .contains("relation APP-1 USES SVC-1");
    }

    @Test
    void equivalentCommandReturnsTheExistingCommitWithoutAddingHistory()
            throws Exception {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String head = repository.commitDsl(
                "draft",
                """
                        relation APP-1 USES SVC-1 {
                          status: accepted;
                          confidence: 0.8;
                          provenance: manual;
                          x-command-source: relation-review;
                        }
                        """,
                "system",
                "Seed relation");

        var result = service.execute(
                context,
                head,
                new UpsertRelation(
                        definition("accepted", 0.8, "manual"),
                        new CommandMetadata("duplicate-review")));

        assertThat(result.changeKind()).isEqualTo(ChangeKind.UNCHANGED);
        assertThat(result.commitCreated()).isFalse();
        assertThat(result.previousHeadCommit()).isEqualTo(head);
        assertThat(result.authoritativeCommitId()).isEqualTo(head);
        assertThat(repository.getHeadCommit("draft")).isEqualTo(head);
        assertThat(repository.getCommitCount("draft")).isEqualTo(1);
    }

    @Test
    void staleNoOpCommandFailsInsteadOfReturningAnObsoleteAuthorityToken()
            throws Exception {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String dsl = """
                relation APP-1 USES SVC-1 {
                  status: accepted;
                  confidence: 0.8;
                  provenance: manual;
                  x-command-source: relation-review;
                }
                """;
        String expected = repository.commitDsl(
                "draft", dsl, "system", "Seed relation");
        String competing = repository.commitDsl(
                "draft",
                dsl + "\n# concurrent metadata update\n",
                "bob",
                "Concurrent edit");

        assertThatThrownBy(() -> service.execute(
                context,
                expected,
                new UpsertRelation(
                        definition("accepted", 0.8, "manual"),
                        new CommandMetadata("stale-review"))))
                .isInstanceOfSatisfying(
                        BranchHeadConflictException.class,
                        conflict -> {
                            assertThat(conflict.getExpectedHeadCommit())
                                    .isEqualTo(expected);
                            assertThat(conflict.getActualHeadCommit())
                                    .isEqualTo(competing);
                        });
        assertThat(repository.getHeadCommit("draft")).isEqualTo(competing);
        assertThat(repository.getCommitCount("draft")).isEqualTo(2);
    }

    @Test
    void commandUsesTheBranchEmbeddedInRepositoryContext() throws Exception {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String reviewHead = repository.commitDsl(
                "review",
                """
                        relation APP-1 USES SVC-1 {
                          status: proposed;
                        }
                        """,
                "system",
                "Seed review branch");
        String draftHead = repository.commitDsl(
                "draft",
                "element APP-1 type Application {\n}\n",
                "system",
                "Seed draft branch");

        var result = service.execute(
                context,
                reviewHead,
                new RemoveRelation(
                        identity(),
                        new CommandMetadata(
                                "remove-9",
                                "No longer part of the reviewed architecture")));

        assertThat(result.branch()).isEqualTo("review");
        assertThat(result.changeKind()).isEqualTo(ChangeKind.REMOVED);
        assertThat(repository.getDslAtHead("review"))
                .doesNotContain("relation APP-1 USES SVC-1");
        assertThat(repository.getHeadCommit("draft")).isEqualTo(draftHead);
        assertThat(repository.getDslAtHead("draft"))
                .contains("element APP-1 type Application");
    }

    @Test
    void validatesExpectedHeadAndCommandMetadata() {
        ArchitectureRelationGitCommandService service = service();
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.execute(
                        context,
                        " ",
                        new RemoveRelation(
                                identity(),
                                new CommandMetadata("remove-1"))))
                .withMessageContaining("expectedHeadCommit");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommandMetadata("line-1\nline-2"))
                .withMessageContaining("must be one line");
        assertThat(new CommandMetadata(
                "decision-1",
                "  reviewed\nby   architecture  ").rationale())
                .isEqualTo("reviewed by architecture");
    }

    private ArchitectureRelationGitCommandService service() {
        repositoryFactory = new DslGitRepositoryFactory(null);
        return new ArchitectureRelationGitCommandService(
                repositoryFactory,
                new ArchitectureRelationDslTransformer(),
                new ExpectedHeadDslCommitter());
    }

    private static RelationIdentity identity() {
        return new RelationIdentity("APP-1", "USES", "SVC-1");
    }

    private static RelationDefinition definition(
            String status,
            Double confidence,
            String provenance) {
        return new RelationDefinition(
                identity(),
                status,
                confidence,
                provenance,
                Map.of("x-command-source", "relation-review"));
    }
}
