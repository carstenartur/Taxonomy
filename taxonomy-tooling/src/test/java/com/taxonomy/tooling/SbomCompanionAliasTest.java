package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbomCompanionAliasTest {

    @Test
    void rejectsHardLinkAliasesWithoutChangingTheSource(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path alias = root.resolve("taxonomy-vex.json");
        String source = """
                {
                  "serialNumber": "urn:uuid:source",
                  "metadata": {"timestamp": "2031-08-15T10:11:12Z"},
                  "components": []
                }
                """;
        Files.writeString(sbom, source, StandardCharsets.UTF_8);
        Files.createLink(alias, sbom);

        assertThat(Files.isSameFile(sbom, alias)).isTrue();
        assertThatThrownBy(() -> SbomCompanionGenerator.generate(sbom, alias))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from the source SBOM");
        assertThat(Files.readString(sbom, StandardCharsets.UTF_8))
                .isEqualTo(source);
        assertThat(Files.readString(alias, StandardCharsets.UTF_8))
                .isEqualTo(source);
    }

    @Test
    void missingReproducibleIdentityRemovesStaleCompanion(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("taxonomy-vex.json");
        Files.writeString(sbom, """
                {
                  "serialNumber": "urn:uuid:source",
                  "components": []
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(output, "stale evidence", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SbomCompanionGenerator.generate(sbom, output))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata.timestamp");
        assertThat(output).doesNotExist();
    }
}
