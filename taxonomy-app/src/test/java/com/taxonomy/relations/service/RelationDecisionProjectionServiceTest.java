package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionContextMismatchException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionSourceException;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationDecisionProjectionServiceTest {

    private DslGitRepositoryFactory gitRepositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (gitRepositoryFactory != null) {
            gitRepositoryFactory.close();
        }
    }

    @Test
    void readsTheExactAuthoritativeUpsertStateBeforeWriting() throws Exception {
        RelationDecisionProjectionWriter writer =
                mock(RelationDecisionProjectionWriter.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        ArchitectureRelationGitCommandService commandService = commandService();
        DslGitRepository gitRepository =
                gitRepositoryFactory.resolveRepository(context);
        String expectedHead = gitRepository.commitDsl(
                "review", seedElements(), "system", "Seed architecture");
        UpsertRelation command = upsert(
                "proposal-17", "accepted", 0.9, "manual");
        CommandResult commandResult = commandService.execute(
                context, expectedHead, command);
        when(writer.write(any())).thenAnswer(invocation -> {
            ProjectionRequest request = invocation.getArgument(0);
            return new ProjectionResult(
                    ProjectionOutcome.CREATED,
                    request.authoritativeCommitId(),
                    request.relationPresent());
        });

        RelationDecisionProjectionService service =
                new RelationDecisionProjectionService(
                        writer, gitRepositoryFactory);
        var result = service.project(context, commandResult, command);

        ArgumentCaptor<ProjectionRequest> projected =
                ArgumentCaptor.forClass(ProjectionRequest.class);
        verify(writer).write(projected.capture());
        ProjectionRequest request = projected.getValue();
        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.CREATED);
        assertThat(request.repositoryId()).isEqualTo("repo-a");
        assertThat(request.workspaceId()).isEqualTo("workspace-a");
        assertThat(request.branch()).isEqualTo("review");
        assertThat(request.sourceCode()).isEqualTo("APP-1");
        assertThat(request.relationType()).isEqualTo(RelationType.USES);
        assertThat(request.targetCode()).isEqualTo("SVC-1");
        assertThat(request.relationPresent()).isTrue();
        assertThat(request.status()).isEqualTo("accepted");
        assertThat(request.confidence()).isEqualTo(0.9);
        assertThat(request.provenance()).isEqualTo("manual");
        assertThat(request.authoritativeCommitId())
                .isEqualTo(commandResult.authoritativeCommitId());
        assertThat(request.causationId()).isEqualTo("proposal-17");
    }

    @Test
    void removalHandsAnExplicitTombstoneToTheWriter() throws Exception {
        RelationDecisionProjectionWriter writer =
                mock(RelationDecisionProjectionWriter.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        ArchitectureRelationGitCommandService commandService = commandService();
        DslGitRepository gitRepository =
                gitRepositoryFactory.resolveRepository(context);
        String expectedHead = gitRepository.commitDsl(
                "draft",
                seedElements() + """
                        
                        relation APP-1 USES SVC-1 {
                          status: accepted;
                          confidence: 0.8;
                          provenance: central-seed;
                        }
                        """,
                "system",
                "Seed inherited relation");
        RemoveRelation command = new RemoveRelation(
                identity(),
                new CommandMetadata(
                        "remove-21",
                        "Removed in this workspace variant"));
        CommandResult commandResult = commandService.execute(
                context, expectedHead, command);
        when(writer.write(any())).thenAnswer(invocation -> {
            ProjectionRequest request = invocation.getArgument(0);
            return new ProjectionResult(
                    ProjectionOutcome.CREATED,
                    request.authoritativeCommitId(),
                    request.relationPresent());
        });

        RelationDecisionProjectionService service =
                new RelationDecisionProjectionService(
                        writer, gitRepositoryFactory);
        var result = service.project(context, commandResult, command);

        ArgumentCaptor<ProjectionRequest> projected =
                ArgumentCaptor.forClass(ProjectionRequest.class);
        verify(writer).write(projected.capture());
        ProjectionRequest request = projected.getValue();
        assertThat(commandResult.changeKind()).isEqualTo(ChangeKind.REMOVED);
        assertThat(result.relationPresent()).isFalse();
        assertThat(request.relationPresent()).isFalse();
        assertThat(request.status()).isNull();
        assertThat(request.confidence()).isNull();
        assertThat(request.provenance()).isNull();
        assertThat(request.authoritativeCommitId())
                .isEqualTo(commandResult.authoritativeCommitId());
    }

    @Test
    void mismatchedContextIsRejectedBeforeGitOrWriterAccess() {
        RelationDecisionProjectionWriter writer =
                mock(RelationDecisionProjectionWriter.class);
        DslGitRepositoryFactory gitFactory =
                mock(DslGitRepositoryFactory.class);
        RelationDecisionProjectionService service =
                new RelationDecisionProjectionService(writer, gitFactory);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        UpsertRelation command = upsert(
                "proposal-41", "accepted", 0.9, "manual");
        CommandResult forged = new CommandResult(
                "repo-b",
                "workspace-a",
                "draft",
                context.scope(),
                null,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ChangeKind.ADDED,
                true,
                "proposal-41");

        assertThatThrownBy(() -> service.project(context, forged, command))
                .isInstanceOf(ProjectionContextMismatchException.class)
                .hasMessageContaining("exact repository context");
        verifyNoInteractions(gitFactory, writer);
    }

    @Test
    void invalidAuthorityTokenIsRejectedBeforeGitOrWriterAccess() {
        RelationDecisionProjectionWriter writer =
                mock(RelationDecisionProjectionWriter.class);
        DslGitRepositoryFactory gitFactory =
                mock(DslGitRepositoryFactory.class);
        RelationDecisionProjectionService service =
                new RelationDecisionProjectionService(writer, gitFactory);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        UpsertRelation command = upsert(
                "proposal-42", "accepted", 0.9, "manual");
        CommandResult forged = new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                null,
                "not-a-commit",
                ChangeKind.ADDED,
                true,
                "proposal-42");

        assertThatThrownBy(() -> service.project(context, forged, command))
                .isInstanceOf(ProjectionContextMismatchException.class)
                .hasMessageContaining("full Git object ID");
        verifyNoInteractions(gitFactory, writer);
    }

    @Test
    void commitWithoutTheClaimedRelationIsRejectedBeforeWriterAccess()
            throws Exception {
        RelationDecisionProjectionWriter writer =
                mock(RelationDecisionProjectionWriter.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");
        commandService();
        DslGitRepository gitRepository =
                gitRepositoryFactory.resolveRepository(context);
        String authoritativeCommit = gitRepository.commitDsl(
                "draft", seedElements(), "system", "Seed without relation");
        UpsertRelation command = upsert(
                "proposal-43", "accepted", 0.9, "manual");
        CommandResult forged = new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                null,
                authoritativeCommit,
                ChangeKind.ADDED,
                true,
                "proposal-43");
        RelationDecisionProjectionService service =
                new RelationDecisionProjectionService(
                        writer, gitRepositoryFactory);

        assertThatThrownBy(() -> service.project(context, forged, command))
                .isInstanceOf(ProjectionSourceException.class)
                .hasMessageContaining("does not contain upserted relation");
        verifyNoInteractions(writer);
    }

    private ArchitectureRelationGitCommandService commandService() {
        gitRepositoryFactory = new DslGitRepositoryFactory(null);
        return new ArchitectureRelationGitCommandService(gitRepositoryFactory);
    }

    private static UpsertRelation upsert(
            String causationId,
            String status,
            Double confidence,
            String provenance) {
        return new UpsertRelation(
                new RelationDefinition(
                        identity(),
                        status,
                        confidence,
                        provenance,
                        Map.of("x-command-source", "projection-test")),
                new CommandMetadata(causationId));
    }

    private static RelationIdentity identity() {
        return new RelationIdentity("APP-1", "USES", "SVC-1");
    }

    private static String seedElements() {
        return """
                element APP-1 type Application {
                  title: "Payments";
                }

                element SVC-1 type Service {
                  title: "Payment service";
                }
                """;
    }
}
