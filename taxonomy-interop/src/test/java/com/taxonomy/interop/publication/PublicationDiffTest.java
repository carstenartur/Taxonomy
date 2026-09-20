package com.taxonomy.interop.publication;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.taxonomy.interop.publication.PublicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationDiffTest {
    final PublicationDiff diff = new PublicationDiff();
    @Test void independentlyMergesPairedBaselineFields() {
        var base = baseline(document(artifact("a", "Base local", "Base text")), document(artifact("a", "Base remote", "Base text")));
        var change = diff.compare(base, document(artifact("a", "Local title", "Base text")), snapshot(document(artifact("a", "Base remote", "Remote description")))).getFirst();
        assertTrue(change.conflicts().isEmpty()); assertEquals("Local title", change.merged().title()); assertEquals("Remote description", change.merged().text());
        assertEquals(Set.of("title"), change.localFields()); assertEquals(Set.of("text"), change.remoteFields());
    }
    @Test void sameFieldAndDeletionVersusUpdateConflict() {
        var base = document(artifact("a", "Base", "Text"));
        assertEquals(List.of("title"), diff.compare(baseline(base, base), document(artifact("a", "Local", "Text")), snapshot(document(artifact("a", "Remote", "Text")))).getFirst().conflicts());
        assertTrue(diff.compare(baseline(base, base), document(), snapshot(document(artifact("a", "Remote", "Text")))).getFirst().conflicts().contains("existence"));
    }
    @Test void bootstrapAbsenceIsAdditionAndIncludesUnmappedLocalObjects() {
        var changes = diff.compare(null, document(artifact("a", "A", "")), snapshot(document(artifact("b", "B", ""))));
        assertEquals(2, changes.size()); assertTrue(changes.stream().allMatch(c -> c.merged() != null && c.conflicts().isEmpty()));
        assertTrue(diff.compare(null, document(artifact("a", "A", "")), snapshot(document(artifact("a", "A", "")))).isEmpty());
    }
    @Test void relationsKeepEndpointDirectionAndHierarchyMoveConflicts() {
        for (String field : List.of("source", "target", "direction", "parent", "container")) {
            var a = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of(field, "base"));
            var l = new Artifact("a", a.kind(), a.type(), a.title(), "", Map.of(), Map.of(field, "local"));
            var r = new Artifact("a", a.kind(), a.type(), a.title(), "", Map.of(), Map.of(field, "remote"));
            assertEquals(List.of("extension:" + field), diff.compare(baseline(document(a), document(a)), document(l), snapshot(document(r))).getFirst().conflicts());
        }
    }
    @Test void semanticDigestExcludesTransportButKeepsMappedProperties() {
        var a = artifact("a", "A", "text"); var doc = document(a);
        var other = new ExchangeDocument(doc.profile(), doc.profileVersion(), "different", true, "different source", doc.artifacts(), doc.relations(), doc.placements(), doc.metadata(), doc.losses());
        assertEquals(DIGESTS.semantic(doc), DIGESTS.semantic(other));
        assertNotEquals(DIGESTS.semantic(a), DIGESTS.semantic(new Artifact("a", a.kind(), a.type(), a.title(), a.text(), Map.of("mapped", "value"), Map.of())));
    }

    @Test void equalCurrentContentStillReportsHistoricalIdentityReplacement() {
        var old = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "old"));
        var replacement = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "new"));
        var changes = diff.compare(baseline(document(old), document(old)), document(replacement), snapshot(document(replacement)));
        assertEquals(1, changes.size()); assertTrue(changes.getFirst().conflicts().contains("identity"));
        assertTrue(diff.compare(baseline(document(old), document(old)), document(old), snapshot(document(old))).isEmpty());
        assertTrue(diff.compare(baseline(document(old), document(old)), document(), snapshot(document())).isEmpty());
    }
    @Test void eitherSideIdentityReplacementRemainsAVisibleConflict() {
        var old = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "old"));
        var replacement = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "new"));
        var base = baseline(document(old), document(old));
        assertTrue(diff.compare(base, document(replacement), snapshot(document(old))).getFirst().conflicts().contains("identity"));
        assertTrue(diff.compare(base, document(old), snapshot(document(replacement))).getFirst().conflicts().contains("identity"));
    }
}
