package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.admbw.AdmbwFffmlvProfile;
import com.taxonomy.dsl.admbw.AdmbwPreparation;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.extension.api.plugin.ExtensionUnavailableException;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static com.taxonomy.composition.architecture.ArchitectureLifecycleService.*;

/** Reviewed native-reference choices join the existing durable architecture operation, then checkpoint. */
@Service
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
public class ArchitectureAdmbwPreparationService {
    private final ArchitectureEditorService editor;
    private final ArchitectureLifecycleService lifecycle;
    private final ExtensionCatalog extensions;
    private final TransactionTemplate transactions;
    private com.taxonomy.dsl.model.CatalogueDefinitionResolver catalogues = com.taxonomy.dsl.model.CatalogueDefinitionResolver.standard();

    @org.springframework.beans.factory.annotation.Autowired
    public void setCatalogueDefinitionResolver(com.taxonomy.dsl.model.CatalogueDefinitionResolver catalogues) {
        this.catalogues = Objects.requireNonNull(catalogues);
    }

    public ArchitectureAdmbwPreparationService(ArchitectureEditorService editor, ArchitectureLifecycleService lifecycle,
            ExtensionCatalog extensions, PlatformTransactionManager transactions) {
        this.editor = editor;
        this.lifecycle = lifecycle;
        this.extensions = extensions;
        this.transactions = new TransactionTemplate(transactions);
    }

