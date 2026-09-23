from pathlib import Path
import shutil

ROOT=Path('.')
def replace(path, old, new):
    p=ROOT/path; text=p.read_text()
    assert text.count(old)==1, (path,text.count(old),old[:100])
    p.write_text(text.replace(old,new))

replace('taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/CatalogueOverlayService.java',
'''    public boolean isProduct(String code) {''',
'''    /** An overlay assignment is not evidence that the source defines semantic inheritance. */
    public boolean hasParentPatch(String code) {
        if (!enabled || code == null) return false;
        loadOverlay();
        return nodeMetadata.containsKey(code);
    }

    public boolean isProduct(String code) {''')

p=ROOT/'taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java'
s=p.read_text()
start=s.index('    @Transactional(readOnly = true)\n    public List<TaxonomyNode> getPathToRoot(String code)')
end=s.index('\n    /**',start+10)
s=s[:start]+'''    @Transactional(readOnly = true)
    public List<TaxonomyNode> getPathToRoot(String code) {
        return resolveCataloguePath(code, false, new HashMap<>());
    }

    /**
     * The source-hierarchy suffix ending at this node, in root-to-leaf order.
     * An overlay parent assignment terminates inheritance, even when its deterministic
     * classification has reviewRequired=false. That flag is not source authority.
     * The ordinary navigation path remains available separately through getPathToRoot.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyNode> getSemanticPathToRoot(String code) {
        return resolveCataloguePath(code, true, new HashMap<>());
    }

    private List<TaxonomyNode> resolveCataloguePath(String code, boolean semanticOnly,
                                                   Map<String, TaxonomyNode> resolved) {
        if (code == null || code.isBlank()) return List.of();
        TaxonomyNode current = resolved.computeIfAbsent(code, this::getNodeByCode);
        if (current == null) return List.of();
        String root = current.getTaxonomyRoot();
        LinkedList<TaxonomyNode> path = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        while (current != null) {
            if (!seen.add(current.getCode())) {
                throw new IllegalStateException("Taxonomy path contains a cycle at " + current.getCode());
            }
            if (!Objects.equals(root, current.getTaxonomyRoot())) {
                throw new IllegalStateException("Taxonomy path crosses roots at " + current.getCode());
            }
            path.addFirst(current);
            String parent = current.getParentCode();
            if (parent == null || parent.isBlank()) break;
            if (semanticOnly && hasOverlayParent(current.getCode())) break;
            current = resolved.computeIfAbsent(parent, this::getNodeByCode);
            if (current == null) throw new IllegalStateException("Missing taxonomy parent " + parent);
        }
        return List.copyOf(path);
    }

    private boolean hasOverlayParent(String code) {
        return catalogueOverlayService != null && catalogueOverlayService.hasParentPatch(code);
    }

    /**
     * Shared scalar context for child assessment and relationship assessment. Resolves
     * each distinct ancestor at most once per batch; does not load lazy graph collections.
     * Mixed-parent batches keep per-candidate contexts. No source node is rewritten.
     */
    @Transactional(readOnly = true)
    public Map<String, String> getAssessmentDescriptions(List<TaxonomyNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        Map<String, TaxonomyNode> resolved = new HashMap<>();
        for (TaxonomyNode node : nodes) {
            if (node == null || node.getCode() == null || node.getCode().isBlank()
                    || resolved.putIfAbsent(node.getCode(), node) != null) {
                throw new IllegalArgumentException("Assessment candidates require unique non-blank IDs");
            }
        }
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            List<TaxonomyNode> path = resolveCataloguePath(node.getCode(), true, resolved);
            StringBuilder text = new StringBuilder();
            if (node.getDescriptionEn() != null) text.append(node.getDescriptionEn());
            if (path.size() > 1) {
                text.append("\\nSource hierarchy context (ancestors, not additional candidates):\\n");
                for (TaxonomyNode ancestor : path.subList(0, path.size() - 1)) {
                    text.append("  ").append(ancestor.getCode()).append(": ").append(ancestor.getNameEn());
                    if (ancestor.getDescriptionEn() != null && !ancestor.getDescriptionEn().isBlank()) {
                        text.append(" - ").append(ancestor.getDescriptionEn());
                    }
                    text.append('\\n');
                }
            }
            if (!path.isEmpty() && hasOverlayParent(path.getFirst().getCode())) {
                text.append("\\nClassification/navigation only: the parent assignment above ")
                        .append(path.getFirst().getCode())
                        .append(" comes from an overlay, not established source hierarchy. ")
                        .append("Do not inherit restrictions or infer irrelevance from that assignment.");
            }
            descriptions.put(node.getCode(), text.toString());
        }
        return Collections.unmodifiableMap(descriptions);
    }
''' + s[end:]
p.write_text(s)

