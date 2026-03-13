package com.taxonomy.dsl;

import com.taxonomy.dsl.parser.DslTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DslTokenizer}.
 */
class DslTokenizerTest {

    private DslTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new DslTokenizer();
    }

    @Test
    void tokenizeExtractsIdentifiers() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Secure Communications";
                }
                element BP-1327 type Process {
                  title: "Conduct Operations";
                }
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("CP-1023");
        assertThat(tokens).contains("BP-1327");
    }

    @Test
    void tokenizeExtractsStructureTokens() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Test";
                }
                relation CP-1023 REALIZES CR-1047 {
                  status: accepted;
                }
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("STRUCT:element");
        assertThat(tokens).contains("STRUCT:relation");
    }

    @Test
    void tokenizeExtractsRelationTokens() {
        String dsl = """
                relation CP-1023 REALIZES CR-1047 {
                  status: accepted;
                }
                relation CR-1047 SUPPORTS BP-1327 {
                  status: proposed;
                }
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("REL:REALIZES");
        assertThat(tokens).contains("REL:SUPPORTS");
    }

    @Test
    void tokenizeExtractsDomainTokens() {
        String dsl = """
                element CP-1023 type Capability {
                }
                element BP-1327 type Process {
                }
                element CR-1047 type CoreService {
                }
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("DOM:Capability");
        assertThat(tokens).contains("DOM:Process");
        assertThat(tokens).contains("DOM:CoreService");
    }

    @Test
    void tokenizeHandlesNullAndEmpty() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    void extractElementIdsFindsAllIds() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "Test";
                }
                relation CP-1023 REALIZES CR-1047 {
                }
                requirement REQ-001 {
                  title: "Test Req";
                }
                """;
        Set<String> ids = tokenizer.extractElementIds(dsl);
        assertThat(ids).containsExactlyInAnyOrder("CP-1023", "CR-1047", "REQ-001");
    }

    @Test
    void extractRelationKeysFindsRelations() {
        String dsl = """
                relation CP-1023 REALIZES CR-1047 {
                  status: accepted;
                }
                relation CR-1047 SUPPORTS BP-1327 {
                  status: proposed;
                }
                """;
        Set<String> keys = tokenizer.extractRelationKeys(dsl);
        assertThat(keys).containsExactlyInAnyOrder(
                "CP-1023 REALIZES CR-1047",
                "CR-1047 SUPPORTS BP-1327"
        );
    }

    @Test
    void extractRelationKeysReturnsEmptyForNoRelations() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "No relations here";
                }
                """;
        Set<String> keys = tokenizer.extractRelationKeys(dsl);
        assertThat(keys).isEmpty();
    }

    @Test
    void tokenizeDeduplicates() {
        String dsl = """
                element CP-1023 type Capability {
                  title: "First";
                }
                element CP-1023 type Capability {
                  title: "Duplicate ID";
                }
                """;
        String tokens = tokenizer.tokenize(dsl);
        // CP-1023 should appear only once
        String[] parts = tokens.split(" ");
        long cpCount = java.util.Arrays.stream(parts).filter(t -> t.equals("CP-1023")).count();
        assertThat(cpCount).isEqualTo(1);
    }
}
