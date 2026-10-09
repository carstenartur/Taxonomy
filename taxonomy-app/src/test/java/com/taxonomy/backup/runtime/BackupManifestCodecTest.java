package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class BackupManifestCodecTest {
    private final BackupManifestCodec codec = new BackupManifestCodec();
    private byte[] fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/backup/current-state-v1.json")) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }
    @Test void readsVersionOneFixtureAndRoundtripsDomainContract() throws Exception {
        var manifest = codec.read(fixture());
        assertThat(manifest.request().profile()).isEqualTo(BackupProfile.CURRENT_STATE);
        assertThat(manifest.repositories().getFirst().captured().workingStates().get("main").semanticRevision()).isEqualTo(7);
        assertThat(codec.read(codec.write(manifest))).isEqualTo(manifest);
        manifest.requireCompatible("1.4.0", Map.of(new BackupComponentId("workspace"), 1));
    }
    @Test void rejectsDuplicateFieldsUnknownClassesTrailingJsonAndOverlargeManifest() throws Exception {
        String json = new String(fixture(), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.read(json.replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"formatVersion\": 2").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read(json.replace("\"profile\": \"CURRENT_STATE\"", "\"profile\": \"CURRENT_STATE\", \"@class\": \"java.lang.ProcessBuilder\"").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read((json + "{}").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read(new byte[BackupManifestCodec.MAX_MANIFEST_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void neverInfersMissingProfileScopeOrTimeFromNull() throws Exception {
        String json = new String(fixture(), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.read(json.replace("\"profile\": \"CURRENT_STATE\"", "\"profile\": null").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read(json.replace("\"kind\": \"WORKSPACE\"", "\"kind\": \"INSTALLATION\"").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
