package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions.AppliedGeneration;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.planning.ArchitectureBrief;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.TaxonomyResponsibilityLookup;
import com.taxonomy.editor.ArchitectureResponsibility;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Bind the authenticated selection before enqueueing, while the request's routing and access decisions are available. */
@Service
@ConditionalOnFeature({"architecture", "analysis"})
public class SelectedArchitectureAdmissionModel implements AnalysisAdmissionModelPort, TaxonomyResponsibilityLookup {
    private final WorkspaceResolver workspaces;
    private final ArchitectureEditorService editor;
    private final RetainedCatalogueDefinitions definitions;

    public SelectedArchitectureAdmissionModel(WorkspaceResolver workspaces, ArchitectureEditorService editor,
            RetainedCatalogueDefinitions definitions) {
        this.workspaces = workspaces;
        this.editor = editor;
        this.definitions = definitions;
    }

    @Override
    public AnalysisModelBinding capture(WorkspaceContext workspace, String expectedCommit, Long expectedRevision,
                                        AnalysisScope scope) {
        RepositoryContext context = workspaces.resolveCurrentRepositoryContextForRead();
        if (workspace == null || !context.username().equals(workspace.username())
                || !context.repositoryId().equals(workspace.repositoryId())
                || !Objects.equals(context.workspaceId(), workspace.workspaceId())
                || !context.branch().equals(workspace.currentBranch())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture context has changed");
        }

        var version = select(context, expectedCommit, expectedRevision);
        var document = version.document();
        var selected = document.context();
        var model = new ArchitectureDslCommands().model(document.dsl());
        var brief = ArchitectureBrief.read(model);
        String catalogueRevision = model.getCatalogueRevision();
        if (catalogueRevision != null && catalogueRevision.isBlank()) catalogueRevision = null;
        var execution = retained(catalogueRevision);
        CatalogueDefinition definition = execution.map(AppliedGeneration::definition).orElse(null);
        AnalysisScope requested = AnalysisScope.orDefault(scope);
        Set<String> roots = Set.of();
        if (definition != null) {
            requested.validateRoots(definition.rootCodes());
            roots = definition.rootCodes().stream().filter(requested::selects).collect(Collectors.toSet());
        }
        var references = brief.references().stream().map(ref -> new AnalysisModelBinding.Reference(ref.id(),
                ref.sourceId(), ref.sourceVersionId(), ref.sourceFragmentId(), ref.values())).toList();
        var profile = ProfilePreview.decision(brief);
        String profileProblem = brief.problem() != null ? brief.problem()
                : brief.present() && profile != ProfilePreview.Decision.ASSIGNED ? "REFERENCE_PROFILE_" + profile.name() : null;
        boolean validRules = brief.problem() == null && profile != ProfilePreview.Decision.CONFLICTING_EDITIONS;
        return new AnalysisModelBinding(state(version.published(), catalogueRevision, definition, brief, profileProblem), selected.repositoryId(), selected.workspaceScopeKey(),
                selected.branch(), version.commit(), version.revision(), document.source(), StableIdentityHash.sha256(document.dsl()),
                catalogueRevision, definition == null ? null : definition.version(), roots, requested,
                brief.version(), brief.fields(), references,
                validRules ? brief.excludedRelationTypes() : Set.of(), profileProblem, execution.map(AppliedGeneration::revision).orElse(null));
    }

    @Override
    public Map<String, Assignment> resolve(AnalysisModelBinding binding) {
        if (binding == null || binding.commit() == null && binding.semanticRevision() == null) return Map.of();
        try {
            return responsibilities(binding.repositoryId(), binding.workspaceScopeKey(), binding.branch(), binding.commit(),
                    binding.semanticRevision(), binding.modelSha256(), binding.analysisCatalogueRevision(), false);
        } catch (ResponseStatusException failure) {
            // Reports remain readable when their source is no longer available to this caller.
            if (failure.getStatusCode().equals(HttpStatus.NOT_FOUND) || failure.getStatusCode().equals(HttpStatus.CONFLICT))
                return Map.of();
            throw failure;
        }
    }

    @Override
    public Map<String, Assignment> current() {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        return assignments(select(context, null, null).document(), null, true);
    }

