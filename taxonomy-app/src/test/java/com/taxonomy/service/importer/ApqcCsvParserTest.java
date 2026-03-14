package com.taxonomy.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ApqcCsvParser}.
 */
class ApqcCsvParserTest {

    private ApqcCsvParser parser;

    @BeforeEach
    void setUp() {
        parser = new ApqcCsvParser();
    }

    @Test
    void fileFormat() {
        assertThat(parser.fileFormat()).isEqualTo("csv");
    }

    @Test
    void parsesSampleFile() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            assertThat(is).as("apqc-sample.csv resource").isNotNull();
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.elements()).isNotEmpty();
        }
    }

    @Test
    void parsesCorrectNumberOfElements() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            // 10 data rows in the sample
            assertThat(result.elements()).hasSize(10);
        }
    }

    @Test
    void assignsCorrectLevelTypes() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> types = result.elements().stream().map(ExternalElement::type).toList();
            // Level 1 → Category, Level 2 → ProcessGroup, Level 3 → Process, Level 4 → Activity
            assertThat(types).contains("Category", "ProcessGroup", "Process", "Activity");
        }
    }

    @Test
    void createsParentChildRelations() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.relations()).isNotEmpty();
            // All relations should be ParentChild
            assertThat(result.relations()).allMatch(r -> "ParentChild".equals(r.type()));
        }
    }

    @Test
    void elementIdsUsePcfPrefix() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.elements().get(0).id()).startsWith("apqc-");
        }
    }

    @Test
    void elementPropertiesContainPcfId() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            ExternalElement first = result.elements().get(0);
            assertThat(first.properties()).containsKey("pcfId");
        }
    }

    @Test
    void parentIdInProperties() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            // Second element (1.1) should have parent "apqc-1"
            ExternalElement second = result.elements().stream()
                    .filter(e -> e.id().equals("apqc-1-1")).findFirst().orElse(null);
            assertThat(second).isNotNull();
            assertThat(second.properties()).containsEntry("parentId", "apqc-1");
        }
    }

    @Test
    void parsesInlineCsv() throws Exception {
        String csv = """
                PCF ID,Name,Level,Description
                3.0,Manage Supply Chain,1,Supply chain management
                3.1,Plan supply chain,2,Planning activities
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(2);
        assertThat(result.elements().get(0).name()).isEqualTo("Manage Supply Chain");
        assertThat(result.elements().get(0).type()).isEqualTo("Category");
        assertThat(result.elements().get(1).type()).isEqualTo("ProcessGroup");
    }

    @Test
    void handlesQuotedFields() throws Exception {
        String csv = """
                PCF ID,Name,Level,Description
                1.0,"Develop Vision, Strategy & Goals",1,"Includes planning, visioning"
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(1);
        assertThat(result.elements().get(0).name()).isEqualTo("Develop Vision, Strategy & Goals");
    }

    @Test
    void emptyInputReturnsEmptyModel() throws Exception {
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(new byte[0]));
        assertThat(result.elements()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void hierarchyRelationLinkage() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/apqc-sample.csv")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            // 1.1 → 1.1.1 should exist
            ExternalRelation parentChild = result.relations().stream()
                    .filter(r -> r.sourceId().equals("apqc-1-1") && r.targetId().equals("apqc-1-1-1"))
                    .findFirst().orElse(null);
            assertThat(parentChild).isNotNull();
        }
    }
}
