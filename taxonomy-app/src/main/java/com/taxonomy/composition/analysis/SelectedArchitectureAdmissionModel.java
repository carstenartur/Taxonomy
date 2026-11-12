package com.taxonomy.composition.analysis;

import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions.AppliedGeneration;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.planning.ArchitectureBrief;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Bind the authenticated selection before enqueueing, while the request's routing and access decisions are available. */
@Service
@ConditionalOnFeature({"architecture", "analysis"})
public class SelectedArchitectureAdmissionModel implements AnalysisAdmissionModelPort {
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
        if (!context.username().equals(selected.actor()) || !context.repositoryId().equals(selected.repositoryId())
                || !context.branch().equals(selected.branch())
                || !WorkspaceOverlayScope.keyFor(context.workspaceId()).equals(selected.workspaceScopeKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected architecture context has changed");
        }
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
