package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Admission captures evidence once; workers bind only authoritative persisted root records. */
@Service
public class CatalogueSnapshotService {
    private final TaxonomyNodeRepository repository;
    private final CatalogueOverlayService overlay;
    private final CatalogueRuntimePolicy policy;
    private final CatalogueSourceJournal sources;

    public CatalogueSnapshotService(TaxonomyNodeRepository repository, CatalogueOverlayService overlay,
                                    CatalogueRuntimePolicy policy, CatalogueSourceJournal sources) {
        this.repository = repository;
        this.overlay = overlay;
        this.policy = policy;
        this.sources = sources;
    }

    /** Singleton convenience; callers needing multiple roots must use one captureRoots call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public RootCatalogueSnapshot captureRoot(CatalogueSourceIdentity source, String rootCode) {
        return captureRoots(source, Set.of(rootCode)).getFirst();
    }

    /**
     * Freeze all requested roots under one import gate. The caller authorizes the architecture
     * operation identity; the independently retained catalogue provenance identifies the shared
     * official dataset actually read, never a fictitious mapping from an architecture Git commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<RootCatalogueSnapshot> captureRoots(CatalogueSourceIdentity source, Set<String> rootCodes) {
        policy.requireCurrentCatalogueAllowed();
        if (FrozenCatalogueContext.current() != null) {
            throw new IllegalStateException("Cannot capture current catalogue inside a frozen snapshot scope");
        }
        Objects.requireNonNull(source, "source");
        if (rootCodes == null || rootCodes.isEmpty()) throw new IllegalArgumentException("Required roots are empty");
        rootCodes.forEach(CatalogueRoot::require);
        var provenance = sources.lockForCapture();
        var metadata = overlay.getOverlayMetadata();
        RootCatalogueSnapshot.requireMatchingOverlay(provenance, metadata);
        return rootCodes.stream().sorted().map(root -> {
            var nodes = repository.findByTaxonomyRootOrderByLevelAscNameEnAsc(root).stream()
                    .map(node -> RootCatalogueSnapshot.Node.capture(node, overlay.getNodeMetadata(node.getCode()),
                            overlay.hasParentPatch(node.getCode()))).toList();
            return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root,
                    nodes, metadata, provenance);
        }).toList();
    }

    public FrozenCatalogueContext.Scope bind(CatalogueSourceIdentity expectedSource, Set<String> requiredRoots,
                                             Collection<RootCatalogueSnapshot> snapshots) {
        policy.requireConfiguredRoots(requiredRoots);
        return FrozenCatalogueContext.bind(new FrozenCatalogueView(expectedSource, requiredRoots, snapshots));
    }
}
