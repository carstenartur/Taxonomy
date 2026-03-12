package com.nato.taxonomy.dsl;

import com.nato.taxonomy.dsl.parser.DslTokenizer;
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
                element CP-1001 type Capability
                  title "Secure Communications"
                element BP-1040 type Process
                  title "Conduct Operations"
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("CP-1001");
        assertThat(tokens).contains("BP-1040");
    }

    @Test
    void tokenizeExtractsStructureTokens() {
        String dsl = """
                element CP-1001 type Capability
                  title "Test"
                relation CP-1001 REALIZES CR-2001
                  status accepted
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("STRUCT:element");
        assertThat(tokens).contains("STRUCT:relation");
    }

    @Test
    void tokenizeExtractsRelationTokens() {
        String dsl = """
                relation CP-1001 REALIZES CR-2001
                  status accepted
                relation CR-2001 SUPPORTS BP-1040
                  status proposed
                """;
        String tokens = tokenizer.tokenize(dsl);
        assertThat(tokens).contains("REL:REALIZES");
        assertThat(tokens).contains("REL:SUPPORTS");
    }

    @Test
    void tokenizeExtractsDomainTokens() {
        String dsl = """
                element CP-1001 type Capability
                element BP-1040 type Process
                element CR-2001 type CoreService
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
                element CP-1001 type Capability
                  title "Test"
                relation CP-1001 REALIZES CR-2001
                requirement REQ-001
                  title "Test Req"
                """;
        Set<String> ids = tokenizer.extractElementIds(dsl);
        assertThat(ids).containsExactlyInAnyOrder("CP-1001", "CR-2001", "REQ-001");
    }

    @Test
    void extractRelationKeysFindsRelations() {
        String dsl = """
                relation CP-1001 REALIZES CR-2001
                  status accepted
                relation CR-2001 SUPPORTS BP-1040
                  status proposed
                """;
        Set<String> keys = tokenizer.extractRelationKeys(dsl);
        assertThat(keys).containsExactlyInAnyOrder(
                "CP-1001 REALIZES CR-2001",
                "CR-2001 SUPPORTS BP-1040"
        );
    }

    @Test
    void extractRelationKeysReturnsEmptyForNoRelations() {
        String dsl = """
                element CP-1001 type Capability
                  title "No relations here"
                """;
        Set<String> keys = tokenizer.extractRelationKeys(dsl);
        assertThat(keys).isEmpty();
    }

    @Test
    void tokenizeDeduplicates() {
        String dsl = """
                element CP-1001 type Capability
                  title "First"
                element CP-1001 type Capability
                  title "Duplicate ID"
                """;
        String tokens = tokenizer.tokenize(dsl);
        // CP-1001 should appear only once
        long count = tokens.chars().filter(c -> c == ' ').count() + 1;
        String[] parts = tokens.split(" ");
        long cpCount = java.util.Arrays.stream(parts).filter(t -> t.equals("CP-1001")).count();
        assertThat(cpCount).isEqualTo(1);
    }
}
