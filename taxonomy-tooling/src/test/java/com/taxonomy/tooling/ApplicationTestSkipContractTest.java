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

    private static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document).trim();
    }

    private static Document pom(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("taxonomy-app/pom.xml"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Cannot locate Taxonomy repository");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = Files.newInputStream(root.resolve(relative))) {
            return factory.newDocumentBuilder().parse(input);
        }
    }
}
