package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.taxonomy.ArchitectureModuleGraph.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureModuleGraphTest {

    @TempDir
    Path temporaryRepository;

    private static final String APP = "taxonomy-app";
    private static final String A = "taxonomy-a";
    private static final String B = "taxonomy-b";
    private static final String C = "taxonomy-c";
    private static final String DOMAIN = "taxonomy-domain";
    private static final String A_CLASS = "com.taxonomy.a.Service";
    private static final String B_CLASS = "com.taxonomy.b.Service";
    private static final String C_CLASS = "com.taxonomy.c.Service";
    private static final Policy POLICY = new Policy(APP, Set.of("AppConfig.java"), List.of(
            new Context("a", A, List.of("com.taxonomy.a..")),
            new Context("b", B, List.of("com.taxonomy.b..")),
            new Context("c", C, List.of("com.taxonomy.c..")),
            new Context("unassigned", null, List.of("com.taxonomy.unassigned..")),
            new Context("composition", APP, List.of("com.taxonomy.composition..", "com.taxonomy.shared.."))));

    @Test
    void rejectsIndirectCycleReachableFromAnExtractedModule() {
        Evaluation result = evaluate(Set.of(A, B, C), extracted(), List.of(
                edge(A_CLASS, B_CLASS), edge(B_CLASS, C_CLASS), edge(C_CLASS, B_CLASS)));

        assertThat(result.cycles()).containsExactly("taxonomy-b -> taxonomy-c -> taxonomy-b");
        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(A, "cycle", "taxonomy-a -> taxonomy-b"));
    }

    @Test
    void rejectsIndirectReturnToTheApplication() {
        Evaluation result = evaluate(Set.of(A, B), List.of(
                owner(A_CLASS, A), owner(B_CLASS, B), owner("com.taxonomy.composition.Wiring", APP)), List.of(
                edge(A_CLASS, B_CLASS), edge(B_CLASS, "com.taxonomy.composition.Wiring")));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-b -> taxonomy-app", "composition"));
    }

    @Test
    void unresolvedContextIsApplicationOwnedAndCannotBeExtractedThrough() {
        Evaluation result = evaluate(Set.of(A), List.of(
                owner(A_CLASS, A), owner("com.taxonomy.unassigned.History", APP)), List.of(
                edge(A_CLASS, "com.taxonomy.unassigned.History")));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-app", "unassigned", "targetModule"));
    }

    @Test
    void acceptsAnAcyclicExtractedChainEndingInAnExistingModule() {
        Evaluation result = evaluate(Set.of(A, B, DOMAIN), List.of(
                owner(A_CLASS, A), owner(B_CLASS, B), owner("com.taxonomy.dto.Result", DOMAIN)), List.of(
                edge(A_CLASS, B_CLASS), edge(B_CLASS, "com.taxonomy.dto.Result")));

        assertThat(result.violations()).isEmpty();
        assertThat(result.cycles()).isEmpty();
        assertThat(result.blockers().get(A)).isEmpty();
        assertThat(result.report()).contains("taxonomy-a -> taxonomy-b", "taxonomy-b -> taxonomy-domain",
                "com.taxonomy.b.Service -> com.taxonomy.dto.Result");
    }

    @Test
    void disconnectedUnextractedCycleIsReportedWithoutBlockingSafeExtraction() {
        Evaluation result = evaluate(Set.of(A), List.of(
                owner(A_CLASS, A), owner(B_CLASS, APP), owner(C_CLASS, APP)), List.of(
                edge(B_CLASS, C_CLASS), edge(C_CLASS, B_CLASS)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.cycles()).containsExactly("taxonomy-b -> taxonomy-c -> taxonomy-b");
        assertThat(result.blockers().get(B)).anyMatch(message -> message.contains("cycle"));
    }

    @Test
    void evaluatesTheCyclicProposalEvenBeforeAnyModuleExists() {
        Evaluation result = evaluate(Set.of(), List.of(owner(A_CLASS, APP), owner(B_CLASS, APP)), List.of(
                edge(A_CLASS, B_CLASS), edge(B_CLASS, A_CLASS)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.cycles()).containsExactly("taxonomy-a -> taxonomy-b -> taxonomy-a");
        assertThat(result.blockers().get(A)).isNotEmpty();
        assertThat(result.report()).contains("Present feature modules: none", "Planned module edges:",
                "taxonomy-a -> taxonomy-b", "taxonomy-b -> taxonomy-a");
    }

    @Test
    void reportsEveryEdgeWithDeterministicRepresentativeClassEvidence() {
        List<ClassOwner> owners = new ArrayList<>(List.of(
                owner(A_CLASS, A), owner("com.taxonomy.a.Another", A), owner(B_CLASS, B), owner(C_CLASS, C)));
        List<ClassDependency> edges = new ArrayList<>(List.of(edge(A_CLASS, B_CLASS),
                edge("com.taxonomy.a.Another", B_CLASS), edge(B_CLASS, C_CLASS), edge(C_CLASS, A_CLASS)));
        Evaluation first = evaluate(Set.of(C, A, B), owners, edges);
        Collections.reverse(owners);
        Collections.reverse(edges);
        edges.add(edge(A_CLASS, B_CLASS));
        Evaluation second = evaluate(Set.of(B, C, A), owners, edges);

        assertThat(second.report()).isEqualTo(first.report());
        assertThat(first.report()).contains("com.taxonomy.a.Another -> com.taxonomy.b.Service",
                "taxonomy-a -> taxonomy-b", "taxonomy-b -> taxonomy-c", "taxonomy-c -> taxonomy-a");
        assertThat(second.violations()).containsExactlyElementsOf(first.violations());
    }

    @Test
    void rootCompositionClassAndItsNestedClassesCannotEvadeTheGraph() {
        Evaluation result = evaluate(Set.of(A), List.of(owner(A_CLASS, A),
                owner("com.taxonomy.AppConfig", APP), owner("com.taxonomy.AppConfig$Nested", APP)), List.of(
                edge(A_CLASS, "com.taxonomy.AppConfig$Nested"), edge("com.taxonomy.AppConfig", A_CLASS)));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-app", "AppConfig$Nested", "root composition"));
        assertThat(result.cycles()).containsExactly("taxonomy-a -> taxonomy-app -> taxonomy-a");
    }

    @Test
    void exactPhysicalOwnershipRecognizesExistingModulesDespiteOverlappingAppPackages() {
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of("taxonomy-extension-api"),
                Set.of(A, "taxonomy-extension-api"), List.of(owner(A_CLASS, A),
                        owner("com.taxonomy.shared.extension.Contract", "taxonomy-extension-api")), List.of(
                        edge(A_CLASS, "com.taxonomy.shared.extension.Contract")));

        assertThat(result.violations()).isEmpty();
        assertThat(result.report()).contains("taxonomy-a -> taxonomy-extension-api");
    }

    @Test
    void unclassifiedAndMissingInternalClassesFailClosedBeforeExtraction() {
        Evaluation result = evaluate(Set.of(), List.of(owner(A_CLASS, APP),
                owner("com.taxonomy.UnlistedRoot", APP), owner("com.taxonomy.unknown.Hidden", APP)), List.of(
                edge(A_CLASS, "com.taxonomy.missing.Gone")));

        assertThat(result.violations()).anyMatch(message -> message.contains("UnlistedRoot"))
                .anyMatch(message -> message.contains("unknown.Hidden"))
                .anyMatch(message -> message.contains("missing.Gone"));
    }

    @Test
    void invalidAndConflictingPhysicalOwnersFailClosed() {
        Evaluation result = evaluate(Set.of(A), List.of(owner(A_CLASS, A), owner(A_CLASS, B),
                owner(B_CLASS, "taxonomy-unplanned")), List.of());

        assertThat(result.violations()).anyMatch(message -> message.contains("multiple physical owners"))
                .anyMatch(message -> message.contains("taxonomy-unplanned"));
    }

    @Test
    void rejectsAnExtractedModuleDependingOnAnUnextractedOwner() {
        Evaluation result = evaluate(Set.of(A), List.of(owner(A_CLASS, A), owner(B_CLASS, APP)), List.of(
                edge(A_CLASS, B_CLASS)));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-b", "unextracted"));
    }

    @Test
    void aPresentModulePomDoesNotMakeItsAppOwnedClassesExtracted() {
        Evaluation result = evaluate(Set.of(A, B), List.of(owner(A_CLASS, A), owner(B_CLASS, APP)), List.of(
                edge(A_CLASS, B_CLASS)));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(A, B_CLASS, "still physically owned by taxonomy-app"));
    }

    @Test
    void anExistingModuleCannotHideATransitiveReturnToApp() {
        Evaluation result = evaluate(Set.of(A, DOMAIN), List.of(owner(A_CLASS, A),
                owner("com.taxonomy.dto.Result", DOMAIN), owner("com.taxonomy.AppConfig", APP)), List.of(
                edge(A_CLASS, "com.taxonomy.dto.Result"), edge("com.taxonomy.dto.Result", "com.taxonomy.AppConfig")));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-domain -> taxonomy-app"));
    }

    @Test
    void aReactorPomAutomaticallyActivatesExtractionEvenBeforeClassesMove() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>features/a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("features/a", A, "");
        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);

        assertThat(modules).containsKey(A);
        Evaluation result = evaluate(modules.keySet(), List.of(owner(A_CLASS, APP), owner(B_CLASS, APP)),
                List.of(edge(A_CLASS, B_CLASS)));
        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(A, "unextracted owner"));
    }

    @Test
    void anUnregisteredFeaturePomCannotEvadeExtractionDiscovery() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("features/a", A, "");

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(A).hasMessageContaining("reactor");
    }

    @Test
    void anUnregisteredFeaturePomWithAnInheritedArtifactPropertyCannotEvadeDiscovery() throws Exception {
        pom("", "taxonomy", """
                <modules><module>taxonomy-app</module></modules>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("taxonomy-app", APP, "");
        pom("features/a", "${feature.module}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy</artifactId><version>1</version>
                  <relativePath>../../pom.xml</relativePath></parent>
                """);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(A).hasMessageContaining("outside the declared reactor");
    }

    @Test
    void anUnregisteredPomWithAnUnresolvedPossibleFeatureArtifactFailsClosed() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("features/a", "taxonomy-${feature.name}", "");

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unresolved")
                .hasMessageContaining("artifactId").hasMessageContaining("features/a/pom.xml");
    }

    @Test
    void anUnregisteredPropertyArtifactThatCannotNameAFeatureRemainsUnrelated() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("tools/resolved", "${tool.module}", "<properties><tool.module>unrelated-tool</tool.module></properties>");
        pom("tools/unresolved", "unrelated-${tool.name}", "");

        assertThat(ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY)).containsOnlyKeys("taxonomy", APP);
    }

    @Test
    void aMissingDeclaredModulePomFailsClosed() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("taxonomy-a/pom.xml");
    }

    @Test
    void invalidAndOverlappingPoliciesCannotSilentlyChooseAnOwner() {
        Policy overlapping = new Policy(APP, POLICY.rootCompositionClasses(), List.of(
                new Context("a", A, List.of("com.taxonomy.a..")),
                new Context("composition", APP, List.of("com.taxonomy.a.nested.."))));

        assertThatThrownBy(() -> ArchitectureModuleGraph.evaluate(overlapping, Set.of(), Set.of(),
                List.of(owner(A_CLASS, APP)), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlapping");
        Evaluation empty = evaluate(Set.of(), List.of(), List.of());
        assertThat(empty.violations()).anyMatch(message -> message.contains("No production"));
    }

    @Test
    void unusedProductionPomDependenciesStillBlockAnIndirectReturnToApp() {
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(DOMAIN), Set.of(A, DOMAIN),
                List.of(owner(A_CLASS, A), owner("com.taxonomy.dto.Result", DOMAIN)), List.of(), List.of(
                        new ModuleDependency(A, DOMAIN), new ModuleDependency(DOMAIN, APP)));

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-domain -> taxonomy-app", "POM"));
        assertThat(result.report()).contains("Declared production POM edges:", "taxonomy-a -> taxonomy-domain");
    }

    @Test
    void productionPomCyclesAndUnknownInternalOwnersFailClosed() {
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(), Set.of(A, B, C), extracted(),
                List.of(edge(A_CLASS, B_CLASS)), List.of(new ModuleDependency(B, C),
                        new ModuleDependency(C, B), new ModuleDependency(A, "taxonomy-unknown")));

        assertThat(result.cycles()).containsExactly("taxonomy-b -> taxonomy-c -> taxonomy-b");
        assertThat(result.violations()).anySatisfy(message -> assertThat(message).contains(A, "cycle"))
                .anyMatch(message -> message.contains("taxonomy-unknown"));
    }

    @Test
    void productionPomGraphIncludesRuntimeAndProfileDependenciesButNotTestsOrDependencyManagement() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", A, """
                <dependencies>
                  <dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId><scope>runtime</scope></dependency>
                  <dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId><scope>test</scope></dependency>
                </dependencies>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-c</artifactId></dependency>
                </dependencies></dependencyManagement>
                <profiles><profile><id>feature</id><dependencies>
                  <dependency><groupId>${project.groupId}</groupId><artifactId>taxonomy-b</artifactId></dependency>
                </dependencies></profile></profiles>
                """);
        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);

        assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(modules, POLICY))
                .containsExactly(new ModuleDependency(A, B), new ModuleDependency(A, DOMAIN));
    }

    @Test
    void aPresentFeatureWithoutAnyPhysicalClassesCannotPassVacuously() {
        Evaluation result = evaluate(Set.of(A), List.of(owner("com.taxonomy.AppConfig", APP)), List.of());

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(A, "no physical production classes"));
    }

    @Test
    void realBytecodeAndPhysicalSourcesDriveTheGateTogether() throws Exception {
        bytecodeRepository();
        Path app = compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path domain = compile(DOMAIN, "com.taxonomy.dto.Result", "public record Result(String value) {}", List.of());
        compile(A, A_CLASS, "public class Service { com.taxonomy.AppConfig app; com.taxonomy.dto.Result result; }",
                List.of(app, domain));

        Evaluation result = ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository);

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-app", "com.taxonomy.AppConfig", "root composition"));
        assertThat(result.report()).contains("com.taxonomy.a.Service -> com.taxonomy.dto.Result",
                "Present feature modules: taxonomy-a");
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

    private Path compiledSupportModule() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        compile(DOMAIN, "com.taxonomy.dto.Result", "public record Result(String value) {}", List.of());
        return temporaryRepository.resolve(DOMAIN + "/src/main/java");
    }

    private void assertStaleSupportModuleIsRejected() {
        assertThat(temporaryRepository.resolve(DOMAIN + "/target/classes/com/taxonomy/dto/Result.class")).exists();
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Compiled binary inventory")
                .hasMessageContaining(DOMAIN + ":com.taxonomy.dto.Result").hasMessageContaining("clean reactor build");
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

    private void staleDeclaration(String original, String removedBinary) throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, original, List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path stale = output.resolve("com/taxonomy/a/" + removedBinary);
        assertThat(stale).exists();
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/Service.java");
        // A timestamp-only check must not make the obsolete declaration valid.
        Files.setLastModifiedTime(stale, Files.getLastModifiedTime(source));

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Compiled binary inventory")
                .hasMessageContaining(removedBinary.substring(0, removedBinary.length() - ".class".length()))
                .hasMessageContaining("clean reactor build");
    }

    private void bytecodeRepository() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-domain</module>"
                + "<module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-domain", DOMAIN, "");
        pom("taxonomy-a", A, "");
        Path policy = temporaryRepository.resolve(".github/architecture-contexts.json");
        Files.createDirectories(policy.getParent());
        Files.writeString(policy, """
                {"schemaVersion":1,"compositionModule":"taxonomy-app","rootCompositionClasses":["AppConfig.java"],
                 "catchAllAdapterModuleAllowed":false,"contexts":[
                   {"id":"a","targetModule":"taxonomy-a","packages":["com.taxonomy.a.."]},
                   {"id":"composition","targetModule":"taxonomy-app","packages":["com.taxonomy.composition.."]}]}
                """);
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

    private void inheritedPoms(String parentBody, String childBody) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-parent</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-parent", "taxonomy-parent", parentBody);
        pom("taxonomy-a", A, """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy-parent</artifactId><version>1</version>
                  <relativePath>../taxonomy-parent/pom.xml</relativePath></parent>
                """ + childBody);
    }

    private List<ModuleDependency> fixturePomDependencies() throws Exception {
        return ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY), POLICY);
    }

    private Path compile(String module, String name, String declaration, List<Path> classpath) throws Exception {
        Path source = temporaryRepository.resolve(module).resolve("src/main/java/" + name.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package " + name.substring(0, name.lastIndexOf('.')) + "; " + declaration);
        Path output = temporaryRepository.resolve(module).resolve("target/classes");
        Files.createDirectories(output);
        List<String> arguments = new ArrayList<>(List.of("-d", output.toString()));
        if (!classpath.isEmpty()) {
            arguments.addAll(List.of("-classpath", String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList())));
        }
        arguments.add(source.toString());
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .as("compile module fixture %s", module).isZero();
        return output;
    }

    private void pom(String directory, String artifact, String body) throws Exception {
        Path path = temporaryRepository.resolve(directory).resolve("pom.xml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + (body.contains("<parent>") ? "" : "<groupId>com.taxonomy</groupId><version>1</version>")
                + "<artifactId>" + artifact + "</artifactId>" + body + "</project>");
    }

    private static Evaluation evaluate(Set<String> present, List<ClassOwner> owners, List<ClassDependency> edges) {
        return ArchitectureModuleGraph.evaluate(POLICY, Set.of(DOMAIN), present, owners, edges);
    }

    private static List<ClassOwner> extracted() {
        return List.of(owner(A_CLASS, A), owner(B_CLASS, B), owner(C_CLASS, C));
    }

    private static ClassOwner owner(String name, String module) {
        return new ClassOwner(name, module);
    }

    private static ClassDependency edge(String from, String to) {
        return new ClassDependency(from, to);
    }
}
