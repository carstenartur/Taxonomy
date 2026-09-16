package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureExceptionLedgerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void checkedLedgerMatchesExactlyTheExceptionsUsedByArchUnit() throws Exception {
        Path ledgerPath = findRepositoryRoot().resolve(".github/architecture-exceptions.json");
        JsonNode root = objectMapper.readTree(Files.readString(ledgerPath));

        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode exceptions = root.path("exceptions");
        assertThat(exceptions.isArray()).isTrue();

        Set<String> ids = new LinkedHashSet<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode entry : exceptions) {
            String id = requiredText(entry, "id");
            assertThat(ids.add(id)).as("duplicate exception id %s", id).isTrue();
            requiredText(entry, "kind");
            requiredText(entry, "fromPackage");
            requiredText(entry, "toPackage");
            requiredText(entry, "owner");
            requiredText(entry, "rationale");
            requiredText(entry, "removalCondition");
            LocalDate expiry = LocalDate.parse(requiredText(entry, "expiresOn"));
            assertThat(expiry)
                    .as("architecture exception %s must not be expired", id)
                    .isAfterOrEqualTo(today);
        }

        assertThat(ids)
                .as("ledger entries and hard-coded ArchUnit exceptions must match exactly")
                .containsExactlyInAnyOrderElementsOf(
                        ArchitectureCycleBoundaryTest.DOCUMENTED_EXCEPTION_IDS);
    }

    /** This pre-existing guard remains selected independently of the new module checks. */
    @Test
    void moduleChecksRemainReachableFromTheArchitectureProfile() throws Exception {
        assertModuleChecksSelected(findRepositoryRoot());
    }

    private static void assertModuleChecksSelected(Path root) throws Exception {
        Path checkout = root.toRealPath();
        Path pom = selectorFile(checkout, "pom.xml");
        Path catalogue = selectorFile(checkout, ".mvn/verification-suites.json");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        String selected;
        try (var input = Files.newInputStream(pom)) {
            var nodes = (org.w3c.dom.NodeList) javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate(
                    "/*[local-name()='project']/*[local-name()='profiles']"
                            + "/*[local-name()='profile'][*[local-name()='id']='architecture-tests']"
                            + "/*[local-name()='properties']/*[local-name()='test']",
                    factory.newDocumentBuilder().parse(input), javax.xml.xpath.XPathConstants.NODESET);
            assertThat(nodes.getLength()).as("one architecture-tests Maven selector").isEqualTo(1);
            selected = nodes.item(0).getTextContent();
        }
        String recorded = new ObjectMapper().readTree(Files.readString(catalogue))
                .path("profiles").path("architecture-tests").path("test").asText();
        for (String selector : java.util.List.of(selected, recorded)) {
            var entries = java.util.Arrays.stream(selector.split(","))
                    .map(String::strip).filter(value -> !value.isEmpty()).toList();
            assertThat(entries).as("module checks must remain reachable from the architecture profile")
                    .contains("ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
                            "ArchitectureSelectorSynchronizationTest").doesNotHaveDuplicates();
        }
        // Once reachable, the downstream synchronization guard verifies the full ordered list
        // and every selected class declaration. No dependency on taxonomy-build is needed here.
    }

    private static Path selectorFile(Path checkout, String relative) throws Exception {
        Path file = checkout.resolve(relative).toRealPath();
        if (!file.startsWith(checkout) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("Architecture selector outside checkout: " + relative);
        }
        return file;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pom", "catalogue", "both"})
    void legacyGuardRejectsMissingModuleChecksInTemporaryPolicy(String changed,
            @org.junit.jupiter.api.io.TempDir Path checkout) throws Exception {
        String complete = "ArchitectureExceptionLedgerTest,ArchitectureModuleGraphTest,"
                + "ArchitectureModuleExtractionTest,ArchitectureSelectorSynchronizationTest";
        String incomplete = "ArchitectureExceptionLedgerTest";
        writeSelectorFixture(checkout, changed.equals("catalogue") ? complete : incomplete,
                changed.equals("pom") ? complete : incomplete);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(checkout))
                .isInstanceOf(AssertionError.class).hasMessageContaining("module checks must remain reachable");
    }

    @Test
    void additionalPhysicalExtractionChecksRemainAllowed(
            @org.junit.jupiter.api.io.TempDir Path checkout) throws Exception {
        String selector = "ArchitectureExceptionLedgerTest,ArchitectureModuleGraphTest,"
                + "ArchitectureModuleExtractionTest,ArchitectureSelectorSynchronizationTest,"
                + "ArchitectureWorkspaceModuleExtractionTest";
        writeSelectorFixture(checkout, selector, selector);
        assertModuleChecksSelected(checkout);
    }

    private static void writeSelectorFixture(Path root, String pom, String catalogue) throws Exception {
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve("pom.xml"), "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                + "<profiles><profile><id>architecture-tests</id><properties><test>" + pom
                + "</test></properties></profile></profiles></project>");
        Files.writeString(root.resolve(".mvn/verification-suites.json"), new ObjectMapper().writeValueAsString(
                java.util.Map.of("profiles", java.util.Map.of("architecture-tests", java.util.Map.of("test", catalogue)))));
    }

    private static String requiredText(JsonNode entry, String field) {
        String value = entry.path(field).asText();
        assertThat(value)
                .as("ledger field %s must be present and non-blank", field)
                .isNotBlank();
        return value;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/architecture-exceptions.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }
}
