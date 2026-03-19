package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests: parse → serialize → parse and verify structural equality.
 *
 * <p>Ensures that the serializer produces output that is functionally equivalent
 * when re-parsed, which is critical for Git-friendly deterministic diffs.
 */
class TaxDslRoundtripTest {

    private TaxDslParser parser;
    private TaxDslSerializer serializer;

    @BeforeEach
    void setUp() {
        parser = new TaxDslParser();
        serializer = new TaxDslSerializer();
    }

    @Test
    void roundtripFullDocument() {
        String original = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "mission.secure-voice";
                }

                element CP-1023 type Capability {
                  title: "Secure Communications Capability";
                  description: "Ability to provide secure communications";
                  taxonomy: "Capabilities";
                }

                element BP-1327 type Process {
                  title: "Conduct Operations";
                  description: "Execution of operations";
                  taxonomy: "Business Processes";
                }

                relation CP-1023 REALIZES BP-1327 {
                  status: accepted;
                  confidence: 0.83;
                  provenance: "manual";
                }

                requirement REQ-001 {
                  title: "Secure voice communications for deployed forces";
                  text: "Provide secure voice communications for deployed joint forces";
                }

                mapping REQ-001 -> CP-1023 {
                  score: 0.92;
                  source: "llm";
                }

                view secure-voice-overview {
                  title: "Secure Voice Architecture Overview";
                  include: REQ-001;
                  include: CP-1023;
                  include: BP-1327;
                  layout: layered;
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        // Verify structural equivalence
        assertThat(doc2.getMeta()).isNotNull();
        assertThat(doc2.getMeta().language()).isEqualTo("taxdsl");
        assertThat(doc2.getMeta().version()).isEqualTo("2.0");
        assertThat(doc2.getMeta().namespace()).isEqualTo("mission.secure-voice");

        assertThat(doc2.blocksOfKind("element")).hasSize(2);
        assertThat(doc2.blocksOfKind("relation")).hasSize(1);
        assertThat(doc2.blocksOfKind("requirement")).hasSize(1);
        assertThat(doc2.blocksOfKind("mapping")).hasSize(1);
        assertThat(doc2.blocksOfKind("view")).hasSize(1);

        // Verify element content preserved (elements sorted by ID: BP-1327 before CP-1023)
        var elements = doc2.blocksOfKind("element");
        var cpEl = elements.stream().filter(b -> b.getHeaderTokens().get(0).equals("CP-1023")).findFirst().orElseThrow();
        assertThat(cpEl.getHeaderTokens()).containsExactly("CP-1023", "type", "Capability");
        assertThat(cpEl.property("title")).isEqualTo("Secure Communications Capability");

        // Verify relation content preserved
        var rel = doc2.blocksOfKind("relation").get(0);
        assertThat(rel.getHeaderTokens()).containsExactly("CP-1023", "REALIZES", "BP-1327");
        assertThat(rel.property("confidence")).isEqualTo("0.83");

        // Verify view includes preserved
        var view = doc2.blocksOfKind("view").get(0);
        assertThat(view.propertyValues("include")).containsExactly("REQ-001", "CP-1023", "BP-1327");
    }

    @Test
    void roundtripPreservesExtensionAttributes() {
        String original = """
                element CP-1023 type Capability {
                  title: "Test";
                  x-owner: "CIS";
                  x-criticality: "high";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        var block = doc2.getBlocks().get(0);
        assertThat(block.getExtensions()).containsEntry("x-owner", "CIS");
        assertThat(block.getExtensions()).containsEntry("x-criticality", "high");
    }

    @Test
    void roundtripPreservesUnknownBlockTypes() {
        String original = """
                constraint CON-001 {
                  title: "Max latency";
                  value: "200ms";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        assertThat(doc2.getBlocks()).hasSize(1);
        assertThat(doc2.getBlocks().get(0).getKind()).isEqualTo("constraint");
        assertThat(doc2.getBlocks().get(0).property("title")).isEqualTo("Max latency");
    }

    @Test
    void roundtripDoubleSerializationIsStable() {
        String original = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test";
                }

                element CP-1023 type Capability {
                  title: "Test";
                  description: "Description";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized1 = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized1);
        String serialized2 = serializer.serialize(doc2);

        // Second serialization must be identical to the first
        assertThat(serialized2).isEqualTo(serialized1);
    }

