package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
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
    private static final String ANCHOR_PHASE_PROPERTY = "taxonomy.app.architectureAnchorPhase";
    private static final String ANCHOR_PHASE_EXPRESSION = "${" + ANCHOR_PHASE_PROPERTY + "}";
    private static final String CI_ANCHOR_EXECUTION = "/*[local-name()='project']/*[local-name()='profiles']"
            + "/*[local-name()='profile'][*[local-name()='id']='ci']"
            + "/*[local-name()='build']/*[local-name()='plugins']/*[local-name()='plugin']"
            + "[*[local-name()='artifactId']='maven-surefire-plugin']"
            + "/*[local-name()='executions']/*[local-name()='execution']"
            + "[*[local-name()='id']='architecture-module-gate-ci-anchor']";

    @Test
    void canonicalVerificationAlsoChecksModuleGateOwnerAndSelectors() throws Exception {
        new ArchitectureExceptionLedgerTest().moduleChecksRemainReachableFromTheArchitectureProfile();
    }

    @Test
    void canonicalCiProfileCannotDropItsSelectorIndependentAnchor() throws Exception {
        assertCanonicalCiAnchor(findRepositoryRoot());
    }

    @ParameterizedTest(name = "{0} {1} cannot disable the canonical anchor")
    @CsvSource({
            "pom.xml, default", "pom.xml, ci", "pom.xml, ci-app-test-shards-core",
            "taxonomy-app/pom.xml, default", "taxonomy-app/pom.xml, ci",
            "taxonomy-app/pom.xml, ci-app-test-shards-core"
    })
    void canonicalPhasePropertyCannotBeDisabledByDefaultsOrActiveProfiles(
            String pom, String profile, @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        copyCanonicalPomFixture(fixture);
        setAnchorPhase(fixture, ANCHOR_PHASE_EXPRESSION);
        setPhaseProperty(fixture.resolve("pom.xml"), "default", "test");
        setPhaseProperty(fixture.resolve(pom), profile, "test");
        assertCanonicalCiAnchor(fixture);

        setPhaseProperty(fixture.resolve(pom), profile, "none");
        assertThatThrownBy(() -> assertCanonicalCiAnchor(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ANCHOR_PHASE_PROPERTY);
    }

    @Test
    void propertyBasedAnchorRequiresAnExplicitTestDefault(
            @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        copyCanonicalPomFixture(fixture);
        setAnchorPhase(fixture, ANCHOR_PHASE_EXPRESSION);
        setPhaseProperty(fixture.resolve("pom.xml"), "default", null);

        assertThatThrownBy(() -> assertCanonicalCiAnchor(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ANCHOR_PHASE_PROPERTY);
    }

    @Test
    void literalTestPhaseNeedsNoPropertyDefault(
            @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        copyCanonicalPomFixture(fixture);
        setAnchorPhase(fixture, "test");
        setPhaseProperty(fixture.resolve("pom.xml"), "default", null);

        assertCanonicalCiAnchor(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "verify", "${unrecognized.anchor.phase}"})
    void canonicalAnchorRejectsOtherPhasesAndUnknownProperties(
            String phase, @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        copyCanonicalPomFixture(fixture);
        setAnchorPhase(fixture, phase);

        assertThatThrownBy(() -> assertCanonicalCiAnchor(fixture))
                .isInstanceOf(AssertionError.class);
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

    @Test
    void canonicalCiAnchorDefaultPomCannotEscapeTheCheckoutThroughASymlink(
            @org.junit.jupiter.api.io.TempDir Path fixture) throws Exception {
        Path checkout = fixture.resolve("checkout");
        copyCanonicalPomFixture(checkout);
        setAnchorPhase(checkout, ANCHOR_PHASE_EXPRESSION);
        Path externalPom = fixture.resolve("outside-pom.xml");
        Files.writeString(externalPom, "<project/>");
        Files.delete(checkout.resolve("pom.xml"));
        Files.createSymbolicLink(checkout.resolve("pom.xml"), externalPom);

        assertThatThrownBy(() -> assertCanonicalCiAnchor(checkout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside checkout");
    }

    private static void assertCanonicalCiAnchor(Path root) throws Exception {
        Path checkout = root.toRealPath();
        Document pom = parse(checkedFile(checkout, "taxonomy-app/pom.xml"));
        String execution = CI_ANCHOR_EXECUTION;

        var phases = values(pom, execution + "/*[local-name()='phase']");
        assertThat(phases).singleElement().isIn("test", ANCHOR_PHASE_EXPRESSION);
        if (ANCHOR_PHASE_EXPRESSION.equals(phases.getFirst())) {
            Document reactor = parse(checkedFile(checkout, "pom.xml"));
            String property = "/*[local-name()='properties']/*[local-name()='" + ANCHOR_PHASE_PROPERTY + "']";
            assertThat(values(reactor, "/*[local-name()='project']" + property))
                    .as("root default for %s", ANCHOR_PHASE_PROPERTY)
                    .containsExactly("test");
            for (Document model : java.util.List.of(reactor, pom)) {
                assertTestPhaseOverride(model, "default", property);
                assertTestPhaseOverride(model, "ci", property);
                assertTestPhaseOverride(model, "ci-app-test-shards-core", property);
            }
        }
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

    private static void assertTestPhaseOverride(Document model, String profile, String property)
            throws Exception {
        var overrides = values(model, propertyOwner(profile) + property);
        if (!overrides.isEmpty()) {
            assertThat(overrides).as("%s must retain %s=test", profile, ANCHOR_PHASE_PROPERTY)
                    .containsExactly("test");
        }
    }

    private static String propertyOwner(String profile) {
        return "/*[local-name()='project']" + (profile.equals("default") ? ""
                : "/*[local-name()='profiles']/*[local-name()='profile'][*[local-name()='id']='" + profile + "']");
    }

    private static void copyCanonicalPomFixture(Path fixture) throws Exception {
        Path root = findRepositoryRoot().toRealPath();
        Files.createDirectories(fixture.resolve("taxonomy-app"));
        Files.copy(checkedFile(root, "pom.xml"), fixture.resolve("pom.xml"));
        Files.copy(checkedFile(root, "taxonomy-app/pom.xml"), fixture.resolve("taxonomy-app/pom.xml"));
    }

    private static void setPhaseProperty(Path file, String profile, String value) throws Exception {
        Document model = parse(file);
        var owner = (Element) XPathFactory.newInstance().newXPath()
                .evaluate(propertyOwner(profile), model, XPathConstants.NODE);
        assertThat(owner).as("property owner %s in %s", profile, file).isNotNull();
        Element property = child(child(owner, "properties"), ANCHOR_PHASE_PROPERTY);
        if (value == null) property.getParentNode().removeChild(property);
        else property.setTextContent(value);
        writeFixturePom(model, file);
    }

    private static Element child(Element parent, String name) throws Exception {
        var existing = (Element) XPathFactory.newInstance().newXPath()
                .evaluate("./*[local-name()='" + name + "']", parent, XPathConstants.NODE);
        if (existing != null) return existing;
        var created = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
        parent.appendChild(created);
        return created;
    }

    private static void setAnchorPhase(Path fixture, String phase) throws Exception {
        Path file = fixture.resolve("taxonomy-app/pom.xml");
        Document model = parse(file);
        var element = (Element) XPathFactory.newInstance().newXPath()
                .evaluate(CI_ANCHOR_EXECUTION + "/*[local-name()='phase']", model, XPathConstants.NODE);
        assertThat(element).isNotNull();
        element.setTextContent(phase);
        writeFixturePom(model, file);
    }

    private static void writeFixturePom(Document model, Path file) throws Exception {
        var factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        factory.newTransformer().transform(new DOMSource(model), new StreamResult(file.toFile()));
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
