package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AstToModelMapper}: DSL → Canonical Model mapping.
 */
class AstToModelMapperTest {

    private TaxDslParser parser;
    private AstToModelMapper mapper;

    @BeforeEach
    void setUp() {
        parser = new TaxDslParser();
        mapper = new AstToModelMapper();
    }

    @Test
    void mapElement() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Secure Communications";
                  description: "Ability to communicate securely";
                  taxonomy: "Capabilities";
                  x-owner: "CIS";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getElements()).hasSize(1);
        ArchitectureElement el = model.getElements().get(0);
        assertThat(el.getId()).isEqualTo("CP-1023");
        assertThat(el.getType()).isEqualTo("Capability");
        assertThat(el.getTitle()).isEqualTo("Secure Communications");
        assertThat(el.getDescription()).isEqualTo("Ability to communicate securely");
        assertThat(el.getTaxonomy()).isEqualTo("Capabilities");
        assertThat(el.getExtensions()).containsEntry("x-owner", "CIS");
    }

    @Test
    void mapRelation() {
        String dsl = """
                relation CR-1011 SUPPORTS BP-1327 {
                  status: proposed;
                  confidence: 0.76;
                  provenance: "analysis";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getRelations()).hasSize(1);
        ArchitectureRelation rel = model.getRelations().get(0);
        assertThat(rel.getSourceId()).isEqualTo("CR-1011");
        assertThat(rel.getRelationType()).isEqualTo("SUPPORTS");
        assertThat(rel.getTargetId()).isEqualTo("BP-1327");
        assertThat(rel.getStatus()).isEqualTo("proposed");
        assertThat(rel.getConfidence()).isEqualTo(0.76);
        assertThat(rel.getProvenance()).isEqualTo("analysis");
    }

    @Test
    void mapRequirement() {
        String dsl = """
                requirement REQ-001 {
                  title: "Secure voice comms";
                  text: "Provide secure voice communications";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getRequirements()).hasSize(1);
        ArchitectureRequirement req = model.getRequirements().get(0);
        assertThat(req.getId()).isEqualTo("REQ-001");
        assertThat(req.getTitle()).isEqualTo("Secure voice comms");
        assertThat(req.getText()).isEqualTo("Provide secure voice communications");
    }

    @Test
    void mapMapping() {
        String dsl = """
                mapping REQ-001 -> CP-1023 {
                  score: 0.92;
                  source: "llm";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getMappings()).hasSize(1);
        RequirementMapping m = model.getMappings().get(0);
        assertThat(m.getRequirementId()).isEqualTo("REQ-001");
        assertThat(m.getElementId()).isEqualTo("CP-1023");
        assertThat(m.getScore()).isEqualTo(0.92);
        assertThat(m.getSource()).isEqualTo("llm");
    }

    @Test
    void mapView() {
        String dsl = """
                view overview {
                  title: "Overview";
                  include: "CP-1023";
                  include: "BP-1327";
                  layout: layered;
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getViews()).hasSize(1);
        ArchitectureView view = model.getViews().get(0);
        assertThat(view.getId()).isEqualTo("overview");
        assertThat(view.getTitle()).isEqualTo("Overview");
        assertThat(view.getIncludes()).containsExactly("CP-1023", "BP-1327");
        assertThat(view.getLayout()).isEqualTo("layered");
    }

    @Test
    void mapEvidence() {
        String dsl = """
                evidence EV-001 {
                  for-relation: CR-1011 SUPPORTS BP-1327;
                  type: LLM;
                  model: "gpt-4.1-mini";
                  confidence: 0.76;
                  summary: "The service supports the process.";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getEvidence()).hasSize(1);
        ArchitectureEvidence ev = model.getEvidence().get(0);
        assertThat(ev.getId()).isEqualTo("EV-001");
        assertThat(ev.getForRelationSource()).isEqualTo("CR-1011");
        assertThat(ev.getForRelationType()).isEqualTo("SUPPORTS");
        assertThat(ev.getForRelationTarget()).isEqualTo("BP-1327");
        assertThat(ev.getEvidenceType()).isEqualTo("LLM");
        assertThat(ev.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(ev.getConfidence()).isEqualTo(0.76);
    }

    @Test
    void mapUnknownBlockTypesAreSkipped() {
        String dsl = """
                constraint CON-001 {
                  title: "Max latency";
                }
                
                element CP-1023 type Capability {
                  title: "Test";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        // Unknown block should not appear in model, only in AST
        assertThat(model.getElements()).hasSize(1);
        assertThat(model.getRelations()).isEmpty();
        assertThat(doc.getBlocks()).hasSize(2); // both blocks in AST
    }

    @Test
    void mapCompleteDocument() {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test";
                }
                
                element CP-1023 type Capability {
                  title: "Cap One";
                }
                
                element BP-1327 type Process {
                  title: "Process One";
                }
                
                relation CP-1023 REALIZES BP-1327 {
                  status: accepted;
                  confidence: 0.83;
                }
                
                requirement REQ-001 {
                  title: "Req One";
                  text: "Requirement text";
                }
                
                mapping REQ-001 -> CP-1023 {
                  score: 0.92;
                  source: "llm";
                }
                
                view overview {
                  title: "Overview";
                  include: "CP-1023";
                  include: "BP-1327";
                  layout: layered;
                }
                
                evidence EV-001 {
                  for-relation: CP-1023 REALIZES BP-1327;
                  type: LLM;
                  confidence: 0.83;
                  summary: "Direct realization.";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getElements()).hasSize(2);
        assertThat(model.getRelations()).hasSize(1);
        assertThat(model.getRequirements()).hasSize(1);
        assertThat(model.getMappings()).hasSize(1);
        assertThat(model.getViews()).hasSize(1);
        assertThat(model.getEvidence()).hasSize(1);
    }

    // ── Provenance block mapping ──────────────────────────────────────────────

    @Test
    void mapSource() {
        String dsl = """
                source SRC-001 {
                  type: "REGULATION";
                  title: "Test Regulation";
                  canonicalIdentifier: "VV-2026-001";
                  canonicalUrl: "https://example.gov/vv/2026/001";
                  originSystem: "gov-portal";
                  language: "de";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getSources()).hasSize(1);
        ArchitectureSource src = model.getSources().get(0);
        assertThat(src.getId()).isEqualTo("SRC-001");
        assertThat(src.getSourceType()).isEqualTo("REGULATION");
        assertThat(src.getTitle()).isEqualTo("Test Regulation");
        assertThat(src.getCanonicalIdentifier()).isEqualTo("VV-2026-001");
        assertThat(src.getLanguage()).isEqualTo("de");
    }

    @Test
    void mapSourceVersion() {
        String dsl = """
                sourceVersion SRCV-001 {
                  source: "SRC-001";
                  versionLabel: "2026-04";
                  contentHash: "sha256:abc123";
                  mimeType: "application/pdf";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getSourceVersions()).hasSize(1);
        ArchitectureSourceVersion sv = model.getSourceVersions().get(0);
        assertThat(sv.getId()).isEqualTo("SRCV-001");
        assertThat(sv.getSourceId()).isEqualTo("SRC-001");
        assertThat(sv.getVersionLabel()).isEqualTo("2026-04");
        assertThat(sv.getContentHash()).isEqualTo("sha256:abc123");
    }

    @Test
    void mapSourceFragment() {
        String dsl = """
                sourceFragment SFR-001 {
                  sourceVersion: "SRCV-001";
                  sectionPath: "Chapter 2";
                  paragraphRef: "§ 4 Abs. 2";
                  pageFrom: 3;
                  pageTo: 5;
                  text: "The authority must ensure...";
                  fragmentHash: "sha256:def456";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getSourceFragments()).hasSize(1);
        ArchitectureSourceFragment sf = model.getSourceFragments().get(0);
        assertThat(sf.getId()).isEqualTo("SFR-001");
        assertThat(sf.getSourceVersionId()).isEqualTo("SRCV-001");
        assertThat(sf.getSectionPath()).isEqualTo("Chapter 2");
        assertThat(sf.getPageFrom()).isEqualTo(3);
        assertThat(sf.getPageTo()).isEqualTo(5);
    }

    @Test
    void mapRequirementSourceLink() {
        String dsl = """
                requirementSourceLink RSL-001 {
                  requirement: "REQ-001";
                  source: "SRC-001";
                  sourceVersion: "SRCV-001";
                  linkType: "EXTRACTED_FROM";
                  confidence: 0.91;
                  note: "Parsed from regulation";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        CanonicalArchitectureModel model = mapper.map(doc);

        assertThat(model.getRequirementSourceLinks()).hasSize(1);
        ArchitectureRequirementSourceLink rsl = model.getRequirementSourceLinks().get(0);
        assertThat(rsl.getId()).isEqualTo("RSL-001");
        assertThat(rsl.getRequirementId()).isEqualTo("REQ-001");
        assertThat(rsl.getSourceId()).isEqualTo("SRC-001");
        assertThat(rsl.getLinkType()).isEqualTo("EXTRACTED_FROM");
        assertThat(rsl.getConfidence()).isEqualTo(0.91);
        assertThat(rsl.getNote()).isEqualTo("Parsed from regulation");
    }
}
