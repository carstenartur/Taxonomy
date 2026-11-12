package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.IOException;
import java.util.Comparator;
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
    private final RetainedCatalogueDefinitions definitions;

    public CatalogueSnapshotService(TaxonomyNodeRepository repository, CatalogueOverlayService overlay,
                                    CatalogueRuntimePolicy policy, CatalogueSourceJournal sources) {
        this(repository, overlay, policy, sources, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CatalogueSnapshotService(TaxonomyNodeRepository repository, CatalogueOverlayService overlay,
            CatalogueRuntimePolicy policy, CatalogueSourceJournal sources, RetainedCatalogueDefinitions definitions) {
        this.definitions = definitions;
        this.repository = repository;
        this.overlay = overlay;
        this.policy = policy;
        this.sources = sources;
    }

    /** The coordinator selects from this definition before captureRoots verifies the locked generation. */
    public CatalogueDefinition currentDefinition() {
        policy.requireCurrentCatalogueAllowed();
        return definitions == null ? CatalogueDefinition.standard() : definitions.current();
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
        var provenance = sources.lockForCapture();
        var definition = definitions == null ? CatalogueDefinition.standard() : definitions.forGeneration(provenance);
        if (!definition.rootCodes().containsAll(rootCodes))
            throw new IllegalArgumentException("Unknown root in the retained catalogue definition");
        var spec = definition.spec().equals(CatalogueDefinition.standard().spec()) ? null : definition.spec();
        var metadata = overlay.getOverlayMetadata();
        RootCatalogueSnapshot.requireMatchingOverlay(provenance, metadata);
        return rootCodes.stream().sorted().map(root -> {
            var nodes = repository.findByTaxonomyRootOrderByLevelAscNameEnAsc(root).stream()
                    .map(node -> RootCatalogueSnapshot.Node.capture(node, overlay.getNodeMetadata(node.getCode()),
                            overlay.hasParentPatch(node.getCode()))).toList();
            return new RootCatalogueSnapshot(spec == null ? RootCatalogueSnapshot.SCHEMA_VERSION
                    : RootCatalogueSnapshot.CONFIGURED_SCHEMA_VERSION, source, root, nodes, metadata, provenance, null, spec);
        }).toList();
    }

    /**
     * Rebuild the given journal revision from its verified workbook and overlay bytes.
     * An empty selection includes all roots in that revision. Historical database row IDs are unavailable.
     */
    @Transactional(readOnly = true)
    public List<RootCatalogueSnapshot> captureRevision(CatalogueSourceIdentity source, String catalogueRevision,
                                                      Set<String> rootCodes) {
        policy.requireCurrentCatalogueAllowed();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rootCodes, "rootCodes");
        if (FrozenCatalogueContext.current() != null) throw new IllegalStateException("Catalogue capture is already bound");
        var generation = sources.findRevision(catalogueRevision)
                .orElseThrow(() -> new IllegalStateException("The requested catalogue revision is unavailable"));
        try (var input = sources.retainedWorkbook(generation.workbook()).openStream(); var book = new XSSFWorkbook(input)) {
            var hierarchy = TaxonomyService.readWorkbookNodes(book);
            var definition = hierarchy.definition();
            var selected = rootCodes.isEmpty() ? definition.rootCodes() : rootCodes;
            if (!definition.rootCodes().containsAll(selected))
                throw new IllegalArgumentException("Unknown root in the requested catalogue revision");
            CatalogueSourceBytes overlayBytes = generation.overlay().use() == Use.NOT_USED ? null : sources.retainedInput(generation.overlay());
            var retained = overlay.applyRetainedHierarchy(hierarchy.nodes(), hierarchy.uuidToCode(), overlayBytes);
            var metadata = retained.getOverlayMetadata();
            var spec = definition.spec().equals(CatalogueDefinition.standard().spec()) ? null : definition.spec();
            var nodes = hierarchy.nodes().values().stream().filter(node -> selected.contains(node.getTaxonomyRoot()))
                    .map(node -> RootCatalogueSnapshot.Node.capture(node, retained.getNodeMetadata(node.getCode()),
                            retained.hasParentPatch(node.getCode())))
                    .sorted(Comparator.comparingInt(RootCatalogueSnapshot.Node::level).thenComparing(RootCatalogueSnapshot.Node::nameEn)
                            .thenComparing(RootCatalogueSnapshot.Node::code))
                    .collect(java.util.stream.Collectors.groupingBy(RootCatalogueSnapshot.Node::taxonomyRoot));
            return selected.stream().sorted().map(root -> new RootCatalogueSnapshot(
                    spec == null ? RootCatalogueSnapshot.SCHEMA_VERSION : RootCatalogueSnapshot.CONFIGURED_SCHEMA_VERSION,
                    source, root, nodes.get(root), metadata, generation, null, spec)).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("The requested catalogue inputs are unavailable", failure);
        }
    }

    public FrozenCatalogueContext.Scope bind(CatalogueSourceIdentity expectedSource, Set<String> requiredRoots,
                                             Collection<RootCatalogueSnapshot> snapshots) {
        policy.requireConfiguredRoots(requiredRoots);
        return FrozenCatalogueContext.bind(new FrozenCatalogueView(expectedSource, requiredRoots, snapshots));
    }
}
