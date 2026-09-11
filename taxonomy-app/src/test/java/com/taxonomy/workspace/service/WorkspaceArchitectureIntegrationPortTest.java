package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WorkspaceArchitectureIntegrationPortTest {

    @Test
    void stateRequiresAConcreteWorkspaceScopeAndNonNegativeRevisionAndNormalizesIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State(null, "commit", 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State("   ", "commit", 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State("workspace", "commit", -1));

        WorkspaceArchitectureIntegrationPort.State withoutCommit =
                new WorkspaceArchitectureIntegrationPort.State("  workspace  ", null, 0);
        WorkspaceArchitectureIntegrationPort.State trimmedCommit =
                new WorkspaceArchitectureIntegrationPort.State("workspace", "  commit  ", 1);
        WorkspaceArchitectureIntegrationPort.State blankCommit =
                new WorkspaceArchitectureIntegrationPort.State("workspace", "   ", 2);

        assertThat(withoutCommit.workspaceScopeKey()).isEqualTo("workspace");
        assertThat(withoutCommit.commitId()).isNull();
        assertThat(withoutCommit.semanticRevision()).isZero();
        assertThat(trimmedCommit.commitId()).isEqualTo("commit");
        assertThat(blankCommit.commitId()).isNull();
    }

    @Test
    void commandMetadataRequiresStableIdsAndAUsefulRationale() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();

        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        null, correlationId, causationId, "reason"));
        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, null, causationId, "reason"));
        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, null, "reason"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "   "));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "x".repeat(1001)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId,
                        "reason" + " ".repeat(1001) + "detail"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "reason\u0000detail"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "reason\tdetail"))
                .withMessage("Rationale must not contain control characters");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "reason\ndetail"))
                .withMessage("Rationale must not contain control characters");

        WorkspaceArchitectureIntegrationPort.CommandMetadata metadata =
                new WorkspaceArchitectureIntegrationPort.CommandMetadata(
                        commandId, correlationId, causationId, "  reviewed   integration  ");

        assertThat(metadata.commandId()).isEqualTo(commandId);
        assertThat(metadata.correlationId()).isEqualTo(correlationId);
        assertThat(metadata.causationId()).isEqualTo(causationId);
        assertThat(metadata.rationale()).isEqualTo("reviewed integration");
    }

    @Test
    void documentAndCheckpointRequireStateAndDocumentRequiresCanonicalDsl() {
        WorkspaceArchitectureIntegrationPort.State state =
                new WorkspaceArchitectureIntegrationPort.State("workspace", "commit", 7);

        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(null, "dsl"));
        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(state, null));
        assertThatNullPointerException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.Checkpoint(null));

        WorkspaceArchitectureReadPort.WorkspaceDocument document =
                new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(state, "dsl");
        WorkspaceArchitectureIntegrationPort.Checkpoint checkpoint =
                new WorkspaceArchitectureIntegrationPort.Checkpoint(state);

        assertThat(document.state()).isSameAs(state);
        assertThat(document.dsl()).isEqualTo("dsl");
        assertThat(checkpoint.state()).isSameAs(state);
    }
}
