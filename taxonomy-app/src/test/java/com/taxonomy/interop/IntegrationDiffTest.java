package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationDiffTest {
    private final IntegrationDiff diff = new IntegrationDiff(new IntegrationJson(JsonMapper.builder().build()));
    private static Artifact artifact(String title, String text, String xml) {
        return new Artifact("requirement-1", ArtifactKind.REQUIREMENT, "type-1", title, text, Map.of(), Map.of("xml", xml));
    }
    private static ExchangeDocument document(boolean complete, Artifact... artifacts) {
        return new ExchangeDocument("reqif-1.2", "1", "v2", complete, "", List.of(artifacts), List.of(), List.of(), Map.of(), List.of());
    }
    private static Identity identity(Artifact value, boolean removed) {
        return new Identity("REQUIREMENT:requirement-1", "internal-1", 1L, "v1", "fingerprint", value, value, UUID.randomUUID(), removed);
    }
    @Test void disjointFieldsMergeWithNewSourceEvidenceWhileIntersectingFieldsConflict() {
        Artifact before = artifact("Title", "Body", "<old/>"), local = artifact("Local title", "Body", "<old/>"), external = artifact("Title", "Remote body", "<new/>");
        Artifact merged = ExchangeItems.merge(before, local, external);
        assertEquals("Local title", merged.title()); assertEquals("Remote body", merged.text()); assertEquals("<new/>", merged.extensions().get("xml"));
        assertEquals(ChangeKind.UPDATE, diff.compare(document(true, external), AuthorityMode.BIDIRECTIONAL, List.of(identity(before, false)),
                Map.of("REQUIREMENT:requirement-1", local)).stream().filter(c -> c.externalId().startsWith("REQUIREMENT:")).findFirst().orElseThrow().kind());
        var conflict = diff.compare(document(true, artifact("Remote title", "Body", "<new/>")), AuthorityMode.BIDIRECTIONAL,
                List.of(identity(before, false)), Map.of("REQUIREMENT:requirement-1", local)).stream().filter(c -> c.externalId().startsWith("REQUIREMENT:")).findFirst().orElseThrow();
        assertEquals(ChangeKind.CONFLICT, conflict.kind()); assertEquals(List.of("title"), conflict.conflicts());
    }
    @Test void omissionsNeedCompleteMirrorAuthorityAndReusedIdsNeverSilentlyResurrect() {
        Artifact value = artifact("Title", "Body", "<source/>");
        for (AuthorityMode mode : AuthorityMode.values()) for (boolean complete : List.of(false, true)) {
            var changes = diff.compare(document(complete), mode, List.of(identity(value, false)), Map.of("REQUIREMENT:requirement-1", value));
            boolean deletion = changes.stream().anyMatch(c -> c.kind() == ChangeKind.REMOVE_CANDIDATE);
            assertEquals(complete && Set.of(AuthorityMode.MIRROR_READ, AuthorityMode.BIDIRECTIONAL).contains(mode), deletion);
        }
        var reused = diff.compare(document(true, value), AuthorityMode.BIDIRECTIONAL, List.of(identity(value, true)), Map.of());
        assertTrue(reused.stream().anyMatch(c -> c.conflicts().contains("EXTERNAL_IDENTITY_REUSED")));
    }
}
