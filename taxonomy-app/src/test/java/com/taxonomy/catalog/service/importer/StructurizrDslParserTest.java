package com.taxonomy.catalog.service.importer;

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
 * Tests for the {@link StructurizrDslParser}.
 */
class StructurizrDslParserTest {

    private StructurizrDslParser parser;

    @BeforeEach
    void setUp() {
        parser = new StructurizrDslParser();
    }

    @Test
    void fileFormat() {
        assertThat(parser.fileFormat()).isEqualTo("dsl");
    }

    @Test
    void parsesSampleFile() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            assertThat(is).as("c4-sample.dsl resource").isNotNull();
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.elements()).isNotEmpty();
            assertThat(result.relations()).isNotEmpty();
        }
    }

    @Test
    void parsesPersonElement() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            ExternalElement person = result.elements().stream()
                    .filter(e -> e.type().equals("Person")).findFirst().orElse(null);
            assertThat(person).isNotNull();
            assertThat(person.name()).isEqualTo("End User");
            assertThat(person.id()).isEqualTo("user");
        }
    }

    @Test
    void parsesSoftwareSystem() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> types = result.elements().stream().map(ExternalElement::type).toList();
            assertThat(types).contains("SoftwareSystem");
        }
    }

    @Test
    void parsesContainer() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> types = result.elements().stream().map(ExternalElement::type).toList();
            assertThat(types).contains("Container");
        }
    }

    @Test
    void parsesComponent() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> types = result.elements().stream().map(ExternalElement::type).toList();
            assertThat(types).contains("Component");
        }
    }

    @Test
    void parsesRelationshipsWithArrowSyntax() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            // user -> webapp "Uses" "HTTPS"
            ExternalRelation rel = result.relations().stream()
                    .filter(r -> r.sourceId().equals("user") && r.targetId().equals("webapp"))
                    .findFirst().orElse(null);
            assertThat(rel).isNotNull();
            assertThat(rel.type()).isEqualTo("Uses");
        }
    }

    @Test
    void parsesContainsRelationsFromNesting() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            // sys contains webapp and api
            List<ExternalRelation> contains = result.relations().stream()
                    .filter(r -> r.type().equals("Contains"))
                    .toList();
            assertThat(contains).isNotEmpty();
        }
    }

    @Test
    void technologyInProperties() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/c4-sample.dsl")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            ExternalElement webapp = result.elements().stream()
                    .filter(e -> e.id().equals("webapp")).findFirst().orElse(null);
            assertThat(webapp).isNotNull();
            assertThat(webapp.properties()).containsEntry("technology", "React");
        }
    }

    @Test
    void parsesInlineDsl() throws Exception {
        String dsl = """
                workspace {
                    model {
                        admin = person "Admin" "System administrator"
                        app = softwareSystem "App" "The application"
                        admin -> app "Manages"
                    }
                }
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(dsl.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(2);
        assertThat(result.relations()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void emptyDslProducesEmptyModel() throws Exception {
        String dsl = """
                workspace {
                }
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(dsl.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void skipsComments() throws Exception {
        String dsl = """
                // This is a comment
                workspace {
                    model {
                        // Another comment
                        u = person "User" "A user"
                    }
                }
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(dsl.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(1);
    }
}
