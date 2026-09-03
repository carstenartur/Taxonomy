package com.taxonomy.export.artifact;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.taxonomy.export.artifact.ArchitectureArtifactLoss.Disposition.MAPPED;
import static com.taxonomy.export.artifact.ArchitectureArtifactLoss.Disposition.OMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureArtifactEnvelopeFactoryTest {

    private static final String PAYLOAD =
            "flowchart LR\n    A --> B\n";
    private static final String C1_CONTROL =
            String.valueOf((char) 0x85);

    private final ArchitectureArtifactEnvelopeFactory factory =
            new ArchitectureArtifactEnvelopeFactory();

    @Test
    void createsDeterministicEnvelopeBoundToExactSnapshotCoordinates() {
        ArchitectureArtifactSource source = source();
        List<ArchitectureArtifactLoss> mutableLosses = new ArrayList<>();
        mutableLosses.add(new ArchitectureArtifactLoss(
                "mermaid.node-type.visual-only",
                "DiagramNode.type",
                MAPPED,
                "Rendered as presentation styling rather than typed semantics."));
        mutableLosses.add(new ArchitectureArtifactLoss(
                "mermaid.node-depth.omitted",
                "DiagramNode.depth",
                OMITTED,
                "Not represented in the Mermaid payload."));
        ArchitectureArtifactLossManifest lossManifest =
                new ArchitectureArtifactLossManifest(
                        "mermaid-diagram-v1",
                        mutableLosses);

        ArchitectureArtifactEnvelope first = factory.create(
                ArchitectureArtifactFormat.MERMAID,
                source,
                PAYLOAD,
                lossManifest);
        ArchitectureArtifactEnvelope second = factory.create(
                ArchitectureArtifactFormat.MERMAID,
                source,
                PAYLOAD,
                lossManifest);
        mutableLosses.clear();

        assertThat(first).isEqualTo(second);
        assertThat(first.schemaVersion()).isEqualTo("1.0");
        assertThat(first.artifactId()).hasSize(64);
        assertThat(first.format())
                .isEqualTo(ArchitectureArtifactFormat.MERMAID);
        assertThat(first.mediaType())
                .isEqualTo("text/plain;charset=UTF-8");
        assertThat(first.fileName()).isEqualTo("architecture.mmd");
        assertThat(first.payloadSha256())
                .isEqualTo(
                        "a73441da373d3f6017b58f4d8065101f8e07a8465fe8ef48"
                                + "082fb559466d8701");
        assertThat(first.source()).isEqualTo(source);
        assertThat(first.lossManifest().losses())
                .extracting(ArchitectureArtifactLoss::code)
                .containsExactly(
                        "mermaid.node-depth.omitted",
                        "mermaid.node-type.visual-only");
    }

    @Test
    void identityChangesWhenAnyAcceptedBindingChanges() {
        ArchitectureArtifactLossManifest noLosses =
                ArchitectureArtifactLossManifest.lossless(
                        "architecture-json-v1");
        ArchitectureArtifactEnvelope baseline = factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "{\"nodes\":[]}",
                noLosses);

        assertThat(factory.create(
                ArchitectureArtifactFormat.JSON,
                new ArchitectureArtifactSource(
                        "snapshot-2",
                        "revision-7",
                        "workspace-a",
                        "feature/export"),
                "{\"nodes\":[]}",
                noLosses).artifactId())
                .isNotEqualTo(baseline.artifactId());
        assertThat(factory.create(
                ArchitectureArtifactFormat.JSON,
                new ArchitectureArtifactSource(
                        "snapshot-1",
                        "revision-8",
                        "workspace-a",
                        "feature/export"),
                "{\"nodes\":[]}",
                noLosses).artifactId())
                .isNotEqualTo(baseline.artifactId());
        assertThat(factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "architecture-export.json",
                "{\"nodes\":[]}",
                noLosses).artifactId())
                .isNotEqualTo(baseline.artifactId());
        assertThat(factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "{\"nodes\":[{\"id\":\"A\"}]}",
                noLosses).artifactId())
                .isNotEqualTo(baseline.artifactId());
        assertThat(factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "{\"nodes\":[]}",
                new ArchitectureArtifactLossManifest(
                        "architecture-json-v1",
                        List.of(new ArchitectureArtifactLoss(
                                "json.layout.omitted",
                                "DiagramModel.layout",
                                OMITTED,
                                "Layout is not part of this profile."))))
                .artifactId())
                .isNotEqualTo(baseline.artifactId());
    }

    @Test
    void validatesCompleteAuthorityPayloadAndSafeFormatSpecificFileName() {
        assertThatThrownBy(() -> new ArchitectureArtifactSource(
                " ",
                "revision-7",
                "workspace-a",
                "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotId");
        assertThatThrownBy(() -> new ArchitectureArtifactSource(
                "snapshot-1\nforged",
                "revision-7",
                "workspace-a",
                "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO control");
        assertThatThrownBy(() -> new ArchitectureArtifactSource(
                "snapshot-1" + C1_CONTROL + "forged",
                "revision-7",
                "workspace-a",
                "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO control");
        assertThatThrownBy(() -> factory.create(
                ArchitectureArtifactFormat.MERMAID,
                source(),
                ".architecture.mmd",
                PAYLOAD,
                ArchitectureArtifactLossManifest.lossless(
                        "mermaid-diagram-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsafe or format-incompatible fileName");
        assertThatThrownBy(() -> factory.create(
                ArchitectureArtifactFormat.MERMAID,
                source(),
                "architecture.json",
                PAYLOAD,
                ArchitectureArtifactLossManifest.lossless(
                        "mermaid-diagram-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsafe or format-incompatible fileName");
        assertThatThrownBy(() -> factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "  ",
                ArchitectureArtifactLossManifest.lossless(
                        "architecture-json-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void rejectsIsoControlsFromEveryIdentityBearingTextField() {
        assertThatThrownBy(() -> new ArchitectureArtifactLoss(
                "loss" + C1_CONTROL + "code",
                "DiagramNode.depth",
                OMITTED,
                "Not represented."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code")
                .hasMessageContaining("ISO control");
        assertThatThrownBy(() -> new ArchitectureArtifactLoss(
                "node-depth",
                "DiagramNode" + C1_CONTROL + ".depth",
                OMITTED,
                "Not represented."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourcePath")
                .hasMessageContaining("ISO control");
        assertThatThrownBy(() -> new ArchitectureArtifactLoss(
                "node-depth",
                "DiagramNode.depth",
                OMITTED,
                "Not" + C1_CONTROL + " represented."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detail")
                .hasMessageContaining("ISO control");
        assertThatThrownBy(() ->
                ArchitectureArtifactLossManifest.lossless(
                        "profile" + C1_CONTROL + "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileVersion")
                .hasMessageContaining("ISO control");
    }

    @Test
    void validationErrorsNeverReflectRejectedValues() {
        ArchitectureArtifactEnvelope valid = factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                "{\"nodes\":[]}",
                ArchitectureArtifactLossManifest.lossless(
                        "architecture-json-v1"));
        String unsafeSchemaVersion =
                "1.0" + C1_CONTROL + "forged";
        String unsafeFileName =
                "architecture" + C1_CONTROL + ".json";

        assertThatThrownBy(() -> new ArchitectureArtifactEnvelope(
                unsafeSchemaVersion,
                valid.artifactId(),
                valid.format(),
                valid.source(),
                valid.mediaType(),
                valid.fileName(),
                valid.payloadSha256(),
                valid.lossManifest(),
                valid.payload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported schemaVersion")
                .hasMessageNotContaining(unsafeSchemaVersion)
                .hasMessageNotContaining(C1_CONTROL);
        assertThatThrownBy(() -> factory.create(
                ArchitectureArtifactFormat.JSON,
                source(),
                unsafeFileName,
                "{\"nodes\":[]}",
                ArchitectureArtifactLossManifest.lossless(
                        "architecture-json-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsafe or format-incompatible fileName")
                .hasMessageNotContaining(unsafeFileName)
                .hasMessageNotContaining(C1_CONTROL);
    }

    @Test
    void lossManifestIsCanonicalImmutableAndRejectsAmbiguousCodes() {
        ArchitectureArtifactLoss first = new ArchitectureArtifactLoss(
                "z-loss",
                "DiagramEdge.relevance",
                OMITTED,
                "Not represented.");
        ArchitectureArtifactLoss second = new ArchitectureArtifactLoss(
                "a-loss",
                "DiagramNode.label",
                MAPPED,
                "Rendered as text.");
        List<ArchitectureArtifactLoss> input = new ArrayList<>(
                List.of(first, second));

        ArchitectureArtifactLossManifest manifest =
                new ArchitectureArtifactLossManifest("profile-v1", input);
        input.clear();

        assertThat(manifest.losses())
                .containsExactly(second, first);
        assertThatThrownBy(() -> manifest.losses().add(first))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ArchitectureArtifactLossManifest(
                "profile-v1",
                List.of(
                        first,
                        new ArchitectureArtifactLoss(
                                "z-loss",
                                "DiagramNode.depth",
                                OMITTED,
                                "Also omitted."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate loss code");
    }

    private static ArchitectureArtifactSource source() {
        return new ArchitectureArtifactSource(
                "snapshot-1",
                "revision-7",
                "workspace-a",
                "feature/export");
    }
}
