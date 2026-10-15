package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the configuration behind the real security-packaging skip regression. */
class ApplicationTestSkipContractTest {
    private static final String SUREFIRE = "plugin[artifactId='maven-surefire-plugin']";
    private static final String FAILSAFE = "plugin[artifactId='maven-failsafe-plugin']";

    @Test
    void ordinaryPackagingRetainsNativeSurefireSkipFlags() throws Exception {
        Document app = pom("taxonomy-app/pom.xml");
        String base = "/project/build/plugins/" + SUREFIRE + "/configuration/";
        // A literal/custom false bound to skip overrides Maven's maven.test.skip
        // even though the compiler has correctly omitted test compilation.
        assertThat(value(app, "count(" + base + "skip)")).isEqualTo("0");
        assertThat(value(app, "count(" + base + "skipTests)")).isEqualTo("0");
    }

    @Test
    void onlyTheOptInPluginPackagingProfileSpecializesAppTestSkipping() throws Exception {
        Document app = pom("taxonomy-app/pom.xml");
        Document root = pom("pom.xml");
        String profile = "/project/profiles/profile[id='ci-plugin-packaging']";
        assertThat(value(app, profile + "/build/plugins/" + SUREFIRE + "/configuration/skip"))
                .isEqualTo("${taxonomy.app.unit.tests.skip}");
        assertThat(value(app, "count(" + profile + "/activation)")).isEqualTo("0");
        assertThat(value(root, profile + "/properties/taxonomy.app.unit.tests.skip"))
                .isEqualTo("true");
        // The additional packaging lane still runs its real Failsafe integration tests.
        assertThat(value(app, "count(" + profile + "/build/plugins/" + FAILSAFE + "/configuration/*[starts-with(name(), 'skip')])"))
                .isEqualTo("0");
        assertThat(value(root, "/project/profiles/profile[id='plugin-packaging-tests']/properties/skipITs"))
                .isEqualTo("false");
    }

    @Test
    void theCoverageJoinRetainsItsInheritedTestSkipWithoutChangingTheFullBuild() throws Exception {
        Document app = pom("taxonomy-app/pom.xml");
        Document root = pom("pom.xml");
        String profile = "/project/profiles/profile[id='ci-app-test-shards-coverage']";
        assertThat(value(root, profile + "/build/pluginManagement/plugins/" + SUREFIRE + "/configuration/skip"))
                .isEqualTo("true");
        assertThat(value(app, "count(" + profile + "/build/plugins/" + SUREFIRE + "/configuration/skip)")).isEqualTo("0");
        assertThat(value(root, "/project/properties/taxonomy.app.unit.tests.skip")).isEqualTo("false");
    }

    @Test
    void databaseCiCompanionSkipsOnlyAppSurefireAndPartialAggregateCoverage() throws Exception {
        Document app = pom("taxonomy-app/pom.xml");
        Document root = pom("pom.xml");
        String profile = "/project/profiles/profile[id='ci-database-compatibility']";
        assertThat(value(app, profile + "/build/plugins/" + SUREFIRE + "/configuration/skip"))
                .isEqualTo("${taxonomy.app.unit.tests.skip}");
        assertThat(value(root, profile + "/properties/taxonomy.app.unit.tests.skip")).isEqualTo("true");
        assertThat(value(root, profile + "/properties/taxonomy.app.shard.coverageAggregateSkip")).isEqualTo("true");
        for (Document document : new Document[]{root, app}) {
            assertThat(value(document, "count(" + profile + "/activation)")).isEqualTo("0");
            assertThat(value(document, profile + "/properties/maven.build.cache.enabled")).isEqualTo("false");
            assertThat(value(document, "count(" + profile + "/properties/*[name()='skipTests' or name()='maven.test.skip' or name()='skipITs' or name()='it.test' or name()='taxonomy.quality.skip'])"))
                    .isEqualTo("0");
            assertThat(value(document, "count(" + profile + "/build//" + FAILSAFE + ")")).isEqualTo("0");
        }
        assertThat(value(root, "count(" + profile + "/build//" + SUREFIRE + ")")).isEqualTo("0");
        Document coverage = pom("taxonomy-coverage/pom.xml");
        assertThat(value(coverage, "/project/build/plugins/plugin[artifactId='jacoco-maven-plugin']/executions/execution[id='reactor-report']/configuration/skip"))
                .isEqualTo("${taxonomy.app.shard.coverageAggregateSkip}");
        assertThat(value(root, "/project/properties/taxonomy.app.shard.coverageAggregateSkip")).isEqualTo("false");
        assertThat(value(root, "/project/properties/taxonomy.quality.skip")).isEqualTo("true");
    }

