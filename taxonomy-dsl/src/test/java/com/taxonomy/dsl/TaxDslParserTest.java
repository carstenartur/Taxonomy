package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TaxDslParser}.
 */
class TaxDslParserTest {

    private TaxDslParser parser;

    @BeforeEach
    void setUp() {
        parser = new TaxDslParser();
    }

    @Test
    void parseEmptyInput() {
        DocumentAst doc = parser.parse("");
        assertThat(doc.getMeta()).isNull();
        assertThat(doc.getBlocks()).isEmpty();
    }

    @Test
    void parseNullInput() {
        DocumentAst doc = parser.parse(null);
        assertThat(doc.getMeta()).isNull();
        assertThat(doc.getBlocks()).isEmpty();
    }

    @Test
    void parseMetaBlock() {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "mission.secure-voice";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getMeta()).isNotNull();
        assertThat(doc.getMeta().language()).isEqualTo("taxdsl");
        assertThat(doc.getMeta().version()).isEqualTo("2.0");
        assertThat(doc.getMeta().namespace()).isEqualTo("mission.secure-voice");
    }

    @Test
    void parseElementBlock() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Secure Communications Capability";
                  description: "Ability to provide secure communications";
                  taxonomy: "Capabilities";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("element");
        assertThat(block.getHeaderTokens()).containsExactly("CP-1023", "type", "Capability");
        assertThat(block.property("title")).isEqualTo("Secure Communications Capability");
        assertThat(block.property("description")).isEqualTo("Ability to provide secure communications");
        assertThat(block.property("taxonomy")).isEqualTo("Capabilities");
    }

    @Test
    void parseRelationBlock() {
        String dsl = """
                relation CR-1011 SUPPORTS BP-1327 {
                  status: proposed;
                  confidence: 0.76;
                  provenance: "analysis";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("relation");
        assertThat(block.getHeaderTokens()).containsExactly("CR-1011", "SUPPORTS", "BP-1327");
        assertThat(block.property("status")).isEqualTo("proposed");
        assertThat(block.property("confidence")).isEqualTo("0.76");
        assertThat(block.property("provenance")).isEqualTo("analysis");
    }

    @Test
    void parseRequirementBlock() {
        String dsl = """
                requirement REQ-001 {
                  title: "Secure voice communications for deployed forces";
                  text: "Provide secure voice communications for deployed joint forces";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("requirement");
        assertThat(block.getHeaderTokens()).containsExactly("REQ-001");
        assertThat(block.property("title")).isEqualTo("Secure voice communications for deployed forces");
        assertThat(block.property("text")).isEqualTo("Provide secure voice communications for deployed joint forces");
    }

    @Test
    void parseMappingBlock() {
        String dsl = """
                mapping REQ-001 -> CP-1023 {
                  score: 0.92;
                  source: "llm";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("mapping");
        assertThat(block.getHeaderTokens()).containsExactly("REQ-001", "->", "CP-1023");
        assertThat(block.property("score")).isEqualTo("0.92");
        assertThat(block.property("source")).isEqualTo("llm");
    }

    @Test
    void parseViewBlock() {
        String dsl = """
                view secure-voice-overview {
                  title: "Secure Voice Architecture Overview";
                  include: REQ-001;
                  include: CP-1023;
                  include: BP-1327;
                  layout: layered;
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("view");
        assertThat(block.getHeaderTokens()).containsExactly("secure-voice-overview");
        assertThat(block.property("title")).isEqualTo("Secure Voice Architecture Overview");
        assertThat(block.propertyValues("include")).containsExactly("REQ-001", "CP-1023", "BP-1327");
        assertThat(block.property("layout")).isEqualTo("layered");
    }

    @Test
    void parseEvidenceBlock() {
        String dsl = """
                evidence EV-001 {
                  for-relation: CR-1011 SUPPORTS BP-1327;
                  type: LLM;
                  model: "gpt-4.1-mini";
                  confidence: 0.76;
                  summary: "The service appears to provide direct support to the process.";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("evidence");
        assertThat(block.getHeaderTokens()).containsExactly("EV-001");
        assertThat(block.property("for-relation")).isEqualTo("CR-1011 SUPPORTS BP-1327");
    }

    @Test
    void parseUnknownAttributes() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Secure Communications";
                  x-owner: "CIS";
                  x-lifecycle: "target";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        BlockAst block = doc.getBlocks().get(0);

        assertThat(block.getExtensions()).containsEntry("x-owner", "CIS");
        assertThat(block.getExtensions()).containsEntry("x-lifecycle", "target");
    }

    @Test
    void parseUnknownBlockType() {
        String dsl = """
                constraint CON-001 {
                  title: "Max latency";
                  value: "200ms";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getKind()).isEqualTo("constraint");
        assertThat(block.getHeaderTokens()).containsExactly("CON-001");
        assertThat(block.property("title")).isEqualTo("Max latency");
    }

    @Test
    void parseMultipleBlocks() {
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
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getMeta()).isNotNull();
        assertThat(doc.getMeta().namespace()).isEqualTo("test");
        assertThat(doc.getBlocks()).hasSize(3);
        assertThat(doc.blocksOfKind("element")).hasSize(2);
        assertThat(doc.blocksOfKind("relation")).hasSize(1);
    }

    @Test
    void parseCommentsAreIgnored() {
        String dsl = """
                # This is a comment
                element CP-1023 type Capability {
                  title: "Cap One";
                  # This is also a comment
                  description: "Desc";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);
        assertThat(doc.getBlocks().get(0).property("title")).isEqualTo("Cap One");
        assertThat(doc.getBlocks().get(0).property("description")).isEqualTo("Desc");
    }

    @Test
    void parseSourceLocationsTracked() {
        String dsl = """
                meta {
                  language: "taxdsl";
                }

                element CP-1023 type Capability {
                  title: "Test";
                }
                """;
        DocumentAst doc = parser.parse(dsl, "test.tax");
        assertThat(doc.getMeta().sourceLocation()).isNotNull();
        assertThat(doc.getMeta().sourceLocation().file()).isEqualTo("test.tax");
        assertThat(doc.getMeta().sourceLocation().line()).isEqualTo(1);

        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.getSourceLocation().file()).isEqualTo("test.tax");
        assertThat(block.getSourceLocation().line()).isEqualTo(5);
    }

    @Test
    void parseVersionedMetaBlock() {
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "mission.secure-voice";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getMeta()).isNotNull();
        assertThat(doc.getMeta().language()).isEqualTo(MetaAst.LANGUAGE_ID);
        assertThat(doc.getMeta().version()).isEqualTo(MetaAst.CURRENT_VERSION);
    }

    @Test
    void parseEscapedQuotesInValues() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "He said \\"hello\\"";
                  description: "Path: C:\\\\Users\\\\test";
                }
                """;
        DocumentAst doc = parser.parse(dsl);
        assertThat(doc.getBlocks()).hasSize(1);
        BlockAst block = doc.getBlocks().get(0);
        assertThat(block.property("title")).isEqualTo("He said \"hello\"");
        assertThat(block.property("description")).isEqualTo("Path: C:\\Users\\test");
    }
}
