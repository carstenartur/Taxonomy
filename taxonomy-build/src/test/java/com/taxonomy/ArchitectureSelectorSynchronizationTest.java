package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact selector coverage complements the module owner's dependency contract. */
class ArchitectureSelectorSynchronizationTest {

    private static final List<String> EXPECTED = List.of(
            "ArchitectureTest",
            "ArchitectureCycleBoundaryTest",
            "ArchitectureExceptionLedgerTest",
            "ArchitectureCycleRuleRegressionTest",
            "ArchitectureContextDependencyRatchetTest",
            "ArchitectureDecisionReportBoundaryTest",
            "ArchitectureCommitHistoryOwnershipTest",
            "ArchitectureDslCompositionBoundaryTest",
            "ArchitectureWorkspaceAuthorityBoundaryTest",
            "ArchitectureApplicationSchemaCompositionTest",
            "ArchitectureWorkspaceStorageOwnershipTest",
            "ArchitectureModuleGraphTest",
            "ArchitectureModuleExtractionTest",
            "ArchitectureSelectorSynchronizationTest");

    @TempDir
    Path fixture;

    @TempDir
    Path externalFixture;

    @Test
    void bothRepositorySelectorsContainEveryGuardExactlyOnceInOrder() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository containing the verification catalogue").isNotNull();
        assertSelectors(root);
    }

    @Test
    void completeOrderedSelectorsAreAccepted() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        assertThatCode(() -> assertSelectors(fixture)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom", "catalog"})
    void droppingAnInheritedGuardIsRejected(String target) throws Exception {
        List<String> changed = new ArrayList<>(EXPECTED);
        changed.remove("ArchitectureCycleBoundaryTest");
        assertRejected(target, changed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom", "catalog"})
    void duplicatingAnInheritedGuardIsRejected(String target) throws Exception {
        List<String> changed = new ArrayList<>(EXPECTED);
        changed.add(1, "ArchitectureCycleBoundaryTest");
        assertRejected(target, changed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom", "catalog"})
    void reorderingInheritedGuardsIsRejected(String target) throws Exception {
        List<String> changed = new ArrayList<>(EXPECTED);
        Collections.swap(changed, 0, 1);
        assertRejected(target, changed);
    }

    @Test
    void identicallyTruncatedSelectorsAreRejectedEvenThoughTheyMatch() throws Exception {
        List<String> changed = new ArrayList<>(EXPECTED);
        changed.remove("ArchitectureCycleBoundaryTest");
        writeSelectors(changed, changed);
        assertThatThrownBy(() -> assertSelectors(fixture)).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @MethodSource("selectedGuards")
    void missingSelectedGuardSourceIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Files.delete(sourcePath(fixture, guard));
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ArchitectureCycleBoundaryTest", "ArchitectureModuleGraphTest"})
    void selectedGuardInTheWrongModuleIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Path source = sourcePath(fixture, guard);
        String other = source.startsWith(fixture.resolve("taxonomy-app"))
                ? "taxonomy-build" : "taxonomy-app";
        Path destination = fixture.resolve(other + "/src/test/java/com/taxonomy/" + guard + ".java");
        Files.createDirectories(destination.getParent());
        Files.move(source, destination);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void selectedGuardSourceOutsideTheCheckoutIsRejected() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Path source = sourcePath(fixture, guard);
        Path outside = externalFixture.resolve(guard + ".java");
        Files.move(source, outside);
        Files.createSymbolicLink(source, outside);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void selectedGuardSourceAliasInsideTheCheckoutIsAccepted() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Path source = sourcePath(fixture, guard);
        Path inside = fixture.resolve("source-aliases/" + guard + ".java");
        Files.createDirectories(inside.getParent());
        Files.move(source, inside);
        Files.createSymbolicLink(source, source.getParent().relativize(inside));
        assertThatCode(() -> assertSelectors(fixture)).doesNotThrowAnyException();
    }

    private static Stream<String> selectedGuards() {
        return EXPECTED.stream();
    }

    private static Path sourcePath(Path root, String guard) {
        String module = switch (guard) {
            case "ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
                    "ArchitectureSelectorSynchronizationTest" -> "taxonomy-build";
            default -> "taxonomy-app";
        };
        return root.resolve(module + "/src/test/java/com/taxonomy/" + guard + ".java");
    }

    private void assertRejected(String target, List<String> changed) throws Exception {
        writeSelectors("pom".equals(target) ? changed : EXPECTED,
                "catalog".equals(target) ? changed : EXPECTED);
        assertThatThrownBy(() -> assertSelectors(fixture)).isInstanceOf(AssertionError.class);
    }

    private void writeSelectors(List<String> pom, List<String> catalog) throws Exception {
        for (String guard : EXPECTED) {
            Path source = sourcePath(fixture, guard);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package com.taxonomy; class " + guard + " {}\n");
        }
        Files.writeString(fixture.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <profiles><profile><id>architecture-tests</id><properties>
                    <test>%s</test>
                  </properties></profile></profiles>
                </project>
                """.formatted(String.join(",", pom)));
        Files.createDirectories(fixture.resolve(".mvn"));
        var root = new ObjectMapper().createObjectNode();
        root.putObject("profiles").putObject("architecture-tests").put("test", String.join(",", catalog));
        Files.writeString(fixture.resolve(".mvn/verification-suites.json"), root.toString());
    }

    static void assertSelectors(Path root) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        String query = "/*[local-name()='project']/*[local-name()='profiles']"
                + "/*[local-name()='profile'][*[local-name()='id']='architecture-tests']"
                + "/*[local-name()='properties']/*[local-name()='test']";
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(query, document, XPathConstants.NODESET);
        assertThat(nodes.getLength()).as("one architecture-tests selector in pom.xml").isEqualTo(1);
        String pom = nodes.item(0).getTextContent();
        var catalog = new ObjectMapper().readTree(Files.readString(root.resolve(".mvn/verification-suites.json")));
        String json = catalog.path("profiles").path("architecture-tests").path("test").asString();
        assertThat(selectors(pom)).as("pom.xml architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
        assertThat(selectors(json)).as("verification catalogue architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
        Path checkout = root.toRealPath();
        for (String guard : EXPECTED) {
            Path source = sourcePath(root, guard);
            assertThat(source).as("selected architecture guard %s in its owning module", guard)
                    .isRegularFile();
            assertThat(source.toRealPath().startsWith(checkout))
                    .as("selected architecture guard %s must remain inside the checkout", guard)
                    .isTrue();
        }
    }

    private static List<String> selectors(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    }
}