    Map<String, Assignment> responsibilities(String repository, String workspaceScope, String branch, String commit,
            Long revision, String hash, String catalogueRevision, boolean live) {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        if (!context.repositoryId().equals(repository) || !context.branch().equals(branch)
                || !WorkspaceOverlayScope.keyFor(context.workspaceId()).equals(workspaceScope)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture context has changed");
        }
        if (live) {
            if (commit != null || revision != null || hash != null || catalogueRevision != null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A live selection cannot include historical coordinates");
            return assignments(select(context, null, null).document(), null, true);
        }
        if (hash == null || !hash.matches("[0-9a-f]{64}") || commit == null && revision == null
                || commit != null && !commit.matches("[0-9a-f]{40}") || revision != null && revision < 0
                || catalogueRevision != null && (catalogueRevision.isBlank() || catalogueRevision.length() > 128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The recorded architecture version and content hash are required");
        // The commit recorded on an unpublished workspace revision may be its Git parent. Its own
        // retained journal bytes and content digest are the authority, even after another checkpoint.
        ArchitectureEditorService.Document document = null;
        if (revision != null) {
            try {
                document = read(context, null, revision);
                requireSelectedContext(context, document);
                if (!"WORKSPACE_REVISION".equals(document.source()) || revision.longValue() != document.context().revision())
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The recorded workspace revision is unavailable");
            } catch (ResponseStatusException missing) {
                if (commit == null || !missing.getStatusCode().equals(HttpStatus.NOT_FOUND)) throw missing;
            }
        }
        if (commit != null && (document == null || !hash.equals(StableIdentityHash.sha256(document.dsl()))))
            document = select(context, commit, null).document();
        if (document == null || !hash.equals(StableIdentityHash.sha256(document.dsl())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The retained architecture differs from the recorded model");
        return assignments(document, catalogueRevision, false);
    }

    private Map<String, Assignment> assignments(ArchitectureEditorService.Document document, String executionCatalogue,
                                               boolean live) {
        var model = new ArchitectureDslCommands().model(document.dsl());
        String revision = model.getCatalogueRevision();
        if (revision != null && executionCatalogue != null && !revision.equals(executionCatalogue))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The architecture and decision catalogue revisions differ");
        if (revision == null) revision = executionCatalogue;
        boolean assigned = model.getElements().stream().anyMatch(element ->
                element.getExtensions().containsKey(ArchitectureOrganization.RESPONSIBLE_ID)
                        || element.getExtensions().containsKey("x-responsible-organization"));
        if (!assigned) return Map.of();
        if (live && revision == null) revision = retained(null).map(AppliedGeneration::revision).orElse(null);
        try {
            return ArchitectureResponsibility.forAnalysis(model, definitions.analysisParents(model, revision));
        } catch (java.util.NoSuchElementException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The recorded decision catalogue is unavailable", unavailable);
        } catch (IllegalStateException unreadable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot read the recorded decision catalogue", unreadable);
        }
    }

    private record Selection(ArchitectureEditorService.Document document, String commit, Long revision, boolean published) {}

    ProfilePreview preview(String repository, String workspaceScope, String branch, String expectedCommit,
                           Long expectedRevision, boolean mayEdit) {
        var context = workspaces.resolveCurrentRepositoryContextForRead();
        if (!context.repositoryId().equals(repository) || !context.branch().equals(branch)
                || !WorkspaceOverlayScope.keyFor(context.workspaceId()).equals(workspaceScope)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture context has changed");
        }
        var selected = select(context, expectedCommit, expectedRevision);
        var document = selected.document();
        var brief = ArchitectureBrief.read(new ArchitectureDslCommands().model(document.dsl()));
        return new ProfilePreview(repository, workspaceScope, branch, selected.commit(), selected.revision(),
                document.source(), com.taxonomy.editor.ArchitectureEditorProjection.editable(document, mayEdit) && brief.problem() == null,
                brief, ProfilePreview.decision(brief));
    }

    private Selection select(RepositoryContext context, String expectedCommit, Long expectedRevision) {
        if (expectedCommit != null && !expectedCommit.matches("[0-9a-f]{40}")
                || expectedRevision != null && expectedRevision < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid requested architecture version");
        }
        var document = read(context, expectedRevision == null ? expectedCommit : null, expectedRevision);
        var selected = document.context();
        requireSelectedContext(context, document);
        boolean semantic = "WORKSPACE_REVISION".equals(document.source());
        if ((!semantic && !"GIT_CHECKPOINT".equals(document.source()))
                || !"READY".equals(document.projectionState()) && !"HISTORICAL".equals(document.projectionState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture version is unavailable");
        }
        Long revision = semantic ? selected.revision() : null;
        String commit = selected.commit();
        boolean published = commit != null && (!semantic || document.checkpointRevision() == selected.revision());
        // A reconciliation can advance the workspace's next Git parent before publishing its checkpoint.
        // For historical journal bytes only a completed receipt identifies their published Git object.
        if (semantic && "HISTORICAL".equals(document.projectionState())) {
            commit = editor.historicalCheckpoint(context, document, expectedCommit);
            published = commit != null;
        }
        if (expectedCommit != null && !expectedCommit.equals(commit)
                || expectedRevision != null && !expectedRevision.equals(revision)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture version does not match the request");
        }

        return new Selection(document, commit, revision, published);
    }

    private static void requireSelectedContext(RepositoryContext context, ArchitectureEditorService.Document document) {
        var selected = document.context();
        if (!context.username().equals(selected.actor()) || !context.repositoryId().equals(selected.repositoryId())
                || !context.branch().equals(selected.branch())
                || !WorkspaceOverlayScope.keyFor(context.workspaceId()).equals(selected.workspaceScopeKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture context has changed");
        }
    }

    private ArchitectureEditorService.Document read(RepositoryContext context, String commit, Long revision) {
        try {
            return editor.read(context, commit, revision);
        } catch (ArchitectureDslCommands.CommandProblem failure) {
            var status = "NOT_FOUND".equals(failure.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot read the selected architecture version", failure);
        }
    }

    private Optional<AppliedGeneration> retained(String projectRevision) {
        try {
            return definitions.appliedGeneration(projectRevision);
        } catch (IllegalStateException unreadable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot read the retained project catalogue", unreadable);
        }
    }

    private static AnalysisModelBinding.State state(boolean published, String projectRevision, CatalogueDefinition definition,
                                                    ArchitectureBrief.Snapshot brief, String profileProblem) {
        if (projectRevision == null || definition == null) return AnalysisModelBinding.State.CATALOGUE_UNRESOLVED;
        if (!published) return AnalysisModelBinding.State.MODEL_UNCOMMITTED;
        if (profileProblem != null) return AnalysisModelBinding.State.PROFILE_UNRESOLVED;
        if (!brief.present()) return AnalysisModelBinding.State.BRIEF_UNASSIGNED;
        return AnalysisModelBinding.State.RESOLVED;
    }
}