p=ROOT/'taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java'
s=p.read_text(); start=s.index('    /**\n     * Builds the node list for LLM prompts, optionally prepending')
end=s.index('    private String buildPrompt(', start)
s=s[:start]+'''    /** Candidate-local source context; navigation assignments never establish inheritance. */
    private String buildNodeListWithContext(List<TaxonomyNode> nodes) {
        Map<String, String> descriptions = taxonomyService.getAssessmentDescriptions(nodes);
        StringBuilder text = new StringBuilder();
        for (TaxonomyNode node : nodes) {
            text.append(node.getCode()).append(": ").append(node.getName());
            String description = descriptions.getOrDefault(node.getCode(), node.getDescription());
            if (description != null && !description.isBlank()) text.append(" - ").append(description);
            text.append("\\n");
        }
        return text.toString();
    }

''' + s[end:]
p.write_text(s)

replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RequirementRelationSearchService.java',
'''            public List<Node> roots() { return catalogue.getRootNodes().stream().map(RequirementRelationSearchService::scalar).toList(); }
            public List<Node> children(Node node) { return catalogue.getChildrenOf(node.id()).stream().map(RequirementRelationSearchService::scalar).toList(); }''',
'''            public List<Node> roots() { return scalars(catalogue.getRootNodes()); }
            public List<Node> children(Node node) { return scalars(catalogue.getChildrenOf(node.id())); }''')
replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RequirementRelationSearchService.java',
'''    private static Node scalar(TaxonomyNode node) {
        if (node == null) return null;
        return new Node(node.getCode(), node.getTaxonomyRoot(), node.getNameEn(), node.getDescriptionEn(),
                node.getParentCode() == null || node.getParentCode().isBlank());
    }''',
'''    private List<Node> scalars(List<TaxonomyNode> nodes) {
        Map<String, String> descriptions = catalogue.getAssessmentDescriptions(nodes);
        return nodes.stream().map(node -> scalar(node,
                descriptions.getOrDefault(node.getCode(), node.getDescriptionEn()))).toList();
    }

    private Node scalar(TaxonomyNode node) {
        return node == null ? null : scalars(List.of(node)).getFirst();
    }

    private static Node scalar(TaxonomyNode node, String description) {
        return new Node(node.getCode(), node.getTaxonomyRoot(), node.getNameEn(), description,
                node.getParentCode() == null || node.getParentCode().isBlank());
    }''')

# Existing miniature real-use-case fixture must supply its actual declared source root.
replace('taxonomy-analysis/src/test/java/com/taxonomy/analysis/relations/RelationSearchUseCaseContract.java',
'''return Map.of("process", source, "IP", root, "evidence", target).get(id);''',
'''return Map.of("process", source, "BP", node("BP", "BP", null), "IP", root, "evidence", target).get(id);''')

replace('taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueOverlayProposalGenerator.java',
'''static final String ALGORITHM_VERSION = "catalogue-overlay-proposal-v2";''',
'''static final String ALGORITHM_VERSION = "catalogue-overlay-proposal-v3";''')
replace('taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueOverlayProposalGenerator.java',
'''        String proposalText = FlatJson.pretty(document) + "\\n";''',
'''        Map<String, Object> hierarchyAudit = CatalogueHierarchyAudit.document(cataloguePath, source, overlay);
        document = new LinkedHashMap<>(document);
        document.put("hierarchyAudit", hierarchyAudit);
        String proposalText = FlatJson.pretty(document) + "\\n";''')
replace('taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueOverlayProposalGenerator.java',
'''        atomicWrite(proposalPath, proposalText);
        atomicWrite(reportPath, reportText);''',
'''        reportText += CatalogueHierarchyAudit.markdown(hierarchyAudit);
        atomicWrite(proposalPath, proposalText);
        atomicWrite(reportPath, reportText);''')

for name,target in [
 ('TaxonomyHierarchyContextTest.java','taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/TaxonomyHierarchyContextTest.java'),
 ('CatalogueHierarchyAuditTest.java','taxonomy-tooling/src/test/java/com/taxonomy/tooling/CatalogueHierarchyAuditTest.java'),
 ('CatalogueHierarchyAudit.java','taxonomy-tooling/src/main/java/com/taxonomy/tooling/CatalogueHierarchyAudit.java'),
 ('hierarchy-context-and-navigation.md','docs/dev/hierarchy-context-and-navigation.md')]:
    shutil.copyfile(Path('/tmp/hierarchy-work')/name, ROOT/target)
