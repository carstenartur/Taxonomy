package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.export.ProjectDataSeed;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.workspace.service.ProjectCatalogueSeed;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static com.taxonomy.composition.architecture.ArchitectureLifecycleService.*;

@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
@RequestMapping("/api/architecture/editor/project-data")
public class ProjectDataImportController {
    private final LifecycleHttpContext contexts;
    private final ArchitectureLifecycleService lifecycle;
    private final ArchitectureEditorService editor;
    private final ProjectDataSeed datasets;
    private final TransactionTemplate transaction;

    public ProjectDataImportController(LifecycleHttpContext contexts, ArchitectureLifecycleService lifecycle,
            ArchitectureEditorService editor, ProjectDataSeed datasets, PlatformTransactionManager transactions) {
        this.contexts = contexts;
        this.lifecycle = lifecycle;
        this.editor = editor;
        this.datasets = datasets;
        this.transaction = new TransactionTemplate(transactions);
    }

    public record Request(Context context, Metadata metadata, String provider, String datasetHash) {
        public Request {
            java.util.Objects.requireNonNull(context);
            java.util.Objects.requireNonNull(metadata);
            ProjectCatalogueSeed.requireProvider(provider);
        }
    }
    public record Preview(Context context, String datasetHash, int added, int existing) {}

    @PostMapping("/preview")
    @Operation(summary="Preview additive import of an installed project dataset into the selected workspace")
    public ResponseEntity<Preview> preview(@RequestBody Request request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        LifecycleHttpContext.requireRevision(request.context(), ifMatch, null);
        var context = contexts.resolveForRead();
        lifecycle.authorize(context, true);
        requireContext(context, request.context());
        var document = editor.read(context, null);
        requireStand(request.context(), document.context());
        var plan = datasets.planImport(document.dsl(), request.provider());
        return LifecycleHttpContext.response(document.context()).body(new Preview(document.context(), plan.datasetHash(),
                plan.added(), plan.existing()));
    }

    @PostMapping("/import")
    @Operation(summary="Import the reviewed dataset into this workspace and checkpoint its original identities")
    public ResponseEntity<Publication> apply(@RequestBody Request request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) throws IOException {
        LifecycleHttpContext.requireRevision(request.context(), ifMatch, null);
        if (request.datasetHash() == null || !request.datasetHash().matches("[0-9a-f]{64}"))
            throw problem("PREVIEW_CHANGED", "Preview the selected project dataset before importing it");
        var context = contexts.resolveForRead();
        lifecycle.authorize(context, true);
        requireContext(context, request.context());
        var checkpoint = checkpointMetadata(request.metadata());
        String intent = fingerprint("project-data-import-v1", request.context(), request.metadata(), request.provider(), request.datasetHash());
        // Provider code runs before the workspace transaction. A retry after a completed import
        // reuses its journal receipt even if the plugin is no longer installed.
        var observed = editor.read(context, null);
        var plan = observed.context().equals(request.context()) ? datasets.planImport(observed.dsl(), request.provider()) : null;
        if (plan != null && !plan.datasetHash().equals(request.datasetHash()))
            throw problem("PREVIEW_CHANGED", "The dataset changed since the preview");
        Context accepted;
        try {
            if (plan != null && plan.added() == 0) {
                var unchanged = transaction.execute(status -> {
                    try {
                        return editor.collaborationBoundary(context, current -> {
                            requireStand(request.context(), current.context());
                            if (!"READY".equals(current.projectionState()) || !current.dsl().equals(observed.dsl()))
                                throw problem("VERSION_CHANGED", "The workspace changed since the preview");
                            return new Publication(current.context(), "UNCHANGED", null, null, null);
                        });
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
                return LifecycleHttpContext.response(unchanged.context()).body(unchanged);
            }
            accepted = transaction.execute(status -> {
                try {
                    return editor.acceptIntegration(context, request.context(), request.metadata(), intent, List.of(), source -> {
                        if (plan == null || !source.equals(observed.dsl()))
                            throw problem("VERSION_CHANGED", "The workspace changed since the preview");
                        return plan.source();
                    }, checkpoint);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        var result = lifecycle.finish(context, accepted, checkpoint);
        return LifecycleHttpContext.response(result.context()).body(result);
    }
}
