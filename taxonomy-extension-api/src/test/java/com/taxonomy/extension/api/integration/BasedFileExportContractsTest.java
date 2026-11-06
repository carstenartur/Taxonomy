package com.taxonomy.extension.api.integration;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reconstructed from the approved SPI contract after workspace recovery. */
class BasedFileExportContractsTest {
    private static final IntegrationContext CONTEXT = new IntegrationContext(UUID.randomUUID(), AuthorityMode.BIDIRECTIONAL,
            new ExternalScope("EXAMPLE", "repo", null), new InternalState("repo", "workspace", "draft", null, 0, null, null),
            "alice", "example", "2");
    private static final ExchangeDocument DOCUMENT = new ExchangeDocument("example", "2", null, false, "",
            List.of(), List.of(), List.of(), Map.of(), List.of());

    @Test void basedRequestRetainsAnImmutableBasisAndChecksItsDigestShape() {
        byte[] bytes = {1, 2, 3};
        var outbound = new OutboundRequest(CONTEXT, DOCUMENT, null);
        var request = new BasedOutboundRequest(outbound, FileExportMode.NEW_PROJECT, CONTEXT.connectionId(),
                "a".repeat(64), "application/example", bytes);
        bytes[0] = 8;
        assertArrayEquals(new byte[]{1, 2, 3}, request.baseContent());
        var returned = request.baseContent(); returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, request.baseContent());
        assertThrows(IllegalArgumentException.class, () -> new BasedOutboundRequest(outbound, FileExportMode.NEW_PROJECT,
                CONTEXT.connectionId(), "not-a-sha256", "application/example", bytes));
        assertThrows(IllegalArgumentException.class, () -> new BasedOutboundRequest(outbound, FileExportMode.NEW_PROJECT,
                CONTEXT.connectionId(), "a".repeat(64), "application/example", new byte[0]));
    }

    @Test void legacyConnectorKeepsItsOriginalExportWithoutImplementingNewMethods() {
        LifecycleIntegrationConnector legacy = new LifecycleIntegrationConnector() {
            public IntegrationDescriptor descriptor() { return new IntegrationDescriptor("example", "2", "Legacy",
                    Set.of(Capability.FILE_EXPORT), Set.of("application/example")); }
            public ExchangeDocument previewInbound(InboundRequest request) { return DOCUMENT; }
            public ExchangeFile previewOutbound(OutboundRequest request) {
                return new ExchangeFile("application/example", "legacy.bin", new byte[]{4}, List.of());
            }
        };
        var outbound = new OutboundRequest(CONTEXT, DOCUMENT, null);
        assertArrayEquals(new byte[]{4}, legacy.previewOutbound(outbound).content());
        assertThrows(UnsupportedOperationException.class, legacy::fileExportProfile);
        assertThrows(UnsupportedOperationException.class, () -> legacy.previewOutbound(new BasedOutboundRequest(outbound,
                FileExportMode.NEW_PROJECT, CONTEXT.connectionId(), "a".repeat(64), "application/example", new byte[]{1})));
    }

    @Test void filePolicyFreezesModesAndTypeMappings() {
        var modes = EnumSet.of(FileExportMode.NEW_PROJECT);
        var elements = new HashMap<>(Map.of("System", "Node"));
        var profile = new FileExportProfile(modes, ExternalIdentityFormat.UUID_BRACED_UPPER, elements,
                Map.of("DEPENDS_ON", "Dependency"), "Requirement", "Package", "Diagram");
        modes.add(FileExportMode.UPDATE_COPY); elements.put("System", "Class");
        assertEquals(Set.of(FileExportMode.NEW_PROJECT), profile.modes());
        assertEquals("Node", profile.elementTypes().get("System"));
        assertThrows(UnsupportedOperationException.class, () -> profile.elementTypes().put("Component", "Component"));
        assertThrows(IllegalArgumentException.class, () -> new FileExportProfile(Set.of(), ExternalIdentityFormat.UUID_BRACED_UPPER,
                Map.of(), Map.of(), "Requirement", "Package", "Diagram"));
        assertThrows(IllegalArgumentException.class, () -> new FileExportProfile(Set.of(FileExportMode.NEW_PROJECT),
                ExternalIdentityFormat.UUID_BRACED_UPPER, Map.of("System", ""), Map.of(), "Requirement", "Package", "Diagram"));
    }

    @Test void retainedChangeFieldsHaveCanonicalOrderIndependentOfInputSetOrder() {
        var expected = List.of("attribute:alias", "attribute:notes", "attribute:priority", "attribute:status",
                "extension:canonicalType", "extension:container", "extension:diagramStyle", "extension:documentation",
                "extension:nativeId", "extension:source", "extension:stereotype", "extension:target", "kind", "text", "title", "type");
        var descending = new TreeSet<String>(Comparator.reverseOrder()); descending.addAll(expected);
        for (Set<String> fields : List.of(new HashSet<>(expected), new LinkedHashSet<>(expected.reversed()), descending))
            assertEquals(expected, new ArrayList<>(reviewedChange(fields).fields()),
                    "Retained HTTP field arrays must have the same order across construction and JVM restarts");
    }

    @Test void retainedChangeFieldsRemainADefensiveImmutableSet() {
        var fields = new HashSet<>(List.of("title", "text", "extension:canonicalType"));
        var change = reviewedChange(fields);
        fields.clear(); fields.add("type");
        assertEquals(Set.of("title", "text", "extension:canonicalType"), change.fields());
        assertEquals(reviewedChange(new LinkedHashSet<>(List.of("text", "extension:canonicalType", "title"))), change);
        assertEquals(Set.of(), reviewedChange(Set.of()).fields());
        assertThrows(UnsupportedOperationException.class, () -> change.fields().add("type"));
        assertThrows(UnsupportedOperationException.class, () -> change.fields().remove("title"));
        var iterator = change.fields().iterator(); iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    @Test void retainedChangeFieldsStillRejectNullSetsAndNullMembers() {
        assertThrows(NullPointerException.class, () -> reviewedChange(null));
        assertThrows(NullPointerException.class, () -> reviewedChange(new HashSet<>(Arrays.asList("title", null))));
        var nullFriendly = new TreeSet<String>(Comparator.nullsFirst(Comparator.naturalOrder()));
        nullFriendly.add(null); nullFriendly.add("title");
        assertThrows(NullPointerException.class, () -> reviewedChange(nullFriendly));
    }

    private static IntegrationChange reviewedChange(Set<String> fields) {
        return new IntegrationChange("change", "ELEMENT:reviewed", ChangeKind.UPDATE, fields,
                "local-fingerprint", "external-fingerprint", null, null, List.of());
    }
}
