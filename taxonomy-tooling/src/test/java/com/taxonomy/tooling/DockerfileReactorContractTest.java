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
import java.util.regex.Pattern;

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
            assertThat(containsCopyInstruction(
                    dockerfile,
                    module + "/pom.xml",
                    module + "/pom.xml"))
                    .as("Dockerfile must copy the Maven descriptor for reactor module %s", module)
                    .isTrue();

            if (Files.isDirectory(root.resolve(module).resolve("src/main"))) {
                assertThat(containsCopyInstruction(
                        dockerfile,
                        module + "/src",
                        module + "/src"))
                        .as("Dockerfile must copy productive sources for reactor module %s", module)
                        .isTrue();
            }
        }
    }

    @Test
    void copyInstructionMatchingAcceptsFlagsFlexibleWhitespaceAndJsonForm() {
        String dockerfile = """
                # A commented instruction must not satisfy the contract.
                # COPY missing/pom.xml missing/pom.xml
                COPY --link --chown=10001:0   module/pom.xml\tmodule/pom.xml
                copy --chmod=0644 ["module/src", "module/src"]
                """;

        assertThat(containsCopyInstruction(
                dockerfile, "module/pom.xml", "module/pom.xml"))
                .isTrue();
        assertThat(containsCopyInstruction(
                dockerfile, "module/src", "module/src"))
                .isTrue();
        assertThat(containsCopyInstruction(
                dockerfile, "missing/pom.xml", "missing/pom.xml"))
                .isFalse();
    }

    @Test
    void pullRequestCiBuildsChangedProductionImagesBeforeCanonicalVerification()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = Files.readString(
                root.resolve(".github/workflows/ci-cd.yml"),
                StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("- name: Detect production-image changes")
                .contains("id: production-image-scope")
                .contains("Dockerfile \\")
                .contains("'taxonomy-*/pom.xml' \\")
                .contains("- name: Verify production Docker image build")
                .contains("if: steps.production-image-scope.outputs.run == 'true'")
                .contains("docker build \\")
                .contains("--build-arg VCS_REF=\"$GITHUB_SHA\" \\")
                .contains("org.opencontainers.image.revision")
                .contains("10001:10001");

        int imageBuild = workflow.indexOf(
                "- name: Verify production Docker image build");
        int canonicalVerification = workflow.indexOf(
                "- name: Run the canonical Maven verification suite");
        assertThat(imageBuild).isGreaterThanOrEqualTo(0);
        assertThat(canonicalVerification).isGreaterThan(imageBuild);
    }

    private static boolean containsCopyInstruction(
            String dockerfile, String source, String destination) {
        String optionalFlags = "(?:\\s+--\\S+)*";
        String shellForm = "\\s+" + Pattern.quote(source)
                + "\\s+" + Pattern.quote(destination);
        String jsonForm = "\\s*\\[\\s*\"" + Pattern.quote(source)
                + "\"\\s*,\\s*\"" + Pattern.quote(destination)
                + "\"\\s*\\]";
        Pattern copyInstruction = Pattern.compile(
                "^\\s*COPY" + optionalFlags
                        + "(?:" + shellForm + "|" + jsonForm + ")"
                        + "\\s*(?:#.*)?$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        return copyInstruction.matcher(dockerfile).find();
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
