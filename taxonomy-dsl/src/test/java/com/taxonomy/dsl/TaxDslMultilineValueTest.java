package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaxDslMultilineValueTest {

    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final TaxDslParser parser = new TaxDslParser();

    @Test
    void multilineRequirementTextRoundTripsWithoutCreatingPhysicalDslLines() {
        String requirementText = "First line\nSecond line\r\n\tIndented with \\\\ and \"quotes\"";
        BlockAst requirement = new BlockAst(
                "requirementVersion",
                List.of("P-001", "REQ-001", "1"),
                List.of(new PropertyAst("text", requirementText, null)),
                List.of(),
                Map.of(),
                null);
        DocumentAst document = new DocumentAst(
                new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, "test", null),
                List.of(requirement));

        String serialized = serializer.serialize(document);

        assertThat(serialized)
                .contains("First line\\nSecond line\\r\\n\\tIndented")
                .contains("\\\\\\\\")
                .contains("\\\"quotes\\\"")
                .doesNotContain("First line\nSecond line");
        assertThat(parser.parse(serialized).blocksOfKind("requirementVersion"))
                .singleElement()
                .extracting(block -> block.property("text"))
                .isEqualTo(requirementText);
    }

    @Test
    void unknownEscapeIsPreservedForForwardCompatibility() {
        String dsl = """
                requirement REQ-001 {
                  text: "Keep \\x literally";
                }
                """;

        assertThat(parser.parse(dsl).blocksOfKind("requirement"))
                .singleElement()
                .extracting(block -> block.property("text"))
                .isEqualTo("Keep \\x literally");
    }
}
