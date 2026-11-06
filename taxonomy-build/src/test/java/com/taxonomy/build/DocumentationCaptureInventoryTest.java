package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentationCaptureInventoryTest {
    @TempDir Path root;
    private final Instant startedAt = Instant.now().minusSeconds(2);

    @Test void failsWhenAnyDocumentedCaptureIsMissing() throws Exception {
        guide("![UI](../images/current.png)\n![Other](../images/missing.png)");
        png("current.png");
        assertThatThrownBy(() -> verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing.png");
    }

    @Test void retainedImagesCannotSatisfyCurrentRunCompleteness() throws Exception {
        guide("![UI](../images/retained.png)");
        Path image = png("retained.png");
        Files.setLastModifiedTime(image, FileTime.from(startedAt.minusSeconds(10)));
        assertThatThrownBy(() -> verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("retained.png").hasMessageContaining("current run");
    }

    @Test void rejectsTextFilesWithPngExtension() throws Exception {
        guide("<img src='../images/invalid.png' alt='UI'>");
        Files.createDirectories(root.resolve("output"));
        Files.writeString(root.resolve("output/invalid.png"), "not an actual PNG");
        assertThatThrownBy(() -> verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("invalid.png").hasMessageContaining("PNG");
    }

    @Test void checksBothLanguagesAndDeduplicatesLinkedImages() throws Exception {
        guide("[![UI](../images/current.png)](../images/current.png)");
        Files.createDirectories(root.resolve("docs/de"));
        Files.writeString(root.resolve("docs/de/USER_GUIDE.md"), "![Ansicht](../images/german.png)");
        png("current.png");
        assertThatThrownBy(() -> verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("german.png");
        png("german.png");
        assertThat(verify()).extracting(DocumentationCaptureInventory.Capture::file)
                .containsExactly("current.png", "german.png");
        assertThat(verify()).allSatisfy(capture -> {
            assertThat(capture.width()).isEqualTo(100);
            assertThat(capture.height()).isEqualTo(80);
            assertThat(capture.sha256()).hasSize(64);
        });
    }

    private java.util.List<DocumentationCaptureInventory.Capture> verify() throws Exception {
        return DocumentationCaptureInventory.verify(root, root.resolve("output"), startedAt);
    }

    private void guide(String markdown) throws Exception {
        Files.createDirectories(root.resolve("docs/en"));
        Files.writeString(root.resolve("docs/en/USER_GUIDE.md"), markdown);
    }

    private Path png(String name) throws Exception {
        Path target = root.resolve("output").resolve(name);
        Files.createDirectories(target.getParent());
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", target.toFile());
        return target;
    }
}
