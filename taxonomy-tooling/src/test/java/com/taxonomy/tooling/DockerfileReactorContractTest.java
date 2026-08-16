package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileReactorContractTest {

    @Test
    void dockerBuildContextContainsEveryReactorDescriptorAndProductiveSourceTree()
            throws Exception {
        Path root = findRepositoryRoot();
        String dockerfile = Files.readString(
                root.resolve("Dockerfile"), StandardCharsets.UTF_8);
        List<String> modules = readReactorModules(root.resolve("pom.xml"));

        assertThat(modules).isNotEmpty();
        for (String module : modules) {
            assertThat(dockerfile)
                    .as("Dockerfile must copy the Maven descriptor for reactor module %s", module)
                    .contains("COPY " + module + "/pom.xml " + module + "/pom.xml");

            if (Files.isDirectory(root.resolve(module).resolve("src/main"))) {
                assertThat(dockerfile)
                        .as("Dockerfile must copy productive sources for reactor module %s", module)
                        .contains("COPY " + module + "/src " + module + "/src");
            }
        }
    }

    private static List<String> readReactorModules(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        NodeList moduleNodes = document.getElementsByTagName("module");
        List<String> modules = new ArrayList<>(moduleNodes.getLength());
        for (int index = 0; index < moduleNodes.getLength(); index++) {
            String module = moduleNodes.item(index).getTextContent().strip();
            if (!module.isEmpty()) {
                modules.add(module);
            }
        }
        return List.copyOf(modules);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("Dockerfile"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
