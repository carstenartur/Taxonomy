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
        List<Map<String,Object>> gaps = entries.stream()
                .filter(CatalogueHierarchyAudit::isAttachmentGap)
                .map(CatalogueHierarchyAudit::gapEvidence)
                .toList();
        result.put("attachmentGapCandidates", gaps);
        // Deliberately empty: audit reports source gaps but does not invent unofficial hierarchy.
        result.put("navigationGroupProposals", List.of());
        return Collections.unmodifiableMap(result);
    }

    private static boolean isAttachmentGap(Map<String,Object> entry) {
        Object status = entry.get("sourceParentStatus");
        return "MISSING".equals(status) || "SELF_REFERENCE".equals(status) || "UNRESOLVED".equals(status);
    }

    private static Map<String,Object> gapEvidence(Map<String,Object> entry) {
        Map<String,Object> gap = new LinkedHashMap<>();
        for (String key : List.of("code", "title", "sourceDescription", "sourceState",
                "sourceParentCode", "resolvedSourceParentCode", "sourceParentStatus", "sourceLevel",
                "overlayParentCode", "overlayParentTitle", "analysisRole", "reviewRequired",
                "existingJustification")) {
            gap.put(key, entry.get(key));
        }
        gap.put("requiresHumanAttachmentDecision", true);
        gap.put("automaticLocalParentProposed", false);
        return Collections.unmodifiableMap(gap);
    }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String,Object> audit) {
        StringBuilder out=new StringBuilder("\n## Hierarchy semantics audit\n\n")
            .append("Structural validity is not semantic approval. Existing review flags are preserved; even a deterministic accepted mapping does not prove inherited source meaning.\n\n")
            .append("Original workbook and active overlay are unchanged. Complete original descriptions, raw parent cells, resolved references and mapping justifications are in `hierarchyAudit.entries` in the JSON proposal.\n\n")
            .append("| Measure | Count |\n|---|---:|\n");
        for(String key:List.of("sourceNodeCount","overlayAssignmentCount","productCount","existingReviewRequiredCount","missingSourceParentCount","invalidSourceParentCount")) {
            out.append('|').append(key).append('|').append(audit.get(key)).append("|\n");
        }
        out.append("\n### IP attachment gaps requiring review\n\n")
            .append("The audit does not invent local grouping nodes or replacement parents. ")
            .append("It only identifies source attachment gaps that may justify a separately reviewed navigation supplement.\n\n");
        for(Map<String,Object> gap:(List<Map<String,Object>>)audit.get("attachmentGapCandidates")) {
            out.append("- `").append(gap.get("code")).append("`: ")
               .append(escape(gap.get("title"))).append(" — ")
               .append(gap.get("sourceParentStatus")).append("; current overlay parent ")
               .append(escape(gap.get("overlayParentCode"))).append(".\n");
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
