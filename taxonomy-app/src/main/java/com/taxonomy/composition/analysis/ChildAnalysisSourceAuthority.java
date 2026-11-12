package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.operation.AnalysisAdapterStore.Operation;
import com.taxonomy.analysis.operation.AnalysisChildSourceAuthority;
import com.taxonomy.analysis.operation.AnalysisNodeReceipts;
import com.taxonomy.analysis.usecase.AnalysisChildAssessment;
import com.taxonomy.composition.architecture.ArchitectureFocusSuggestionService;
import com.taxonomy.composition.architecture.ArchitectureSourceFocusDtos;
import com.taxonomy.composition.architecture.ArchitectureSourceFocusService;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnFeature({"analysis"})
public class ChildAnalysisSourceAuthority implements AnalysisChildSourceAuthority {
    // A valid child points directly to its root. Restored cyclic references must fail closed.
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();
    private final WorkspaceResolver workspaces;
    private final ObjectProvider<AnalysisChildAssessment> assessments;
    private final ObjectProvider<ArchitectureFocusSuggestionService> editor;
    private final ObjectProvider<ArchitectureSourceFocusService> sources;
    private final ObjectMapper json;

    public ChildAnalysisSourceAuthority(WorkspaceResolver workspaces, ObjectProvider<AnalysisChildAssessment> assessments,
            ObjectProvider<ArchitectureFocusSuggestionService> editor, ObjectProvider<ArchitectureSourceFocusService> sources,
            ObjectMapper json) {
        this.workspaces = workspaces;
        this.assessments = assessments;
        this.editor = editor;
        this.sources = sources;
        this.json = json;
    }

    @Override public boolean current(Operation operation) {
        if (operation.resultJson() == null) return true;
        return switch (operation.kind()) {
            case "NODE_CHILDREN", "LEAF_JUSTIFICATION", "FOCUS_INVESTIGATION" -> {
                int depth = DEPTH.orElse(0);
                yield depth < 2 && ScopedValue.where(DEPTH, depth + 1).call(() -> revalidate(operation));
            }
            default -> true;
        };
    }

    private boolean revalidate(Operation operation) {
        var selected = workspaces.resolveCurrentRepositoryContextForRead();
        var stored = json.readTree(operation.resultJson());
        try {
            if ("FOCUS_INVESTIGATION".equals(operation.kind())) {
                var hash = stored.path("sourceHash").asString();
                if (stored.hasNonNull("context"))
                    return editor.getObject().editorSourceCurrent(selected, json.treeToValue(stored.get("context"), Context.class), hash);
                return sources.getObject().sourceCurrent(selected, json.treeToValue(stored.get("source"), ArchitectureSourceFocusDtos.Source.class), hash);
            }
            var workspace = new WorkspaceContext(selected.username(), selected.workspaceId(), selected.branch(), selected.repositoryId());
            return assessments.getObject().sourceCurrent(json.treeToValue(stored.path("source"), AnalysisNodeReceipts.Source.class),
                    "NODE_CHILDREN".equals(operation.kind()), selected.username(), workspace);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
