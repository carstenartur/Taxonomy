package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueOverlayProposalAtomicWriteTest {

    @Test
    void supportsAOneCharacterOutputFilename(@TempDir Path root) throws Exception {
        Path target = root.resolve("a");
        Method atomicWrite = CatalogueOverlayProposalGenerator.class
                .getDeclaredMethod("atomicWrite", Path.class, String.class);
        atomicWrite.setAccessible(true);

        atomicWrite.invoke(null, target, "proposal\n");

        assertThat(target).isRegularFile();
        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .isEqualTo("proposal\n");
    }
}
