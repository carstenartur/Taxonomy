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

abstract class ArchitectureModuleGraphTestSupport {

    @TempDir
    Path temporaryRepository;

    @TempDir(cleanup = CleanupMode.ALWAYS)
    Path externalFixtureRoot;

    protected static final String APP = "taxonomy-app";
    protected static final String A = "taxonomy-a";
    protected static final String B = "taxonomy-b";
    protected static final String C = "taxonomy-c";
    protected static final String DOMAIN = "taxonomy-domain";
    protected static final String A_CLASS = "com.taxonomy.a.Service";
    protected static final String B_CLASS = "com.taxonomy.b.Service";
    protected static final String C_CLASS = "com.taxonomy.c.Service";
    protected static final Policy POLICY = new Policy(APP, Set.of("AppConfig.java"), List.of(
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
    void aSupportPomDependencyCannotSubstituteForReactorOwnership() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """);
        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);
        var dependencies = fixturePomDependencies();
        assertThat(dependencies).containsExactly(new ModuleDependency(APP, DOMAIN));

        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(DOMAIN), modules.keySet(),
                List.of(owner("com.taxonomy.AppConfig", APP)), List.of(), dependencies);

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("support module absent from the reactor", "taxonomy-app -> taxonomy-domain"));
    }

    @Test
    void aPresentSupportPomTargetRemainsALegitimateLeaf() {
        Evaluation result = ArchitectureModuleGraph.evaluate(POLICY, Set.of(DOMAIN), Set.of(A, DOMAIN),
                List.of(owner(A_CLASS, A)), List.of(), List.of(new ModuleDependency(A, DOMAIN)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.blockers().get(A)).isEmpty();
        assertThat(result.report()).contains("taxonomy-a -> taxonomy-domain");
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
                owner("com.taxonomy.AppConfig", APP), owner("com.taxonomy.AppConfig$Nested", APP, "AppConfig.java")), List.of(
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
    void aDeclaredFeatureArtifactPropertyResolvesThroughItsLocalParent() throws Exception {
        pom("", "taxonomy", """
                <modules><module>taxonomy-app</module><module>features/a</module></modules>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("taxonomy-app", APP, "");
        pom("features/a", "${feature.module}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy</artifactId><version>1</version>
                  <relativePath>../../pom.xml</relativePath></parent>
                """);

        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);

        assertThat(modules).containsEntry(A, temporaryRepository.resolve("features/a"));
        assertThat(evaluate(modules.keySet(), List.of(owner(A_CLASS, A)), List.of()).violations()).isEmpty();
    }

    @Test
    void aDeclaredArtifactPropertyCanUseALaterDeclaredLocalReactorParent() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>features/a</module><module>taxonomy-parent</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-parent", "taxonomy-parent", "<properties><feature.module>taxonomy-a</feature.module></properties>");
        pom("features/a", "${feature.module}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy-parent</artifactId><version>1</version>
                  <relativePath/></parent>
                """);

        assertThat(ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .containsEntry(A, temporaryRepository.resolve("features/a"));
    }

    @Test
    void aDeclaredPropertyArtifactRetainsItsRuntimeApplicationDependency() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>features/a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("features/a", "${feature.module}", """
                <properties><feature.module>taxonomy-a</feature.module></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <scope>runtime</scope></dependency></dependencies>
                """);

        var modules = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);
        var dependencies = fixturePomDependencies();

        assertThat(dependencies).containsExactly(new ModuleDependency(A, APP));
        assertThat(ArchitectureModuleGraph.evaluate(POLICY, Set.of(), modules.keySet(),
                List.of(owner(A_CLASS, A)), List.of(), dependencies).violations())
                .anyMatch(message -> message.contains("taxonomy-a -> taxonomy-app"));
    }

