package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationLinkPolicyTest {

    private final DocumentationLinkPolicy policy = new DocumentationLinkPolicy();

    @Test
    void repositoryDocumentationHasNoBrokenLocalLinks() throws Exception {
        DocumentationLinkPolicy.Inspection inspection =
                policy.inspect(findRepositoryRoot());

        assertThat(inspection.markdownFiles()).isNotEmpty();
        assertThat(inspection.errors())
                .as("repository-local documentation links and images")
                .isEmpty();
    }

    @Test
    void reportsBrokenAndEscapingRepositoryTargets(@TempDir Path root)
            throws Exception {
        Path guide = write(root, "docs/guide.md", """
                [Missing](missing.md)
                [Escape](../../outside.md)
                """);

        DocumentationLinkPolicy.Inspection inspection =
                policy.inspect(root, List.of("docs"));

        assertThat(inspection.markdownFiles()).containsExactly(guide);
        assertThat(inspection.errors()).containsExactly(
                "docs/guide.md:1: missing local target: missing.md",
                "docs/guide.md:2: target escapes repository: ../../outside.md");
    }

    @Test
    void supportsInlineImagesHtmlReferencesEncodingAndAbsoluteDocumentationPaths(
            @TempDir Path root) throws Exception {
        write(root, "docs/target.md", "# Target\n");
        write(root, "docs/root.md", "# Root\n");
        write(root, "docs/space file.md", "# Encoded\n");
        write(root, "docs/a&b.md", "# Entity\n");
        write(root, "assets/image.png", "image\n");
        Path guide = write(root, "docs/guide.md", """
                [Inline](target.md#section)
                ![Image](../assets/image.png "Rendered image")
                <a href="target.md?view=full">HTML link</a>
                <img src="../assets/image.png">
                [reference]: <target.md>
                [Absolute](/docs/root.md)
                [Encoded](space%20file.md)
                [Entity](a&amp;b.md)
                [External](https://example.invalid/missing.md)
                [Mail](mailto:owner@example.invalid)
                [Anchor](#local-section)
                [Runtime](/swagger-ui.html)
                """);

        DocumentationLinkPolicy.Inspection inspection =
                policy.inspect(root, List.of("docs"));

        assertThat(inspection.markdownFiles())
                .contains(guide, root.resolve("docs/target.md"));
        assertThat(inspection.errors()).isEmpty();
        assertThat(DocumentationLinkPolicy.collectTargets(
                "![A](a.png) <a href='b.md'>B</a>"))
                .containsExactly("a.png", "b.md");
        assertThat(DocumentationLinkPolicy.collectTargets("[c]: c.md"))
                .containsExactly("c.md");
    }

    @Test
    void prunesEveryGeneratedOrThirdPartyDirectory(@TempDir Path root)
            throws Exception {
        for (String directory : DocumentationLinkPolicy.IGNORED_DIRECTORY_NAMES) {
            write(root, "docs/" + directory + "/README.md",
                    "[Missing](missing.md)\n");
        }
        Path owned = write(root, "docs/owned.md", "[Target](owned.txt)\n");
        write(root, "docs/owned.txt", "ok\n");

        DocumentationLinkPolicy.Inspection inspection =
                policy.inspect(root, List.of("docs"));

        assertThat(inspection.markdownFiles()).containsExactly(owned);
        assertThat(inspection.errors()).isEmpty();
    }

    @Test
    void reportsMarkdownThatCannotBeDecodedAsUtf8(@TempDir Path root)
            throws Exception {
        Path invalid = root.resolve("docs/invalid.md");
        Files.createDirectories(invalid.getParent());
        Files.write(invalid, new byte[] {(byte) 0xC3, 0x28});

        DocumentationLinkPolicy.Inspection inspection =
                policy.inspect(root, List.of("docs"));

        assertThat(inspection.markdownFiles()).containsExactly(invalid);
        assertThat(inspection.errors())
                .singleElement()
                .asString()
                .startsWith("docs/invalid.md: cannot read as UTF-8:");
    }

    @Test
    void normalizationPreservesLiteralPlusAndToleratesMalformedPercentEncoding() {
        assertThat(DocumentationLinkPolicy.normalizeTarget("<a+b.md?x=1#section>"))
                .isEqualTo("a+b.md");
        assertThat(DocumentationLinkPolicy.normalizeTarget("bad%target.md"))
                .isEqualTo("bad%target.md");
    }

    private static Path write(Path root, String relativePath, String content)
            throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path.toAbsolutePath().normalize();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".mvn/verification-suites.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
