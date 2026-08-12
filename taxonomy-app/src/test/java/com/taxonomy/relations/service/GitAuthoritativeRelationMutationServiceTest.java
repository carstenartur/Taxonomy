package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RelationCommand;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitAuthoritativeRelationMutationServiceTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Mock
    private ArchitectureRelationGitCommandService commandService;

    @Mock
    private RelationDecisionProjectionService projectionService;

    @Mock
    private RelationProjectionRecoveryService recoveryService;

    @Test
    void commitsBeforeProjectingAndReturnsBothResults() throws Exception {
        RepositoryContext context = context();
        CommandResult authority = authority();
        RelationDecisionProjectionService.ProjectionResult projection =
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                        AUTHORITY,
                        true);
        when(commandService.execute(
                eq(context), eq(PREVIOUS), any(RelationCommand.class)))
                .thenReturn(authority);
        when(projectionService.project(
                eq(context), eq(authority), any(RelationCommand.class)))
                .thenReturn(projection);
        GitAuthoritativeRelationMutationService service = service();

        var result = service.upsert(
                context,
                PREVIOUS,
                definition(),
                new CommandMetadata("request-17", "reviewed"));

        assertThat(result.authority()).isSameAs(authority);
        assertThat(result.projection()).isSameAs(projection);
        InOrder order = inOrder(commandService, projectionService);
        order.verify(commandService).execute(
                eq(context), eq(PREVIOUS), any(RelationCommand.class));
        order.verify(projectionService).project(
                eq(context), eq(authority), any(RelationCommand.class));
        verifyNoInteractions(recoveryService);
    }

    @Test
    void persistsRecoveryAfterProjectionFailureAndExposesAuthority()
            throws Exception {
        RepositoryContext context = context();
        CommandResult authority = authority();
        IllegalStateException projectionFailure =
                new IllegalStateException("database unavailable");
        RecoveryRecord recovery = recovery();
        when(commandService.execute(
                eq(context), eq(PREVIOUS), any(RelationCommand.class)))
                .thenReturn(authority);
        when(projectionService.project(
                eq(context), eq(authority), any(RelationCommand.class)))
                .thenThrow(projectionFailure);
        when(recoveryService.recordPending(authority, projectionFailure))
                .thenReturn(recovery);
        GitAuthoritativeRelationMutationService service = service();

        assertThatThrownBy(() -> service.remove(
                context,
                PREVIOUS,
                definition().identity(),
                new CommandMetadata("request-17")))
                .isInstanceOfSatisfying(
                        ProjectionPendingException.class,
                        error -> {
                            assertThat(error.getAuthority()).isSameAs(authority);
                            assertThat(error.getRecovery()).isSameAs(recovery);
                            assertThat(error.isRecoveryPersisted()).isTrue();
                        })
                .hasMessageContaining(AUTHORITY);

        InOrder order = inOrder(
                commandService, projectionService, recoveryService);
        order.verify(commandService).execute(
                eq(context), eq(PREVIOUS), any(RelationCommand.class));
        order.verify(projectionService).project(
                eq(context), eq(authority), any(RelationCommand.class));
        order.verify(recoveryService).recordPending(
                authority, projectionFailure);
    }

    @Test
    void secondaryRecoveryFailureNeverMasksTheImmutableGitAuthority()
            throws Exception {
        RepositoryContext context = context();
        CommandResult authority = authority();
        IllegalStateException projectionFailure =
                new IllegalStateException("projection unavailable");
        IllegalStateException recoveryFailure =
                new IllegalStateException("recovery table unavailable");
        when(commandService.execute(
                eq(context), eq(PREVIOUS), any(RelationCommand.class)))
                .thenReturn(authority);
        when(projectionService.project(
                eq(context), eq(authority), any(RelationCommand.class)))
                .thenThrow(projectionFailure);
        when(recoveryService.recordPending(authority, projectionFailure))
                .thenThrow(recoveryFailure);

        assertThatThrownBy(() -> service().upsert(
                context,
                PREVIOUS,
                definition(),
                new CommandMetadata("request-17")))
                .isInstanceOfSatisfying(
                        ProjectionPendingException.class,
                        error -> {
                            assertThat(error.getAuthority()).isSameAs(authority);
                            assertThat(error.isRecoveryPersisted()).isFalse();
                            assertThat(error.getCause())
                                    .isSameAs(projectionFailure);
                            assertThat(error.getCause().getSuppressed())
                                    .containsExactly(recoveryFailure);
                        });
    }

    private GitAuthoritativeRelationMutationService service() {
        return new GitAuthoritativeRelationMutationService(
                commandService, projectionService, recoveryService);
    }

    private static RepositoryContext context() {
        return new RepositoryContext(
                "repo-a",
                "workspace-a",
                "review",
                "alice",
                RepositoryScope.WORKSPACE);
    }

    private static RelationDefinition definition() {
        return new RelationDefinition(
                new RelationIdentity("BP-1", "SUPPORTS", "CP-2"),
                "accepted",
                0.9,
                "manual-review",
                Map.of("x-command-source", "http"));
    }

    private static CommandResult authority() {
        return new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                PREVIOUS,
                AUTHORITY,
                ChangeKind.UPDATED,
                true,
                "request-17");
    }

    private static RecoveryRecord recovery() {
        Instant observed = Instant.parse("2026-08-12T12:00:00Z");
        return new RecoveryRecord(
                23L,
                "repo-a",
                "workspace-a",
                "review",
                PREVIOUS,
                AUTHORITY,
                "request-17",
                RecoveryStatus.PENDING,
                1,
                IllegalStateException.class.getName(),
                "database unavailable",
                observed,
                observed,
                null);
    }
}
