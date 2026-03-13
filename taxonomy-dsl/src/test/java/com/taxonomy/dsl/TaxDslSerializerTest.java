package com.taxonomy.dsl;

import com.taxonomy.dsl.ast.*;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TaxDslSerializer}.
 */
class TaxDslSerializerTest {

    private TaxDslSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new TaxDslSerializer();
    }

    @Test
    void serializeEmptyDocument() {
        DocumentAst doc = new DocumentAst(null, List.of());
        String result = serializer.serialize(doc);
        assertThat(result).isEmpty();
    }

    @Test
    void serializeMetaOnly() {
        MetaAst meta = new MetaAst("taxdsl", "2.0", "test.ns", null);
        DocumentAst doc = new DocumentAst(meta, List.of());
        String result = serializer.serialize(doc);
        assertThat(result).contains("meta {");
        assertThat(result).contains("language: \"taxdsl\";");
        assertThat(result).contains("version: \"2.0\";");
        assertThat(result).contains("namespace: \"test.ns\";");
        assertThat(result).contains("}");
    }

    @Test
    void serializeElementBlock() {
        BlockAst block = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(
                        new PropertyAst("title", "Secure Communications", null),
                        new PropertyAst("description", "Ability to communicate securely", null)
                ),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).contains("element CP-1023 type Capability {");
        assertThat(result).contains("  title: \"Secure Communications\";");
        assertThat(result).contains("  description: \"Ability to communicate securely\";");
        assertThat(result).contains("}");
    }

    @Test
    void serializeRelationWithBareValues() {
        BlockAst block = new BlockAst("relation",
                List.of("CR-1011", "SUPPORTS", "BP-1327"),
                List.of(
                        new PropertyAst("status", "proposed", null),
                        new PropertyAst("confidence", "0.76", null),
                        new PropertyAst("provenance", "analysis", null)
                ),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).contains("relation CR-1011 SUPPORTS BP-1327 {");
        assertThat(result).contains("  status: proposed;");
        assertThat(result).contains("  confidence: 0.76;");
        assertThat(result).contains("  provenance: \"analysis\";");
    }

    @Test
    void serializeExtensionAttributes() {
        Map<String, String> extensions = new LinkedHashMap<>();
        extensions.put("x-owner", "CIS");
        extensions.put("x-lifecycle", "target");

        BlockAst block = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(
                        new PropertyAst("title", "Test", null),
                        new PropertyAst("x-owner", "CIS", null),
                        new PropertyAst("x-lifecycle", "target", null)
                ),
                List.of(), extensions, null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).contains("  title: \"Test\";");
        assertThat(result).contains("  x-owner: \"CIS\";");
        assertThat(result).contains("  x-lifecycle: \"target\";");
    }

    @Test
    void serializeViewWithMultipleIncludes() {
        BlockAst block = new BlockAst("view",
                List.of("overview"),
                List.of(
                        new PropertyAst("title", "Overview", null),
                        new PropertyAst("include", "CP-1023", null),
                        new PropertyAst("include", "BP-1327", null),
                        new PropertyAst("layout", "layered", null)
                ),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).contains("view overview {");
        assertThat(result).contains("  include: \"CP-1023\";");
        assertThat(result).contains("  include: \"BP-1327\";");
        assertThat(result).contains("  layout: layered;");
    }

    @Test
    void serializeTrailingNewline() {
        BlockAst block = new BlockAst("element",
                List.of("CP-1023"),
                List.of(new PropertyAst("title", "Test", null)),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).endsWith("\n");
    }

    @Test
    void serializeMultipleBlocksSeparatedByBlankLine() {
        BlockAst block1 = new BlockAst("element",
                List.of("CP-1023"),
                List.of(new PropertyAst("title", "One", null)),
                List.of(), Map.of(), null);
        BlockAst block2 = new BlockAst("element",
                List.of("CP-1027"),
                List.of(new PropertyAst("title", "Two", null)),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block1, block2));
        String result = serializer.serialize(doc);

        // Blocks should be separated by a blank line
        assertThat(result).contains("}\n\nelement CP-1027");
    }

    @Test
    void serializeDeterministicOutput() {
        MetaAst meta = new MetaAst("taxdsl", "2.0", "test", null);
        BlockAst block = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(new PropertyAst("title", "Test", null)),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(meta, List.of(block));
        String result1 = serializer.serialize(doc);
        String result2 = serializer.serialize(doc);

        assertThat(result1).isEqualTo(result2);
    }

    @Test
    void serializeEscapesQuotesInValues() {
        BlockAst block = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(
                        new PropertyAst("title", "He said \"hello\"", null),
                        new PropertyAst("description", "Path: C:\\Users\\test", null)
                ),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        assertThat(result).contains("  title: \"He said \\\"hello\\\"\";");
        assertThat(result).contains("  description: \"Path: C:\\\\Users\\\\test\";");
    }

    @Test
    void serializeBlocksSortedByKindThenId() {
        // Intentionally out of order: relation before element, BP before CP
        BlockAst relation = new BlockAst("relation",
                List.of("CP-1023", "REALIZES", "BP-1327"),
                List.of(new PropertyAst("status", "accepted", null)),
                List.of(), Map.of(), null);
        BlockAst element2 = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(new PropertyAst("title", "Cap One", null)),
                List.of(), Map.of(), null);
        BlockAst element1 = new BlockAst("element",
                List.of("BP-1327", "type", "Process"),
                List.of(new PropertyAst("title", "Proc One", null)),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(relation, element2, element1));
        String result = serializer.serialize(doc);

        // Elements should come before relations; BP-1327 before CP-1023
        int elemBp = result.indexOf("element BP-1327");
        int elemCp = result.indexOf("element CP-1023");
        int rel = result.indexOf("relation CP-1023");

        assertThat(elemBp).isLessThan(elemCp);
        assertThat(elemCp).isLessThan(rel);
    }

    @Test
    void serializePropertiesInCanonicalOrder() {
        // Properties given in non-canonical order: description before title
        BlockAst block = new BlockAst("element",
                List.of("CP-1023", "type", "Capability"),
                List.of(
                        new PropertyAst("description", "A description", null),
                        new PropertyAst("taxonomy", "CP", null),
                        new PropertyAst("title", "A title", null)
                ),
                List.of(), Map.of(), null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        // Canonical order for element: title, description, taxonomy
        int titlePos = result.indexOf("  title: ");
        int descPos = result.indexOf("  description: ");
        int taxPos = result.indexOf("  taxonomy: ");

        assertThat(titlePos).isLessThan(descPos);
        assertThat(descPos).isLessThan(taxPos);
    }

    @Test
    void serializeExtensionsSortedAlphabetically() {
        Map<String, String> extensions = new LinkedHashMap<>();
        extensions.put("x-zebra", "last");
        extensions.put("x-alpha", "first");

        BlockAst block = new BlockAst("element",
                List.of("CP-1023"),
                List.of(
                        new PropertyAst("title", "Test", null),
                        new PropertyAst("x-zebra", "last", null),
                        new PropertyAst("x-alpha", "first", null)
                ),
                List.of(), extensions, null);

        DocumentAst doc = new DocumentAst(null, List.of(block));
        String result = serializer.serialize(doc);

        int alphaPos = result.indexOf("x-alpha");
        int zebraPos = result.indexOf("x-zebra");
        assertThat(alphaPos).isLessThan(zebraPos);
    }
}
