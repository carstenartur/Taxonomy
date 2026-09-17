package com.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import tools.jackson.databind.ObjectMapper;

class ArchitectureModuleGateReachabilityRegressionTest {

    private static final String SUPPORT_REGRESSION = "ArchitectureSupportOwnershipRegressionTest";

    @Test
    void supportOwnershipRegressionRemainsInFocusedArchitectureSelectors() throws Exception {
        Path root = findRepositoryRoot();
        Document reactor = parse(root.resolve("pom.xml"));
        String query = "/*[local-name()='project']/*[local-name()='profiles']"
                + "/*[local-name()='profile'][*[local-name()='id']='architecture-tests']"
                + "/*[local-name()='properties']/*[local-name()='test']";
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(query, reactor, XPathConstants.NODESET);
        assertThat(nodes.getLength()).as("one architecture-tests Maven selector").isEqualTo(1);
        List<String> maven = selectors(nodes.item(0).getTextContent());
        String recorded = new ObjectMapper().readTree(Files.readString(root.resolve(".mvn/verification-suites.json")))
                .path("profiles").path("architecture-tests").path("test").asText();

        assertThat(maven).as("focused Maven architecture selector")
                .contains(SUPPORT_REGRESSION).doesNotHaveDuplicates();
        assertThat(selectors(recorded)).as("verification-catalogue architecture selector")
                .contains(SUPPORT_REGRESSION).doesNotHaveDuplicates();
    }

    @Test
    void selectorAnchorRunsOutsideArchitectureProfile() throws Exception {
        Document app = parse(findRepositoryRoot().resolve("taxonomy-app/pom.xml"));
        String execution = "/*[local-name()='project']/*[local-name()='build']/*[local-name()='plugins']"
                + "/*[local-name()='plugin'][*[local-name()='groupId']='org.apache.maven.plugins']"
                + "[*[local-name()='artifactId']='maven-surefire-plugin']"
                + "/*[local-name()='executions']/*[local-name()='execution']"
                + "[*[local-name()='id']='architecture-selector-anchor']";
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(execution, app, XPathConstants.NODESET);

        assertThat(nodes.getLength())
                .as("architecture selector/owner anchor must run in ordinary test and canonical -Pci verification")
                .isEqualTo(1);
    }

    private static List<String> selectors(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::strip).filter(entry -> !entry.isEmpty()).toList();
    }

    private static Document parse(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (var input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/architecture-contexts.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }
}
