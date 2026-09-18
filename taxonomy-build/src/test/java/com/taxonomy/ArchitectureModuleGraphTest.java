package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.tools.ToolProvider;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taxonomy.ArchitectureModuleGraph.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureModuleGraphTest extends ArchitectureModuleGraphTestSupport {

    @Test
    void anOrdinaryExternalSourceLinkRemainsRejectedDuringRepositoryDiscovery() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/Service.java");
        Path outside = externalFixture("ordinary-outside-source").resolve("Service.java");
        Files.move(source, outside);
        Files.createSymbolicLink(source, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM path is outside repository through a symlink")
                .hasMessageContaining("Service.java");
    }

    @Test
    void sourceAndClassFileAliasesWhoseTargetsRemainInTheCheckoutAreAccepted() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path aliases = temporaryRepository.resolve("fixture-alias-targets");
        Files.createDirectories(aliases);
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/Service.java");
        Path sourceTarget = aliases.resolve("Service.java");
        Files.move(source, sourceTarget);
        Files.createSymbolicLink(source, sourceTarget);
        Path compiledClass = output.resolve("com/taxonomy/a/Service.class");
        Path classTarget = aliases.resolve("Service.class");
        Files.move(compiledClass, classTarget);
        Files.createSymbolicLink(compiledClass, classTarget);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void moduleGateIsOwnedByBuildPolicyAfterEveryInventoryProducerWithCompleteSelectors() throws Exception {
        Path root = checkoutRoot();
        for (String source : List.of("ArchitectureModuleGraph.java", "ArchitectureModuleGraphTest.java",
                "ArchitectureModuleExtractionTest.java")) {
            assertThat(root.resolve("taxonomy-app/src/test/java/com/taxonomy").resolve(source)).doesNotExist();
            assertThat(root.resolve("taxonomy-build/src/test/java/com/taxonomy").resolve(source)).isRegularFile();
        }
        assertModuleGateOwnerDependencies(root, root.resolve("taxonomy-build/pom.xml"));
        ArchitectureSelectorSynchronizationTest.assertSelectors(root);

        String pomSelector = profileProperty(root.resolve("pom.xml"), "architecture-tests", "test");
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(root.resolve(".mvn/verification-suites.json")));
        String catalogSelector = catalog.path("profiles").path("architecture-tests").path("test").asString();
        for (String gate : List.of("ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest")) {
            assertThat(pomSelector.split(",")).contains(gate);
            assertThat(catalogSelector.split(",")).contains(gate);
        }
    }

    @Test
    void sameArtifactDependenciesFromOtherGroupsCannotSatisfyTheOwnerContract() throws Exception {
        pom("taxonomy-build", "taxonomy-build", """
                <dependencies>
                  <dependency><groupId>org.example</groupId><artifactId>taxonomy-app</artifactId></dependency>
                  <dependency><groupId>org.example</groupId><artifactId>taxonomy-coverage</artifactId><type>pom</type></dependency>
                  <dependency><groupId>org.example</groupId><artifactId>taxonomy-tooling</artifactId><scope>test</scope></dependency>
                  <dependency><groupId>org.example</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
                </dependencies>
                """);

        assertThatThrownBy(() -> assertModuleGateOwnerDependencies(temporaryRepository, temporaryRepository.resolve("taxonomy-build/pom.xml")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aClassifiedApplicationDependencyCannotSatisfyTheOwnerContract() throws Exception {
        assertClassifiedDependencyCannotSatisfyOwnerContract("taxonomy-app");
    }

    @Test
    void aClassifiedCoverageDependencyCannotSatisfyTheOwnerContract() throws Exception {
        assertClassifiedDependencyCannotSatisfyOwnerContract("taxonomy-coverage");
    }

    @Test
    void aClassifiedToolingDependencyCannotSatisfyTheOwnerContract() throws Exception {
        assertClassifiedDependencyCannotSatisfyOwnerContract("taxonomy-tooling");
    }

    @Test
    void aClassifiedArchUnitDependencyCannotSatisfyTheOwnerContract() throws Exception {
        assertClassifiedDependencyCannotSatisfyOwnerContract("archunit-junit5");
    }

    @Test
    void unclassifiedOwnerDependenciesRemainValidAlongsideClassifiedDependencies() throws Exception {
        pom("taxonomy-build", "taxonomy-build", moduleGateOwnerDependencies("classified-extras"));

        assertThatCode(() -> assertModuleGateOwnerDependencies(temporaryRepository, temporaryRepository.resolve("taxonomy-build/pom.xml")))
                .doesNotThrowAnyException();
    }

    @Test
    void staleOutputCannotInventADependencyRemovedFromCurrentSource() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path app = compile(APP, "com.taxonomy.composition.Wiring", "public class Wiring {}", List.of());
        compile(A, A_CLASS, "public class Service { com.taxonomy.composition.Wiring dependency; }", List.of(app));
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/Service.java");
        Files.writeString(source, "package com.taxonomy.a; public class Service {}");

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void aRemovedNestedClassCannotHideBehindItsExistingSourceFile() throws Exception {
        staleDeclaration("public class Service { static class Old {} }", "Service$Old.class");
    }

    @Test
    void aRemovedTopLevelClassCannotHideBehindAnotherDeclarationInItsSourceFile() throws Exception {
        staleDeclaration("public class Service {} class Old {}", "Old.class");
    }

    @Test
    void anAbsentSourceDirectoryCannotHideStaleSupportModuleBinaries() throws Exception {
        Path sourceRoot = compiledSupportModule();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        assertThat(sourceRoot).doesNotExist();

        assertStaleSupportModuleIsRejected();
    }

    @Test
    void anEmptyJavaSourceInventoryCannotHideStaleSupportModuleBinaries() throws Exception {
        Path sourceRoot = compiledSupportModule();
        Files.delete(sourceRoot.resolve("com/taxonomy/dto/Result.java"));
        assertThat(sourceRoot).isDirectory();

        assertStaleSupportModuleIsRejected();
    }

    @Test
    void genuinelyEmptySupportAndPomModulesRemainValid() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Files.createDirectories(temporaryRepository.resolve(DOMAIN + "/src/main/java"));
        Files.createDirectories(temporaryRepository.resolve(DOMAIN + "/target/classes"));
        Files.createDirectories(temporaryRepository.resolve("target/classes"));

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void cleanNamedLocalAnonymousAndCompilerGeneratedClassesAreAccepted() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, """
                public class Service {
                    static class Nested {}
                    int mode(java.time.DayOfWeek mode) { switch (mode) { case MONDAY: return 1; default: return 0; } }
                    Object local() { class Local {} return new Local(); }
                    Runnable anonymous() { return new Runnable() { public void run() {} }; }
                }
                class Companion {}
                """, List.of());
        assertThat(output.resolve("com/taxonomy/a/Service$Nested.class")).exists();
        assertThat(output.resolve("com/taxonomy/a/Service$1Local.class")).exists();
        assertThat(output.resolve("com/taxonomy/a/Service$1.class")).exists();
        assertThat(output.resolve("com/taxonomy/a/Service$2.class")).exists();
        assertThat(output.resolve("com/taxonomy/a/Companion.class")).exists();

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void anUnlistedDollarNamedRootSourceDoesNotInheritAnotherFilesCompositionAllowance() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(APP, "com.taxonomy.AppConfig$Plugin", "public class AppConfig$Plugin {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations())
                .contains("Unmapped production class: com.taxonomy.AppConfig$Plugin (physical owner taxonomy-app)");
    }

    @Test
    void aGenuineNestedRootClassUsesItsOriginatingCompositionSource() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig { static class Plugin {} }", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void anExplicitlyAllowedDollarNamedRootSourceRemainsCompositionOwned() throws Exception {
        bytecodeRepository();
        Path policy = temporaryRepository.resolve(".github/architecture-contexts.json");
        Files.writeString(policy, Files.readString(policy).replace("[\"AppConfig.java\"]",
                "[\"AppConfig.java\",\"AppConfig$Plugin.java\"]"));
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(APP, "com.taxonomy.AppConfig$Plugin", "public class AppConfig$Plugin {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void propertyResolvedApplicationDependencyIsEnforced() throws Exception {
        inheritedPoms("", """
                <properties><taxonomy.groupId>com.taxonomy</taxonomy.groupId><app.module>taxonomy-app</app.module></properties>
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>${app.module}</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """);

        List<ModuleDependency> dependencies = fixturePomDependencies();
        assertThat(dependencies).containsExactly(new ModuleDependency(A, APP));
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(), Set.of(A),
                List.of(owner(A_CLASS, A)), List.of(), dependencies);
        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-app", "POM"));
    }

    @Test
    void propertyResolvedExternalGroupDoesNotInventAnApplicationEdge() throws Exception {
        inheritedPoms("", """
                <properties><taxonomy.groupId>org.external</taxonomy.groupId></properties>
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void inheritedRuntimeDependencyUsesParentPropertiesAndTheChildGroupAlias() throws Exception {
        inheritedPoms("""
                <properties><taxonomy.groupId>${pom.groupId}</taxonomy.groupId><app.module>taxonomy-app</app.module></properties>
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>${app.module}</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """, "");

        List<ModuleDependency> dependencies = fixturePomDependencies();
        assertThat(dependencies).containsExactly(new ModuleDependency(A, APP));
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(), Set.of(A),
                List.of(owner(A_CLASS, A)), List.of(), dependencies);
        assertThat(result.violations()).anyMatch(message -> message.contains("taxonomy-a -> taxonomy-app"));
    }

    @Test
    void inheritedTestDependencyDoesNotBecomeAProductionEdge() throws Exception {
        inheritedPoms("""
                <properties><inherited.scope>test</inherited.scope></properties>
                <dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>${inherited.scope}</scope></dependency></dependencies>
                """, "");

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void childExplicitScopeOverridesTheInheritedRuntimeDependency() throws Exception {
        inheritedPoms("""
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>test</scope></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void differentRawInheritanceKeysRetainTheProductionEdgeAfterInterpolation() throws Exception {
        // Maven 3.9.16 ModelBuilder retains both effective dependencies here:
        // inheritance merges raw keys before interpolating the group expression.
        inheritedPoms("""
                <properties><taxonomy.groupId>com.taxonomy</taxonomy.groupId></properties>
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>test</scope></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void inheritedManagedTestScopePreventsAnInventedProductionEdge() throws Exception {
        inheritedPoms("""
                <properties><managed.scope>test</managed.scope></properties>
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><scope>${managed.scope}</scope>
                </dependency></dependencies></dependencyManagement>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void managedTestScopeMatchesEffectiveDependencyKeysOnBothSides() throws Exception {
        String properties = """
                <properties><app.module>taxonomy-app</app.module><dependency.type>jar</dependency.type>
                  <dependency.classifier>tests</dependency.classifier></properties>
                """;
        String constant = """
                <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><type>jar</type><classifier>tests</classifier>
                """;
        String expression = """
                <groupId>${project.groupId}</groupId><artifactId>${app.module}</artifactId>
                  <type>${dependency.type}</type><classifier>${dependency.classifier}</classifier>
                """;
        for (boolean managedExpression : List.of(false, true)) {
            inheritedPoms("<dependencyManagement><dependencies><dependency>"
                    + (managedExpression ? expression : constant)
                    + "<scope>test</scope></dependency></dependencies></dependencyManagement>",
                    properties + "<dependencies><dependency>" + (managedExpression ? constant : expression)
                            + "</dependency></dependencies>");

            assertThat(fixturePomDependencies()).isEmpty();
        }
    }

    @Test
    void managedScopeDoesNotMatchADifferentEffectiveTypeOrClassifier() throws Exception {
        for (String[] coordinates : List.of(new String[]{"test-jar", "tests"}, new String[]{"jar", "runtime"})) {
            inheritedPoms("""
                    <dependencyManagement><dependencies><dependency>
                      <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><type>jar</type><classifier>tests</classifier>
                      <scope>test</scope></dependency></dependencies></dependencyManagement>
                    """, """
                    <properties><dependency.type>%s</dependency.type><dependency.classifier>%s</dependency.classifier></properties>
                    <dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>taxonomy-app</artifactId>
                      <type>${dependency.type}</type><classifier>${dependency.classifier}</classifier></dependency></dependencies>
                    """.formatted(coordinates[0], coordinates[1]));

            assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
        }
    }

    @Test
    void unresolvedInternalDependencyKeyComponentsFailClosedBeforeManagement() throws Exception {
        String coordinates = """
                <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><type>jar</type><classifier>tests</classifier>
                """;
        for (String[] component : List.of(new String[]{"groupId", "com.taxonomy"}, new String[]{"artifactId", APP},
                new String[]{"type", "jar"}, new String[]{"classifier", "tests"})) {
            String unresolved = coordinates.replace("<" + component[0] + ">" + component[1] + "</" + component[0] + ">",
                    "<" + component[0] + ">${missing}</" + component[0] + ">");
            inheritedPoms("<dependencyManagement><dependencies><dependency>" + coordinates
                    + "<scope>test</scope></dependency></dependencies></dependencyManagement>",
                    "<dependencies><dependency>" + unresolved + "</dependency></dependencies>");

            assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unresolved").hasMessageContaining(component[0]);
        }
    }

    @Test
    void childPropertyOverrideCanMakeInheritedManagedScopeRuntime() throws Exception {
        inheritedPoms("""
                <properties><managed.scope>test</managed.scope><taxonomy.groupId>org.external</taxonomy.groupId></properties>
                <dependencyManagement><dependencies><dependency>
                  <groupId>${taxonomy.groupId}</groupId><artifactId>taxonomy-app</artifactId><scope>${managed.scope}</scope>
                </dependency></dependencies></dependencyManagement>
                """, """
                <properties><managed.scope>runtime</managed.scope><taxonomy.groupId>${project.groupId}</taxonomy.groupId></properties>
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void unresolvedPotentiallyInternalGroupFailsClosed() throws Exception {
        inheritedPoms("", """
                <dependencies><dependency><groupId>${taxonomy.groupId}</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved").hasMessageContaining("groupId");
    }

    @Test
    void unresolvedInternalManagedScopeFailsClosed() throws Exception {
        inheritedPoms("""
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><scope>${managed.scope}</scope>
                </dependency></dependencies></dependencyManagement>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved").hasMessageContaining("scope");
    }

    @Test
    void profileOnlyManagedTestScopeCannotHideTheBaseProductionDependency() throws Exception {
        inheritedPoms("", """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                <profiles><profile><id>test-scope</id><dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><scope>test</scope>
                </dependency></dependencies></dependencyManagement></profile></profiles>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved").hasMessageContaining("scope");
    }

    @Test
    void unresolvedParentCoordinatesFailClosedBeforeTheExternalFallback() throws Exception {
        for (String coordinate : List.of("groupId", "artifactId", "version")) {
            inheritedPoms("", "");
            parentReference(coordinate, "${missing.parent.coordinate}", "");

            assertThatThrownBy(this::fixturePomDependencies).as("unresolved parent %s", coordinate)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unresolved parent " + coordinate);
        }
    }

    @Test
    void cyclicParentCoordinatesFailClosedBeforeTheExternalFallback() throws Exception {
        for (String coordinate : List.of("groupId", "artifactId", "version")) {
            inheritedPoms("", "");
            parentReference(coordinate, "${parent.coordinate}", """
                    <properties><parent.coordinate>${parent.other}</parent.coordinate>
                      <parent.other>${parent.coordinate}</parent.other></properties>
                    """);

            assertThatThrownBy(this::fixturePomDependencies).as("cyclic parent %s", coordinate)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unresolved parent " + coordinate);
        }
    }

    @Test
    void aMismatchedLocalReactorParentCannotFallThroughAsExternal() throws Exception {
        inheritedPoms("", "");
        parentReference("version", "2", "");

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot resolve local reactor parent com.taxonomy:taxonomy-parent:2");
    }

    @Test
    void aGenuineExternalParentRemainsOutsideTheLocalProjection() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", A, """
                <parent><groupId>org.external</groupId><artifactId>external-parent</artifactId><version>1</version>
                  <relativePath/></parent><groupId>com.taxonomy</groupId>
                """);

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void anExternalParentMayShareAReactorArtifactName() throws Exception {
        externalParentPoms(APP, "");

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void anExternalParentMayShareItsChildArtifactNameWithoutCreatingALocalCycle() throws Exception {
        externalParentPoms(A, "");

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void aProfileDependentParentGroupCannotHideItsBaseRuntimeDependency() throws Exception {
        propertyGroupParentPoms(true, false);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved parent groupId").hasMessageContaining("unresolved-profile-property:local.group");
    }

    @Test
    void aProfileDependentParentGroupCannotHideItsProfileRuntimeDependency() throws Exception {
        propertyGroupParentPoms(true, true);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved parent groupId").hasMessageContaining("unresolved-profile-property:local.group");
    }

    @Test
    void identicalRawParentGroupsWithDifferentChildResolutionCannotLoseInheritance() throws Exception {
        // Maven matches the raw ${local.group} strings before inheritance and
        // child interpolation, then retains the parent's runtime dependency.
        propertyGroupParentPoms(false, false);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported context-dependent local parent groupId")
                .hasMessageContaining("org.other").hasMessageContaining("org.example");
    }

    @Test
    void identicalRawParentGroupsWithTheSameResolutionStillInherit() throws Exception {
        propertyGroupParentPoms(false, false);
        Path parent = temporaryRepository.resolve("local-parent/pom.xml");
        Files.writeString(parent, Files.readString(parent)
                .replace("<local.group>org.other</local.group>", "<local.group>org.example</local.group>"));

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void unrelatedProfilePropertiesDoNotInventALocalParentArtifactCollision() throws Exception {
        externalParentPoms(A, "");
        pom("taxonomy-a", A, """
                <parent><groupId>org.example</groupId><artifactId>taxonomy-a</artifactId><version>1</version>
                  <relativePath/></parent><groupId>${module.group}</groupId><version>1</version>
                <properties><module.group>com.taxonomy</module.group></properties>
                <profiles><profile><id>unrelated</id><properties><unrelated.property>value</unrelated.property></properties></profile></profiles>
                """);

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void anUnknownTaxonomyParentStillFailsClosed() throws Exception {
        inheritedPoms("", "");
        parentReference("artifactId", "missing-parent", "");

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot resolve local reactor parent com.taxonomy:missing-parent:1");
    }

    @Test
    void identicalLocalParentArtifactExpressionsRetainInheritedRuntimeDependencies() throws Exception {
        propertyArtifactParentPoms("local-parent", "");

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void aRegisteredPropertyArtifactParentMatchesALiteralReferenceInEitherDeclarationOrder() throws Exception {
        for (boolean parentFirst : List.of(false, true)) {
            registeredPropertyParentPoms(parentFirst, """
                    <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                      <version>1</version><scope>runtime</scope></dependency></dependencies>
                    """, "");
            var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);
            var dependencies = fixturePomDependencies();

            assertThat(dependencies).containsExactly(new ModuleDependency(A, APP));
            assertThat(ArchitectureModuleGraph.evaluate(POLICY, Set.of(), modules.keySet(),
                    List.of(owner(A_CLASS, A)), List.of(), dependencies).violations())
                    .anyMatch(message -> message.contains("taxonomy-a -> taxonomy-app"));
        }
    }

    @Test
    void aRegisteredPropertyArtifactParentRetainsManagedTestScopesInEitherDeclarationOrder() throws Exception {
        for (boolean parentFirst : List.of(false, true)) {
            registeredPropertyParentPoms(parentFirst, """
                    <dependencyManagement><dependencies><dependency>
                      <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><version>1</version><scope>test</scope>
                    </dependency></dependencies></dependencyManagement>
                    """, """
                    <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                    """);

            assertThat(fixturePomDependencies()).isEmpty();
        }
    }

    @Test
    void propertyArtifactParentAliasesRetainRuntimeDependenciesInAllModelBuilderControls() throws Exception {
        Path cases = temporaryRepository;
        for (String alias : List.of("file", "directory", "registered-directory")) {
            temporaryRepository = cases.resolve(alias);
            aliasedPropertyParentPoms(alias, """
                    <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                      <version>1</version><scope>runtime</scope></dependency></dependencies>
                    """, "");

            assertThat(fixturePomDependencies()).as(alias)
                    .containsExactly(new ModuleDependency(A, APP));
        }
    }

    @Test
    void propertyArtifactParentAliasesRetainManagedTestScopesInAllModelBuilderControls() throws Exception {
        Path cases = temporaryRepository;
        for (String alias : List.of("file", "directory", "registered-directory")) {
            temporaryRepository = cases.resolve(alias);
            aliasedPropertyParentPoms(alias, """
                    <dependencyManagement><dependencies><dependency>
                      <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><version>1</version><scope>test</scope>
                    </dependency></dependencies></dependencyManagement>
                    """, """
                    <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                    """);

            assertThat(fixturePomDependencies()).as(alias).isEmpty();
        }
    }

    @Test
    void aRegisteredPropertyArtifactCannotOverrideAForeignParentWithTheSameArtifact() throws Exception {
        registeredPropertyParentPoms(false, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """, "");
        Path child = temporaryRepository.resolve(A + "/pom.xml");
        Files.writeString(child, Files.readString(child).replace("<groupId>org.example.build</groupId>",
                "<groupId>org.foreign.build</groupId>"));

        assertThat(fixturePomDependencies()).isEmpty();
    }

    @Test
    void contextDependentLocalParentArtifactExpressionsFailClosed() throws Exception {
        propertyArtifactParentPoms("other-parent", "");

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported context-dependent local parent artifactId")
                .hasMessageContaining("other-parent").hasMessageContaining("local-parent");
    }

    @Test
    void effectiveArtifactIdentityMatchesANonReactorLocalParent() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("local-parent", "org.example.build", "build-${parent.module}", """
                <packaging>pom</packaging><properties><parent.module>local-parent</parent.module></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId><version>1</version><scope>runtime</scope>
                </dependency></dependencies></dependencyManagement>
                """);
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>build-local-parent</artifactId><version>1</version>
                  <relativePath>../local-parent/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId>
                </dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).containsExactly(
                new ModuleDependency(A, APP), new ModuleDependency(A, DOMAIN));
    }

    @Test
    void profileDependentLocalParentArtifactExpressionsFailClosed() throws Exception {
        propertyArtifactParentPoms("other-parent", """
                <profiles><profile><id>choose-parent-artifact</id><properties>
                  <parent.module>local-parent</parent.module></properties></profile></profiles>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved parent artifactId").hasMessageContaining("unresolved-profile-property");
    }

    @Test
    void aMatchingReactorParentGroupStillRequiresTheDeclaredVersion() throws Exception {
        inheritedPoms("", "");
        pom("taxonomy-parent", "org.example.build", "taxonomy-parent", "<packaging>pom</packaging>");
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>taxonomy-parent</artifactId><version>2</version>
                  <relativePath>../taxonomy-parent/pom.xml</relativePath></parent><groupId>com.taxonomy</groupId>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot resolve local reactor parent org.example.build:taxonomy-parent:2");
    }

    @Test
    void parentPathsOutsideTheRepositoryAreRejectedBeforeReading() throws Exception {
        Path outside = outsideParentFixture();
        for (String relative : List.of("../../outside/pom.xml", outside.toString(), "../../outside/missing-pom.xml")) {
            externalParentPoms("external-parent", relative);

            assertOutsidePomIsRejected();
        }
    }

    @Test
    void aPomFileSymlinkCannotReadAnOutsideParent() throws Exception {
        Path outside = outsideParentFixture();
        Path link = temporaryRepository.resolve("linked-parent/pom.xml");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        externalParentPoms("external-parent", "../linked-parent/pom.xml");

        assertOutsidePomIsRejected();
    }

    @Test
    void aDirectorySymlinkCannotReadOrProbeAnOutsideParent() throws Exception {
        Path outside = outsideParentFixture();
        Files.createSymbolicLink(temporaryRepository.resolve("linked-parent"), outside.getParent());
        for (String name : List.of("pom.xml", "missing-pom.xml")) {
            externalParentPoms("external-parent", "../linked-parent/" + name);

            assertOutsidePomIsRejected();
        }
    }

    @Test
    void aParentSymlinkInsideTheRepositoryRemainsLocal() throws Exception {
        localParentPoms("1", """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """, "");
        Path link = temporaryRepository.resolve("linked-parent/pom.xml");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, temporaryRepository.resolve("local-parent/pom.xml"));
        Path child = temporaryRepository.resolve(A + "/pom.xml");
        Files.writeString(child, Files.readString(child).replace("../local-parent/pom.xml", "../linked-parent/pom.xml"));

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void aTwoPomParentCycleThroughLocalDirectoryAliasesFailsExplicitly() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>org.example.build</groupId><artifactId>parent-b</artifactId><version>1</version>
                  <relativePath>alias-to-b/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("parent-b", "org.example.build", "parent-b", """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy-a</artifactId><version>1</version>
                  <relativePath>alias-to-a/pom.xml</relativePath></parent>
                <groupId>org.example.build</groupId><version>1</version>
                """);
        Files.createSymbolicLink(temporaryRepository.resolve("taxonomy-a/alias-to-b"),
                temporaryRepository.resolve("parent-b"));
        Files.createSymbolicLink(temporaryRepository.resolve("parent-b/alias-to-a"),
                temporaryRepository.resolve("taxonomy-a"));

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic local parent POM inheritance")
                .hasMessageContaining("alias-to-a/pom.xml");
    }

    @Test
    void aSharedAcyclicParentCanBeResolvedThroughDistinctLocalAliases() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module>"
                + "<module>taxonomy-b</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("shared-parent", "org.example.build", "shared-parent", """
                <packaging>pom</packaging>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """);
        Files.createSymbolicLink(temporaryRepository.resolve("parent-alias-a"),
                temporaryRepository.resolve("shared-parent"));
        Files.createSymbolicLink(temporaryRepository.resolve("parent-alias-b"),
                temporaryRepository.resolve("shared-parent"));
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>org.example.build</groupId><artifactId>shared-parent</artifactId><version>1</version>
                  <relativePath>../parent-alias-a/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("taxonomy-b", "${feature.module}", """
                <parent><groupId>org.example.build</groupId><artifactId>shared-parent</artifactId><version>1</version>
                  <relativePath>../parent-alias-b/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <properties><feature.module>taxonomy-b</feature.module></properties>
                """);

        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);

        assertThat(modules).containsEntry(A, temporaryRepository.resolve("taxonomy-a"))
                .containsEntry(B, temporaryRepository.resolve("taxonomy-b"));
        assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                temporaryRepository, modules, POLICY)).containsExactly(
                new ModuleDependency(A, APP), new ModuleDependency(B, APP));
    }

    @Test
    void laterPropertyParentCanInheritItsOwnArtifactPropertyThroughAnotherReactorParent() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module>"
                + "<module>build-parent</module><module>build-grandparent</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>build-parent</artifactId><version>1</version>
                  <relativePath/></parent>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("build-parent", "build-${parent.name}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>build-grandparent</artifactId><version>1</version>
                  <relativePath/></parent><packaging>pom</packaging>
                """);
        pom("build-grandparent", "build-${grandparent.name}", """
                <packaging>pom</packaging>
                <properties><grandparent.name>grandparent</grandparent.name><parent.name>parent</parent.name></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """);
        assertThatCode(() -> assertThat(fixturePomDependencies())
                .containsExactly(new ModuleDependency(A, APP))).doesNotThrowAnyException();
    }

    @Test
    void aLocalParentVersionRangeCannotHideAnInheritedRuntimeApplicationEdge() throws Exception {
        localParentPoms("[1,2)", """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """, "");

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported local-parent version range").hasMessageContaining("[1,2)");
    }

    @Test
    void aLocalParentVersionRangeCannotTurnInheritedManagedTestsIntoProduction() throws Exception {
        localParentPoms("[1,2)", """
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><version>1</version><scope>test</scope>
                </dependency></dependencies></dependencyManagement>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId></dependency></dependencies>
                """);

        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported local-parent version range").hasMessageContaining("[1,2)");
    }

    @Test
    void anExactLocalParentOutsideTheReactorInheritsDependenciesAndManagedScopes() throws Exception {
        localParentPoms("1", """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId><version>1</version><scope>test</scope>
                </dependency></dependencies></dependencyManagement>
                """, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId></dependency></dependencies>
                """);

        assertThat(fixturePomDependencies()).containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void compiledBytesRejectContradictorySourceFileMetadata() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        rewriteCompiledSourceAttribute(output.resolve("com/taxonomy/a/Service.class"), "WrongOwner.java");
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SourceFile")
                .hasMessageContaining("WrongOwner.java").hasMessageContaining("Service.java");
    }

    @Test
    void compiledBytesAllowAnOmittedOptionalSourceFileAttribute() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        rewriteCompiledSourceAttribute(output.resolve("com/taxonomy/a/Service.class"), null);
        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }
}
