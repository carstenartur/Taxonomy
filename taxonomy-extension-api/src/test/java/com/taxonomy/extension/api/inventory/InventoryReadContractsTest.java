package com.taxonomy.extension.api.inventory;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.net.URI;
import java.time.Instant;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.taxonomy.extension.api.inventory.InventoryReadContracts.*;

class InventoryReadContractsTest {
    @Test void distinguishesUnknownFromAnExplicitEmptyValue() {
        assertNotEquals(Field.missing(State.ABSENT, "name"), Field.missing(State.NULL, "name"));
        assertNotEquals(Field.text("", "name"), Field.missing(State.ABSENT, "name"));
        assertEquals(State.PRESENT, Field.text("false", "custom").state());
        assertThrows(IllegalArgumentException.class, () -> new Field(State.ABSENT, "value", "name"));
    }
    @Test void identityDoesNotDependOnDisplayNameOrProfileRevision() {
        assertEquals(new ExternalKey("instance", "factsheet", "1"), new ExternalKey("instance", "factsheet", "1"));
        assertNotEquals(new ExternalKey("a", "factsheet", "1"), new ExternalKey("b", "factsheet", "1"));
    }
    @Test void typeProjectionIsImmutableAndUnknownSubtypeIsNotPositive() {
        Map<String, ObjectKind> kinds = new HashMap<>(); kinds.put("software", ObjectKind.TECHNOLOGY_PRODUCT);
        TypeProjection p = new TypeProjection("ITComponent", ObjectKind.OTHER, "category", kinds, Map.of());
        kinds.put("unknown", ObjectKind.SERVICE);
        assertEquals(ObjectKind.OTHER, p.classify(Field.text("unknown", "category")));
        assertEquals(ObjectKind.OTHER, p.classify(Field.missing(State.NULL, "category")));
        assertEquals(ObjectKind.TECHNOLOGY_PRODUCT, p.classify(Field.text("software", "category")));
        assertThrows(UnsupportedOperationException.class, () -> p.subtypeKinds().clear());
    }
    @Test void reportRelationsNeedNoInventedDurableExternalIdentity() {
        var source = new ExternalKey("report-instance", "objects", "base");
        var target = new ExternalKey("report-instance", "objects", "target");
        var one = new ObservedRelation("row-1", null, source, target, "local-link", "UNMAPPED", Map.of());
        var two = new ObservedRelation("row-2", null, source, target, "local-link", "UNMAPPED", Map.of());
        assertNull(one.key()); assertNotEquals(one, two);
    }
    @Test void requestKeepsItsApprovedSelectionAndProjectionImmutable() {
        var projections = new ArrayList<>(List.of(new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of())));
        var ids = new HashSet<>(Set.of("first"));
        var request = new Request(context(AuthorityMode.LINK_ONLY), "instance", "mapping-7", URI.create("https://example.invalid/"),
                projections, ids, false, Limits.defaults());
        projections.clear(); ids.clear();
        assertEquals(1, request.projections().size()); assertEquals(Set.of("first"), request.selectedIds());
        assertEquals("mapping-7", request.configurationRevision());
        assertThrows(UnsupportedOperationException.class, () -> request.selectedIds().clear());
    }
    @Test void inventoryObservationCannotGrantWriteAuthority() {
        var projection = new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of());
        for (AuthorityMode mode : AuthorityMode.values()) if (mode != AuthorityMode.LINK_ONLY)
            assertThrows(IllegalArgumentException.class, () -> new Request(context(mode), "instance", "1", URI.create("https://example.invalid/"),
                    List.of(projection), Set.of(), false, Limits.defaults()));
    }
    @Test void duplicateSourceTypesAndOversizedIdSelectionsAreRejected() {
        var projection = new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> new Request(context(AuthorityMode.LINK_ONLY), "instance", "1", URI.create("https://example.invalid/"),
                List.of(projection, projection), Set.of(), false, Limits.defaults()));
        Set<String> ids = new HashSet<>(); for (int i = 0; i < 201; i++) ids.add("id-" + i);
        assertThrows(IllegalArgumentException.class, () -> new Request(context(AuthorityMode.LINK_ONLY), "instance", "1", URI.create("https://example.invalid/"),
                List.of(projection), ids, false, Limits.defaults()));
    }
    @Test void aSourceWithoutRevisionDoesNotNeedAnInventedVersion() {
        var fields = new HashMap<String, Field>(); fields.put("name", Field.text("App", "Name"));
        var object = new ObservedObject(new ExternalKey("instance", "objects", "a"), "Application",
                Field.missing(State.NOT_SUPPORTED, "subtype"), ObjectKind.APPLICATION, null, fields, State.NULL, List.of());
        fields.clear(); assertEquals("App", object.fields().get("name").value()); assertNull(object.revision());
        assertThrows(UnsupportedOperationException.class, () -> object.fields().clear());
    }
    @Test void aMissingLifecycleCannotContainPositivePhaseEvidence() {
        var phase = new LifecyclePhase(Field.text("planned", "phase"), Field.text("2030", "date"), AssertionKind.PLANNED);
        assertThrows(IllegalArgumentException.class, () -> new ObservedObject(new ExternalKey("instance", "objects", "a"), "Application",
                Field.missing(State.NOT_SUPPORTED, "subtype"), ObjectKind.APPLICATION, null, Map.of(), State.ABSENT, List.of(phase)));
    }
    @Test void observationsRetainSelectionAndDoNotAllowTheirEvidenceToMutate() {
        var request = new Request(context(AuthorityMode.LINK_ONLY), "instance", "1", URI.create("https://example.invalid/"),
                List.of(new TypeProjection("Application", ObjectKind.APPLICATION, null, Map.of(), Map.of())), Set.of("a"), false, Limits.defaults());
        var limitations = new HashSet<>(Set.of("NO_ATOMIC_SNAPSHOT"));
        var observation = new Observation(request, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                Completeness.SELECTED_SCOPE_READ_NON_ATOMIC, 2, 100, List.of(), List.of(), limitations);
        limitations.clear(); assertSame(request, observation.request());
        assertEquals(Set.of("NO_ATOMIC_SNAPSHOT"), observation.limitations());
        assertThrows(UnsupportedOperationException.class, () -> observation.objects().clear());
        assertThrows(IllegalArgumentException.class, () -> new Observation(request, Instant.EPOCH, Instant.EPOCH.minusSeconds(1),
                Completeness.SELECTED_SCOPE_READ_NON_ATOMIC, 2, 100, List.of(), List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(request, Instant.EPOCH, Instant.EPOCH,
                Completeness.SELECTED_SCOPE_READ_NON_ATOMIC, -1, 100, List.of(), List.of(), Set.of()));
    }
    @Test void failureCodesCannotSmuggleResponseTextIntoDiagnostics() {
        var failure = new ReadFailure("SOURCE_UNAVAILABLE");
        assertEquals("SOURCE_UNAVAILABLE", failure.code()); assertNull(failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> new ReadFailure("secret response with spaces"));
    }
    @Test void textAndProfileBoundsAreEnforcedWithoutCallingAnySource() {
        assertThrows(IllegalArgumentException.class, () -> Field.text("x".repeat(65537), "description"));
        assertThrows(IllegalArgumentException.class, () -> Field.text("ok", "line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> new TypeProjection("Type", ObjectKind.OTHER, null, Map.of("x", ObjectKind.SERVICE), Map.of()));
        ReadAccess access = () -> { throw new AssertionError("TLS configuration must not resolve credentials"); };
        assertNotNull(access.tls());
    }
    private static IntegrationContext context(AuthorityMode authority) {
        return new IntegrationContext(UUID.randomUUID(), authority, new ExternalScope("reference", "source", null),
                new InternalState("repo", "scope", "main", null, 0, null, null), "reader", "reference-read", "1");
    }
    @Test void budgetsCannotBeDisabledOrMadeUnbounded() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 20, 100, 100, 1024, 2048, 10));
        assertThrows(IllegalArgumentException.class, () -> new Limits(20, 20, 100, 100, 1024, 2048, 121));
    }
}
