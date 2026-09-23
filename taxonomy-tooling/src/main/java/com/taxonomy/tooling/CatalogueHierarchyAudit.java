package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Source-first diagnostics; a classification hint is neither inheritance nor a new source node. */
final class CatalogueHierarchyAudit {
    private CatalogueHierarchyAudit() { }

    static Map<String, Object> document(Path workbook, SourceCatalogue source, OverlayModel overlay)
            throws IOException {
        Map<String, Map<String,String>> originalRows = new HashMap<>();
        for (Map<String,String> row : OpenXmlWorkbook.readSheet(workbook,"Information Products").rows()) {
            originalRows.put(row.get("Page"),row);
        }
        List<Map<String,Object>> entries = new ArrayList<>();
        int missing = 0, invalid = 0, products = 0, provisional = 0;
        for (Patch patch : overlay.patches().values().stream().sorted(Comparator.comparing(Patch::code)).toList()) {
            SourceNode node = source.nodes().get(patch.code());
            Map<String,String> original = originalRows.getOrDefault(node.code(),Map.of());
            String rawParent = original.get("Parent");
            String status;
            if (rawParent == null || rawParent.isBlank()) { status="MISSING"; missing++; }
            else if (node.code().equals(node.sourceParentCode())) { status="SELF_REFERENCE"; invalid++; }
            else if (!source.nodes().containsKey(node.sourceParentCode()) && !source.rootCode().equals(node.sourceParentCode())) {
                status="UNRESOLVED"; invalid++;
            } else status="RESOLVED_SOURCE_REFERENCE";
            if ("PRODUCT".equals(patch.analysisRole())) products++;
            if (patch.reviewRequired()) provisional++;
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("code",node.code()); entry.put("title",node.title());
            entry.put("sourceDescription",node.description()); entry.put("sourceState",node.state());
            entry.put("sourceParentCode",rawParent); entry.put("resolvedSourceParentCode",node.sourceParentCode());
            entry.put("sourceParentStatus",status); entry.put("sourceLevel",original.get("Level"));
            entry.put("overlayParentCode",patch.parentCode());
            SourceNode parent = source.nodes().get(patch.parentCode());
            entry.put("overlayParentTitle",parent == null ? patch.parentCode() : parent.title());
            entry.put("analysisRole",patch.analysisRole());
            entry.put("parentRelationKind","OVERLAY_CLASSIFICATION");
            entry.put("reviewRequired",patch.reviewRequired());
            entry.put("semanticReviewRequired",true);
            entry.put("existingJustification",patch.justification());
            entry.put("secondaryClassificationCodes",patch.secondaryClassificationCodes());
            entries.add(Collections.unmodifiableMap(entry));
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schemaVersion",1); result.put("semanticApprovalInferred",false);
        result.put("sourceNodeCount",source.nodes().size()); result.put("overlayAssignmentCount",entries.size());
        result.put("productCount",products); result.put("existingReviewRequiredCount",provisional);
        result.put("missingSourceParentCount",missing); result.put("invalidSourceParentCount",invalid);
        result.put("entries",List.copyOf(entries));
        result.put("navigationGroupProposals",navigationGroups(source,overlay));
        return Collections.unmodifiableMap(result);
    }

    /** Explicit lexical entry points for review, not an automatic domain classification. */
    private static List<Map<String,Object>> navigationGroups(SourceCatalogue source, OverlayModel overlay) {
        List<GroupRule> rules=List.of(
            new GroupRule("hazards","Hazard and warning information",Set.of("hazard","hazards","warning","warnings")),
            new GroupRule("reports","Reports",Set.of("report","reports")),
            new GroupRule("plans","Plans",Set.of("plan","plans")),
            new GroupRule("requests","Requests",Set.of("request","requests")),
            new GroupRule("orders","Orders",Set.of("order","orders")));
        List<Map<String,Object>> groups=new ArrayList<>();
        for (GroupRule rule:rules) {
            List<String> members=source.nodes().values().stream()
                .filter(n -> overlay.patches().containsKey(n.code())
                    && "PRODUCT".equals(overlay.patches().get(n.code()).analysisRole()))
                .filter(n -> Arrays.stream(n.title().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                    .anyMatch(rule.terms()::contains))
                .map(SourceNode::code).sorted().toList();
            if (members.isEmpty()) continue;
            Map<String,Object> group=new LinkedHashMap<>();
            group.put("id","local:ip:navigation:"+rule.key());
            group.put("displayCode","ip-"+rule.key()); group.put("kind","NAVIGATION_GROUP");
            group.put("title",rule.title()); group.put("parentId",source.rootCode());
            group.put("scopeDescription","Candidate entry point for original information products whose source title contains one of: "
                + rule.terms().stream().sorted().toList() + ". Membership is a title-based proposal; review original descriptions before adoption.");
            group.put("memberCodes",members); group.put("reviewRequired",true);
            group.put("affectsScores",false); group.put("inheritsSemantics",false);
            group.put("createsArchitectureElement",false); group.put("origin","LOCAL_NAVIGATION_PROPOSAL");
            groups.add(Collections.unmodifiableMap(group));
        }
        return List.copyOf(groups);
    }
    private record GroupRule(String key, String title, Set<String> terms) { }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String,Object> audit) {
        StringBuilder out=new StringBuilder("\n## Hierarchy semantics audit\n\n")
            .append("Structural validity is not semantic approval. Existing review flags are preserved; even a deterministic accepted mapping does not prove inherited source meaning.\n\n")
            .append("Original workbook and active overlay are unchanged. Complete original descriptions, raw parent cells, resolved references and mapping justifications are in `hierarchyAudit.entries` in the JSON proposal.\n\n")
            .append("| Measure | Count |\n|---|---:|\n");
        for(String key:List.of("sourceNodeCount","overlayAssignmentCount","productCount","existingReviewRequiredCount","missingSourceParentCount","invalidSourceParentCount")) {
            out.append('|').append(key).append('|').append(audit.get(key)).append("|\n");
        }
        out.append("\n### Local navigation group proposals\n\nThese are additional entry points, not new original taxonomy concepts, semantic parents, score weights or architecture components. Original IDs remain unchanged. Overlapping memberships refer to the same original node. No proposal is automatically promoted.\n\n");
        for(Map<String,Object> group:(List<Map<String,Object>>)audit.get("navigationGroupProposals")) {
            out.append("- `").append(group.get("id")).append("`: ").append(escape(group.get("title")))
               .append(" — ").append(((List<?>)group.get("memberCodes")).size()).append(" original references; review required.\n");
        }
        out.append("\n### Source / overlay comparison\n\n| Original code | Title | Source parent status | Overlay parent | Existing review required |\n|---|---|---|---|---|\n");
        for(Map<String,Object> row:(List<Map<String,Object>>)audit.get("entries")) {
            out.append('|').append(escape(row.get("code"))).append('|').append(escape(row.get("title")))
               .append('|').append(row.get("sourceParentStatus")).append('|').append(escape(row.get("overlayParentCode")))
               .append('|').append(row.get("reviewRequired")).append("|\n");
        }
        return out.toString();
    }
    private static String escape(Object value) {
        return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("|","&#124;").replace("\r"," ").replace("\n"," ").replace("`","&#96;");
    }
}
