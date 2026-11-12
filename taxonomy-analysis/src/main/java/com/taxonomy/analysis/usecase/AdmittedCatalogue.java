package com.taxonomy.analysis.usecase;

import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
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
        if (binding == null) return null;
        requireWorkspace(binding, workspace);
        var identity = new CatalogueSourceIdentity(binding.repositoryId(), workspace.workspaceId(),
                binding.branch(), binding.commit(), binding.commit() == null ? binding.modelSha256() : null);
        String revision = revision(binding);
        var archived = Objects.requireNonNull(snapshots, "Archived catalogue service")
                .captureRevision(identity, revision, Set.of());
        var roots = archived.stream().map(RootCatalogueSnapshot::rootCode).collect(Collectors.toSet());
        binding.scope().validateRoots(roots);
        if (!roots.containsAll(binding.rootCodes())) {
            throw new IllegalStateException("The archived catalogue does not contain the admitted roots");
        }
        return snapshots.bind(identity, roots, archived);
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
