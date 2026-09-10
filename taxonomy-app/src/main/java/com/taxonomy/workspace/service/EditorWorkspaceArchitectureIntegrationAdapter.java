package com.taxonomy.workspace.service;

import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureCommandPort.CreateCheckpointCommand;
import com.taxonomy.editor.ArchitectureEditorService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/** Keeps concrete editor/journal/checkpoint types inside the workspace bounded context. */
@Service
public class EditorWorkspaceArchitectureIntegrationAdapter implements WorkspaceArchitectureIntegrationPort {

    private final ArchitectureEditorService editor;

    public EditorWorkspaceArchitectureIntegrationAdapter(ArchitectureEditorService editor) {
        this.editor = editor;
    }

    @Override
    public WorkspaceDocument read(RepositoryContext context, String commit) throws IOException {
        return document(editor.read(context, commit));
    }

    @Override
    public <T> T locked(RepositoryContext context, Function<WorkspaceDocument, T> action) throws IOException {
        Objects.requireNonNull(action, "action");
        return editor.integrationBoundary(context, document -> action.apply(document(document)));
    }

    @Override
    public State acceptIntegration(RepositoryContext context, State expected, CommandMetadata metadata,
                                   String fingerprint, java.util.List<com.taxonomy.dsl.command.ArchitectureCommand> commands,
                                   java.util.function.UnaryOperator<String> portfolioContribution,
                                   CommandMetadata checkpointMetadata) throws IOException {
        Context accepted = editor.acceptIntegration(context, editorContext(context, expected), editorMetadata(metadata),
                fingerprint, commands, portfolioContribution, editorMetadata(checkpointMetadata));
        return state(accepted);
    }

    @Override
    public Checkpoint checkpoint(RepositoryContext context, State expected,
                                 CommandMetadata checkpointMetadata) throws IOException {
        var accepted = editor.checkpoint(context,
                new CreateCheckpointCommand(editorContext(context, expected), editorMetadata(checkpointMetadata)));
        return new Checkpoint(state(accepted.context()));
    }

    private static WorkspaceDocument document(ArchitectureEditorService.Document document) {
        return new WorkspaceDocument(state(document.context()), document.dsl());
    }

    private static State state(Context context) {
        return new State(context.workspaceScopeKey(), context.commit(), context.revision());
    }

    private static Context editorContext(RepositoryContext context, State state) {
        Objects.requireNonNull(state, "state");
        Context editorContext = Context.of(context, state.commitId(), state.semanticRevision());
        if (!editorContext.workspaceScopeKey().equals(state.workspaceScopeKey())) {
            throw new IllegalArgumentException("Workspace scope does not match repository context");
        }
        return editorContext;
    }

    private static com.taxonomy.editor.ArchitectureCommandPort.Metadata editorMetadata(CommandMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return new com.taxonomy.editor.ArchitectureCommandPort.Metadata(
                metadata.commandId().toString(), metadata.correlationId().toString(),
                metadata.causationId().toString(), metadata.rationale());
    }
}
