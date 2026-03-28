package com.taxonomy;

import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.catalog.service.TaxonomyService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Autowired
    private TaxonomyService taxonomyService;

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
        //
        // IMPORTANT: The taxonomy hierarchy has up to 5 levels beneath each root.
        // Every intermediate node on the path from root to leaf must carry a score
        // so that the hierarchical narrowing is visible.  The paths are:
        //   CP → CP-1000 → CP-1023                          (3 levels)
        //   CP → CP-1000 → CP-1010 → CP-1030                (4 levels)
        //   CO → CO-1000 → CO-1011                           (3 levels)
        //   CO → CO-1000 → CO-1063                           (3 levels)
        //   CO → CO-1000 → CO-1063 → CO-1050 → CO-1019      (5 levels)
        //   CR → CR-1000 → CR-1047                           (3 levels)
        //   CR → CR-1000 → CR-1047 → CR-1039 → CR-1021      (5 levels)
        //   UA → UA-1000 → UA-1015                           (3 levels)
        //   BP → BP-1000 → BP-1327                           (3 levels)
        //   BR → BR-1000 → BR-1154 → BR-1161 → BR-1063 → BR-1043  (6 levels)
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

        // Intermediate + leaf scores — every node on the path from root to leaf.
        // Only codes known to exist in the C3 Taxonomy Catalogue workbook.

        // CP path: CP → CP-1000 → CP-1023 / CP-1010 → CP-1030
        scores.put("CP-1000", 90);  // Capabilities (L1 container)
        scores.put("CP-1023", 85);  // Communication and Information System Capabilities (L2)
        scores.put("CP-1010", 40);  // Battlespace Management Capabilities (L2)
        scores.put("CP-1030", 30);  // Cyberspace Battlespace Management Capabilities (L3)

        // CO path: CO → CO-1000 → CO-1011 / CO-1063 → CO-1050 → CO-1019
        scores.put("CO-1000", 86);  // Communications Services (L1 container)
        scores.put("CO-1011", 80);  // Communications Access Services (L2)
        scores.put("CO-1063", 70);  // Transport Services (L2)
        scores.put("CO-1050", 55);  // Switching Services (L3)
        scores.put("CO-1019", 52);  // Frame Switching Services (L4)

        // CR path: CR → CR-1000 → CR-1047 → CR-1039 → CR-1021
        scores.put("CR-1000", 79);  // Core Services (L1 container)
        scores.put("CR-1047", 75);  // Infrastructure Services (L2)
        scores.put("CR-1039", 52);  // Security Services (L3)
        scores.put("CR-1021", 48);  // Digital Certificate Services (L4)

        // UA path: UA → UA-1000 → UA-1015
        scores.put("UA-1000", 72);  // User Applications (L1 container)
        scores.put("UA-1015", 68);  // Air Applications (L2)

        // BP path: BP → BP-1000 → BP-1327
        scores.put("BP-1000", 69);  // Business Processes (L1 container)
        scores.put("BP-1327", 65);  // Enable (L2)

        // BR path: BR → BR-1000 → BR-1154 → BR-1161 → BR-1063 → BR-1043
        scores.put("BR-1000", 53);  // Business Roles (L1 container)
        scores.put("BR-1154", 45);  // Management Roles (L2)
        scores.put("BR-1161", 42);  // Facilities Roles (L3)
        scores.put("BR-1063", 38);  // Facilities Management (L4)
        scores.put("BR-1043", 35);  // Facilities Management Roles (L5)

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

        // Every scored leaf must have scores on every intermediate node
        // from root to leaf — the scoring path must be complete.
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            String code = entry.getKey();
            if (!code.contains("-")) continue; // skip roots
            List<TaxonomyNode> path = taxonomyService.getPathToRoot(code);
            for (TaxonomyNode n : path) {
                assertThat(scores)
                        .as("Intermediate node '%s' on path to '%s' must have a score", n.getCode(), code)
                        .containsKey(n.getCode());
            }
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

        // Map root codes to layer names (shared by all tables)
        Map<String, String> rootToLayer = Map.of(
                "CP", "Capabilities", "BP", "Business Processes", "BR", "Business Roles",
                "CR", "Core Services", "CI", "COI Services", "CO", "Communications Services",
                "UA", "User Applications", "IP", "Information Products");

        // Build code → title map from included elements AND scored nodes
        Map<String, String> codeToTitle = new LinkedHashMap<>();
        for (RequirementElementView el : view.getIncludedElements()) {
            codeToTitle.put(el.getNodeCode(),
                    el.getTitle() != null ? el.getTitle() : el.getNodeCode());
        }
        // Also resolve names for intermediate nodes that may not be in the view
        for (String code : scores.keySet()) {
            if (!codeToTitle.containsKey(code)) {
                TaxonomyNode node = taxonomyService.getNodeByCode(code);
                if (node != null) {
                    codeToTitle.put(code, node.getNameEn() != null ? node.getNameEn() : code);
                }
            }
        }

        // ── Scoring Trace section ──────────────────────────────────────────

        showcase.append("<details>\n");
        showcase.append("<summary><strong>Scoring trace</strong> — "
                + "how relevance narrows from root to leaf</summary>\n\n");
        showcase.append("The LLM does **not** score leaf nodes in isolation. "
                + "It first evaluates each root category (distributing a total relevance budget), "
                + "then recursively narrows the score into child nodes at each taxonomy level. "
                + "Every intermediate node receives a score and carries architectural meaning — "
                + "the result is **hierarchical narrowing**, not isolated leaf matching.\n\n");

        showcase.append("| Scoring Path | Score | Role |\n");
        showcase.append("|---|---|---|\n");

        // Cache all paths for scored non-root codes (avoids repeated DB lookups)
        Map<String, List<TaxonomyNode>> pathCache = new LinkedHashMap<>();
        for (String code : scores.keySet()) {
            if (code.contains("-")) {
                pathCache.put(code, taxonomyService.getPathToRoot(code));
            }
        }

        // Collect the deepest leaf per distinct path (leaf = no scored children).
        // A code is a leaf if no other scored code has it on its path.
        List<String> leafCodes = pathCache.keySet().stream()
                .filter(code -> pathCache.entrySet().stream()
                        .noneMatch(e -> !e.getKey().equals(code)
                                && e.getValue().stream()
                                        .anyMatch(n -> n.getCode().equals(code))))
                .toList();

        // Iterate roots in descending score order
        List<Map.Entry<String, Integer>> rootEntries = scores.entrySet().stream()
                .filter(e -> !e.getKey().contains("-"))
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .toList();

        for (Map.Entry<String, Integer> rootEntry : rootEntries) {
            String rootCode = rootEntry.getKey();
            int rootScore = rootEntry.getValue();
            String rootName = rootToLayer.getOrDefault(rootCode, rootCode);

            // Find all leaves belonging to this root
            List<String> rootLeaves = leafCodes.stream()
                    .filter(c -> c.startsWith(rootCode + "-"))
                    .toList();

            showcase.append("| **").append(rootCode).append("** ").append(rootName)
                    .append(" | **").append(rootScore).append("%** | Root category |\n");

            if (rootLeaves.isEmpty()) {
                showcase.append("| &ensp;└ _(no leaf nodes scored above threshold)_ "
                        + "| — | — |\n");
                continue;
            }

            // For each leaf, render the full path from root to leaf.
            // Track already-shown intermediate codes to avoid duplicates.
            var shownIntermediates = new java.util.HashSet<String>();
            for (int li = 0; li < rootLeaves.size(); li++) {
                String leafCode = rootLeaves.get(li);
                List<TaxonomyNode> fullPath = pathCache.get(leafCode);
                boolean isLastLeaf = (li == rootLeaves.size() - 1);

                // Skip root (already rendered); render intermediates + leaf
                for (int pi = 1; pi < fullPath.size(); pi++) {
                    TaxonomyNode n = fullPath.get(pi);
                    boolean isLeaf = (pi == fullPath.size() - 1);
                    int nodeScore = scores.getOrDefault(n.getCode(), 0);
                    String nodeName = codeToTitle.getOrDefault(n.getCode(), n.getCode());
                    int parentScore = scores.getOrDefault(fullPath.get(pi - 1).getCode(), 0);

                    // Skip already-shown intermediates (shared ancestors)
                    if (!isLeaf && shownIntermediates.contains(n.getCode())) {
                        continue;
                    }

                    // Indentation: depth within the tree (pi = 1 → 1 indent, etc.)
                    String indent = "&ensp;".repeat(pi);
                    String prefix = (isLeaf && isLastLeaf) ? "└" : "├";
                    String role = isLeaf
                            ? "Leaf — narrowed from " + parentScore + "%"
                            : "Intermediate (L" + n.getLevel() + ") — narrows " + parentScore + "%";

                    showcase.append("| ").append(indent).append(prefix).append(" ")
                            .append(n.getCode()).append(" ").append(nodeName)
                            .append(" | ").append(nodeScore).append("%")
                            .append(" | ").append(role).append(" |\n");

                    if (!isLeaf) {
                        shownIntermediates.add(n.getCode());
                    }
                }
            }
        }

        showcase.append("\n> Each root score is the **budget** that the LLM distributes "
                + "among its children. A leaf score of 85% under a root of 92% means "
                + "that child consumed most of the parent's relevance.\n");
        showcase.append("\n</details>\n\n");

        // ── Detail tables in collapsible section ───────────────────────────

        showcase.append("<details>\n");
        showcase.append("<summary><strong>Pipeline details</strong> — included elements and relationships</summary>\n\n");

        // Included Elements table (with Path column)
        showcase.append("**Included Elements** — selected by the pipeline "
                + "(anchors + propagated + enriched leaf nodes):\n\n");
        showcase.append("| Code | Name | Layer | Relevance | Path | Role | Included Because |\n");
        showcase.append("|---|---|---|---|---|---|---|\n");
        for (RequirementElementView el : view.getIncludedElements()) {
            String name = el.getTitle() != null ? el.getTitle() : el.getNodeCode();
            String sheet = el.getTaxonomySheet() != null
                    ? rootToLayer.getOrDefault(el.getTaxonomySheet(), el.getTaxonomySheet())
                    : "—";
            String pct = String.format("%.0f%%", el.getRelevance() * 100);
            // Build hierarchy path from the real taxonomy tree (use cache if available)
            String path;
            String code = el.getNodeCode();
            if (code.contains("-")) {
                List<TaxonomyNode> fullPath = pathCache.containsKey(code)
                        ? pathCache.get(code)
                        : taxonomyService.getPathToRoot(code);
                path = fullPath.stream()
                        .map(TaxonomyNode::getCode)
                        .collect(Collectors.joining(" > "));
            } else {
                path = code + " _(root)_";
            }
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
            showcase.append("| ").append(code)
                    .append(" | ").append(name)
                    .append(" | ").append(sheet)
                    .append(" | ").append(pct)
                    .append(" | ").append(path)
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
