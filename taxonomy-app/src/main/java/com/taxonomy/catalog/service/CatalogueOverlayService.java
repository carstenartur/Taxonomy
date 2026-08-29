package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the checked-in JSON catalogue overlay to the Excel catalogue before persistence.
 *
 * <p>The Excel workbook remains the source of node identity, titles, descriptions and source
 * provenance. The overlay contains explicit, reviewable corrections for parent relationships and
 * analysis metadata that cannot safely be inferred at runtime. Overlay errors fail closed: an
 * unknown node, unknown parent, cross-root parent, self-reference, cycle or incomplete strict
 * coverage prevents the catalogue from being persisted.</p>
 */
@Service
public class CatalogueOverlayService {

    private static final Logger log = LoggerFactory.getLogger(CatalogueOverlayService.class);
    private static final int SUPPORTED_SCHEMA_VERSION = 2;
    private static final String SUPPORTED_MODE = "OVERLAY";
    private static final String ROLE_CATEGORY = "CATEGORY";
    private static final String ROLE_PRODUCT = "PRODUCT";
    private static final String ROLE_PRODUCT_FAMILY = "PRODUCT_FAMILY";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final boolean enabled;
    private final String overlayResource;

    private volatile LoadedOverlay loadedOverlay;
    private final Map<String, NodeMetadata> nodeMetadata = new ConcurrentHashMap<>();

