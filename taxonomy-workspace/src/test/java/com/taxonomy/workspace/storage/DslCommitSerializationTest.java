package com.taxonomy.workspace.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.workspace.signing.CommitSignature;
import com.taxonomy.workspace.signing.SignatureStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DslCommitSerializationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyConstructorAndOldJsonHaveExplicitUnsignedStatus() throws Exception {
        var old = new DslCommit("head", "alice", null, "Legacy change");
        assertThat(old.signature().status()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(old.signature().serviceSignature()).isFalse();
        var restored = json.readValue("""
                {"commitId":"head","author":"alice","timestamp":null,"message":"Legacy change"}
                """, DslCommit.class);
        assertThat(restored).isEqualTo(old);
        assertThat(json.readTree(json.writeValueAsBytes(old)).at("/signature/status").asText())
                .isEqualTo("UNSIGNED");
    }

    @ParameterizedTest
    @EnumSource(SignatureStatus.class)
    void verificationMetadataSurvivesJsonRoundTrip(SignatureStatus status) throws Exception {
        var signature = new CommitSignature(status, "Service <operator@example.invalid>",
                "0123456789ABCDEF", status == SignatureStatus.VERIFIED, "Verification result");
        var commit = new DslCommit("head", "alice", null, "Model change", signature);
        var wire = json.writeValueAsBytes(commit);
        assertThat(json.readValue(wire, DslCommit.class)).isEqualTo(commit);
        var names = new java.util.HashSet<String>();
        json.readTree(wire).at("/signature").fieldNames().forEachRemaining(names::add);
        assertThat(names)
                .containsExactlyInAnyOrder("status", "signer", "fingerprint", "serviceSignature", "reason");
    }
}
