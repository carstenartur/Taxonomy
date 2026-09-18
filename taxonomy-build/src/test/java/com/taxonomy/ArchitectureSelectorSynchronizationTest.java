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
import java.util.Set;
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
            "ArchitectureSelectorSynchronizationTest",
            "ArchitectureWorkspaceModuleTest",
            "ArchitectureTemplatesModuleTest");

    private static final String APP = "taxonomy-app";
    private static final String FEATURE = "taxonomy-a";
    private static final String SUPPORT = "taxonomy-domain";
    private static final ArchitectureModuleGraph.Policy OWNERSHIP_POLICY = new ArchitectureModuleGraph.Policy(
            APP,
            Set.of("AppConfig.java"),
            List.of(
                    new ArchitectureModuleGraph.Context("a", FEATURE, List.of("com.taxonomy.a..")),
                    new ArchitectureModuleGraph.Context("composition", APP,
                            List.of("com.taxonomy.composition..", "com.taxonomy.shared.."))));

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
    void supportModuleCannotBeConfiguredAsPlannedFeatureTarget() {
        var invalid = new ArchitectureModuleGraph.Policy(
                APP,
                Set.of("AppConfig.java"),
                List.of(
                        new ArchitectureModuleGraph.Context(
                                "a", SUPPORT, List.of("com.taxonomy.a..")),
                        new ArchitectureModuleGraph.Context(
                                "composition", APP, List.of("com.taxonomy.composition.."))));

        assertThatThrownBy(() -> ArchitectureModuleGraph.evaluate(
                invalid,
                Set.of(SUPPORT),
                Set.of(SUPPORT),
                List.of(new ArchitectureModuleGraph.ClassOwner(
                        "com.taxonomy.a.Service", SUPPORT, "Service.java")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Support module", SUPPORT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"taxonomy-coverage", "taxonomy-build"})
    void nonProductionModuleCannotBeConfiguredAsPlannedFeatureTarget(String targetModule) {
        var invalid = new ArchitectureModuleGraph.Policy(
                APP,
                Set.of("AppConfig.java"),
                List.of(
                        new ArchitectureModuleGraph.Context(
                                "a", targetModule, List.of("com.taxonomy.a..")),
                        new ArchitectureModuleGraph.Context(
                                "composition", APP, List.of("com.taxonomy.composition.."))));

        assertThatThrownBy(() -> ArchitectureModuleGraph.evaluate(
                invalid,
                Set.of(SUPPORT),
                Set.of(targetModule),
                List.of(new ArchitectureModuleGraph.ClassOwner(
                        "com.taxonomy.a.Service", targetModule, "Service.java")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-production module", targetModule);
    }

    @Test
    void supportModuleCannotHideFeatureContextOwnership() {
        var result = ArchitectureModuleGraph.evaluate(
                OWNERSHIP_POLICY,
                Set.of(SUPPORT),
                Set.of(FEATURE, SUPPORT),
                List.of(
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Service", FEATURE, "Service.java"),
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Other", SUPPORT, "Other.java")),
                List.of());

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("com.taxonomy.a.Other", SUPPORT, "planned owner is " + FEATURE));
    }

    @Test
    void supportModuleCannotHideRootCompositionOwnership() {
        var result = ArchitectureModuleGraph.evaluate(
                OWNERSHIP_POLICY,
                Set.of(SUPPORT),
                Set.of(FEATURE, SUPPORT),
                List.of(
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Service", FEATURE, "Service.java"),
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.AppConfig", SUPPORT, "AppConfig.java")),
                List.of());

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("com.taxonomy.AppConfig", SUPPORT, "planned owner is " + APP));
    }

    @Test
    void supportModuleStillAcceptsSharedCompositionContract() {
        var result = ArchitectureModuleGraph.evaluate(
                OWNERSHIP_POLICY,
                Set.of(SUPPORT),
                Set.of(FEATURE, SUPPORT),
                List.of(
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.a.Service", FEATURE, "Service.java"),
                        new ArchitectureModuleGraph.ClassOwner("com.taxonomy.shared.Contract", SUPPORT, "Contract.java")),
                List.of());

        assertThat(result.violations()).isEmpty();
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

    @ParameterizedTest
    @MethodSource("selectedGuards")
    void renamedSelectedGuardDeclarationIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Files.writeString(sourcePath(fixture, guard), "package com.taxonomy; class RenamedGuard {}\n");
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"comment", "nested", "wrong-package", "interface", "malformed"})
    void aGuardFilenameDoesNotSubstituteForItsDeclaration(String kind) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        String source = switch (kind) {
            case "comment" -> "package com.taxonomy; /* class " + guard + " {} */";
            case "nested" -> "package com.taxonomy; class Other { class " + guard + " {} }";
            case "wrong-package" -> "package other; class " + guard + " {}";
            case "interface" -> "package com.taxonomy; interface " + guard + " {}";
            case "malformed" -> "package com.taxonomy; class " + guard + " {";
            default -> throw new IllegalArgumentException(kind);
        };
        Files.writeString(sourcePath(fixture, guard), source);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void annotatedPackagePrivateGuardWithCommentsIsAccepted() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Files.writeString(sourcePath(fixture, guard), """
                /* class NotTheGuard {} */
                package com.taxonomy;
                @Deprecated
                class ArchitectureCycleBoundaryTest {
                    String example = "class Another {}";
                }
                """);
        assertThatCode(() -> assertSelectors(fixture)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", ".mvn/verification-suites.json", ".mvn"})
    void selectorPolicyCannotEscapeThroughAFileOrAncestorAlias(String relative) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Path original = fixture.resolve(relative);
        Path outside = externalFixture.resolve(original.getFileName());
        Files.move(original, outside);
        Files.createSymbolicLink(original, outside);

        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining("inside the checkout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", ".mvn/verification-suites.json", ".mvn"})
    void selectorPolicyAcceptsAliasesWhollyInsideTheCheckout(String relative) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Path original = fixture.resolve(relative);
        Path inside = fixture.resolve("policy-alias-target");
        Files.move(original, inside);
        Files.createSymbolicLink(original, inside);

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
        Path checkout = root.toRealPath();
        Path pomInput = checkedSelectorInput(root.resolve("pom.xml"), checkout);
        Path catalogInput = checkedSelectorInput(root.resolve(".mvn/verification-suites.json"), checkout);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(pomInput.toFile());
        String query = "/*[local-name()='project']/*[local-name()='profiles']"
                + "/*[local-name()='profile'][*[local-name()='id']='architecture-tests']"
                + "/*[local-name()='properties']/*[local-name()='test']";
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(query, document, XPathConstants.NODESET);
        assertThat(nodes.getLength()).as("one architecture-tests selector in pom.xml").isEqualTo(1);
        String pom = nodes.item(0).getTextContent();
        var catalog = new ObjectMapper().readTree(Files.readString(catalogInput));
        String json = catalog.path("profiles").path("architecture-tests").path("test").asString();
        assertThat(selectors(pom)).as("pom.xml architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
        assertThat(selectors(json)).as("verification catalogue architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
        for (String guard : EXPECTED) {
            Path source = sourcePath(root, guard);
            assertThat(source).as("selected architecture guard %s in its owning module", guard)
                    .isRegularFile();
            assertThat(source.toRealPath().startsWith(checkout))
                    .as("selected architecture guard %s must remain inside the checkout", guard)
                    .isTrue();
            assertGuardDeclaration(source, guard);
        }
    }

    private static Path checkedSelectorInput(Path input, Path checkout) throws Exception {
        assertThat(input).as("selector policy input %s", input).isRegularFile();
        Path real = input.toRealPath();
        assertThat(real.startsWith(checkout))
                .as("selector policy input %s must remain inside the checkout", input).isTrue();
        return real;
    }

    private static void assertGuardDeclaration(Path source, String guard) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK parser for selected architecture guard %s", guard).isNotNull();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT,
                java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(new java.io.StringWriter(), files,
                    diagnostics, List.of("--release", "21", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(List.of(source)));
            List<String> declarations = new ArrayList<>();
            for (var unit : task.parse()) {
                if (unit.getPackageName() != null && unit.getPackageName().toString().equals("com.taxonomy")) {
                    for (var declaration : unit.getTypeDecls()) {
                        if (declaration instanceof com.sun.source.tree.ClassTree type
                                && type.getKind() == com.sun.source.tree.Tree.Kind.CLASS) {
                            declarations.add(type.getSimpleName().toString());
                        }
                    }
                }
            }
            assertThat(diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR).toList())
                    .as("valid Java declaration for selected architecture guard %s", guard).isEmpty();
            assertThat(declarations).as("selected architecture guard %s must be declared in com.taxonomy", guard)
                    .contains(guard);
        }
    }

    private static List<String> selectors(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    }
}
