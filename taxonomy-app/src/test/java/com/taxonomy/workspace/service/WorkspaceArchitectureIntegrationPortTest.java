package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WorkspaceArchitectureIntegrationPortTest {

    @Test
    void stateRequiresAConcreteWorkspaceScopeAndNonNegativeRevision() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State(null, "commit", 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State("   ", "commit", 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkspaceArchitectureIntegrationPort.State("workspace", "commit", -1));

        WorkspaceArchitectureIntegrationPort.State state =
                new WorkspaceArchitectureIntegrationPort.State("workspace", null, 0);

        assertThat(state.workspaceScopeKey()).isEqualTo("workspace");
        assertThat(state.commitId()).isNull();
        assertThat(state.semanticRevision()).isZero();
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
                        commandId, correlationId, causationId, "reason\u0000detail"));

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

        WorkspaceArchitectureIntegrationPort.WorkspaceDocument document =
                new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(state, "dsl");
        WorkspaceArchitectureIntegrationPort.Checkpoint checkpoint =
                new WorkspaceArchitectureIntegrationPort.Checkpoint(state);

        assertThat(document.state()).isSameAs(state);
        assertThat(document.dsl()).isEqualTo("dsl");
        assertThat(checkpoint.state()).isSameAs(state);
    }
}
