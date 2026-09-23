package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueHierarchyAuthorityTest {

    @SuppressWarnings("unchecked")
    @Test
    void auditReportsGapsWithoutInventingNavigationGroups(@TempDir Path out) throws Exception {
        Path data = CatalogueHierarchyAuditTest.root()
                .resolve("taxonomy-knowledge/src/main/resources/data");
        var result = CatalogueOverlayProposalGenerator.generate(
                data.resolve("C3_Taxonomy_Catalogue_25AUG2025.xlsx"),
                data.resolve("nato-taxonomy.json"),
                out.resolve("proposal.json"),
                out.resolve("proposal.md"));
        Map<String,Object> document = FlatJson.parseObject(
                java.nio.file.Files.readString(result.proposalOutput()));
        Map<String,Object> audit = (Map<String,Object>) document.get("hierarchyAudit");

        assertThat((List<?>) audit.get("navigationGroupProposals"))
                .as("the audit must not invent unofficial hierarchy nodes automatically")
                .isEmpty();
        List<Map<String,Object>> gaps =
                (List<Map<String,Object>>) audit.get("attachmentGapCandidates");
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).allSatisfy(row -> {
            assertThat(row.get("code")).asString().startsWith("IP-");
            assertThat(row.get("sourceParentStatus"))
                    .isIn("MISSING", "SELF_REFERENCE", "UNRESOLVED");
            assertThat(row).doesNotContainKeys("localGroupId", "suggestedLocalParent");
        });
    }
}