    public record Request(Context context, Metadata metadata, AdmbwPreparation.Selection selection,
                          String basisSha256, String previewFingerprint,
                          boolean relationshipReviewed, boolean derivationReviewed) {
        public Request {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(selection, "selection");
            if (basisSha256 == null || !basisSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Inspect the exact QEA basis before preparing a delivery");
        }
    }

    public record Preview(Context context, String fingerprint, String viewId, String requirementId,
                          int requirementVersion, String requirementContentHash, String basisSha256,
                          List<String> notes, AdmbwFffmlvProfile.Report assessment,
                          List<AdmbwFffmlvProfile.Issue> qeaIssues, List<ArchitectureSemanticPatch.BlockChange> changes) { }

    public AdmbwQeaExport.Basis inspect(RepositoryContext context, byte[] bytes) {
        lifecycle.authorize(context, true);
        return inspect(bytes);
    }

    public Preview preview(RepositoryContext context, Request request, byte[] bytes) throws IOException {
        lifecycle.authorize(context, true);
        requireContext(context, request.context());
        var document = editor.read(context, null);
        requireStand(request.context(), document.context());
        if (!"READY".equals(document.projectionState()))
            throw problem("VERSION_CHANGED", "Refresh or reconcile the current architecture before preparing a delivery");
        var basis = basis(request, bytes);
        var prepared = prepare(document.dsl(), request, basis);
        return new Preview(document.context(), previewFingerprint(request, prepared), prepared.viewId(),
                prepared.requirementId(), prepared.requirementVersion(), prepared.requirementContentHash(),
                prepared.basisSha256(), prepared.notes(), prepared.assessment(), ArchitectureEditorExportController.qeaIssues(prepared.assessment()),
                ArchitectureSemanticPatch.between(document.dsl(), prepared.dsl()));
    }

    public Publication accept(RepositoryContext context, Request request, byte[] bytes) throws IOException {
        lifecycle.authorize(context, true);
        requireContext(context, request.context());
        if (!request.relationshipReviewed() || !request.derivationReviewed())
            throw problem("ADMBW_REVIEW_REQUIRED", "Review both the service/capability relationship and the requirement derivation");
        if (request.previewFingerprint() == null || !request.previewFingerprint().matches("[a-f0-9]{64}"))
            throw problem("PREVIEW_CHANGED", "Preview the exact selected architecture and references before accepting");
        // The byte identity is part of the durable request fingerprint. A retry after a successful
        // commit does not need to open the plugin or reapply a plan to a newer source revision.
        requireBasisHash(request, bytes);
        String intent = fingerprint("admbw-preparation-v1", request.context(), request.metadata(), selectionFingerprint(request.selection()),
                request.basisSha256(), request.previewFingerprint(), request.relationshipReviewed(), request.derivationReviewed());
        Metadata checkpoint = checkpointMetadata(request.metadata());
        Context accepted;
        try {
            accepted = transactions.execute(status -> {
                try {
                    return editor.acceptIntegration(context, request.context(), request.metadata(), intent, List.of(), source -> {
                        var prepared = prepare(source, request, basis(request, bytes));
                        if (!Objects.equals(request.previewFingerprint(), previewFingerprint(request, prepared)))
                            throw problem("PREVIEW_CHANGED", "The source, choices or evidence changed; review a fresh preview");
                        return prepared.dsl();
                    }, checkpoint);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        return lifecycle.finish(context, accepted, checkpoint);
    }

    private AdmbwPreparation.Prepared prepare(String source, Request request, AdmbwQeaExport.Basis basis) {
        if (basis.roots().stream().noneMatch(root -> root.guid().equalsIgnoreCase(request.selection().rootPackageGuid())))
            throw problem("ADMBW_REFERENCE_MISSING", "Select an actual model root from this QEA basis");
        var references = basis.references().stream().map(ref -> new AdmbwPreparation.Reference(ref.guid(), ref.stereotype(),
                ref.name(), ref.notes(), ref.packagePath(), ref.rootGuid(), ref.ownerGuid())).toList();
        return new AdmbwPreparation(catalogues).prepare(source, request.selection(),
                new AdmbwPreparation.Basis(basis.sha256(), basis.projectGuid(), references));
    }

    private static String previewFingerprint(Request request, AdmbwPreparation.Prepared prepared) {
        return fingerprint("admbw-preparation-preview-v1", request.context(), selectionFingerprint(request.selection()), request.basisSha256(),
                request.metadata().rationale(), StableIdentityHash.sha256(prepared.dsl()));
    }

    // Free text may contain Java record delimiters. Hash each field with its own length.
    private static String selectionFingerprint(AdmbwPreparation.Selection choice) {
        return fingerprint(choice.projectCode(), choice.rootPackageGuid(), choice.serviceId(), choice.capabilityGuid(),
                choice.requirementId(), choice.requirementType(), choice.subjectGuid(), choice.constraintText(), choice.evidenceQuote());
    }

    private AdmbwQeaExport.Basis basis(Request request, byte[] bytes) {
        requireBasisHash(request, bytes);
        return inspect(bytes);
    }

    private static void requireBasisHash(Request request, byte[] bytes) {
        if (!request.basisSha256().equals(hash(bytes)))
            throw problem("ADMBW_BASIS_CHANGED", "The uploaded QEA basis changed; inspect it and preview again");
    }

    private AdmbwQeaExport.Basis inspect(byte[] bytes) {
        String hash = hash(bytes);
        try (var lease = extensions.acquire(AdmbwQeaExport.KEY, IntegrationConnectorExtension.class)) {
            if (!(lease.extension() instanceof AdmbwQeaExport inspector) || !inspector.supportsBasisInspection())
                throw unavailable();
            var basis = inspector.inspectAdmbwBasis(bytes);
            if (basis == null || !hash.equals(basis.sha256()))
                throw problem("ADMBW_BASIS_CHANGED", "The QEA inspection does not describe the uploaded bytes");
            return basis;
        } catch (ExtensionUnavailableException unavailable) { throw unavailable(); }
    }

    private static com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem unavailable() {
        return problem("ADMBW_INSPECTOR_UNAVAILABLE", "Install a Sparx plugin with ADMBw basis inspection support");
    }

    private static String hash(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > AdmbwQeaExport.MAX_BASIS_BYTES)
            throw new IllegalArgumentException("Supply a nonempty QEA basis up to 32 MiB");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