    @Test
    void standaloneDatabaseProfilesRetainTheirCompleteLifecycleAndFailsafeSelection() throws Exception {
        Document root = pom("pom.xml");
        Document app = pom("taxonomy-app/pom.xml");
        String[] databases = {"postgres", "mssql", "oracle"};
        String[] selectors = {"*Postgres*IT", "*Mssql*IT", "*Oracle*IT"};
        String workflow = Files.readString(repositoryRoot().resolve(".github/workflows/database-compatibility.yml"));
        for (int i = 0; i < databases.length; i++) {
            String id = "database-" + databases[i];
            String profile = "/project/profiles/profile[id='" + id + "']";
            assertThat(value(root, profile + "/properties/skipITs")).isEqualTo("false");
            assertThat(value(root, profile + "/properties/it.test")).isEqualTo(selectors[i]);
            assertThat(value(root, profile + "/properties/excludedGroups")).isEqualTo("real-llm,onnx");
            assertThat(value(root, profile + "/properties/taxonomy.model.download.skip")).isEqualTo("true");
            assertThat(value(root, "count(" + profile + "/properties/*)")).isEqualTo("4");
            assertThat(value(root, "count(" + profile + "/build)")).isEqualTo("0");
            assertThat(value(app, "count(" + profile + ")")).isEqualTo("0");
            assertThat(workflow).contains("command: ./mvnw -B verify -P" + id + ",ci-database-compatibility");
        }
        // The App owner must keep Failsafe's fail-closed default for explicit selectors.
        assertThat(value(app, "count(/project/build/plugins/" + FAILSAFE
                + "/configuration/*[name()='failIfNoSpecifiedTests' or starts-with(name(), 'skip')])")).isEqualTo("0");
        Document build = pom("taxonomy-build/pom.xml");
        assertThat(value(build, "count(/project/build/plugins/" + SUREFIRE
                + "/executions/execution[starts-with(id, 'required-') and phase='test' and configuration/failIfNoTests='true' and configuration/failIfNoSpecifiedTests='true'])"))
                .isEqualTo("4");
        // No companion property turns the build-policy integration lane into a coverage claim.
        assertThat(value(build, "/project/build/plugins/" + FAILSAFE
                + "/executions/execution/configuration/skipITs")).isEqualTo("${taxonomy.quality.skip}");
    }

    @Test
    void onnxCiCompanionSelectsReferenceUnitTestsWithoutSkippingFailsafeOrReactorGuards() throws Exception {
        Document root = pom("pom.xml");
        Document app = pom("taxonomy-app/pom.xml");
        String profile = "/project/profiles/profile[id='ci-onnx-reference']";
        String execution = profile + "/build/plugins/" + SUREFIRE + "/executions/execution[id='default-test']";
        String includes = execution + "/configuration/includes/include";
        assertThat(value(app, "count(" + includes + ")")).isEqualTo("3");
        assertThat(value(app, includes + "[1]")).isEqualTo("**/*Onnx*Test.java");
        assertThat(value(app, includes + "[2]")).isEqualTo("**/*Onnx*Tests.java");
        assertThat(value(app, includes + "[3]")).isEqualTo("**/WorkerRuntimeFootprintTest.java");
        assertThat(value(app, execution + "/configuration/failIfNoTests")).isEqualTo("true");
        assertThat(value(root, profile + "/properties/taxonomy.productDemo.testPhase")).isEqualTo("none");
        assertThat(value(root, profile + "/properties/taxonomy.app.shard.coverageAggregateSkip")).isEqualTo("true");
        for (Document document : new Document[]{root, app}) {
            assertThat(value(document, "count(" + profile + "/activation)")).isEqualTo("0");
            assertThat(value(document, profile + "/properties/maven.build.cache.enabled")).isEqualTo("false");
            assertThat(value(document, "count(" + profile + "/properties/*[name()='groups' or name()='excludedGroups' or name()='skipTests' or name()='maven.test.skip' or name()='skipITs' or name()='it.test' or name()='taxonomy.quality.skip' or name()='taxonomy.app.unit.tests.skip'])"))
                    .isEqualTo("0");
            assertThat(value(document, "count(" + profile + "/build//" + FAILSAFE + ")")).isEqualTo("0");
        }
        assertThat(value(root, "count(" + profile + "/build)")).isEqualTo("0");
        assertThat(value(app, "count(" + execution + "/configuration/*[starts-with(name(), 'skip') or name()='test' or name()='groups' or name()='excludedGroups'])"))
                .isEqualTo("0");
        String workflow = Files.readString(repositoryRoot().resolve(".github/workflows/local-onnx-reference.yml"));
        assertThat(workflow).contains("./mvnw -B verify -Ponnx,ci-onnx-reference -T 2");
    }

    @Test
    void standaloneOnnxAndCanonicalCiRetainTheirApplicationTestScope() throws Exception {
        Document root = pom("pom.xml");
        Document app = pom("taxonomy-app/pom.xml");
        String profile = "/project/profiles/profile[id='onnx']";
        assertThat(value(root, profile + "/properties/skipITs")).isEqualTo("false");
        assertThat(value(root, profile + "/properties/it.test")).isEqualTo("LocalOnnxPipelineIT,OnnxSeleniumIT");
        assertThat(value(root, profile + "/properties/runOnnxTests")).isEqualTo("true");
        assertThat(value(root, profile + "/properties/excludedGroups")).isEqualTo("real-llm,db-postgres,db-mssql,db-oracle");
        assertThat(value(root, profile + "/properties/taxonomy.model.download.skip")).isEqualTo("false");
        assertThat(value(root, "count(" + profile + "/properties/*)")).isEqualTo("5");
        assertThat(value(root, "/project/properties/taxonomy.productDemo.testPhase")).isEqualTo("test");
        for (String id : new String[]{"onnx", "ci"}) {
            String scope = "/project/profiles/profile[id='" + id + "']";
            assertThat(value(root, "count(" + scope + "/properties/taxonomy.productDemo.testPhase)")).isEqualTo("0");
            assertThat(value(app, "count(" + scope + "/build/plugins/" + SUREFIRE + "/configuration/includes)")).isEqualTo("0");
            assertThat(value(app, "count(" + scope + "/build/plugins/" + SUREFIRE + "/executions/execution[id='default-test'])")).isEqualTo("0");
        }
    }

    private static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document).trim();
    }

    private static Document pom(String relative) throws Exception {
        Path root = repositoryRoot();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = Files.newInputStream(root.resolve(relative))) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("taxonomy-app/pom.xml"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Cannot locate Taxonomy repository");
        return root;
    }
}