    public CatalogueOverlayService(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${taxonomy.catalogue.overlay.enabled:true}") boolean enabled,
            @Value("${taxonomy.catalogue.overlay-resource:classpath:data/nato-taxonomy.json}")
            String overlayResource) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.enabled = enabled;
        this.overlayResource = overlayResource;
    }

    /**
     * Applies parent patches and builds a strictly validated, level-consistent hierarchy.
     *
     * @param nodes             all virtual roots and workbook nodes, keyed by node code
     * @param uuidToCode        source UUID to node-code lookup used to normalize workbook parents
     * @param baseCataloguePath configured Excel catalogue resource
     * @return auditable application statistics
     */
    public OverlayApplicationResult applyAndValidate(
            Map<String, TaxonomyNode> nodes,
            Map<String, String> uuidToCode,
            String baseCataloguePath) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(uuidToCode, "uuidToCode");

        resolveUuidParents(nodes, uuidToCode);

        LoadedOverlay overlay = enabled ? loadOverlay() : LoadedOverlay.disabled(overlayResource);
        if (enabled) {
            validateBaseCatalogue(overlay.definition(), baseCataloguePath);
            applyPatches(nodes, overlay.definition());
            validateStrictCoverage(nodes, overlay.definition());
        } else {
            nodeMetadata.clear();
            log.info("Taxonomy catalogue overlay is disabled.");
        }

        HierarchyStats hierarchyStats = wireAndValidateHierarchy(nodes);
        validateAnalysisRoles(nodes);

        long productCount = nodeMetadata.values().stream()
                .filter(metadata -> ROLE_PRODUCT.equals(metadata.analysisRole()))
                .count();
        long productFamilyCount = nodeMetadata.values().stream()
                .filter(metadata -> ROLE_PRODUCT_FAMILY.equals(metadata.analysisRole()))
                .count();

        OverlayApplicationResult result = new OverlayApplicationResult(
                overlay.definition().getNodePatches().size(),
                productCount,
                productFamilyCount,
                hierarchyStats.maxDirectChildren(),
                hierarchyStats.maxDepth(),
                overlay.sha256(),
                overlay.definition().getMappingVersion());

        log.info("Catalogue overlay applied: {} patches, {} products, {} product families, "
                        + "max direct children {}, max depth {}, digest {}.",
                result.patchCount(), result.productCount(), result.productFamilyCount(),
                result.maxDirectChildren(), result.maxDepth(), abbreviate(result.overlaySha256()));
        return result;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isProduct(String code) {
        return ROLE_PRODUCT.equals(getNodeMetadata(code).analysisRole());
    }

    public boolean isProductFamily(String code) {
        return ROLE_PRODUCT_FAMILY.equals(getNodeMetadata(code).analysisRole());
    }

    public String analysisRole(String code) {
        return getNodeMetadata(code).analysisRole();
    }

    public NodeMetadata getNodeMetadata(String code) {
        if (enabled) {
            loadOverlay();
        }
        return nodeMetadata.getOrDefault(code, NodeMetadata.category());
    }

    public OverlayMetadata getOverlayMetadata() {
        if (!enabled) {
            return new OverlayMetadata(false, overlayResource, null, null, null, 0);
        }
        LoadedOverlay overlay = loadOverlay();
        return new OverlayMetadata(
                true,
                overlayResource,
                overlay.definition().getBaseCatalogue(),
                overlay.definition().getMappingVersion(),
                overlay.sha256(),
                overlay.definition().getNodePatches().size());
    }

    private LoadedOverlay loadOverlay() {
        LoadedOverlay current = loadedOverlay;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (loadedOverlay == null) {
                loadedOverlay = readOverlay();
                rebuildMetadataIndex(loadedOverlay.definition());
            }
            return loadedOverlay;
        }
    }

    private LoadedOverlay readOverlay() {
        Resource resource = resourceLoader.getResource(overlayResource);
        if (!resource.exists()) {
            throw new IllegalStateException("Configured taxonomy catalogue overlay does not exist: "
                    + overlayResource);
        }
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            OverlayDefinition definition = objectMapper.readValue(bytes, OverlayDefinition.class);
            validateDefinition(definition);
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            return new LoadedOverlay(definition, sha256);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot load taxonomy catalogue overlay '" + overlayResource + "'", exception);
        }
    }

    private void validateDefinition(OverlayDefinition definition) {
        if (definition == null) {
            throw new IllegalStateException("Taxonomy catalogue overlay is empty");
        }
        if (definition.getSchemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported taxonomy catalogue overlay schemaVersion "
                    + definition.getSchemaVersion() + "; expected " + SUPPORTED_SCHEMA_VERSION);
        }
        if (!SUPPORTED_MODE.equals(normalize(definition.getMode()))) {
            throw new IllegalStateException("Unsupported taxonomy catalogue overlay mode '"
                    + definition.getMode() + "'; expected " + SUPPORTED_MODE);
        }
        if (isBlank(definition.getBaseCatalogue())) {
            throw new IllegalStateException("Taxonomy catalogue overlay must declare baseCatalogue");
        }
        if (isBlank(definition.getMappingVersion())) {
            throw new IllegalStateException("Taxonomy catalogue overlay must declare mappingVersion");
        }

        Set<String> codes = new HashSet<>();
        for (NodePatch patch : definition.getNodePatches()) {
            if (patch == null || isBlank(patch.getCode())) {
                throw new IllegalStateException("Taxonomy catalogue overlay contains a patch without code");
            }
            if (!codes.add(patch.getCode())) {
                throw new IllegalStateException("Duplicate taxonomy catalogue overlay patch for code "
                        + patch.getCode());
            }
            if (isBlank(patch.getExpectedTitle())) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " must declare expectedTitle for source-drift detection");
            }
            if (isBlank(patch.getExpectedState())) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " must declare expectedState for source-drift detection");
            }
            if (isBlank(patch.getParentCode())) {
                throw new IllegalStateException("Patch " + patch.getCode() + " has no parentCode");
            }
            if (patch.getCode().equals(patch.getParentCode())) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " creates a self-parent reference");
            }
            Set<String> secondaryCodes = new HashSet<>();
            for (String secondaryCode : patch.getSecondaryClassificationCodes()) {
                if (isBlank(secondaryCode)) {
                    throw new IllegalStateException("Patch " + patch.getCode()
                            + " contains a blank secondary classification");
                }
                if (!secondaryCodes.add(secondaryCode)) {
                    throw new IllegalStateException("Patch " + patch.getCode()
                            + " contains duplicate secondary classification " + secondaryCode);
                }
            }
            String role = normalizeRole(patch.getAnalysisRole());
            if (!Set.of(ROLE_PRODUCT, ROLE_PRODUCT_FAMILY).contains(role)) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " has unsupported analysisRole '" + patch.getAnalysisRole() + "'");
            }
            if (patch.getConfidence() < 0.0 || patch.getConfidence() > 1.0) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " confidence must be between 0 and 1");
            }
            if (isBlank(patch.getJustification())) {
                throw new IllegalStateException("Patch " + patch.getCode()
                        + " must contain a justification");
            }
        }
    }

    private void rebuildMetadataIndex(OverlayDefinition definition) {
        nodeMetadata.clear();
        for (NodePatch patch : definition.getNodePatches()) {
            nodeMetadata.put(patch.getCode(), new NodeMetadata(
                    normalizeRole(patch.getAnalysisRole()),
                    List.copyOf(patch.getSecondaryClassificationCodes()),
                    patch.getConfidence(),
                    patch.isReviewRequired(),
                    patch.getJustification()));
        }
    }

    private void validateBaseCatalogue(OverlayDefinition definition, String baseCataloguePath) {
        Resource base = resourceLoader.getResource(baseCataloguePath);
        String actualFilename = base.getFilename();
        if (actualFilename == null) {
            actualFilename = baseCataloguePath.substring(baseCataloguePath.lastIndexOf('/') + 1);
        }
        if (!definition.getBaseCatalogue().equals(actualFilename)) {
            throw new IllegalStateException("Taxonomy catalogue overlay targets baseCatalogue '"
                    + definition.getBaseCatalogue() + "' but configured catalogue is '"
                    + actualFilename + "'");
        }
    }

    private void resolveUuidParents(Map<String, TaxonomyNode> nodes,
                                    Map<String, String> uuidToCode) {
        for (TaxonomyNode node : nodes.values()) {
            String parentCode = node.getParentCode();
            if (isBlank(parentCode) || nodes.containsKey(parentCode)) {
                continue;
            }
            String resolved = uuidToCode.get(parentCode);
            if (resolved != null) {
                node.setParentCode(resolved);
            }
        }
    }

    private void applyPatches(Map<String, TaxonomyNode> nodes, OverlayDefinition definition) {
        for (NodePatch patch : definition.getNodePatches()) {
            TaxonomyNode node = nodes.get(patch.getCode());
            if (node == null) {
                throw new IllegalStateException("Overlay patch references unknown node "
                        + patch.getCode());
            }
            TaxonomyNode parent = nodes.get(patch.getParentCode());
            if (parent == null) {
                throw new IllegalStateException("Overlay patch " + patch.getCode()
                        + " references unknown parent " + patch.getParentCode());
            }
            if (!isBlank(patch.getExpectedTitle())
                    && !normalizeWhitespace(patch.getExpectedTitle())
                            .equals(normalizeWhitespace(node.getName()))) {
                throw new IllegalStateException("Overlay patch " + patch.getCode()
                        + " expected title '" + patch.getExpectedTitle()
                        + "' but source title is '" + node.getName() + "'");
            }
            if (!isBlank(patch.getExpectedState())
                    && !patch.getExpectedState().equalsIgnoreCase(nullToEmpty(node.getState()))) {
                throw new IllegalStateException("Overlay patch " + patch.getCode()
                        + " expected state '" + patch.getExpectedState()
                        + "' but source state is '" + node.getState() + "'");
            }
            if (node == parent) {
                throw new IllegalStateException("Overlay patch " + patch.getCode()
                        + " creates a self-parent reference");
            }
            if (!Objects.equals(node.getTaxonomyRoot(), parent.getTaxonomyRoot())) {
                throw new IllegalStateException("Overlay patch " + patch.getCode()
                        + " crosses taxonomy roots from " + node.getTaxonomyRoot()
                        + " to " + parent.getTaxonomyRoot());
            }
            for (String secondaryCode : patch.getSecondaryClassificationCodes()) {
                TaxonomyNode secondary = nodes.get(secondaryCode);
                if (secondary == null) {
                    throw new IllegalStateException("Overlay patch " + patch.getCode()
                            + " references unknown secondary classification " + secondaryCode);
                }
                if (!Objects.equals(node.getTaxonomyRoot(), secondary.getTaxonomyRoot())) {
                    throw new IllegalStateException("Overlay patch " + patch.getCode()
                            + " has cross-root secondary classification " + secondaryCode);
                }
                if (secondaryCode.equals(patch.getCode())
                        || secondaryCode.equals(patch.getParentCode())) {
                    throw new IllegalStateException("Overlay patch " + patch.getCode()
                            + " contains a redundant secondary classification " + secondaryCode);
                }
            }
            node.setParentCode(patch.getParentCode());
        }
    }

    private void validateStrictCoverage(Map<String, TaxonomyNode> nodes,
                                        OverlayDefinition definition) {
        OverlayValidation validation = definition.getValidation();
        if (validation == null
                || isBlank(validation.getRequireExplicitPatchForRoot())
                || isBlank(validation.getRequireExplicitPatchForState())) {
            return;
        }
        Set<String> patchedCodes = new HashSet<>();
        for (NodePatch patch : definition.getNodePatches()) {
            patchedCodes.add(patch.getCode());
        }
        List<String> missing = nodes.values().stream()
                .filter(node -> validation.getRequireExplicitPatchForRoot()
                        .equals(node.getTaxonomyRoot()))
                .filter(node -> validation.getRequireExplicitPatchForState()
                        .equalsIgnoreCase(nullToEmpty(node.getState())))
                .map(TaxonomyNode::getCode)
                .filter(code -> !patchedCodes.contains(code))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Catalogue overlay does not explicitly classify "
                    + missing.size() + " node(s) required by strict validation: "
                    + missing.stream().limit(20).toList());
        }
    }

    private HierarchyStats wireAndValidateHierarchy(Map<String, TaxonomyNode> nodes) {
        List<TaxonomyNode> roots = nodes.values().stream()
                .filter(node -> node.getLevel() == 0)
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalStateException("Catalogue has no virtual taxonomy roots");
        }

        for (TaxonomyNode node : nodes.values()) {
            node.setParent(null);
            if (node.getLevel() == 0) {
                node.setParentCode(null);
            } else if (isBlank(node.getParentCode())) {
                TaxonomyNode root = nodes.get(node.getTaxonomyRoot());
                if (root == null || root.getLevel() != 0) {
                    throw new IllegalStateException("Node " + node.getCode()
                            + " has no parent and unknown virtual root " + node.getTaxonomyRoot());
                }
                node.setParentCode(root.getCode());
            }
        }

        Map<String, List<TaxonomyNode>> childrenByParent = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes.values()) {
            if (node.getLevel() == 0) {
                continue;
            }
            TaxonomyNode parent = nodes.get(node.getParentCode());
            if (parent == null) {
                throw new IllegalStateException("Node " + node.getCode()
                        + " references unresolved parent " + node.getParentCode());
            }
            if (node == parent) {
                throw new IllegalStateException("Node " + node.getCode()
                        + " references itself as parent");
            }
            if (!Objects.equals(node.getTaxonomyRoot(), parent.getTaxonomyRoot())) {
                throw new IllegalStateException("Node " + node.getCode()
                        + " crosses taxonomy roots via parent " + parent.getCode());
            }
            childrenByParent.computeIfAbsent(parent.getCode(), ignored -> new ArrayList<>())
                    .add(node);
        }
        childrenByParent.values().forEach(children ->
                children.sort(Comparator.comparing(TaxonomyNode::getCode)));

        Map<String, VisitState> states = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        int maxDepth = 0;
        for (TaxonomyNode root : roots) {
            root.setLevel(0);
            maxDepth = Math.max(maxDepth, visit(root, 0, childrenByParent, states, visited));
        }

        if (visited.size() != nodes.size()) {
            List<String> unreachable = nodes.keySet().stream()
                    .filter(code -> !visited.contains(code))
                    .sorted()
                    .limit(20)
                    .toList();
            throw new IllegalStateException("Catalogue hierarchy contains unreachable or cyclic nodes: "
                    + unreachable);
        }

        int maxDirectChildren = childrenByParent.values().stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
        return new HierarchyStats(maxDirectChildren, maxDepth);
    }

    private int visit(TaxonomyNode node,
                      int level,
                      Map<String, List<TaxonomyNode>> childrenByParent,
                      Map<String, VisitState> states,
                      Set<String> visited) {
        VisitState current = states.get(node.getCode());
        if (current == VisitState.VISITING) {
            throw new IllegalStateException("Catalogue hierarchy contains a cycle at "
                    + node.getCode());
        }
        if (current == VisitState.VISITED) {
            return level;
        }
        states.put(node.getCode(), VisitState.VISITING);
        node.setLevel(level);
        visited.add(node.getCode());
        int maxDepth = level;
        for (TaxonomyNode child : childrenByParent.getOrDefault(node.getCode(), List.of())) {
            child.setParent(node);
            maxDepth = Math.max(maxDepth,
                    visit(child, level + 1, childrenByParent, states, visited));
        }
        states.put(node.getCode(), VisitState.VISITED);
        return maxDepth;
    }

    private void validateAnalysisRoles(Map<String, TaxonomyNode> nodes) {
        Set<String> parentCodes = nodes.values().stream()
                .map(TaxonomyNode::getParentCode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<String> productParents = nodeMetadata.entrySet().stream()
                .filter(entry -> ROLE_PRODUCT.equals(entry.getValue().analysisRole()))
                .map(Map.Entry::getKey)
                .filter(parentCodes::contains)
                .sorted()
                .toList();
        if (!productParents.isEmpty()) {
            throw new IllegalStateException("Nodes classified as PRODUCT must be leaves: "
                    + productParents.stream().limit(20).toList());
        }
    }

    private String normalizeWhitespace(String value) {
        return nullToEmpty(value).trim().replaceAll("\\s+", " ");
    }

    private String normalizeRole(String role) {
        String normalized = normalize(role);
        return normalized.isBlank() ? ROLE_CATEGORY : normalized;
    }

    private String normalize(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12);
    }

    private enum VisitState { VISITING, VISITED }

    private record LoadedOverlay(OverlayDefinition definition, String sha256) {
        static LoadedOverlay disabled(String resource) {
            OverlayDefinition definition = new OverlayDefinition();
            definition.setSchemaVersion(SUPPORTED_SCHEMA_VERSION);
            definition.setMode(SUPPORTED_MODE);
            definition.setMappingVersion("disabled");
            definition.setBaseCatalogue(resource);
            return new LoadedOverlay(definition, null);
        }
    }

    private record HierarchyStats(int maxDirectChildren, int maxDepth) {}

    public record OverlayApplicationResult(
            int patchCount,
            long productCount,
            long productFamilyCount,
            int maxDirectChildren,
            int maxDepth,
            String overlaySha256,
            String mappingVersion) {}

    public record OverlayMetadata(
            boolean enabled,
            String resource,
            String baseCatalogue,
            String mappingVersion,
            String sha256,
            int patchCount) {}

    public record NodeMetadata(
            String analysisRole,
            List<String> secondaryClassificationCodes,
            double confidence,
            boolean reviewRequired,
            String justification) {
        static NodeMetadata category() {
            return new NodeMetadata(ROLE_CATEGORY, List.of(), 1.0, false, null);
        }
    }

    /** Jackson-bound root object for schema version 2. */
    public static class OverlayDefinition {
        private int schemaVersion;
        private String mode;
        private String baseCatalogue;
        private String mappingVersion;
        private String description;
        private OverlayValidation validation;
        private List<NodePatch> nodePatches = new ArrayList<>();

        public int getSchemaVersion() { return schemaVersion; }
        public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getBaseCatalogue() { return baseCatalogue; }
        public void setBaseCatalogue(String baseCatalogue) { this.baseCatalogue = baseCatalogue; }
        public String getMappingVersion() { return mappingVersion; }
        public void setMappingVersion(String mappingVersion) { this.mappingVersion = mappingVersion; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public OverlayValidation getValidation() { return validation; }
        public void setValidation(OverlayValidation validation) { this.validation = validation; }
        public List<NodePatch> getNodePatches() {
            return nodePatches == null ? List.of() : nodePatches;
        }
        public void setNodePatches(List<NodePatch> nodePatches) { this.nodePatches = nodePatches; }
    }

    public static class OverlayValidation {
        private String requireExplicitPatchForRoot;
        private String requireExplicitPatchForState;

        public String getRequireExplicitPatchForRoot() { return requireExplicitPatchForRoot; }
        public void setRequireExplicitPatchForRoot(String value) { this.requireExplicitPatchForRoot = value; }
        public String getRequireExplicitPatchForState() { return requireExplicitPatchForState; }
        public void setRequireExplicitPatchForState(String value) { this.requireExplicitPatchForState = value; }
    }

    public static class NodePatch {
        private String code;
        private String expectedTitle;
        private String expectedState;
        private String parentCode;
        private String analysisRole;
        private List<String> secondaryClassificationCodes = new ArrayList<>();
        private double confidence;
        private boolean reviewRequired;
        private String justification;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getExpectedTitle() { return expectedTitle; }
        public void setExpectedTitle(String expectedTitle) { this.expectedTitle = expectedTitle; }
        public String getExpectedState() { return expectedState; }
        public void setExpectedState(String expectedState) { this.expectedState = expectedState; }
        public String getParentCode() { return parentCode; }
        public void setParentCode(String parentCode) { this.parentCode = parentCode; }
        public String getAnalysisRole() { return analysisRole; }
        public void setAnalysisRole(String analysisRole) { this.analysisRole = analysisRole; }
        public List<String> getSecondaryClassificationCodes() {
            return secondaryClassificationCodes == null ? List.of() : secondaryClassificationCodes;
        }
        public void setSecondaryClassificationCodes(List<String> value) {
            this.secondaryClassificationCodes = value;
        }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public boolean isReviewRequired() { return reviewRequired; }
        public void setReviewRequired(boolean reviewRequired) { this.reviewRequired = reviewRequired; }
        public String getJustification() { return justification; }
        public void setJustification(String justification) { this.justification = justification; }
    }
}
