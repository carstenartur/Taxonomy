package com.taxonomy;

import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.RequirementArchitectureView;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-prevention test: verifies that the README.md Mermaid diagram matches
 * the output of {@code exportShowcase()} with the current pipeline.
 *
 * <p>If this test fails, run:
 * <pre>
 *   mvn test -pl taxonomy-app -Dtest=ReadmeShowcaseTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * then copy the Mermaid block from {@code target/readme-showcase.md} into {@code README.md}.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class ReadmeShowcaseDriftTest {

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

    private static final int MAX_PARENT_SEARCH_DEPTH = 5;

    private static final String BUSINESS_TEXT =
            "Provide an integrated communication platform for hospital staff, "
                    + "enabling real-time voice and data exchange between departments, "
                    + "with a clinical dashboard application for patient handoff tracking "
                    + "and team coordination.";

    @BeforeEach
    void ensureSeedRelations() {
        relationRepository.deleteAll();

        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "showcase");
        relationService.createRelation("CP", "CI", RelationType.REALIZES, null, "showcase");
        relationService.createRelation("CP", "CO", RelationType.REALIZES, null, "showcase");

        relationService.createRelation("CR", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CI", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CO", "BP", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CO", "CR", RelationType.SUPPORTS, null, "showcase");

        relationService.createRelation("UA", "CR", RelationType.USES, null, "showcase");
        relationService.createRelation("UA", "CI", RelationType.USES, null, "showcase");
        relationService.createRelation("UA", "CO", RelationType.USES, null, "showcase");

        relationService.createRelation("CR", "UA", RelationType.SUPPORTS, null, "showcase");

        relationService.createRelation("CI", "CP", RelationType.FULFILLS, null, "showcase");
        relationService.createRelation("CR", "CP", RelationType.FULFILLS, null, "showcase");

        relationService.createRelation("BR", "BP", RelationType.ASSIGNED_TO, null, "showcase");

        relationService.createRelation("BP", "IP", RelationType.PRODUCES, null, "showcase");
        relationService.createRelation("BP", "IP", RelationType.CONSUMES, null, "showcase");

        relationService.createRelation("CR", "CI", RelationType.DEPENDS_ON, null, "showcase");
        relationService.createRelation("CI", "CR", RelationType.DEPENDS_ON, null, "showcase");
        relationService.createRelation("CO", "CR", RelationType.DEPENDS_ON, null, "showcase");

        relationService.createRelation("UA", "IP", RelationType.CONSUMES, null, "showcase");
        relationService.createRelation("UA", "BP", RelationType.SUPPORTS, null, "showcase");

        relationService.createRelation("CR", "BR", RelationType.SUPPORTS, null, "showcase");
        relationService.createRelation("CI", "BR", RelationType.SUPPORTS, null, "showcase");
    }

    @Test
    void readmeMermaidMatchesExportShowcase() throws IOException {
        // ── Generate the expected Mermaid output ────────────────────────────
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 92);
        scores.put("CO", 88);
        scores.put("CR", 81);
        scores.put("UA", 74);
        scores.put("BP", 71);
        scores.put("IP", 60);
        scores.put("BR", 55);
        scores.put("CI", 45);

        scores.put("CP-1000", 90);
        scores.put("CP-1023", 85);
        scores.put("CP-1010", 40);
        scores.put("CP-1030", 30);

        scores.put("CO-1000", 86);
        scores.put("CO-1011", 80);
        scores.put("CO-1063", 70);
        scores.put("CO-1050", 55);
        scores.put("CO-1019", 52);

        scores.put("CR-1000", 79);
        scores.put("CR-1047", 75);
        scores.put("CR-1039", 52);
        scores.put("CR-1021", 48);

        scores.put("UA-1000", 72);
        scores.put("UA-1179", 68);
        scores.put("UA-1574", 62);

        scores.put("BP-1000", 69);
        scores.put("BP-1327", 65);
        scores.put("BP-1490", 58);
        scores.put("BP-1697", 52);

        scores.put("IP-1000", 58);
        scores.put("IP-1078", 48);
        scores.put("IP-1023", 42);
        scores.put("IP-2106", 38);

        scores.put("BR-1000", 53);
        scores.put("BR-1057", 45);
        scores.put("BR-1023", 38);
        scores.put("BR-1334", 32);

        RequirementArchitectureView view = architectureViewService.build(scores, BUSINESS_TEXT, 20);
        DiagramModel diagram = diagramProjectionService.project(view, BUSINESS_TEXT);
        String expectedMermaid = mermaidExportService.exportShowcase(diagram, MermaidLabels.english());

        // ── Read the actual Mermaid block from README.md ─────────────────────
        Path readmePath = findReadme();
        String readmeMermaid = extractMermaidFromReadme(readmePath);

        // ── Compare (normalized) ─────────────────────────────────────────────
        String normalizedExpected = normalize(expectedMermaid);
        String normalizedActual = normalize(readmeMermaid);

        assertThat(normalizedActual)
                .as("README.md Mermaid diagram is out of sync with exportShowcase() output.\n"
                        + "Run ReadmeShowcaseTest.generateReadmeShowcase() and copy "
                        + "target/readme-showcase.md into README.md to fix this.\n"
                        + "Command: mvn test -pl taxonomy-app -Dtest=ReadmeShowcaseTest "
                        + "-Dsurefire.failIfNoSpecifiedTests=false")
                .isEqualTo(normalizedExpected);
    }

    /**
     * Locates README.md by walking up from the working directory until found.
     */
    private static Path findReadme() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < MAX_PARENT_SEARCH_DEPTH; i++) {
            Path candidate = dir.resolve("README.md");
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) break;
            dir = parent;
        }
        throw new IOException("README.md not found from working directory: "
                + Path.of("").toAbsolutePath());
    }

    /**
     * Extracts the Mermaid code block that appears after "Architecture Impact Showcase"
     * in the given file.
     */
    private static String extractMermaidFromReadme(Path readmePath) throws IOException {
        List<String> lines = Files.readAllLines(readmePath);
        boolean inShowcaseSection = false;
        boolean inMermaidBlock = false;
        StringBuilder mermaid = new StringBuilder();

        for (String line : lines) {
            if (!inShowcaseSection) {
                if (line.contains("Architecture Impact Showcase")) {
                    inShowcaseSection = true;
                }
                continue;
            }
            if (!inMermaidBlock) {
                if (line.trim().equals("```mermaid")) {
                    inMermaidBlock = true;
                }
                continue;
            }
            if (line.trim().equals("```")) {
                break;
            }
            mermaid.append(line).append('\n');
        }

        if (mermaid.isEmpty()) {
            throw new IOException("No Mermaid block found after 'Architecture Impact Showcase' in "
                    + readmePath);
        }
        return mermaid.toString();
    }

    /**
     * Normalizes a Mermaid string for comparison: trims each line and strips trailing blank lines.
     */
    private static String normalize(String mermaid) {
        String[] lines = mermaid.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
