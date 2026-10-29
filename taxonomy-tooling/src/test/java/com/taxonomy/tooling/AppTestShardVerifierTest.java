package com.taxonomy.tooling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

import static com.taxonomy.tooling.AppTestShardVerifier.*;
import static org.junit.jupiter.api.Assertions.*;

class AppTestShardVerifierTest {
    @TempDir Path root;
    Path sources, evidence;
    Map<String, Object> plan;
    static final String COMMIT = "a".repeat(40), TREE = "b".repeat(40);

    @BeforeEach void fixture() throws Exception {
        sources = root.resolve("taxonomy-app/src/test/java"); evidence = root.resolve("target/app-test-shards");
        plan = map("defaultGroup", "core", "excludedTags", List.of(), "requiredExecutions", List.of(), "groups", map("core", map("default", true, "tests", List.of()), "restart", map("tests", List.of("com.taxonomy.RestartTest"), "tag", "ci-restart")));
        source("CoreTest", "// application's annotation must survive apostrophes\nclass CoreTest { @Test void test() {} }");
        source("RestartTest", "@Tag(\"ci-restart\") class RestartTest { @Test void test() {} }");
        for (String group : List.of("core", "restart")) {
            String name = group.equals("core") ? "CoreTest" : "RestartTest";
            Path target = root.resolve("taxonomy-app/target");
            delete(target.resolve("surefire-reports"));
            put("taxonomy-app/target/surefire-reports/TEST-com.taxonomy." + name + ".xml", xml("com.taxonomy." + name, "<testcase name=\"test\"/>", 1, 0));
            put("taxonomy-app/target/jacoco.exec", group + "-coverage");
            stage(root, group, COMMIT, TREE, "123.1." + group);
        }
    }
    Path put(String relative, String text) throws Exception { Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, text); return file; }
    void source(String name, String body) throws Exception { put("taxonomy-app/src/test/java/com/taxonomy/" + name + ".java", "package com.taxonomy; import org.junit.jupiter.api.*;\n" + body); }
    String xml(String name, String cases, int count, int skips) { return "<testsuite name=\"" + name + "\" tests=\"" + count + "\" failures=\"0\" errors=\"0\" skipped=\"" + skips + "\" time=\"1.25\">" + cases + "</testsuite>"; }
    Map<String, Object> verify() throws Exception { return AppTestShardVerifier.verify(root, sources, evidence, plan, COMMIT, TREE, false); }
    Map<String, Object> inputs() throws Exception { return AppTestShardVerifier.verify(root, sources, evidence, plan, COMMIT, TREE, true); }
    Path report(String group, String name) { return evidence.resolve(group + "/tests/surefire-reports/TEST-com.taxonomy." + name + ".xml"); }
    Map<String, Object> manifest(String group) throws Exception { return read(evidence.resolve(group + "/manifest.json")); }
    void manifest(String group, Map<String, Object> manifest) throws Exception { write(evidence.resolve(group + "/manifest.json"), manifest); }
    void replace(String group, String name, String xml) throws Exception {
        Path file = report(group, name); Files.writeString(file, xml);
        Map<String, Object> manifest = manifest(group);
        object(list(manifest.get("reports")).getFirst()).put("sha256", digest(file));
        manifest(group, manifest);
    }
    void addReport(String group, String name, String suiteName, String xml) throws Exception {
        Path file = report(group, name); Files.createDirectories(file.getParent()); Files.writeString(file, xml);
        Map<String, Object> manifest = manifest(group);
        @SuppressWarnings("unchecked") List<Object> reports = (List<Object>) manifest.get("reports");
        reports.add(map("path", "tests/surefire-reports/" + file.getFileName(), "className", suiteName, "sha256", digest(file), "tests", 1, "skipped", 0));
        manifest(group, manifest);
    }
    void fails(String message) { Exception error = assertThrows(Exception.class, this::verify); assertTrue(error.getMessage().contains(message), error.toString()); }

    @Test void mavenCoverageInvocationVerifiesStagedEvidence() throws Exception {
        write(root.resolve(".mvn/verification-suites.json"), map("applicationTestShards", plan));
        var pom = XmlSupport.parse(repository().resolve("taxonomy-build/pom.xml")).getDocumentElement();
        var execution = XmlSupport.descendants(pom, "execution").stream()
                .filter(element -> "verify-complete-app-test-shard-inventory".equals(XmlSupport.childText(element, "id")))
                .findFirst().orElseThrow();
        var arguments = XmlSupport.descendants(execution, "argument").stream()
                .map(org.w3c.dom.Element::getTextContent).toList();
        int command = arguments.indexOf("check-app-test-shards");
        assertTrue(command >= 0, "Maven must invoke the Java shard verifier");
        var invocation = new ArrayList<>(arguments.subList(command + 1, arguments.size()));
        invocation.addAll(List.of("--root", root.toString(), "--commit", COMMIT, "--source-tree", TREE));
        var output = new ByteArrayOutputStream();
        int status = AppTestShardVerifier.run(invocation.toArray(String[]::new), Map.of(), root,
                new PrintStream(output), new PrintStream(output));
        assertEquals(0, status, output.toString());
        assertTrue(Files.isRegularFile(evidence.resolve("timings.json")));
    }

    @Test void discoversNewMandatoryDefaultShardTests() throws Exception {
        source("NewRegressionTest", "class NewRegressionTest { @Test void test() {} }");
        fails("missing report for com.taxonomy.NewRegressionTest");
        addReport("core", "NewRegressionTest", "com.taxonomy.NewRegressionTest", xml("com.taxonomy.NewRegressionTest", "<testcase/>", 1, 0));
        assertEquals(3, object(verify().get("tests")).get("classes"));
    }
    @Test void excludesClassTagsButNeverMethodTagsOrStringsOrComments() throws Exception {
        plan.put("excludedTags", List.of("real-llm"));
        source("RealLlmTest", "@Tag(\"real-llm\") class RealLlmTest { @Test void call() {} }");
        assertEquals(1, object(verify().get("tests")).get("excludedClasses"));
        source("MethodTagTest", "class MethodTagTest { @Test @Tag(\"real-llm\") void call() {} }");
        fails("missing report for com.taxonomy.MethodTagTest");
        Files.delete(sources.resolve("com/taxonomy/MethodTagTest.java"));
        source("StringTagTest", "// @Tag(\"real-llm\")\nclass StringTagTest { String s = \"@Tag(\\\"real-llm\\\")\"; @Test void call() {} }");
        fails("missing report for com.taxonomy.StringTagTest");
    }
    @Test void rejectsReportsForExcludedClass() throws Exception {
        source("CoreTest", "@Tag(\"real-llm\") class CoreTest { @Test void test() {} }");
        plan.put("excludedTags", List.of("real-llm")); fails("profile-excluded");
    }
    @Test void inventoriesArchUnitInheritedParameterizedAndNestedTests() throws Exception {
        source("ArchitectureOnlyTest", "class ArchitectureOnlyTest { @ArchTest static final Object rule = null; }");
        source("AbstractBase", "abstract class AbstractBase { @Test void inherited() {} }");
        source("InheritedOnlyTest", "class InheritedOnlyTest extends AbstractBase {}");
        source("ParameterizedTests", "class ParameterizedTests { @ParameterizedTest void parameterized() {} }");
        source("NestedSuiteTest", "class NestedSuiteTest { @Nested class Nested { @Test void nested() {} } }");
        source("HelperTest", "class HelperTest { String s = \"@Test\"; /* @Test */ }");
        assertEquals(List.of("com.taxonomy.ArchitectureOnlyTest", "com.taxonomy.CoreTest", "com.taxonomy.InheritedOnlyTest", "com.taxonomy.NestedSuiteTest", "com.taxonomy.ParameterizedTests", "com.taxonomy.RestartTest"), discover(sources));
        for (String name : List.of("ArchitectureOnlyTest", "InheritedOnlyTest", "ParameterizedTests")) addReport("core", name, "com.taxonomy." + name, xml("com.taxonomy." + name, "<testcase/>", 1, 0));
        addReport("core", "NestedSuiteTest", "com.taxonomy.NestedSuiteTest", xml("com.taxonomy.NestedSuiteTest", "", 0, 0));
        fails("empty required test execution");
        addReport("core", "NestedSuiteTest$Nested", "com.taxonomy.NestedSuiteTest$Nested", xml("com.taxonomy.NestedSuiteTest$Nested", "<testcase/>", 1, 0));
        assertEquals(6, object(verify().get("tests")).get("classes"));
    }
    @Test void mapsActualWorkspaceNestedReportsToSource() throws Exception {
        Path actual = repository().resolve("taxonomy-app/src/test/java/com/taxonomy/workspace/service/WorkspaceDataIsolationTest.java");
        Path destination = sources.resolve("com/taxonomy/workspace/service/WorkspaceDataIsolationTest.java");
        Files.createDirectories(destination.getParent()); Files.copy(actual, destination);
        String name = "com.taxonomy.workspace.service.WorkspaceDataIsolationTest$ContextResolution";
        Path report = evidence.resolve("core/tests/TEST-" + name + ".xml"); Files.writeString(report, xml(name, "<testcase classname=\"" + name + "\"/>", 1, 0));
        Map<String, Object> manifest = manifest("core");
        @SuppressWarnings("unchecked") var rows = (List<Object>) manifest.get("reports");
        rows.add(map("path", "tests/" + report.getFileName(), "className", name, "sha256", digest(report)));
        manifest("core", manifest);
        assertEquals(3, object(verify().get("tests")).get("classes"));
    }
    @Test void rejectsEmptyOrdinaryExecution() throws Exception { replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "", 0, 0)); fails("empty required test execution"); }
    @ParameterizedTest
    @ValueSource(strings = {"CoreTest", "WorkspaceContext resolution"})
    void acceptsEngineSpecificTestcaseDisplayNamesWithSourceBoundSuite(String displayName) throws Exception {
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest",
                "<testcase classname=\"" + displayName + "\" name=\"test\"/>", 1, 0));
        assertEquals(2, object(verify().get("tests")).get("classes"));
    }
    @Test void requiresExactIsolatedExecutionIdentities() throws Exception {
        String suffix = "module-gate-ci-anchor";
        addReport("core", "CoreTest-" + suffix, "com.taxonomy.CoreTest", xml("com.taxonomy.CoreTest(" + suffix + ")", "<testcase classname=\"com.taxonomy.CoreTest(module-gate-ci-anchor)\"/>", 1, 0));
        fails("unauthorized execution identity");
        plan.put("requiredExecutions", List.of(map("className", "com.taxonomy.CoreTest", "identities", List.of("default", suffix))));
        assertEquals(3, object(verify().get("tests")).get("reports"));
        plan.put("requiredExecutions", List.of(map("className", "com.taxonomy.CoreTest", "identities", List.of(suffix))));
        fails("execution identities mismatch");
    }
    @Test void requiresMissingCatalogueExecutionEvenWithPassingDefaultReport() throws Exception {
        plan.put("requiredExecutions", List.of(map("className", "com.taxonomy.CoreTest", "identities", List.of("default", "product-demo-isolated")))); fails("execution identities mismatch");
    }
    @Test void rejectsOverlappingAssignmentsUncataloguedTagsAndMissingTags() throws Exception {
        Map<String, Object> groups = object(plan.get("groups"));
        object(groups.get("core")).put("tests", List.of("com.taxonomy.RestartTest")); fails("assigned more than once");
        object(groups.get("core")).put("tests", List.of()); object(groups.get("restart")).put("tests", List.of()); fails("catalogue selector");
        object(groups.get("restart")).put("tests", List.of("com.taxonomy.RestartTest"));
        source("RestartTest", "class RestartTest { @Test void test() {} }"); fails("no source tag");
    }
    @Test void rejectsUnknownMissingAndUnlistedArtifacts() throws Exception {
        Files.createDirectories(evidence.resolve("unexpected")); fails("unknown shard artifact");
        delete(evidence.resolve("unexpected"));
        Path extra = evidence.resolve("core/tests/TEST-Extra.xml"); Files.writeString(extra, "unused"); fails("unlisted JUnit"); Files.delete(extra);
        Files.delete(report("restart", "RestartTest")); fails("missing report");
        delete(evidence.resolve("restart")); fails("missing shard artifact: restart");
    }
    @ParameterizedTest @ValueSource(strings = {"commit", "sourceTree", "buildId"}) void bindsCommitTreeAndRun(String field) throws Exception {
        Map<String, Object> manifest = manifest("restart"); manifest.put(field, field.equals("buildId") ? "124.1.restart" : "c".repeat(40)); manifest("restart", manifest);
        fails(field.equals("buildId") ? "same workflow run" : field.equals("sourceTree") ? "source tree mismatch" : "commit mismatch");
    }
    @ParameterizedTest @ValueSource(strings = {"", "invalid", "0"}) void rejectsInvalidBuildIdentity(String value) throws Exception { Map<String, Object> manifest = manifest("core"); manifest.put("buildId", value); manifest("core", manifest); fails("build identity"); }
    @ParameterizedTest @ValueSource(strings = {"", "not-a-digest", "0000000000000000000000000000000000000000000000000000000000000000"}) void rejectsInvalidReportAndCoverageDigests(String value) throws Exception {
        Map<String, Object> manifest = manifest("core"); Map<String, Object> report = object(list(manifest.get("reports")).getFirst()); Object original = report.put("sha256", value); manifest("core", manifest); fails("report digest");
        report.put("sha256", original); object(manifest.get("coverage")).put("sha256", value); manifest("core", manifest); fails("coverage execution digest");
    }
    @Test void detectsTamperedReportAndCoverageBytes() throws Exception {
        Path report = report("core", "CoreTest"); String original = Files.readString(report); Files.writeString(report, original + " "); fails("report digest"); Files.writeString(report, original);
        Files.writeString(evidence.resolve("restart/coverage.exec"), "tampered"); fails("coverage execution digest");
    }
    @ParameterizedTest @ValueSource(strings = {"failure", "error", "flakyFailure", "flakyError", "rerunFailure", "rerunError"}) void detectsFailureEvidenceBehindPassingCounters(String element) throws Exception {
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "<testcase><" + element + "/></testcase>", 1, 0)); fails("failure evidence");
    }
    @ParameterizedTest @ValueSource(strings = {"<testsuite><testcase></testsuite>", "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite/>", "<testsuite name='x' name='y'/>", "<testsuite>&unknown;</testsuite>"}) void rejectsMalformedOrExternalXml(String xml) throws Exception { replace("core", "CoreTest", xml); fails("Cannot parse XML"); }
    @Test void validatesSuiteFilenameSuffixCaseIdentityAndCounters() throws Exception {
        replace("core", "CoreTest", xml("com.taxonomy.OtherTest", "<testcase/>", 1, 0)); fails("filename does not identify");
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest(unauthorized)", "<testcase/>", 1, 0)); fails("execution suffix mismatch");
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "<testcase/>", 2, 0)); fails("inconsistent JUnit");
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "<testcase/>", 1, 1)); fails("inconsistent JUnit");
    }
    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "-1", "invalid"}) void rejectsInvalidDuration(String duration) throws Exception { replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "<testcase/>", 1, 0).replace("1.25", duration)); fails("invalid JUnit duration"); }
    @Test void rejectsDuplicateWrongSourceAndWrongShardReports() throws Exception {
        addReport("core", "RestartTest", "com.taxonomy.RestartTest", xml("com.taxonomy.RestartTest", "<testcase/>", 1, 0)); fails("belongs to restart");
        Files.delete(report("core", "RestartTest")); var manifest = manifest("core"); @SuppressWarnings("unchecked") var rows = (List<Object>) manifest.get("reports"); rows.removeLast(); manifest("core", manifest);
        addReport("core", "MissingTest", "com.taxonomy.MissingTest", xml("com.taxonomy.MissingTest", "<testcase/>", 1, 0)); fails("no matching test source");
    }
    @Test void keepsSkippedCasesAndRequiresEveryCoverageExecution() throws Exception {
        replace("core", "CoreTest", xml("com.taxonomy.CoreTest", "<testcase/><testcase><skipped/></testcase>", 2, 1));
        Map<String, Object> result = verify(); assertEquals(1, object(result.get("tests")).get("skipped")); assertEquals(List.of("core", "restart"), result.get("coverage"));
        Files.delete(evidence.resolve("restart/coverage.exec")); fails("restart coverage execution is missing");
    }
    @Test void preflightValidatesOnlyRequiredExternalGroupsAndBindsRuns() throws Exception {
        delete(evidence.resolve("core")); assertEquals(List.of("restart"), inputs().get("groups"));
        assertEquals(List.of("com.taxonomy.RestartTest"), inputs().get("classes"));
        Files.delete(evidence.resolve("restart/coverage.exec")); assertTrue(assertThrows(Exception.class, this::inputs).getMessage().contains("coverage execution is missing"));
    }
    @Test void stagingRetainsFailureXmlAndCoverageDigest() throws Exception {
        put("taxonomy-app/target/surefire-reports/TEST-com.taxonomy.RestartTest.xml", xml("com.taxonomy.RestartTest", "<testcase><flakyFailure/></testcase>", 1, 0));
        Map<String, Object> result = stage(root, "restart", COMMIT, TREE, "123.1.restart");
        assertEquals(List.of("flakyFailure"), object(list(result.get("reports")).getFirst()).get("failureElements"));
        assertEquals(digest(evidence.resolve("restart/coverage.exec")), object(result.get("coverage")).get("sha256"));
        assertEquals(1.25, object(result.get("timing")).get("durationSeconds")); fails("failure evidence");
    }
    @Test void rejectsArtifactTraversalAndSymlinkEscapes() throws Exception {
        Map<String, Object> manifest = manifest("restart"); object(manifest.get("coverage")).put("path", "../../outside.exec"); manifest("restart", manifest); fails("invalid shard artifact path");
        object(manifest.get("coverage")).put("path", "coverage.exec"); manifest("restart", manifest);
        Path coverage = evidence.resolve("restart/coverage.exec"); Files.delete(coverage); Files.createSymbolicLink(coverage, put("outside.exec", "bytes")); fails("coverage execution is missing");
    }
    @Test void cliWritesTimingsAndNeverDropsRequiredExecutions() throws Exception {
        write(root.resolve(".mvn/verification-suites.json"), map("applicationTestShards", plan));
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        String[] args = {"check-app-test-shards", "--root", root.toString(), "--verify", "true", "--commit", COMMIT, "--source-tree", TREE};
        assertEquals(0, TaxonomyTooling.run(args, Map.of("GITHUB_STEP_SUMMARY", root.resolve("summary.md").toString()), root, new PrintStream(out), new PrintStream(err)), err.toString());
        assertEquals(2L, object(read(evidence.resolve("timings.json")).get("tests")).get("classes"));
        assertTrue(Files.readString(root.resolve("summary.md")).contains("Application test shards"));
        plan.put("requiredExecutions", List.of(map("className", "com.taxonomy.CoreTest", "identities", List.of("default", "module-gate-ci-anchor")))); write(root.resolve(".mvn/verification-suites.json"), map("applicationTestShards", plan));
        assertEquals(1, TaxonomyTooling.run(args, root, new PrintStream(out), new PrintStream(err))); assertTrue(err.toString().contains("execution identities mismatch"));
    }
    @Test void actualCatalogueSelectorsResolveInActualInventory() throws Exception {
        Map<String, Object> actual = object(read(repository().resolve(".mvn/verification-suites.json")).get("applicationTestShards"));
        Set<String> inventory = new HashSet<>(discover(repository().resolve("taxonomy-app/src/test/java")));
        for (Object group : object(actual.get("groups")).values()) assertTrue(inventory.containsAll(strings(object(group).get("tests"))));
        assertTrue(strings(object(object(actual.get("groups")).get("browser")).get("tests")).contains("com.taxonomy.portfolio.reformulation.InventoryComparisonUiIT"));
        assertTrue(inventory.contains("com.taxonomy.ArchitectureExceptionLedgerTest"));
        assertTrue(inventory.contains("com.taxonomy.composition.demo.ProductDemoEndToEndTest"));
    }
    static Path repository() { Path path = Path.of("").toAbsolutePath(); while (!Files.exists(path.resolve(".mvn/verification-suites.json"))) path = Objects.requireNonNull(path.getParent()); return path; }
}
