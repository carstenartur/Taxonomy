package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompositeBackupDataContributorTest {
    private static final BackupComponentId ID = new BackupComponentId("application");
    private static final BackupInventory INVENTORY = new BackupInventory(List.of(
            new BackupInventory.Category("records", ID, BackupStorageRule.PORTABLE_PRIMARY, "Durable records"),
            new BackupInventory.Category("preferences", ID, BackupStorageRule.GIT_PRIMARY, "Stored settings"),
            new BackupInventory.Category("provider", ID, BackupStorageRule.EXTERNAL_DEPENDENCY, "Operator verification"),
            new BackupInventory.Category("cache", ID, BackupStorageRule.REBUILDABLE, "Rebuild from captured records"),
            new BackupInventory.Category("lease", ID, BackupStorageRule.TRANSIENT, "Do not reactivate source workers"),
            new BackupInventory.Category("foreign", new BackupComponentId("workspace"), BackupStorageRule.GIT_PRIMARY, "Other module")));

    @Test void rejectsAnAssemblyThatForgetsAnOwnedDataSourceBeforeCapture() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(ID, 1, INVENTORY,
                List.of(adapter("records"), adapter("preferences"))));
    }

    @Test void rejectsMultipleAdaptersClaimingTheSamePersistentCategory() {
        assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(ID, 1, INVENTORY,
                List.of(adapter("records"), adapter("preferences"), adapter("provider"), adapter("records"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unclassified", "foreign", "cache", "lease"})
    void rejectsUnownedUnknownOrExplicitlyExcludedCaptureCategories(String category) {
        var adapters = complete();
        adapters.add(adapter(category));
        assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(ID, 1, INVENTORY, adapters));
    }

    @Test void rejectsMixedComponentsSchemasEmptyDeclarationsAndUnknownOwners() {
        for (var invalid : List.of(
                new Adapter(new BackupComponentId("workspace"), 1, Set.of("records"), (snapshot, sink) -> {}),
                new Adapter(ID, 2, Set.of("records"), (snapshot, sink) -> {}),
                new Adapter(ID, 1, Set.of(), (snapshot, sink) -> {}))) {
            assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(ID, 1, INVENTORY,
                    List.of(invalid, adapter("preferences"), adapter("provider"))));
        }
        assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(ID, 0, INVENTORY, complete()));
        assertThrows(IllegalArgumentException.class, () -> new CompositeBackupDataContributor(new BackupComponentId("unknown"), 1, INVENTORY, List.of()));
    }

    @Test void keepsDeclaredCoverageAndReviewedProfileOmissionsImmutable() {
        var adapters = complete();
        var composition = new CompositeBackupDataContributor(ID, 1, INVENTORY, adapters);
        adapters.clear();
        assertEquals(ID, composition.componentId());
        assertEquals(1, composition.schemaVersion());
        assertEquals(Set.of("records", "preferences", "provider"), composition.categories());
        assertThrows(UnsupportedOperationException.class, () -> composition.categories().clear());
        var omissions = composition.omissions(BackupProfile.CURRENT_STATE);
        assertEquals(List.of("cache: excluded (REBUILDABLE); Rebuild from captured records",
                "lease: excluded (TRANSIENT); Do not reactivate source workers",
                "records: CURRENT_STATE", "preferences: CURRENT_STATE", "provider: CURRENT_STATE"), omissions);
        assertTrue(composition.omissions(BackupProfile.SELECTED_VERSION).contains("records: SELECTED_VERSION"));
        assertThrows(UnsupportedOperationException.class, () -> omissions.clear());
    }

    @Test void preservesSnapshotSinkAndOrderWithCancellationCheckpointsAroundEachAdapter() throws Exception {
        var snapshot = snapshot(Map.of(ID, 1));
        var events = new ArrayList<String>();
        var sink = new RecordingSink(events, 0);
        var adapters = new ArrayList<BackupDataContributor>();
        for (String category : List.of("records", "preferences", "provider")) adapters.add(new Adapter(ID, 1, Set.of(category),
                (actualSnapshot, actualSink) -> {
                    assertSame(snapshot, actualSnapshot); assertSame(sink, actualSink); events.add(category);
                }));
        var composition = new CompositeBackupDataContributor(ID, 1, INVENTORY, adapters);
        adapters.clear();
        composition.write(snapshot, sink);
        assertEquals(List.of("checkpoint", "records", "checkpoint", "preferences", "checkpoint", "provider", "checkpoint"), events);
    }

    @Test void refusesMissingOrDifferentCapturedSchemaBeforeAnyAdapterOrSinkAccess() {
        var composition = new CompositeBackupDataContributor(ID, 1, INVENTORY, complete());
        for (Map<BackupComponentId, Integer> versions : List.of(Map.<BackupComponentId, Integer>of(), Map.of(ID, 2))) {
            var events = new ArrayList<String>();
            assertThrows(IOException.class, () -> composition.write(snapshot(versions), new RecordingSink(events, 0)));
            assertTrue(events.isEmpty());
        }
    }

    @Test void stopsAtDelegateFailureWithoutAttemptingLaterAdapters() {
        var failure = new IOException("Unsupported profile");
        var events = new ArrayList<String>();
        var composition = new CompositeBackupDataContributor(ID, 1, INVENTORY, List.of(
                new Adapter(ID, 1, Set.of("records"), (snapshot, sink) -> events.add("records")),
                new Adapter(ID, 1, Set.of("preferences"), (snapshot, sink) -> { events.add("failure"); throw failure; }),
                new Adapter(ID, 1, Set.of("provider"), (snapshot, sink) -> fail("Later adapter ran"))));
        assertSame(failure, assertThrows(IOException.class, () -> composition.write(snapshot(Map.of(ID, 1)), new RecordingSink(events, 0))));
        assertEquals(List.of("checkpoint", "records", "checkpoint", "failure"), events);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void cancellationBeforeBetweenOrAfterAdaptersNeverReturnsSuccess(int cancelledAt) {
        var events = new ArrayList<String>();
        var adapters = new ArrayList<BackupDataContributor>();
        for (String category : List.of("records", "preferences", "provider")) adapters.add(new Adapter(ID, 1, Set.of(category),
                (snapshot, sink) -> events.add(category)));
        var composition = new CompositeBackupDataContributor(ID, 1, INVENTORY, adapters);
        assertThrows(InterruptedIOException.class, () -> composition.write(snapshot(Map.of(ID, 1)), new RecordingSink(events, cancelledAt)));
        assertEquals(cancelledAt - 1, events.stream().filter(event -> !event.equals("checkpoint")).count());
    }

    @Test void anOwnerWithOnlyExplicitExclusionsRequiresNoSourceReader() throws Exception {
        var inventory = new BackupInventory(List.of(new BackupInventory.Category("cache", ID, BackupStorageRule.REBUILDABLE, "Rebuild")));
        var composition = new CompositeBackupDataContributor(ID, 1, inventory, List.of());
        var events = new ArrayList<String>();
        composition.write(snapshot(Map.of(ID, 1)), new RecordingSink(events, 0));
        assertEquals(List.of("checkpoint"), events);
        assertTrue(composition.categories().isEmpty());
        assertEquals(List.of("cache: excluded (REBUILDABLE); Rebuild"), composition.omissions(BackupProfile.INSTALLATION_CURRENT));
    }

    private static ArrayList<BackupDataContributor> complete() {
        return new ArrayList<>(List.of(adapter("records"), adapter("preferences"), adapter("provider")));
    }

    private static BackupDataContributor adapter(String category) {
        return new Adapter(ID, 1, Set.of(category), (snapshot, sink) -> {});
    }

    @FunctionalInterface private interface Writer { void write(SnapshotContext snapshot, ComponentSink sink) throws IOException; }
    private record Adapter(BackupComponentId componentId, int schemaVersion, Set<String> categories, Writer writer) implements BackupDataContributor {
        @Override public List<String> omissions(BackupProfile profile) { return categories.stream().sorted().map(category -> category + ": " + profile).toList(); }
        @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException { writer.write(snapshot, sink); }
    }
    private static final class RecordingSink implements ComponentSink {
        private final List<String> events;
        private final int cancelledAt;
        private int checkpoints;
        RecordingSink(List<String> events, int cancelledAt) { this.events = events; this.cancelledAt = cancelledAt; }
        @Override public BackupEntry write(String path, InputStream input) { return fail("Stub adapters should not write payloads"); }
        @Override public void checkpoint() throws IOException {
            events.add("checkpoint");
            if (++checkpoints == cancelledAt) throw new InterruptedIOException("Cancelled");
        }
    }
    private static SnapshotContext snapshot(Map<BackupComponentId, Integer> versions) {
        var request = new BackupRequest(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation(),
                new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var authorization = new AuthorizedBackupRequest(request, PrincipalId.create(), "decision", Instant.EPOCH,
                EnumSet.allOf(BackupCapability.class));
        return new SnapshotContext(BackupId.create(), authorization, Instant.EPOCH, Instant.EPOCH, 1, Map.of(), versions);
    }
}
