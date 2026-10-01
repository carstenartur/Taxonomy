package com.taxonomy.interop.backup;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IntegrationBackupPayloadsTest {
    private static final IntegrationJson SOURCE = new IntegrationJson(JsonMapper.builder().build());
    private static final UUID OP = UUID.randomUUID();
    private static final ExternalScope EXTERNAL = new ExternalScope("contract", "remote", "configuration");
    private static final PublicationScope SCOPE = new PublicationScope(EXTERNAL, "root", "selector");
    private static final ProviderIdentity PROVIDER = new ProviderIdentity("provider", "remote", "configuration");
    private static final InternalState STATE = new InternalState("repo-a", "private-a", "draft", null, 1, 7L, "project-digest");
    private static final IntegrationContext CONTEXT = new IntegrationContext(UUID.randomUUID(), AuthorityMode.BIDIRECTIONAL,
            EXTERNAL, STATE, "alice", "contract", "1");
    private static final PublicationPreviewRequest REQUEST = new PublicationPreviewRequest(OP, STATE, PublicationMode.PUSH, SCOPE, "scope-1");

    @Test void currentDocumentAndChangesKeepCandidatesButHaveNoOriginalInputOrBeforePayload() throws Exception {
        var document = document("SAVED-CANDIDATE");
        var change = new IntegrationChange("change", "a", ChangeKind.UPDATE, Set.of("text"), "local-digest", "remote-digest",
                artifact("BEFORE-SECRET"), artifact("SAVED-CANDIDATE"), List.of("text"));
        assertThat(json(IntegrationBackupPayloads.document(document, false))).contains("SAVED-CANDIDATE")
                .doesNotContain("SOURCE-SECRET", "\"source\"");
        assertThat(json(IntegrationBackupPayloads.change(change, false))).contains("SAVED-CANDIDATE", "local-digest")
                .doesNotContain("BEFORE-SECRET", "\"before\"");
        assertThat(json(IntegrationBackupPayloads.document(document, true))).contains("SOURCE-SECRET");
        assertThat(json(IntegrationBackupPayloads.change(change, true))).contains("BEFORE-SECRET");
    }

    @Test void currentPublicationPreservesConflictWorkAndTargetsWithoutSavedBaselines() throws Exception {
        var base = new CommonBaseline(1, PROVIDER, SCOPE, "contract", "1", document("COMMON-SECRET"), document("COMMON-SECRET"), "common-digest");
        var change = new PublicationChange("change", "a", artifact("BASE-LOCAL-SECRET"), artifact("BASE-REMOTE-SECRET"),
                artifact("LOCAL-CANDIDATE"), artifact("REMOTE-CANDIDATE"), artifact("MERGED-CANDIDATE"), Set.of("text"), Set.of("text"), List.of("text"), Set.of());
        var preview = new PublicationPreviewEnvelope(1, REQUEST, CONTEXT, 1, UUID.randomUUID(), base, document("LOCAL-BEFORE-SECRET"),
                snapshot("REMOTE-BEFORE-SECRET"), List.of(change), List.of(), "source-preview-digest");
        var capabilities = new PublicationCapabilities(1, "1", PROVIDER, SCOPE, Set.of(), Set.of(), Set.of(), "verification", "capability-digest", 10, 1024, 3600);
        var plan = new PublicationPlan(1, OP, CONTEXT, PublicationMode.PUSH, preview.commonCheckpointId(), 1, "request-digest", "review-digest",
                capabilities, snapshot("REMOTE-BEFORE-SECRET"), document("LOCAL-BEFORE-SECRET"), document("LOCAL-TARGET"), document("REMOTE-TARGET"),
                List.of(), List.of(), "source-plan-digest");
        String current = json(IntegrationBackupPayloads.preview(preview, false)) + json(IntegrationBackupPayloads.plan(plan, false));
        assertThat(current).contains("LOCAL-CANDIDATE", "REMOTE-CANDIDATE", "MERGED-CANDIDATE", "LOCAL-TARGET", "REMOTE-TARGET", "source-preview-digest", "source-plan-digest")
                .doesNotContain("SECRET", "\"baseLocal\"", "\"baseRemote\"", "\"localBefore\"", "\"remoteBefore\"", "\"commonBaseline\"");
        assertThat(json(IntegrationBackupPayloads.preview(preview, true))).contains("COMMON-SECRET", "BASE-LOCAL-SECRET", "BASE-REMOTE-SECRET");
        assertThat(json(IntegrationBackupPayloads.plan(plan, true))).contains("LOCAL-BEFORE-SECRET", "REMOTE-BEFORE-SECRET");
    }

    @Test void removedStagedBindingsLoseTheirLastKnownPayloadInCurrentProjection() throws Exception {
        var removed = new StagedBinding("a", "business-a", 42L, artifact("REMOVED-EXTERNAL-SECRET"), artifact("REMOVED-INTERNAL-SECRET"), true);
        var pending = new StagedBinding("a", "business-a", 42L, artifact("PENDING-EXTERNAL"), artifact("PENDING-INTERNAL"), false);
        assertThat(json(IntegrationBackupPayloads.binding(removed, false))).contains("business-a", "portfolio.requirement").doesNotContain("SECRET");
        assertThat(json(IntegrationBackupPayloads.binding(pending, false))).contains("PENDING-EXTERNAL", "PENDING-INTERNAL");
        assertThat(json(IntegrationBackupPayloads.binding(removed, true))).contains("REMOVED-EXTERNAL-SECRET", "REMOVED-INTERNAL-SECRET");
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"systemType\":\"x\",\"systemType\":\"SECRET\",\"repository\":\"r\"}",
            "{\"systemType\":\"x\",\"repository\":\"r\",\"password\":\"SECRET\"}",
            "{\"systemType\":\"x\",\"repository\":\"r\"} {}",
            "{\"systemType\":42,\"repository\":\"r\"}", "{SECRET"
    }) void rejectsAmbiguousOrUnreviewedStoredJsonWithoutEchoingIt(String value) {
        assertThatThrownBy(() -> IntegrationBackupJson.read(value, ExternalScope.class)).isInstanceOf(IOException.class)
                .hasMessage("Invalid or unsupported integration backup evidence").hasNoCause();
    }

    @Test void validLegacyOptionalFieldsAndNullPayloadsRemainReadable() throws Exception {
        assertThat(IntegrationBackupJson.read("{\"systemType\":\"contract\",\"repository\":\"remote\"}", ExternalScope.class))
                .isEqualTo(new ExternalScope("contract", "remote", null));
        assertThat(IntegrationBackupJson.read(null, Artifact.class)).isNull();
        assertThat(IntegrationBackupJson.read("null", Artifact.class)).isNull();
        assertThat(IntegrationBackupJson.read(SOURCE.write(REQUEST), PublicationPreviewRequest.class)).isEqualTo(REQUEST);
    }

    @ParameterizedTest @ValueSource(strings = { "0", "\"0\"" })
    void enumNamesCannotBeReplacedByNumericOrdinals(String kind) {
        assertThatThrownBy(() -> IntegrationBackupJson.read("{\"id\":\"a\",\"kind\":" + kind
                + ",\"attributes\":{},\"extensions\":{}}", Artifact.class))
                .isInstanceOf(IOException.class).hasMessage("Invalid or unsupported integration backup evidence").hasNoCause();
    }

    @Test void oversizedAndDeepJsonFailWithoutSourceDiagnostics() {
        for (String value : List.of("SECRET".repeat(PortableRows.MAX_RECORD_BYTES / 12 + 1), "[".repeat(65) + "0" + "]".repeat(65)))
            assertThatThrownBy(() -> IntegrationBackupJson.read(value, Object.class)).isInstanceOf(IOException.class)
                    .hasMessage("Invalid or unsupported integration backup evidence").hasNoCause();
    }

    private static Artifact artifact(String text) { return new Artifact("a", ArtifactKind.ELEMENT, "component", "A", text, Map.of(), Map.of()); }
    private static ExchangeDocument document(String text) { return new ExchangeDocument("contract", "1", "source-version", true, "SOURCE-SECRET", List.of(artifact(text)), List.of(), List.of(), Map.of(), List.of()); }
    private static ScopeSnapshot snapshot(String text) { return new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-1", "source-digest", true, document(text), Map.of()); }
    private static String json(Record value) throws Exception { return PortableRows.json().writeValueAsString(value); }
}
