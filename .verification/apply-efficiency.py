from pathlib import Path
import shutil

def replace(path, old, new):
    p=Path(path); s=p.read_text(); assert s.count(old)==1,(path,s.count(old),old[:100]); p.write_text(s.replace(old,new))

replace('taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java',
'''    public Map<String, String> getAssessmentDescriptions(List<TaxonomyNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");''',
'''    public Map<String, String> getAssessmentDescriptions(List<TaxonomyNode> nodes) {
        Map<String, String> contexts = getAssessmentContexts(nodes);
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            descriptions.put(node.getCode(), Objects.toString(node.getDescriptionEn(), "")
                    + contexts.get(node.getCode()));
        }
        return Collections.unmodifiableMap(descriptions);
    }

    /** Context only, so callers can transmit common sibling context once without parsing text. */
    @Transactional(readOnly = true)
    public Map<String, String> getAssessmentContexts(List<TaxonomyNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");''')
replace('taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java',
'''            if (node.getDescriptionEn() != null) text.append(node.getDescriptionEn());
''','')
replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java',
'''    /** Candidate-local source context; navigation assignments never establish inheritance. */
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
''',
'''    /** Shared source context is sent once per sibling group, never inferred from the first node. */
    private String buildNodeListWithContext(List<TaxonomyNode> nodes) {
        // Root-only assessments have no inherited context and need no catalogue lookup.
        if (nodes.isEmpty() || nodes.stream().allMatch(node -> node.getParentCode() == null
                || node.getParentCode().isBlank())) return buildNodeList(nodes);
        Map<String, String> contexts = taxonomyService.getAssessmentContexts(nodes);
        Map<String, List<TaxonomyNode>> groups = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            groups.computeIfAbsent(contexts.getOrDefault(node.getCode(), ""), unused -> new ArrayList<>())
                    .add(node);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<TaxonomyNode>> group : groups.entrySet()) {
            if (!group.getKey().isBlank()) text.append(group.getKey())
                    .append("\\nNodes to evaluate (only the following candidates):\\n");
            text.append(buildNodeList(group.getValue()));
        }
        return text.toString();
    }
''')
shutil.copyfile('/tmp/hierarchy-work/ContextBatchEfficiencyTest.java',
 'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/ContextBatchEfficiencyTest.java')
p=Path('docs/dev/hierarchy-context-and-navigation.md')
s=p.read_text().replace('The existing category/product prompt builder and relationship adapter use this same method.',
 'The existing category/product prompt builder and relationship adapter share this context resolver. `getAssessmentContexts` separates context from the original candidate description, so identical sibling context is transmitted once per group. Different parent scopes remain distinct. Root-only assessments require no ancestor lookup and retain their existing diagnostic behavior.')
p.write_text(s)
