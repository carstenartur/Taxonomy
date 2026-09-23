package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CatalogueHierarchyAuditTest {
    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json"))) p=p.getParent();
        if (p==null) throw new IllegalStateException("Repository root not found"); return p;
    }
    @SuppressWarnings("unchecked") @Test void realAuditSeparatesSourceAndOverlayAndReportsAttachmentGaps(@TempDir Path out) throws Exception {
        Path data=root().resolve("taxonomy-knowledge/src/main/resources/data");
        Path workbook=data.resolve("C3_Taxonomy_Catalogue_25AUG2025.xlsx"), overlay=data.resolve("nato-taxonomy.json");
        byte[] sourceBefore=Files.readAllBytes(workbook), overlayBefore=Files.readAllBytes(overlay);
        var first=CatalogueOverlayProposalGenerator.generate(workbook,overlay,out.resolve("a.json"),out.resolve("a.md"));
        CatalogueOverlayProposalGenerator.generate(workbook,overlay,out.resolve("b.json"),out.resolve("b.md"));
        assertThat(Files.readAllBytes(out.resolve("a.json"))).isEqualTo(Files.readAllBytes(out.resolve("b.json")));
        assertThat(Files.readAllBytes(workbook)).isEqualTo(sourceBefore); assertThat(Files.readAllBytes(overlay)).isEqualTo(overlayBefore);
        Map<String,Object> doc=FlatJson.parseObject(Files.readString(first.proposalOutput()));
        assertThat(doc).containsKey("hierarchyAudit");
        Map<String,Object> audit=(Map<String,Object>)doc.get("hierarchyAudit");
        assertThat(audit).containsEntry("semanticApprovalInferred",false);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)audit.get("entries");
        assertThat(rows).hasSize(866);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsKeys("sourceParentCode","overlayParentCode","sourceDescription","parentRelationKind","reviewRequired"));
        Map<String,Object> hazard=rows.stream().filter(r -> "IP-1051".equals(r.get("code"))).findFirst().orElseThrow();
        assertThat(hazard).containsEntry("parentRelationKind","OVERLAY_CLASSIFICATION").containsEntry("overlayParentCode","IP-1064");
        List<Map<String,Object>> groups=(List<Map<String,Object>>)audit.get("navigationGroupProposals");
        assertThat(groups).isEmpty();
        List<Map<String,Object>> gaps=(List<Map<String,Object>>)audit.get("attachmentGapCandidates");
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).allSatisfy(gap -> {
            assertThat(gap.get("code")).asString().startsWith("IP-");
            assertThat(gap.get("sourceParentStatus")).isIn("MISSING","SELF_REFERENCE","UNRESOLVED");
            assertThat(gap).containsEntry("requiresHumanAttachmentDecision",true)
                    .containsEntry("automaticLocalParentProposed",false)
                    .doesNotContainKeys("localGroupId","suggestedLocalParent");
        });
        assertThat(Files.readString(out.resolve("a.md"))).contains("Hierarchy semantics audit").contains("not semantic approval");
    }
}
