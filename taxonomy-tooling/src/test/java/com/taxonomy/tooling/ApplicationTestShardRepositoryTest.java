package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.taxonomy.tooling.AppTestShardVerifier.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationTestShardRepositoryTest {
    final Path root = AppTestShardVerifierTest.repository();
    Element pom(String module) throws Exception { return XmlSupport.parse(root.resolve(module.isEmpty() ? "pom.xml" : module + "/pom.xml")).getDocumentElement(); }
    Element profile(Element project, String id) { return XmlSupport.descendants(project, "profile").stream().filter(element -> id.equals(XmlSupport.childText(element, "id"))).findFirst().orElseThrow(() -> new AssertionError("Missing profile " + id)); }
    @Test void catalogueProfilesAndRealSourceTagsStaySynchronized() throws Exception {
        Map<String, Object> plan = object(read(root.resolve(".mvn/verification-suites.json")).get("applicationTestShards"));
        assertDoesNotThrow(() -> catalogueAssignments(root.resolve("taxonomy-app/src/test/java"), plan),
                "Every source shard tag must be registered before expensive application tests start");
        assertEquals("core", plan.get("defaultGroup")); assertFalse(plan.containsKey("allowedRepeatedClasses"));
        assertEquals(List.of(map("className", "com.taxonomy.composition.demo.ProductDemoEndToEndTest", "identities", List.of("product-demo-isolated")), map("className", "com.taxonomy.ArchitectureExceptionLedgerTest", "identities", List.of("default", "module-gate-ci-anchor"))), plan.get("requiredExecutions"));
        Element project = pom("");
        Set<String> coreExclusions = new HashSet<>(strings(plan.get("excludedTags")));
        for (Map<String, Object> group : object(plan.get("groups")).values().stream().map(AppTestShardVerifier::object).toList()) {
            Element profile = profile(project, (String) group.get("profile"));
            if (Boolean.TRUE.equals(group.get("default"))) continue;
            coreExclusions.add((String) group.get("tag"));
            assertTrue(XmlSupport.descendants(profile, "groups").stream().anyMatch(element -> element.getTextContent().equals(group.get("tag"))));
            for (String name : strings(group.get("tests"))) {
                String source = Files.readString(root.resolve("taxonomy-app/src/test/java/" + name.replace('.', '/') + ".java"));
                assertTrue(source.contains("@Tag(\"" + group.get("tag") + "\")"), name);
            }
        }
        assertEquals(coreExclusions, Set.of(XmlSupport.childText(XmlSupport.child(
                profile(project, "ci-app-test-shards-core"), "properties"), "excludedGroups").split(",")),
                "Core must exclude exactly the global exclusions and every separately executed shard");
        assertEquals(new HashSet<>(strings(plan.get("excludedTags"))), Set.of(XmlSupport.childText(XmlSupport.child(
                profile(project, "ci"), "properties"), "excludedGroups").split(",")),
                "Complete CI verification must retain every shard's tests");
        assertTrue(Boolean.TRUE.equals(object(object(plan.get("groups")).get("recovery")).get("requiresEmbeddingModel")));
        Element app = pom("taxonomy-app"), build = pom("taxonomy-build"), coverage = pom("taxonomy-coverage");
        assertTrue(XmlSupport.descendants(app, "execution").stream().anyMatch(element -> "stage-core-app-test-shard".equals(XmlSupport.childText(element, "id")) && "post-integration-test".equals(XmlSupport.childText(element, "phase"))));
        assertTrue(XmlSupport.descendants(build, "execution").stream().anyMatch(element -> "verify-complete-app-test-shard-inventory".equals(XmlSupport.childText(element, "id")) && "test".equals(XmlSupport.childText(element, "phase"))));
        assertTrue(XmlSupport.descendants(app, "phase").stream().anyMatch(element -> element.getTextContent().equals("${taxonomy.app.architectureAnchorPhase}")));
        assertTrue(XmlSupport.descendants(coverage, "goal").stream().anyMatch(element -> element.getTextContent().equals("merge")));
        assertTrue(XmlSupport.descendants(coverage, "dataFileInclude").stream().anyMatch(element -> element.getTextContent().equals("${taxonomy.app.shard.coverageInclude}")));
    }
    @Test void longArchitectureScenarioRunsSeparatelyFromBrowserAcceptance() throws Exception {
        Map<String, Object> groups = object(object(read(root.resolve(".mvn/verification-suites.json")).get("applicationTestShards")).get("groups"));
        var scenario = groups.entrySet().stream().filter(entry -> strings(object(entry.getValue()).get("tests"))
                .contains("com.taxonomy.ScenarioArchitectureAcceptanceTest")).findFirst().orElseThrow();
        var browser = groups.entrySet().stream().filter(entry -> strings(object(entry.getValue()).get("tests"))
                .contains("com.taxonomy.CoreUiAcceptanceIT")).findFirst().orElseThrow();
        assertNotEquals(scenario.getKey(), browser.getKey(),
                "The measured long architecture scenario must not consume the browser acceptance job's time budget");
    }
    @Test void shardAndPackagingProfilesDisableCrossProfileBuildCacheReuse() throws Exception {
        for (String module : List.of("", "taxonomy-app")) for (String id : List.of("ci-app-test-shards-core", "ci-app-test-shard-recovery", "ci-app-test-shard-browser", "ci-app-test-shard-scenario", "ci-app-test-shards-coverage", "ci-plugin-packaging")) {
            Element profile = profile(pom(module), id);
            assertEquals("false", XmlSupport.childText(XmlSupport.child(profile, "properties"), "maven.build.cache.enabled"), module + " " + id);
        }
    }
    @Test void aggregateFailsafeExecutionReceivesRequiredShardCoverageFlag() throws Exception {
        Element build = pom("taxonomy-build");
        Element plugin = XmlSupport.descendants(XmlSupport.child(build, "build"), "plugin").stream()
                .filter(element -> "maven-failsafe-plugin".equals(XmlSupport.childText(element, "artifactId")))
                .findFirst().orElseThrow();
        Element aggregate = profile(build, "ci-app-test-shards-coverage");
        Element execution = XmlSupport.descendants(aggregate, "execution").stream()
                .filter(element -> "coverage-policy-only".equals(XmlSupport.childText(element, "id")))
                .findFirst().orElseThrow();
        boolean propagated = List.of(plugin, execution).stream()
                .map(element -> XmlSupport.child(element, "configuration"))
                .filter(Objects::nonNull)
                .map(configuration -> XmlSupport.child(configuration, "systemPropertyVariables"))
                .filter(Objects::nonNull)
                .anyMatch(properties -> "${taxonomy.app.shard.coverage.enabled}".equals(
                        XmlSupport.childText(properties, "taxonomy.app.shard.coverage.enabled")));
        assertTrue(propagated, "The aggregate Failsafe execution must enforce the union of all JaCoCo probes");
        assertEquals("true", XmlSupport.childText(XmlSupport.child(
                profile(pom(""), "ci-app-test-shards-coverage"), "properties"), "taxonomy.app.shard.coverage.enabled"));
    }
    @Test void packagingRetainsRealFailsafeSelectorAndOnlySkipsAppSurefire() throws Exception {
        var catalog = read(root.resolve(".mvn/verification-suites.json")); var packaging = object(object(catalog.get("profiles")).get("ci-plugin-packaging"));
        assertEquals("plugin-packaging-tests", packaging.get("baseProfile")); assertEquals("ExternalPluginPackagedIT,OptionalFeaturePackagedIT", packaging.get("itTest"));
        assertEquals(packaging.get("itTest"), XmlSupport.childText(XmlSupport.child(profile(pom(""), "plugin-packaging-tests"), "properties"), "it.test"));
        assertTrue(XmlSupport.descendants(pom("taxonomy-app"), "plugin").stream()
                .filter(plugin -> "maven-surefire-plugin".equals(XmlSupport.childText(plugin, "artifactId")))
                .anyMatch(plugin -> "${taxonomy.app.unit.tests.skip}".equals(XmlSupport.childText(XmlSupport.child(plugin, "configuration"), "skip"))));
        String workflow = Files.readString(root.resolve(".github/workflows/ci-cd.yml")); assertTrue(workflow.contains("verify -Pplugin-packaging-tests,ci-plugin-packaging"));
        assertFalse(workflow.lines().anyMatch(line -> line.contains("plugin-packaging-tests") && line.contains("-DskipTests")));
    }
    @Test void producersStayIndependentAndAggregateRequiresBoth() throws Exception {
        String workflow = Files.readString(root.resolve(".github/workflows/ci-cd.yml"));
        Map<String, Set<String>> jobs = new LinkedHashMap<>(); String job = null; boolean needs = false;
        for (String line : workflow.split("\n")) {
            if (line.matches("  [a-z][a-z0-9-]*:")) { job = line.strip().replace(":", ""); jobs.put(job, new HashSet<>()); needs = false; }
            else if (job != null && line.startsWith("    needs:")) {
                needs = true; String value = line.substring("    needs:".length()).strip();
                if (value.startsWith("[")) for (String dependency : value.replace("[", "").replace("]", "").split(",")) jobs.get(job).add(dependency.strip());
            } else if (job != null && needs && line.matches("      - [a-z][a-z0-9-]*")) jobs.get(job).add(line.substring(8));
            else if (!line.startsWith("      ")) needs = false;
        }
        assertTrue(jobs.containsKey("core")); assertTrue(jobs.containsKey("app-test-shards"));
        assertFalse(depends(jobs, "core", "app-test-shards", new HashSet<>()));
        assertTrue(depends(jobs, "coverage-aggregate", "core", new HashSet<>())); assertTrue(depends(jobs, "coverage-aggregate", "app-test-shards", new HashSet<>())); assertTrue(depends(jobs, "verify", "coverage-aggregate", new HashSet<>()));
        String core = workflow.substring(workflow.indexOf("\n  core:\n"), workflow.indexOf("\n  coverage-aggregate:\n"));
        assertFalse(core.substring(0, core.indexOf("Run the core reactor test suite")).contains("download-artifact"));
        assertTrue(workflow.contains("requiresModel: (.value.requiresEmbeddingModel == true)")); assertTrue(workflow.contains("if: matrix.requiresModel == true"));
    }
    boolean depends(Map<String, Set<String>> jobs, String job, String prerequisite, Set<String> seen) { return seen.add(job) && jobs.getOrDefault(job, Set.of()).stream().anyMatch(dependency -> prerequisite.equals(dependency) || depends(jobs, dependency, prerequisite, seen)); }
}
