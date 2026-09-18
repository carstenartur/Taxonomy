package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keeps the module-gate owner/selector contract active in the ordinary application
 * test suite used by canonical {@code -Pci} verification. The focused
 * {@code architecture-tests} profile has its separate fixed selector anchor.
 */
class ArchitectureModuleGateReachabilityRegressionTest {

    @Test
    void canonicalVerificationAlsoChecksModuleGateOwnerAndSelectors() throws Exception {
        new ArchitectureExceptionLedgerTest().moduleChecksRemainReachableFromTheArchitectureProfile();
    }

    @Test
    void canonicalCiProfileCannotDropItsSelectorIndependentAnchor() throws Exception {
        assertCanonicalCiAnchor(findRepositoryRoot());
    }

    @Test
    void canonicalCiAnchorPomCannotEscapeTheCheckoutThroughASymlink(
            @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        Path checkout = fixture.resolve("checkout");
        Path outside = fixture.resolve("outside");
        Files.createDirectories(checkout.resolve("taxonomy-app"));
        Files.createDirectories(outside);
        Path externalPom = outside.resolve("pom.xml");
        Files.writeString(externalPom, "<project/>");
        Files.createSymbolicLink(
                checkout.resolve("taxonomy-app/pom.xml"),
                externalPom);

        assertThatThrownBy(() -> assertCanonicalCiAnchor(checkout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside checkout");
    }

    private static void assertCanonicalCiAnchor(Path root) throws Exception {
        Path checkout = root.toRealPath();
        Document pom = parse(checkedFile(checkout, "taxonomy-app/pom.xml"));
        String execution = "/*[local-name()='project']/*[local-name()='profiles']"
                + "/*[local-name()='profile'][*[local-name()='id']='ci']"
                + "/*[local-name()='build']/*[local-name()='plugins']/*[local-name()='plugin']"
                + "[*[local-name()='artifactId']='maven-surefire-plugin']"
                + "/*[local-name()='executions']/*[local-name()='execution']"
                + "[*[local-name()='id']='architecture-module-gate-ci-anchor']";

        assertThat(values(pom, execution + "/*[local-name()='phase']"))
                .containsExactly("test");
        assertThat(values(pom, execution + "/*[local-name()='goals']/*[local-name()='goal']"))
                .containsExactly("test");
        assertThat(values(pom, execution + "/*[local-name()='configuration']/*[local-name()='test']"))
                .containsExactly(
                        "ArchitectureExceptionLedgerTest#moduleChecksRemainReachableFromTheArchitectureProfile");
        assertThat(values(pom, execution
                + "/*[local-name()='configuration']/*[local-name()='failIfNoTests']"))
                .containsExactly("true");
        assertThat(values(pom, execution
                + "/*[local-name()='configuration']/*[local-name()='failIfNoSpecifiedTests']"))
                .containsExactly("true");
    }

    private static Path checkedFile(Path checkout, String relative) throws Exception {
        Path file = checkout.resolve(relative).toRealPath();
        if (!file.startsWith(checkout) || !Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "Architecture CI anchor outside checkout: " + relative);
        }
        return file;
    }

    private static Document parse(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (var input = Files.newInputStream(pom)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static java.util.List<String> values(Document document, String expression)
            throws Exception {
        var nodes = (org.w3c.dom.NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(expression, document, XPathConstants.NODESET);
        var values = new java.util.ArrayList<String>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            values.add(nodes.item(index).getTextContent().strip());
        }
        return values;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