    @Test
    void roundtripEvidenceBlock() {
        String original = """
                evidence EV-001 {
                  for-relation: CR-1011 SUPPORTS BP-1327;
                  type: LLM;
                  model: "gpt-4.1-mini";
                  confidence: 0.76;
                  summary: "The service supports the process.";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        assertThat(doc2.getBlocks()).hasSize(1);
        var block = doc2.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("evidence");
        assertThat(block.getHeaderTokens()).containsExactly("EV-001");
        assertThat(block.property("for-relation")).isEqualTo("CR-1011 SUPPORTS BP-1327");
        assertThat(block.property("confidence")).isEqualTo("0.76");
    }

    @Test
    void roundtripEscapedQuotes() {
        // Create a document with escaped quotes in values
        String original = """
                element CP-1023 type Capability {
                  title: "He said \\"hello\\"";
                  description: "Path: C:\\\\Users\\\\test";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        assertThat(doc1.getBlocks().get(0).property("title")).isEqualTo("He said \"hello\"");

        String serialized = serializer.serialize(doc1);
        assertThat(serialized).contains("He said \\\"hello\\\"");

        DocumentAst doc2 = parser.parse(serialized);
        assertThat(doc2.getBlocks().get(0).property("title")).isEqualTo("He said \"hello\"");

        // Verify double serialization is stable
        String serialized2 = serializer.serialize(doc2);
        assertThat(serialized2).isEqualTo(serialized);
    }

    @Test
    void roundtripDeterministicBlockOrdering() {
        // Input with blocks in non-canonical order
        String original = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                }

                relation CP-1023 REALIZES BP-1327 {
                  status: accepted;
                }

                element CP-1023 type Capability {
                  title: "Cap One";
                }

                element BP-1327 type Process {
                  title: "Proc One";
                }
                """;

        DocumentAst doc1 = parser.parse(original);
        String serialized = serializer.serialize(doc1);

        // After serialization, blocks are sorted: elements first (BP before CP), then relations
        assertThat(serialized.indexOf("element BP-1327")).isLessThan(serialized.indexOf("element CP-1023"));
        assertThat(serialized.indexOf("element CP-1023")).isLessThan(serialized.indexOf("relation CP-1023"));

        // Double serialization is stable
        DocumentAst doc2 = parser.parse(serialized);
        String serialized2 = serializer.serialize(doc2);
        assertThat(serialized2).isEqualTo(serialized);
    }

    // ── Provenance round-trip ─────────────────────────────────────────────────

    @Test
    void roundtripProvenanceBlocks() {
        String dsl = """
                source SRC-001 {
                  type: "REGULATION";
                  title: "Test Regulation";
                }

                sourceVersion SRCV-001 {
                  source: "SRC-001";
                  versionLabel: "2026-04";
                  contentHash: "sha256:abc123";
                }

                sourceFragment SFR-001 {
                  sourceVersion: "SRCV-001";
                  sectionPath: "Chapter 2";
                  paragraphRef: "§ 4";
                  pageFrom: 3;
                  pageTo: 5;
                  text: "The authority must ensure...";
                  fragmentHash: "sha256:def456";
                }

                requirementSourceLink RSL-001 {
                  requirement: "REQ-001";
                  source: "SRC-001";
                  sourceVersion: "SRCV-001";
                  sourceFragment: "SFR-001";
                  linkType: "EXTRACTED_FROM";
                  confidence: 0.91;
                  note: "Parsed from regulation";
                }
                """;
        DocumentAst doc1 = parser.parse(dsl);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        assertThat(doc2.blocksOfKind("source")).hasSize(1);
        assertThat(doc2.blocksOfKind("sourceVersion")).hasSize(1);
        assertThat(doc2.blocksOfKind("sourceFragment")).hasSize(1);
        assertThat(doc2.blocksOfKind("requirementSourceLink")).hasSize(1);

        // Verify content preserved
        var src = doc2.blocksOfKind("source").get(0);
        assertThat(src.property("type")).isEqualTo("REGULATION");
        assertThat(src.property("title")).isEqualTo("Test Regulation");

        var rsl = doc2.blocksOfKind("requirementSourceLink").get(0);
        assertThat(rsl.property("linkType")).isEqualTo("EXTRACTED_FROM");
        assertThat(rsl.property("confidence")).isEqualTo("0.91");

        // Double serialization is stable
        String serialized2 = serializer.serialize(doc2);
        assertThat(serialized2).isEqualTo(serialized);
    }

    @Test
    void roundtripMixedArchitectureAndProvenanceBlocks() {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "test.provenance";
                }

                element CP-1023 type Capability {
                  title: "Test Capability";
                }

                requirement REQ-001 {
                  title: "Test Requirement";
                  text: "Requirement text";
                }

                source SRC-001 {
                  type: "REGULATION";
                  title: "Test Source";
                }

                requirementSourceLink RSL-001 {
                  requirement: "REQ-001";
                  source: "SRC-001";
                  linkType: "EXTRACTED_FROM";
                  confidence: 0.85;
                }
                """;
        DocumentAst doc1 = parser.parse(dsl);
        String serialized = serializer.serialize(doc1);
        DocumentAst doc2 = parser.parse(serialized);

        assertThat(doc2.getMeta()).isNotNull();
        assertThat(doc2.blocksOfKind("element")).hasSize(1);
        assertThat(doc2.blocksOfKind("requirement")).hasSize(1);
        assertThat(doc2.blocksOfKind("source")).hasSize(1);
        assertThat(doc2.blocksOfKind("requirementSourceLink")).hasSize(1);

        // Verify deterministic ordering: element before requirement before source
        int elemPos = serialized.indexOf("element CP-1023");
        int reqPos = serialized.indexOf("requirement REQ-001");
        int srcPos = serialized.indexOf("source SRC-001");
        int rslPos = serialized.indexOf("requirementSourceLink RSL-001");
        assertThat(elemPos).isLessThan(reqPos);
        assertThat(reqPos).isLessThan(srcPos);
        assertThat(srcPos).isLessThan(rslPos);
    }
}
