package com.taxonomy;

import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.MermaidLabels;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates the README architecture showcase using the real analysis pipeline.
 *
 * <p>This test creates realistic scores (matching what the LLM analysis would
 * produce for the hospital communication requirement), feeds them through the
 * full {@code RequirementArchitectureViewService → DiagramProjectionService →
 * MermaidExportService} pipeline, and writes the resulting Mermaid graph and
 * detail tables to {@code target/readme-showcase.md}.
 *
 * <p>The output is deterministic (no LLM call) and should be embedded verbatim
 * in {@code README.md} whenever the pipeline changes.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class ReadmeShowcaseTest {

    @Autowired
    private RequirementArchitectureViewService architectureViewService;

    @Autowired
    private DiagramProjectionService diagramProjectionService;

    @Autowired
    private MermaidExportService mermaidExportService;

    @Autowired
    private TaxonomyRelationService relationService;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    private static final String BUSINESS_TEXT =
            "Provide an integrated communication platform for hospital staff, "
                    + "enabling real-time voice and data exchange between departments, "
                    + "with a clinical dashboard application for patient handoff tracking "
                    + "and team coordination.";

    @BeforeEach
    void ensureSeedRelations() {
        // Set up the key seed relations that the real CSV data would provide.
        // Other test classes (e.g. ArchitectureViewTests) may share this Spring
        // context and clean relations in their own @BeforeEach, so we must
        // recreate them here to ensure the propagation produces a rich result.
        relationRepository.deleteAll();

        // Capabilities are realized by services (NAF NCV-2)
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "showcase");
        relationService.createRelation("CP", "CI", RelationType.REALIZES, null, "showcase");
        relationService.createRelation("CP", "CO", RelationType.REALIZES, null, "showcase");

        // Services support processes (TOGAF Business Architecture)
        relationService.createRelation("CR", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CI", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CO", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CO", "CR", RelationType.SUPPORTS, null, "showcase");

        // User Applications use services (NAF NSV-1)
        relationService.createRelation("UA", "CR", RelationType.USES, null, "showcase");
        relationService.createRelation("UA", "CI", RelationType.USES, null, "showcase");
        relationService.createRelation("UA", "CO", RelationType.USES, null, "showcase");

        // Core Services support User Applications (TOGAF Application Architecture)
        relationService.createRelation("CR", "UA", RelationType.SUPPORTS, null, "showcase");

        // Services fulfill Capabilities (NAF NCV-5)
        relationService.createRelation("CI", "CP", RelationType.FULFILLS, null, "showcase");
        relationService.createRelation("CR", "CP", RelationType.FULFILLS, null, "showcase");

        // Business Roles assigned to processes (TOGAF Org Mapping)
        relationService.createRelation("BR", "BP", RelationType.ASSIGNED_TO, null, "showcase");

        // Processes produce/consume Information Products
        relationService.createRelation("BP", "IP", RelationType.PRODUCES, null, "showcase");
        relationService.createRelation("BP", "IP", RelationType.CONSUMES, null, "showcase");

        // Technical dependencies
        relationService.createRelation("CR", "CI", RelationType.DEPENDS_ON, null, "showcase");
        relationService.createRelation("CI", "CR", RelationType.DEPENDS_ON, null, "showcase");
        relationService.createRelation("CO", "CR", RelationType.DEPENDS_ON, null, "showcase");

        // User Applications consume Information Products
        relationService.createRelation("UA", "IP", RelationType.CONSUMES, null, "showcase");

        // User Applications support Processes
        relationService.createRelation("UA", "BP", RelationType.SUPPORTS, null, "showcase");

        // Services support Business Roles
        relationService.createRelation("CR", "BR", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CI", "BR", RelationType.SUPPORTS, null, "showcase");
    }

    @Test
    void generateReadmeShowcase() throws IOException {
        // Realistic scores for the hospital communication requirement.
        // Root-level scores determine anchors; leaf-level scores drive enrichment.
        Map<String, Integer> scores = new LinkedHashMap<>();

        // Root-level scores (anchors are selected at score >= 70)
        scores.put("CP", 92);
        scores.put("CO", 88);
        scores.put("CR", 81);
        scores.put("UA", 74);
        scores.put("BP", 71);
        scores.put("BR", 55);
        scores.put("IP", 50);
        scores.put("CI", 45);

        // Leaf-level scores for enrichment (top-3 per root, score >= 5).
        // Only codes known to exist in the C3 Taxonomy Catalogue workbook.
        scores.put("CP-1023", 85);  // Communication and Information System Capabilities
        scores.put("CP-1030", 30);  // Cyberspace Battlespace Management Capabilities

        scores.put("CO-1011", 80);  // Communications Access Services
        scores.put("CO-1019", 52);  // Frame Switching Services
        scores.put("CO-1063", 35);  // Transport Services

        scores.put("CR-1047", 75);  // Infrastructure Services
        scores.put("CR-1021", 48);  // Digital Certificate Services

        scores.put("UA-1015", 68);  // Air Applications

        scores.put("BP-1327", 65);  // Enable

        scores.put("BR-1043", 35);  // Facilities Management Roles

        // ── Run the REAL pipeline ──────────────────────────────────────────

        // Step 1: Build architecture view (anchors → propagation → enrichment)
        RequirementArchitectureView view = architectureViewService.build(
                scores, BUSINESS_TEXT, 20);

        // Step 2: Project to neutral diagram model
        DiagramModel diagram = diagramProjectionService.project(view, BUSINESS_TEXT);

        // Step 3: Export as Mermaid SHOWCASE (leaf-dominant, TD layout, re-routed edges, localized labels)
        String mermaid = mermaidExportService.exportShowcase(diagram, MermaidLabels.english());

        // ── Assertions: the output must satisfy showcase requirements ───────

        // Graph must not be empty
        assertThat(mermaid).startsWith("flowchart TD");
        assertThat(diagram.nodes()).isNotEmpty();

        // Must have concrete leaf/domain nodes (not just root categories)
        var nodeIds = diagram.nodes().stream().map(n -> n.id()).toList();
        assertThat(nodeIds).anyMatch(id -> id.contains("-"));

        // Must span multiple architecture layers
        var layers = diagram.nodes().stream().map(n -> n.type()).distinct().toList();
        assertThat(layers).hasSizeGreaterThanOrEqualTo(3);

        // Must have visible relations
        assertThat(diagram.edges()).isNotEmpty();

        // Must include anchors
        assertThat(diagram.nodes().stream().anyMatch(n -> n.anchor())).isTrue();

        // Layer types must use human-readable names (not two-letter codes)
        for (var node : diagram.nodes()) {
            assertThat(node.type()).doesNotMatch("^[A-Z]{2}$");
        }

        // ── Generate README showcase content ────────────────────────────────

        StringBuilder showcase = new StringBuilder();

        // Summary line — key stats
        long leafCount = showcaseNodeCount(diagram);
        long layerCount = diagram.nodes().stream().map(n -> n.type()).distinct().count();
        showcase.append(String.format(
                "**%d concrete architecture elements** across **%d layers**, "
                        + "connected by **%d traced relations** — generated by the real pipeline.\n\n",
                leafCount, layerCount, diagram.edges().size()));

        // Mermaid graph
        showcase.append("```mermaid\n");
        showcase.append(mermaid);
        showcase.append("```\n\n");

        // Legend
        showcase.append("> **Legend:** ");
        showcase.append("★ = direct match (anchor) · ");
        showcase.append("⚠ = impact hotspot (≥ 80%) · ");
        showcase.append("Rounded nodes = anchors/hotspots · ");
        showcase.append("% = relevance score · ");
        showcase.append("Arrow labels = relation type\n\n");

        // Detail tables in collapsible section
        showcase.append("<details>\n");
        showcase.append("<summary><strong>Pipeline details</strong> — included elements and relationships</summary>\n\n");

        // Included Elements table
        showcase.append("**Included Elements** — selected by the pipeline "
                + "(anchors + propagated + enriched leaf nodes):\n\n");
        showcase.append("| Code | Name | Layer | Relevance | Role | Included Because |\n");
        showcase.append("|---|---|---|---|---|---|\n");
        // Map root codes to layer names for the table
        Map<String, String> rootToLayer = Map.of(
                "CP", "Capabilities", "BP", "Business Processes", "BR", "Business Roles",
                "CR", "Core Services", "CI", "COI Services", "CO", "Communications Services",
                "UA", "User Applications", "IP", "Information Products");
        for (RequirementElementView el : view.getIncludedElements()) {
            String name = el.getTitle() != null ? el.getTitle() : el.getNodeCode();
            String sheet = el.getTaxonomySheet() != null
                    ? rootToLayer.getOrDefault(el.getTaxonomySheet(), el.getTaxonomySheet())
                    : "—";
            String pct = String.format("%.0f%%", el.getRelevance() * 100);
            String role;
            if (el.isAnchor()) {
                role = "★ Anchor";
            } else if (el.getIncludedBecause() != null
                    && el.getIncludedBecause().startsWith("leaf-enrichment")) {
                role = "Enriched leaf";
            } else {
                role = "Propagated";
            }
            String reason = el.getIncludedBecause() != null ? el.getIncludedBecause() : "—";
            showcase.append("| ").append(el.getNodeCode())
                    .append(" | ").append(name)
                    .append(" | ").append(sheet)
                    .append(" | ").append(pct)
                    .append(" | ").append(role)
                    .append(" | ").append(reason)
                    .append(" |\n");
        }

        showcase.append("\n");

        // Included Relationships table
        showcase.append("**Included Relationships** — traversed by relevance propagation:\n\n");
        showcase.append("| Source | Target | Relation Type | Propagated Relevance | Hop |\n");
        showcase.append("|---|---|---|---|---|\n");
        for (RequirementRelationshipView rel : view.getIncludedRelationships()) {
            String pct = String.format("%.0f%%", rel.getPropagatedRelevance() * 100);
            showcase.append("| ").append(rel.getSourceCode())
                    .append(" | ").append(rel.getTargetCode())
                    .append(" | ").append(rel.getRelationType())
                    .append(" | ").append(pct)
                    .append(" | ").append(rel.getHopDistance())
                    .append(" |\n");
        }

        showcase.append("\n</details>\n");

        // Write to target directory for README embedding
        Path outputPath = Path.of("target/readme-showcase.md");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, showcase.toString());

        System.out.println("=== README SHOWCASE OUTPUT ===");
        System.out.println(showcase);
        System.out.println("=== END ===");
        System.out.println("Written to: " + outputPath.toAbsolutePath());
    }

    private static long showcaseNodeCount(DiagramModel diagram) {
        // Count leaf nodes (containing '-') as the "concrete" elements
        return diagram.nodes().stream().filter(n -> n.id().contains("-")).count();
    }
}