    @Test
    void declaredPropertyArtifactsCannotResolveToDuplicateReactorNames() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>first</module><module>second</module></modules>");
        pom("taxonomy-app", APP, "");
        for (String directory : List.of("first", "second")) {
            pom(directory, "${feature.module}", "<properties><feature.module>taxonomy-a</feature.module></properties>");
        }

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate reactor artifactId: taxonomy-a");
    }

    @Test
    void unresolvedOrProfileDependentDeclaredArtifactPropertiesFailClosed() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>features/a</module></modules>");
        pom("taxonomy-app", APP, "");
        for (String properties : List.of("", "<properties><feature.module>${feature.module}</feature.module></properties>", """
                <properties><feature.module>taxonomy-a</feature.module></properties>
                <profiles><profile><id>other-name</id><properties><feature.module>taxonomy-b</feature.module></properties></profile></profiles>
                """)) {
            pom("features/a", "${feature.module}", properties);

            assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("artifactId");
        }
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
    void aFeatureDirectorySymlinkIntoAnExcludedBuildTreeCannotEvadeDiscovery() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("target/staged-feature", A, "");
        Files.createSymbolicLink(temporaryRepository.resolve("feature-alias"), temporaryRepository.resolve("target/staged-feature"));

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(A).hasMessageContaining("outside the declared reactor");
    }

    @Test
    void anOutsideDirectorySymlinkIsRejectedBeforeDiscoveryReadsItsPom() throws Exception {
        Path outside = outsideParentFixture();
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        Files.createSymbolicLink(temporaryRepository.resolve("feature-alias"), outside.getParent());

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("POM path is outside repository");
    }

    @Test
    void aSafeDirectorySymlinkCanReferToADeclaredFeature() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>feature-alias</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("local-feature", A, "");
        Files.createSymbolicLink(temporaryRepository.resolve("feature-alias"), temporaryRepository.resolve("local-feature"));

        assertThat(ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .containsEntry(A, temporaryRepository.resolve("feature-alias"));
    }

    @Test
    void aDeclaredPropertyArtifactModuleAliasCycleFailsExplicitly() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>features/a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("features/a", "${feature.module}", """
                <properties><feature.module>taxonomy-a</feature.module></properties>
                <modules><module>self-alias</module></modules>
                """);
        Files.createSymbolicLink(temporaryRepository.resolve("features/a/self-alias"),
                temporaryRepository.resolve("features/a"));

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate or cyclic reactor module declaration")
                .hasMessageContaining("features/a/self-alias/pom.xml");
    }

    @Test
    void aDirectorySymlinkCycleFailsClosed() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module></modules>");
        pom("taxonomy-app", APP, "");
        Files.createSymbolicLink(temporaryRepository.resolve("loop"), temporaryRepository);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY))
                .isInstanceOf(java.nio.file.FileSystemLoopException.class).hasMessageContaining("loop");
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
    void rootAggregatorRemainsDiscoverableButIsNotAProductionGraphOrigin() throws Exception {
        pom("", "taxonomy", """
                <modules>
                  <module>taxonomy-app</module>
                  <module>taxonomy-a</module>
                </modules>
                """);
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", A, """
                <dependencies>
                  <dependency>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy-app</artifactId>
                  </dependency>
                </dependencies>
                """);

        var modules = ArchitectureModuleExtractionTest.discoverModules(
                temporaryRepository, POLICY);

        assertThat(modules).containsKey("taxonomy")
                .containsEntry(APP, temporaryRepository.resolve("taxonomy-app"))
                .containsEntry(A, temporaryRepository.resolve("taxonomy-a"));
        assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                temporaryRepository, modules, POLICY))
                .as("POM-only root aggregator is model metadata, not a production origin")
                .containsExactly(new ModuleDependency(A, APP));
    }

    @Test
    void unclassifiedReactorModuleCannotEvadeTheProductionPomGraph() throws Exception {
        pom("", "taxonomy", """
                <modules>
                  <module>taxonomy-app</module>
                  <module>taxonomy-a</module>
                  <module>taxonomy-catchall</module>
                </modules>
                """);
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", A, "");
        pom("taxonomy-catchall", "taxonomy-catchall", """
                <dependencies>
                  <dependency>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy-app</artifactId>
                  </dependency>
                </dependencies>
                """);
        var modules = ArchitectureModuleExtractionTest.discoverModules(
                temporaryRepository, POLICY);

        assertThatThrownBy(() ->
                ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                        temporaryRepository, modules, POLICY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unclassified reactor module", "taxonomy-catchall");
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

        assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(temporaryRepository, modules, POLICY))
                .containsExactly(new ModuleDependency(A, B), new ModuleDependency(A, DOMAIN));
    }

    @Test
    void aPresentFeatureWithoutAnyPhysicalClassesCannotPassVacuously() {
        Evaluation result = evaluate(Set.of(A), List.of(owner("com.taxonomy.AppConfig", APP)), List.of());

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(A, "no physical production classes"));
    }

    @Test
    void anExternalArchitecturePolicyFileIsRejectedBeforeParsing() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path policy = temporaryRepository.resolve(".github/architecture-contexts.json");
        Path outside = externalFixture("outside-policy-file").resolve("architecture-contexts.json");
        Files.move(policy, outside);
        Files.writeString(outside, "OUTSIDE_POLICY_MUST_NOT_BE_PARSED");
        Files.createSymbolicLink(policy, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Architecture policy path is outside repository through a symlink")
                .hasMessageContaining(".github/architecture-contexts.json");
    }

    @Test
    void anExternalArchitecturePolicyAncestorIsRejectedBeforeParsing() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path policyDirectory = temporaryRepository.resolve(".github");
        Path outside = externalFixture("outside-policy-directory");
        Files.move(policyDirectory.resolve("architecture-contexts.json"), outside.resolve("architecture-contexts.json"));
        Files.writeString(outside.resolve("architecture-contexts.json"), "OUTSIDE_POLICY_MUST_NOT_BE_PARSED");
        Files.delete(policyDirectory);
        Files.createSymbolicLink(policyDirectory, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Architecture policy path is outside repository through a symlink")
                .hasMessageContaining(".github/architecture-contexts.json");
    }

    @Test
    void anArchitecturePolicyFileAliasInsideTheCheckoutRemainsValid() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path policy = temporaryRepository.resolve(".github/architecture-contexts.json");
        Path target = temporaryRepository.resolve("fixture-policy/architecture-contexts.json");
        Files.createDirectories(target.getParent());
        Files.move(policy, target);
        Files.createSymbolicLink(policy, target);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void anExternalArchitectureReportDirectoryIsRejectedBeforeWriting() throws Exception {
        Path outside = externalFixture("outside-report-directory");
        Path build = temporaryRepository.resolve("taxonomy-build");
        Files.createDirectories(build);
        Files.createSymbolicLink(build.resolve("target"), outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.writeReport(temporaryRepository, "report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Architecture report path is outside repository through a symlink")
                .hasMessageContaining("taxonomy-build/target/architecture-module-graph.txt");
        assertThat(outside.resolve("architecture-module-graph.txt")).doesNotExist();
    }

    @Test
    void anExternalArchitectureReportFileIsRejectedWithoutChangingIt() throws Exception {
        Path outside = externalFixture("outside-report-file").resolve("architecture-module-graph.txt");
        Files.writeString(outside, "outside sentinel");
        Path report = temporaryRepository.resolve("taxonomy-build/target/architecture-module-graph.txt");
        Files.createDirectories(report.getParent());
        Files.createSymbolicLink(report, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.writeReport(temporaryRepository, "report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Architecture report path is outside repository through a symlink")
                .hasMessageContaining("taxonomy-build/target/architecture-module-graph.txt");
        assertThat(outside).hasContent("outside sentinel");
    }

    @Test
    void anOrdinaryArchitectureReportPathRemainsWritable() throws Exception {
        ArchitectureModuleExtractionTest.writeReport(temporaryRepository, "ordinary report");

        assertThat(temporaryRepository.resolve("taxonomy-build/target/architecture-module-graph.txt"))
                .hasContent("ordinary report");
    }

    @Test
    void anArchitectureReportDirectoryAliasInsideTheCheckoutRemainsWritable() throws Exception {
        Path target = temporaryRepository.resolve("fixture-report-directory");
        Files.createDirectories(target);
        Path build = temporaryRepository.resolve("taxonomy-build");
        Files.createDirectories(build);
        Files.createSymbolicLink(build.resolve("target"), target);

        ArchitectureModuleExtractionTest.writeReport(temporaryRepository, "directory alias report");

        assertThat(target.resolve("architecture-module-graph.txt")).hasContent("directory alias report");
    }

    @Test
    void anArchitectureReportFileAliasInsideTheCheckoutRemainsWritable() throws Exception {
        Path target = temporaryRepository.resolve("fixture-report.txt");
        Files.writeString(target, "old report");
        Path report = temporaryRepository.resolve("taxonomy-build/target/architecture-module-graph.txt");
        Files.createDirectories(report.getParent());
        Files.createSymbolicLink(report, target);

        ArchitectureModuleExtractionTest.writeReport(temporaryRepository, "file alias report");

        assertThat(target).hasContent("file alias report");
    }

    @Test
    void anArchitecturePolicyDirectoryAliasInsideTheCheckoutRemainsValid() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path policyDirectory = temporaryRepository.resolve(".github");
        Path target = temporaryRepository.resolve("fixture-policy-directory");
        Files.move(policyDirectory, target);
        Files.createSymbolicLink(policyDirectory, target);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void aProductionSourceRootAliasInsideTheCheckoutRetainsItsLogicalInventory() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path sourceRoot = temporaryRepository.resolve(A + "/src/main/java");
        Path target = temporaryRepository.resolve("fixture-alias-targets/source-root");
        Files.createDirectories(target.getParent());
        Files.move(sourceRoot, target);
        Files.createSymbolicLink(sourceRoot, target);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void aProductionOutputRootAliasInsideTheCheckoutIsInventoriedAndImported() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path outputRoot = temporaryRepository.resolve(A + "/target/classes");
        Path target = temporaryRepository.resolve("fixture-alias-targets/output-root");
        Files.createDirectories(target.getParent());
        Files.move(outputRoot, target);
        Files.createSymbolicLink(outputRoot, target);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void nestedSourceAndOutputAliasesInsideTheCheckoutRetainLogicalPackagesAndReachArchUnit() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path aliases = Files.createDirectories(temporaryRepository.resolve("fixture-alias-targets"));
        Path sourcePackage = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a");
        Path sourceTarget = aliases.resolve("nested-source-package");
        Files.move(sourcePackage, sourceTarget);
        Files.createSymbolicLink(sourcePackage, sourceTarget);
        Path outputPackage = temporaryRepository.resolve(A + "/target/classes/com/taxonomy/a");
        Path outputTarget = aliases.resolve("nested-output-package");
        Files.move(outputPackage, outputTarget);
        Files.createSymbolicLink(outputPackage, outputTarget);

        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    @Test
    void anExternalNestedSourceDirectoryIsRejectedBeforeTraversal() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, "com.taxonomy.a.target.Service", "public class Service {}", List.of());
        Path sourceDirectory = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/target");
        Path outside = externalFixture("outside-source-directory").resolve("target");
        Files.move(sourceDirectory, outside);
        Files.createSymbolicLink(sourceDirectory, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production source path is outside repository through a symlink")
                .hasMessageContaining("com/taxonomy/a/target");
    }

    @Test
    void anExternalNestedOutputDirectoryIsRejectedBeforeTraversal() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path outputDirectory = temporaryRepository.resolve(A + "/target/classes/com/taxonomy/a");
        Path outside = externalFixture("outside-output-directory").resolve("a");
        Files.move(outputDirectory, outside);
        Files.createSymbolicLink(outputDirectory, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production output path is outside repository through a symlink")
                .hasMessageContaining("com/taxonomy/a");
    }

    @Test
    void aNestedOutputDirectoryAliasCycleFailsExplicitly() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path packageDirectory = output.resolve("com/taxonomy/a");
        Path loop = packageDirectory.resolve("loop");
        Files.createSymbolicLink(loop, packageDirectory);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(java.nio.file.FileSystemLoopException.class)
                .hasMessageContaining("loop");
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
    void anExternalSourceLinkUnderAnExcludedPackageDirectoryIsRejectedBeforeCompilation() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, "com.taxonomy.a.target.Service", "public class Service {}", List.of());
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/target/Service.java");
        Path outside = externalFixture("outside-source").resolve("Service.java");
        Files.move(source, outside);
        Files.writeString(outside, "OUTSIDE_SOURCE_MUST_NOT_REACH_JAVAC");
        Files.createSymbolicLink(source, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production source path is outside repository through a symlink")
                .hasMessageContaining("target/Service.java");
    }

    @Test
    void anExternalCompiledClassLinkIsRejectedBeforeImport() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path compiledClass = output.resolve("com/taxonomy/a/Service.class");
        Path outside = externalFixture("outside-class").resolve("Service.class");
        Files.move(compiledClass, outside);
        Files.createSymbolicLink(compiledClass, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production output path is outside repository through a symlink")
                .hasMessageContaining("Service.class");
    }

    @Test
    void anExternalTargetDirectoryLinkIsRejectedBeforeClasspathUse() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        Path target = temporaryRepository.resolve(A + "/target");
        Path outside = externalFixture("outside-target").resolve("target");
        Files.move(target, outside);
        Files.createSymbolicLink(target, outside);

        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production output path is outside repository through a symlink")
                .hasMessageContaining(A + "/target/classes");
    }



















    protected void assertClassifiedDependencyCannotSatisfyOwnerContract(String classifiedArtifact) throws Exception {
        pom("taxonomy-build", "taxonomy-build", moduleGateOwnerDependencies(classifiedArtifact));

        assertThatThrownBy(() -> assertModuleGateOwnerDependencies(temporaryRepository, temporaryRepository.resolve("taxonomy-build/pom.xml")))
                .isInstanceOf(AssertionError.class);
    }

    protected static String moduleGateOwnerDependencies(String classifiedArtifact) {
        String dependencies = ownerDependency("com.taxonomy", "taxonomy-app", null, null,
                "taxonomy-app".equals(classifiedArtifact))
                + ownerDependency("com.taxonomy", "taxonomy-coverage", "pom", null,
                "taxonomy-coverage".equals(classifiedArtifact))
                + ownerDependency("com.taxonomy", "taxonomy-tooling", null, "test",
                "taxonomy-tooling".equals(classifiedArtifact))
                + ownerDependency("com.tngtech.archunit", "archunit-junit5", null, "test",
                "archunit-junit5".equals(classifiedArtifact));
        if ("classified-extras".equals(classifiedArtifact)) {
            dependencies += ownerDependency("com.taxonomy", "taxonomy-app", null, null, true)
                    + ownerDependency("com.taxonomy", "taxonomy-coverage", "pom", null, true)
                    + ownerDependency("com.taxonomy", "taxonomy-tooling", null, "test", true)
                    + ownerDependency("com.tngtech.archunit", "archunit-junit5", null, "test", true);
        }
        return "<dependencies>" + dependencies + "</dependencies>";
    }

    protected static String ownerDependency(String group, String artifact, String type, String scope,
                                          boolean classified) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (type == null ? "" : "<type>" + type + "</type>")
                + (classified ? "<classifier>task5-fixture</classifier>" : "")
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }

    protected static void assertModuleGateOwnerDependencies(Path checkout, Path pom) throws Exception {
        Path physicalPom = pom.toRealPath();
        if (!physicalPom.startsWith(checkout.toRealPath()) || !Files.isRegularFile(physicalPom)) {
            throw new IllegalStateException("Owner POM outside checkout: " + pom);
        }
        Map<String, String> dependencies = directProjectDependencies(physicalPom);
        assertThat(dependencies).containsEntry("com.taxonomy:taxonomy-app:jar:", "compile")
                .containsEntry("com.taxonomy:taxonomy-coverage:pom:", "compile")
                .containsEntry("com.taxonomy:taxonomy-tooling:jar:", "test")
                .containsEntry("com.tngtech.archunit:archunit-junit5:jar:", "test");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "public class Service { com.taxonomy.composition.Wiring dependency; }",
            "public class Service extends com.taxonomy.composition.Wiring {}",
            "public class Service { Object make() { return new com.taxonomy.composition.Wiring(); } }"})
    void sameNamedStaleOutputCannotHideANewSourceDependency(String changedDeclaration) throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(APP, "com.taxonomy.composition.Wiring", "public class Wiring {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path binary = output.resolve("com/taxonomy/a/Service.class");
        byte[] original = Files.readAllBytes(binary);
        Path source = temporaryRepository.resolve(A + "/src/main/java/com/taxonomy/a/Service.java");
        Files.writeString(source, "package com.taxonomy.a; " + changedDeclaration);
        Files.setLastModifiedTime(source, Files.getLastModifiedTime(binary));

        Evaluation result = ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository);

        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains("taxonomy-a -> taxonomy-app", "composition"));
        assertThat(Files.readAllBytes(binary)).as("gate must not mutate reactor output").isEqualTo(original);
    }













    protected Path compiledSupportModule() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        compile(A, A_CLASS, "public class Service {}", List.of());
        compile(DOMAIN, "com.taxonomy.dto.Result", "public record Result(String value) {}", List.of());
        return temporaryRepository.resolve(DOMAIN + "/src/main/java");
    }

    protected void assertStaleSupportModuleIsRejected() {
        assertThat(temporaryRepository.resolve(DOMAIN + "/target/classes/com/taxonomy/dto/Result.class")).exists();
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Compiled binary inventory")
                .hasMessageContaining(DOMAIN + ":com.taxonomy.dto.Result").hasMessageContaining("clean reactor build");
    }









    protected void staleDeclaration(String original, String removedBinary) throws Exception {
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

    protected void bytecodeRepository() throws Exception {
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



















































    protected void propertyGroupParentPoms(boolean profile, boolean dependencyInProfile) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        String dependency = """
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """;
        String profiles = profile ? """
                <profiles><profile><id>choose-parent-group</id><properties><local.group>org.example</local.group></properties>
                %s</profile></profiles>
                """.formatted(dependencyInProfile ? dependency : "") : "";
        pom("local-parent", "${local.group}", "local-parent", """
                <packaging>pom</packaging><properties><local.group>org.other</local.group></properties>
                """ + (dependencyInProfile ? "" : dependency) + profiles);
        pom("taxonomy-a", A, """
                <parent><groupId>${local.group}</groupId><artifactId>local-parent</artifactId><version>1</version>
                  <relativePath>../local-parent/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version><properties><local.group>org.example</local.group></properties>
                """);
    }















    protected void registeredPropertyParentPoms(boolean parentFirst, String parentBody, String childBody) throws Exception {
        String modules = parentFirst ? "<module>local-parent</module><module>taxonomy-a</module>"
                : "<module>taxonomy-a</module><module>local-parent</module>";
        pom("", "taxonomy", "<modules><module>taxonomy-app</module>" + modules + "</modules>");
        pom("taxonomy-app", APP, "");
        pom("local-parent", "org.example.build", "build-${parent.module}", """
                <packaging>pom</packaging><properties><parent.module>local-parent</parent.module></properties>
                """ + parentBody);
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>build-local-parent</artifactId><version>1</version>
                  <relativePath/></parent><groupId>com.taxonomy</groupId><version>1</version>
                """ + childBody);
    }

    protected void aliasedPropertyParentPoms(String alias, String parentBody, String childBody) throws Exception {
        boolean registeredAlias = alias.equals("registered-directory");
        String registeredParent = registeredAlias ? "parent-alias" : "parent-real";
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module><module>"
                + registeredParent + "</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("parent-real", "org.example.build", "build-${parent.module}", """
                <packaging>pom</packaging><properties><parent.module>local-parent</parent.module></properties>
                """ + parentBody);
        if (alias.equals("file")) {
            Files.createDirectories(temporaryRepository.resolve("parent-alias"));
            Files.createSymbolicLink(temporaryRepository.resolve("parent-alias/pom.xml"),
                    temporaryRepository.resolve("parent-real/pom.xml"));
        } else {
            Files.createSymbolicLink(temporaryRepository.resolve("parent-alias"),
                    temporaryRepository.resolve("parent-real"));
        }
        String relativeParent = registeredAlias ? "../parent-real/pom.xml" : "../parent-alias/pom.xml";
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>build-local-parent</artifactId><version>1</version>
                  <relativePath>%s</relativePath></parent><groupId>com.taxonomy</groupId><version>1</version>
                """.formatted(relativeParent) + childBody);
    }







    protected void propertyArtifactParentPoms(String parentArtifact, String profiles) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("local-parent", "org.example.build", "build-${parent.module}", """
                <packaging>pom</packaging><properties><parent.module>%s</parent.module></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """.formatted(parentArtifact) + profiles);
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>build-${parent.module}</artifactId><version>1</version>
                  <relativePath>../local-parent/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version><properties><parent.module>local-parent</parent.module></properties>
                """);
    }















    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"child-first", "parent-first", "external-child-first"})
    void propertyReactorParentLookupDoesNotDependOnDeclarationOrder(String order) throws Exception {
        String group = order.startsWith("external") ? "org.example.build" : "com.taxonomy";
        String modules = order.equals("parent-first")
                ? "<module>build-parent</module><module>taxonomy-a</module>"
                : "<module>taxonomy-a</module><module>build-parent</module>";
        pom("", "taxonomy", "<modules><module>taxonomy-app</module>" + modules + "</modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>%s</groupId><artifactId>build-parent</artifactId><version>1</version>
                  <relativePath/></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId>
                  <version>1</version></dependency></dependencies>
                """.formatted(group));
        pom("build-parent", group, "build-${parent.name}", """
                <packaging>pom</packaging>
                <properties><parent.name>parent</parent.name></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                <dependencyManagement><dependencies><dependency><groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy-domain</artifactId><version>1</version><scope>test</scope>
                </dependency></dependencies></dependencyManagement>
                """);

        assertThatCode(() -> {
            var discovered = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);
            assertThat(discovered).containsEntry(A, temporaryRepository.resolve("taxonomy-a"))
                    .containsEntry("build-parent", temporaryRepository.resolve("build-parent"));
            assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                    temporaryRepository, discovered, POLICY)).containsExactly(new ModuleDependency(A, APP));
        }).doesNotThrowAnyException();
    }



    protected void externalParentPoms(String artifact, String relative) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", A, """
                <parent><groupId>org.example</groupId><artifactId>%s</artifactId><version>1</version>
                  <relativePath>%s</relativePath></parent><groupId>com.taxonomy</groupId><version>1</version>
                """.formatted(artifact, relative));
    }

    protected Path outsideParentFixture() throws Exception {
        Path outside = temporaryRepository.resolve("outside/pom.xml");
        Files.createDirectories(outside.getParent());
        // Malformed sentinel: a parser error would prove that content was read
        // before the checkout boundary was enforced.
        Files.writeString(outside, "Outside fixture content must not be parsed");
        temporaryRepository = temporaryRepository.resolve("checkout");
        Files.createDirectories(temporaryRepository);
        return outside;
    }

    protected void assertOutsidePomIsRejected() {
        assertThatThrownBy(this::fixturePomDependencies).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM path is outside repository");
    }







    protected void parentReference(String coordinate, String value, String body) throws Exception {
        String parent = """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy-parent</artifactId><version>1</version>
                  <relativePath>../taxonomy-parent/pom.xml</relativePath></parent>
                """;
        String original = switch (coordinate) {
            case "groupId" -> "com.taxonomy";
            case "artifactId" -> "taxonomy-parent";
            case "version" -> "1";
            default -> throw new IllegalArgumentException(coordinate);
        };
        pom("taxonomy-a", A, parent.replace("<" + coordinate + ">" + original + "</" + coordinate + ">",
                "<" + coordinate + ">" + value + "</" + coordinate + ">") + body);
    }

    protected void localParentPoms(String version, String parentBody, String childBody) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("local-parent", "org.example.build", "local-build-parent", "<packaging>pom</packaging>" + parentBody);
        pom("taxonomy-a", A, """
                <parent><groupId>org.example.build</groupId><artifactId>local-build-parent</artifactId><version>%s</version>
                  <relativePath>../local-parent/pom.xml</relativePath></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                """.formatted(version) + childBody);
    }

    protected void inheritedPoms(String parentBody, String childBody) throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-parent</module><module>taxonomy-a</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-parent", "taxonomy-parent", "<packaging>pom</packaging>" + parentBody);
        pom("taxonomy-a", A, """
                <parent><groupId>com.taxonomy</groupId><artifactId>taxonomy-parent</artifactId><version>1</version>
                  <relativePath>../taxonomy-parent/pom.xml</relativePath></parent>
                """ + childBody);
    }

    protected List<ModuleDependency> fixturePomDependencies() throws Exception {
        return ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                temporaryRepository, ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY), POLICY);
    }

    protected Path compile(String module, String name, String declaration, List<Path> classpath) throws Exception {
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

    protected Path externalFixture(String name) throws Exception {
        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid external fixture name: " + name);
        }
        Path managedRoot = externalFixtureRoot.toAbsolutePath().normalize();
        Path outside = managedRoot.resolve(name).normalize();
        if (!managedRoot.equals(outside.getParent())) {
            throw new IllegalArgumentException("External fixture must be a direct child of its managed root: " + name);
        }
        return Files.createDirectory(outside);
    }

    protected static Path checkoutRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(".github/architecture-contexts.json"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root for module gate owner contract");
        }
        return current;
    }

    protected static Map<String, String> directProjectDependencies(Path pom) throws Exception {
        Element project = xml(pom);
        Map<String, String> result = new HashMap<>();
        for (Element dependencies : directChildren(project, "dependencies")) {
            for (Element dependency : directChildren(dependencies, "dependency")) {
                String group = directChildText(dependency, "groupId");
                String artifact = directChildText(dependency, "artifactId");
                String type = directChildText(dependency, "type");
                String classifier = directChildText(dependency, "classifier");
                String scope = directChildText(dependency, "scope");
                result.put(group + ":" + artifact + ":" + (type.isBlank() ? "jar" : type) + ":" + classifier,
                        scope.isBlank() ? "compile" : scope);
            }
        }
        return result;
    }

    protected static String profileProperty(Path pom, String profileId, String property) throws Exception {
        for (Element profiles : directChildren(xml(pom), "profiles")) {
            for (Element profile : directChildren(profiles, "profile")) {
                if (profileId.equals(directChildText(profile, "id"))) {
                    List<Element> properties = directChildren(profile, "properties");
                    return properties.isEmpty() ? "" : directChildText(properties.getFirst(), property);
                }
            }
        }
        return "";
    }

    protected static Element xml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    protected static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    protected static String directChildText(Element parent, String name) {
        List<Element> result = directChildren(parent, name);
        return result.isEmpty() ? "" : result.getFirst().getTextContent().trim();
    }

    protected void pom(String directory, String artifact, String body) throws Exception {
        pom(directory, "com.taxonomy", artifact, body);
    }

    protected void pom(String directory, String group, String artifact, String body) throws Exception {
        Path path = temporaryRepository.resolve(directory).resolve("pom.xml");
        Files.createDirectories(path.getParent());
        String effectiveBody = directory.isEmpty() && !body.contains("<packaging>")
                ? "<packaging>pom</packaging>" + body
                : body;
        Files.writeString(path, "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + (effectiveBody.contains("<parent>") ? "" : "<groupId>" + group + "</groupId><version>1</version>")
                + "<artifactId>" + artifact + "</artifactId>" + effectiveBody + "</project>");
    }

    protected static Evaluation evaluate(Set<String> present, List<ClassOwner> owners, List<ClassDependency> edges) {
        return ArchitectureModuleGraph.evaluate(POLICY, Set.of(DOMAIN), present, owners, edges);
    }

    protected static List<ClassOwner> extracted() {
        return List.of(owner(A_CLASS, A), owner(B_CLASS, B), owner(C_CLASS, C));
    }

    protected static ClassOwner owner(String name, String module) {
        return owner(name, module, name.substring(name.lastIndexOf('.') + 1) + ".java");
    }

    protected static ClassOwner owner(String name, String module, String sourceFile) {
        return new ClassOwner(name, module, sourceFile);
    }

    protected static ClassDependency edge(String from, String to) {
        return new ClassDependency(from, to);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"copy", "file-alias", "directory-alias"})
    void compiledBytesRejectWrongInternalIdentity(String placement) throws Exception {
        bytecodeRepository();
        Path app = compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path destination = output.resolve("com/taxonomy/a/Service.class");
        Path alias = temporaryRepository.resolve("fixture-binary-alias/Service.class");
        Files.createDirectories(alias.getParent());
        Files.copy(app.resolve("com/taxonomy/AppConfig.class"), alias);
        if (placement.equals("copy")) {
            Files.copy(alias, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else if (placement.equals("file-alias")) {
            Files.delete(destination);
            Files.createSymbolicLink(destination, alias);
        } else {
            Files.delete(destination);
            Files.delete(destination.getParent());
            Files.createSymbolicLink(destination.getParent(), alias.getParent());
        }
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("compiled class")
                .hasMessageContaining("com.taxonomy.a.Service");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void compiledBytesRejectTruncatedOutput(boolean alias) throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path destination = output.resolve("com/taxonomy/a/Service.class");
        byte[] truncated = java.util.Arrays.copyOf(Files.readAllBytes(destination), 12);
        if (alias) {
            Path target = temporaryRepository.resolve("fixture-truncated.class");
            Files.write(target, truncated);
            Files.delete(destination);
            Files.createSymbolicLink(destination, target);
        } else {
            Files.write(destination, truncated);
        }
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("compiled class");
    }





    protected static void rewriteCompiledSourceAttribute(Path file, String source) throws Exception {
        var reader = new org.springframework.asm.ClassReader(Files.readAllBytes(file));
        var writer = new org.springframework.asm.ClassWriter(0);
        reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.Opcodes.ASM9, writer) {
            @Override public void visitSource(String ignored, String debug) {
                if (source != null) super.visitSource(source, debug);
            }
        }, 0);
        Files.write(file, writer.toByteArray());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void ownerPomCannotParseOutsideCheckout(String linkKind) throws Exception {
        Path outside = externalFixture("outside-owner-pom");
        Files.writeString(outside.resolve("pom.xml"), "EXTERNAL_CONTENT_MUST_NOT_BE_PARSED");
        Path build = temporaryRepository.resolve("taxonomy-build");
        if (linkKind.equals("file")) {
            Files.createDirectories(build);
            Files.createSymbolicLink(build.resolve("pom.xml"), outside.resolve("pom.xml"));
        } else {
            Files.createSymbolicLink(build, outside);
        }
        assertThatThrownBy(() -> assertModuleGateOwnerDependencies(temporaryRepository, build.resolve("pom.xml")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Owner POM outside checkout");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void ownerPomAcceptsContainedAliases(String linkKind) throws Exception {
        pom("owner-alias-target", "taxonomy-build", moduleGateOwnerDependencies("classified-extras"));
        Path target = temporaryRepository.resolve("owner-alias-target");
        Path build = temporaryRepository.resolve("taxonomy-build");
        if (linkKind.equals("file")) {
            Files.createDirectories(build);
            Files.createSymbolicLink(build.resolve("pom.xml"), target.resolve("pom.xml"));
        } else {
            Files.createSymbolicLink(build, target);
        }
        assertThatCode(() -> assertModuleGateOwnerDependencies(temporaryRepository, build.resolve("pom.xml")))
                .doesNotThrowAnyException();
    }


}
