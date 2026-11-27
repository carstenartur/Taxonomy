package com.taxonomy.analysis.usecase;

import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Binds the archived catalogue to the thread doing the work, then restores the enclosing scope. */
public final class AdmittedCatalogue {
    private AdmittedCatalogue() { }

    public static FrozenCatalogueContext.Scope open(CatalogueSnapshotService snapshots, AnalysisModelBinding binding,
                                                    WorkspaceContext workspace) {
        return open(snapshots, binding, workspace, false);
    }

    /** Local scoring reads the same exact-commit relation-enriched vectors in a servlet or a cluster worker. */
    public static FrozenCatalogueContext.Scope openForScoring(CatalogueSnapshotService snapshots,
            AnalysisModelBinding binding, WorkspaceContext workspace, LlmService llm) {
        if (binding == null || llm.isMockMode() || !LlmProvider.LOCAL_ONNX.id().equals(llm.getActiveProviderId())
                || !llm.isAvailable()) {
            return open(snapshots, binding, workspace, false);
        }
        if (workspace == null || binding.commit() == null || binding.state() == AnalysisModelBinding.State.MODEL_UNCOMMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkpoint this architecture before local ONNX analysis");
        }
        return open(snapshots, binding, workspace, true);
    }

    private static FrozenCatalogueContext.Scope open(CatalogueSnapshotService snapshots, AnalysisModelBinding binding,
            WorkspaceContext workspace, boolean localScoring) {
        if (binding == null) return null;
        var identity = source(binding, workspace);
        String revision = revision(binding);
        var archived = Objects.requireNonNull(snapshots, "Archived catalogue service")
                .captureRevision(identity, revision, Set.of());
        var roots = archived.stream().map(RootCatalogueSnapshot::rootCode).collect(Collectors.toSet());
        binding.scope().validateRoots(roots);
        if (!roots.containsAll(binding.rootCodes())) {
            throw new IllegalStateException("The archived catalogue does not contain the admitted roots");
        }
        if (localScoring) {
            archived = snapshots.prepareLocalScoring(identity, roots, archived, workspace.username())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Prepare the relation projection for this architecture checkpoint before local ONNX analysis"));
        }
        return snapshots.bind(identity, roots, archived);
    }

    static CatalogueSourceIdentity source(AnalysisModelBinding binding, WorkspaceContext workspace) {
        requireWorkspace(binding, workspace);
        return new CatalogueSourceIdentity(binding.repositoryId(), workspace.workspaceId(),
                binding.branch(), binding.commit(), binding.commit() == null ? binding.modelSha256() : null);
    }

    public static String revision(AnalysisModelBinding binding) {
        String revision = binding.analysisCatalogueRevision();
        if (revision == null || binding.catalogueDefinitionVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No retained catalogue generation is available for this analysis");
        }
        return revision;
    }

    public static void requireWorkspace(AnalysisModelBinding binding, WorkspaceContext workspace) {
        String workspaceKey = com.taxonomy.model.WorkspaceOverlayScope.keyFor(workspace.workspaceId());
        if (!binding.repositoryId().equals(workspace.repositoryId())
                || !binding.workspaceScopeKey().equals(workspaceKey)
                || !binding.branch().equals(workspace.currentBranch())) {
            throw new IllegalStateException("The recorded analysis model belongs to a different workspace or branch");
        }
    }
}
