package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.WorkspaceDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Neutral source profiles must not depend on a vendor name or confuse diagrams with packages. */
class IntegrationCanonicalProjectionTest {
    private final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
    private final IntegrationDomainAdapter domain = new IntegrationDomainAdapter(null, json,
            List.of(new IntegrationDomainAdapter.ProjectionProfile("example-model", "1")));
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private final RepositoryContext context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
    private final Connection connection = new Connection(UUID.fromString("97166d68-1078-48e0-9e06-f24a046055a2"),
            "USER:alice", "Model file", "example-model", "1", AuthorityMode.IMPORT_COPY,
            new ExternalScope("example", "model", null), null, null, 0, null, null, "alice");

    @Test void installedDescriptorGrantsCanonicalProjectionOnlyToItsExactProfileVersion() {
        var registry = new ExchangeConnectorRegistry(List.of(
                connector("1", Set.of(Capability.FILE_IMPORT, Capability.ARCHITECTURE_MODEL, Capability.CANONICAL_PROJECTION_V1)),
                connector("2", Set.of(Capability.FILE_IMPORT, Capability.ARCHITECTURE_MODEL))));
        new ApplicationContextRunner().withBean(IntegrationPortfolioPort.class, () -> mock(IntegrationPortfolioPort.class))
                .withBean(IntegrationJson.class, () -> json).withBean(ExchangeConnectorRegistry.class, () -> registry)
                .withUserConfiguration(IntegrationDomainAdapter.class).run(application -> {
                    var loaded = application.getBean(IntegrationDomainAdapter.class);
                    assertTrue(loaded.supportsNativePackages(connection));
                    assertTrue(loaded.supportsArchitecture(connection));
                    var other = new Connection(connection.id(), connection.organizationId(), connection.displayName(), connection.connectorId(), "2",
                            connection.authority(), connection.externalScope(), null, null, 0, null, null, connection.createdBy());
                    assertFalse(loaded.supportsNativePackages(other));
                    assertFalse(loaded.supportsArchitecture(other));
                });
    }

    @Test void packageMembershipAndRepeatedDiagramOccurrencesAreIndependent() {
        var selected = ExchangeItems.flatten(source());
        String dsl = commands.applyAll("", domain.architectureCommands(connection, "", Map.of(), selected, List.of())).dsl();
        var model = commands.model(dsl);

        assertEquals(2, model.getPackages().size());
        assertEquals(2, model.getElements().size());
        String packageA = identity(selected.get("SPECIFICATION:package-a"));
        assertEquals(packageA, model.getElements().stream().filter(e -> e.getTitle().equals("First")).findFirst().orElseThrow().getPackageId());
        assertEquals(packageA, model.getElements().stream().filter(e -> e.getTitle().equals("Second")).findFirst().orElseThrow().getPackageId());
        var view = ArchitectureSemanticPatch.index(dsl).get("view:" + identity(selected.get("VIEW:view-a")));
        assertEquals(Set.of(identity(selected.get("ELEMENT:element-a")), identity(selected.get("ELEMENT:element-b"))),
                Set.copyOf(view.propertyValues("include")));
    }

    @Test void snapshotPreservesEveryPackageAndDiagramOccurrenceWithoutPhantomChanges() {
        var selected = ExchangeItems.flatten(source());
        var mappings = bindings(selected);
        String dsl = manuallyProjectedDsl(selected);
        var snapshot = domain.snapshot(context, connection, mappings, document(dsl));

        for (var entry : selected.entrySet()) {
            assertEquals(ExchangeItems.fields(entry.getValue()), ExchangeItems.fields(snapshot.items().get(entry.getKey())),
                    "Snapshot must preserve source evidence for " + entry.getKey());
        }
        assertEquals(selected.keySet(), snapshot.items().keySet());
        assertTrue(new IntegrationDiff(json).compare(source(), AuthorityMode.IMPORT_COPY, mappings, snapshot.items()).stream()
                .allMatch(change -> change.kind() == ChangeKind.UNCHANGED));
        assertTrue(domain.architectureCommands(connection, dsl, snapshot.items(), selected, mappings).isEmpty(),
                "Reading the unchanged source again must not generate semantic edits");
    }

    @Test void canonicalTypeChangesRetainTheExternalTypeAndIdentity() {
        var selected = ExchangeItems.flatten(source());
        var mappings = bindings(selected);
        String element = identity(selected.get("ELEMENT:element-a"));
        String dsl = commands.apply(manuallyProjectedDsl(selected),
                new UpdateArchitectureElement(element, "Process", Map.of("title", "Retyped", "description", ""))).dsl();

        Artifact observed = domain.snapshot(context, connection, mappings, document(dsl)).items().get("ELEMENT:element-a");
        assertEquals("element-a", observed.id());
        assertEquals("Class", observed.type(), "A canonical edit must not invent an ArchiMate or vendor source type");
        assertEquals("Process", observed.extensions().get("canonicalType"));
        assertEquals("Retyped", observed.title());
        assertEquals(Map.of("stereotype", "ExternalSystem"), observed.attributes());
    }

    @Test void aReviewedPackageMoveKeepsElementAndDiagramIdentities() {
        var selected = ExchangeItems.flatten(source());
        var mappings = bindings(selected);
        String before = manuallyProjectedDsl(selected);
        var incoming = new TreeMap<>(selected);
        Artifact member = incoming.get("PLACEMENT:member:a");
        var moved = new TreeMap<>(member.extensions());
        moved.put("parent", "package:b");
        incoming.put("PLACEMENT:member:a", new Artifact(member.id(), member.kind(), member.type(), member.title(), member.text(), member.attributes(), moved));
        Artifact first = incoming.get("ELEMENT:element-a");
        incoming.put("ELEMENT:element-a", new Artifact(first.id(), first.kind(), first.type(), "Renamed", first.text(), first.attributes(), first.extensions()));

        String after = commands.applyAll(before, domain.architectureCommands(connection, before, selected, incoming, mappings)).dsl();
        var model = commands.model(after);
        assertEquals(2, model.getElements().size());
        var element = model.getElements().stream().filter(e -> e.getTitle().equals("Renamed")).findFirst().orElseThrow();
        assertEquals(identity(first), element.getId());
        assertEquals(identity(selected.get("SPECIFICATION:package-b")), element.getPackageId());
        var snapshot = domain.snapshot(context, connection, bindings(incoming), document(after));
        for (String id : List.of("diagram:a", "diagram:a:copy", "diagram:b"))
            assertEquals(ExchangeItems.fields(selected.get("PLACEMENT:" + id)), ExchangeItems.fields(snapshot.items().get("PLACEMENT:" + id)));
        assertEquals("package:b", snapshot.items().get("PLACEMENT:member:a").extensions().get("parent"));
    }

    @Test void removingADiagramOccurrenceDoesNotRemovePackageMembership() {
        var selected = ExchangeItems.flatten(source());
        var mappings = bindings(selected);
        String before = manuallyProjectedDsl(selected);
        var incoming = new TreeMap<>(selected);
        incoming.remove("PLACEMENT:diagram:b");

        String after = commands.applyAll(before, domain.architectureCommands(connection, before, selected, incoming, mappings)).dsl();
        var second = commands.model(after).getElements().stream().filter(e -> e.getTitle().equals("Second")).findFirst().orElseThrow();
        assertEquals(identity(selected.get("SPECIFICATION:package-a")), second.getPackageId());
        var view = ArchitectureSemanticPatch.index(after).get("view:" + identity(selected.get("VIEW:view-a")));
        assertFalse(view.propertyValues("include").contains(second.getId()));
    }

    @Test void editingDiagramGeometryCannotMoveAnElementToThePackageRoot() {
        var selected = ExchangeItems.flatten(source());
        String before = manuallyProjectedDsl(selected);
        var incoming = new TreeMap<>(selected);
        Artifact occurrence = incoming.get("PLACEMENT:diagram:a");
        incoming.put("PLACEMENT:diagram:a", new Artifact(occurrence.id(), occurrence.kind(), occurrence.type(), occurrence.title(), occurrence.text(),
                Map.of("placementKind", "diagram", "geometry", "left=900;top=60"), occurrence.extensions()));

        String after = commands.applyAll(before, domain.architectureCommands(connection, before, selected, incoming, bindings(selected))).dsl();
        var first = commands.model(after).getElements().stream().filter(e -> e.getTitle().equals("First")).findFirst().orElseThrow();
        assertEquals(identity(selected.get("SPECIFICATION:package-a")), first.getPackageId());
    }

    @Test void packageSnapshotsDoNotDependOnPlacementIdentifierSortOrder() {
        var original = source();
        var placements = original.placements().stream().map(p -> new Placement(
                p.id().startsWith("diagram:") ? "z-" + p.id() : p.id(), p.containerId(),
                p.parentId() != null && p.parentId().startsWith("diagram:") ? "z-" + p.parentId() : p.parentId(),
                p.artifactId(), p.position(), p.attributes())).toList();
        var source = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), original.completeScope(),
                original.source(), original.artifacts(), original.relations(), placements, original.metadata(), original.losses());
        var selected = ExchangeItems.flatten(source);
        var snapshot = domain.snapshot(context, connection, bindings(selected), document(manuallyProjectedDsl(selected)));

        for (var entry : selected.entrySet()) if (entry.getValue().kind() == ArtifactKind.PLACEMENT)
            assertEquals(ExchangeItems.fields(entry.getValue()), ExchangeItems.fields(snapshot.items().get(entry.getKey())), entry.getKey());
    }

    @Test void localViewMembershipChangesDoNotRewritePackageEvidence() {
        var selected = ExchangeItems.flatten(source());
        String first = identity(selected.get("ELEMENT:element-a"));
        String view = identity(selected.get("VIEW:view-a"));
        String dsl = commands.apply(manuallyProjectedDsl(selected),
                new UpsertArchitectureView(view, "Overview", "", List.of(first), Map.of())).dsl();
        var snapshot = domain.snapshot(context, connection, bindings(selected), document(dsl));

        assertFalse(snapshot.items().containsKey("PLACEMENT:diagram:b"));
        assertEquals(ExchangeItems.fields(selected.get("PLACEMENT:member:b")),
                ExchangeItems.fields(snapshot.items().get("PLACEMENT:member:b")));
        assertEquals(ExchangeItems.fields(selected.get("PLACEMENT:diagram:a")),
                ExchangeItems.fields(snapshot.items().get("PLACEMENT:diagram:a")));
        assertEquals(ExchangeItems.fields(selected.get("PLACEMENT:diagram:a:copy")),
                ExchangeItems.fields(snapshot.items().get("PLACEMENT:diagram:a:copy")));
    }

    @Test void unknownTypesRequireAnExplicitCanonicalMapping() {
        Artifact unsupported = new Artifact("external", ArtifactKind.ELEMENT, "VendorSpecific", "Unknown", "", Map.of(), Map.of());
        var problem = assertThrows(IntegrationProblem.class, () -> domain.architectureCommands(connection, "", Map.of(),
                Map.of("ELEMENT:external", unsupported), List.of()));
        assertEquals("UNSUPPORTED_ELEMENT_TYPE", problem.code());
    }

    @Test void interleavedRequirementsDoNotInventLocalOrderChangesOrReimportConflicts() {
        var original = sourceWithInterleavedRequirements();
        var selected = ExchangeItems.flatten(original);
        String imported = commands.applyAll("", domain.architectureCommands(connection, "", Map.of(), selected, List.of())).dsl();
        var mappings = bindings(selected);
        var observed = domain.snapshot(context, connection, mappings, document(imported)).items();
        assertEquals("1", observed.get("PLACEMENT:member:a").extensions().get("position"));
        assertEquals("3", observed.get("PLACEMENT:member:b").extensions().get("position"));

        // Only the source requirement crosses A. Native component order stays A,B.
        var reordered = new TreeMap<>(selected);
        position(reordered, "member:a", 2);
        position(reordered, "requirement:second", 1);
        var incoming = ExchangeItems.expand(original, reordered);
        var delta = new IntegrationDiff(json).compare(incoming, AuthorityMode.BIDIRECTIONAL, mappings, observed);
        var move = delta.stream().filter(c -> c.externalId().equals("PLACEMENT:member:a")).findFirst().orElseThrow();
        assertEquals(ChangeKind.MOVE, move.kind());
        assertTrue(move.conflicts().isEmpty());

        String applied = commands.applyAll(imported, domain.architectureCommands(connection, imported, observed, reordered, mappings)).dsl();
        var model = commands.model(applied);
        assertEquals(0, model.getElements().stream().filter(e -> e.getTitle().equals("First")).findFirst().orElseThrow().getPackagePosition());
        assertEquals(1, model.getElements().stream().filter(e -> e.getTitle().equals("Second")).findFirst().orElseThrow().getPackagePosition());
        var after = domain.snapshot(context, connection, bindings(reordered), document(applied)).items();
        assertEquals("2", after.get("PLACEMENT:member:a").extensions().get("position"));
        assertEquals("3", after.get("PLACEMENT:member:b").extensions().get("position"));
    }

    @Test void realNativeReorderingUsesTheOriginalSourceSlotsAroundRequirements() {
        var original = sourceWithInterleavedRequirements();
        var selected = ExchangeItems.flatten(original);
        String imported = commands.applyAll("", domain.architectureCommands(connection, "", Map.of(), selected, List.of())).dsl();
        String parent = identity(selected.get("SPECIFICATION:package-a"));
        String changed = commands.apply(imported, new SetArchitecturePackagePlacements(List.of(
                new PackagePlacement(PackageMemberKind.ELEMENT, identity(selected.get("ELEMENT:element-b")), parent, 0),
                new PackagePlacement(PackageMemberKind.ELEMENT, identity(selected.get("ELEMENT:element-a")), parent, 1)), Set.of(parent))).dsl();
        var current = domain.snapshot(context, connection, bindings(selected), document(changed)).items();
        assertEquals("3", current.get("PLACEMENT:member:a").extensions().get("position"));
        assertEquals("1", current.get("PLACEMENT:member:b").extensions().get("position"));
        for (String id : List.of("requirement:first", "requirement:second"))
            assertEquals(ExchangeItems.fields(selected.get("PLACEMENT:" + id)), ExchangeItems.fields(current.get("PLACEMENT:" + id)));
        var incoming = new TreeMap<>(selected);
        position(incoming, "member:a", 2);
        position(incoming, "requirement:second", 1);
        var delta = new IntegrationDiff(json).compare(ExchangeItems.expand(original, incoming), AuthorityMode.BIDIRECTIONAL,
                bindings(selected), current);
        assertEquals(ChangeKind.CONFLICT, delta.stream().filter(c -> c.externalId().equals("PLACEMENT:member:a"))
                .findFirst().orElseThrow().kind(), "A real independent local reorder must remain visible");
    }

    private ExchangeDocument sourceWithInterleavedRequirements() {
        var original = source();
        var artifacts = new ArrayList<>(original.artifacts());
        artifacts.add(new Artifact("requirement-first", ArtifactKind.REQUIREMENT, "Requirement", "First requirement", "", Map.of(), Map.of()));
        artifacts.add(new Artifact("requirement-second", ArtifactKind.REQUIREMENT, "Requirement", "Second requirement", "", Map.of(), Map.of()));
        var placements = new ArrayList<>(original.placements().stream().map(p -> new Placement(p.id(), p.containerId(), p.parentId(), p.artifactId(),
                p.id().equals("member:a") ? 1 : p.id().equals("member:b") ? 3 : p.position(), p.attributes())).toList());
        placements.add(new Placement("requirement:first", "model", "package:a", "requirement-first", 0, Map.of("placementKind", "package")));
        placements.add(new Placement("requirement:second", "model", "package:a", "requirement-second", 2, Map.of("placementKind", "package")));
        return new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), original.completeScope(), original.source(),
                artifacts, original.relations(), placements, original.metadata(), original.losses());
    }

    private void position(Map<String, Artifact> items, String id, int position) {
        Artifact old = items.get("PLACEMENT:" + id);
        var extensions = new TreeMap<>(old.extensions()); extensions.put("position", Integer.toString(position));
        items.put("PLACEMENT:" + id, new Artifact(old.id(), old.kind(), old.type(), old.title(), old.text(), old.attributes(), extensions));
    }

    private ExchangeDocument source() {
        return new ExchangeDocument("example-model", "1", "source-revision", true, "",
                List.of(new Artifact("package-a", ArtifactKind.SPECIFICATION, "Package", "Package A", "", Map.of(), Map.of()),
                        new Artifact("package-b", ArtifactKind.SPECIFICATION, "Package", "Package B", "", Map.of(), Map.of()),
                        new Artifact("element-a", ArtifactKind.ELEMENT, "Class", "First", "", Map.of("stereotype", "ExternalSystem"), Map.of("canonicalType", "System")),
                        new Artifact("element-b", ArtifactKind.ELEMENT, "Component", "Second", "", Map.of(), Map.of("canonicalType", "Component")),
                        new Artifact("view-a", ArtifactKind.VIEW, "Diagram", "Overview", "", Map.of(), Map.of())), List.of(),
                List.of(new Placement("package:a", "model", null, "package-a", 0, Map.of("placementKind", "package")),
                        new Placement("package:b", "model", null, "package-b", 1, Map.of("placementKind", "package")),
                        new Placement("member:a", "model", "package:a", "element-a", 0, Map.of("placementKind", "package")),
                        new Placement("member:b", "model", "package:a", "element-b", 1, Map.of("placementKind", "package")),
                        new Placement("diagram:a", "view-a", null, "element-a", 0, Map.of("placementKind", "diagram", "geometry", "left=20;top=40")),
                        new Placement("diagram:b", "view-a", "diagram:a", "element-b", 1, Map.of("placementKind", "diagram", "geometry", "left=80;top=60")),
                        new Placement("diagram:a:copy", "view-a", null, "element-a", 2, Map.of("placementKind", "diagram", "geometry", "left=220;top=40"))),
                Map.of("identifier", "model"), List.of());
    }

    private String manuallyProjectedDsl(Map<String, Artifact> selected) {
        String a = identity(selected.get("ELEMENT:element-a")), b = identity(selected.get("ELEMENT:element-b"));
        String p = identity(selected.get("SPECIFICATION:package-a")), q = identity(selected.get("SPECIFICATION:package-b"));
        List<ArchitectureCommand> changes = List.of(
                new CreateArchitecturePackage(p, Map.of("title", "Package A", "description", "")),
                new CreateArchitecturePackage(q, Map.of("title", "Package B", "description", "")),
                new CreateArchitectureElement(a, "System", Map.of("title", "First", "description", "")),
                new CreateArchitectureElement(b, "Component", Map.of("title", "Second", "description", "")),
                new SetArchitecturePackagePlacements(List.of(new PackagePlacement(PackageMemberKind.PACKAGE, p, "", 0),
                        new PackagePlacement(PackageMemberKind.PACKAGE, q, "", 1),
                        new PackagePlacement(PackageMemberKind.ELEMENT, a, p, 0),
                        new PackagePlacement(PackageMemberKind.ELEMENT, b, p, 1)), Set.of("", p)),
                new UpsertArchitectureView(identity(selected.get("VIEW:view-a")), "Overview", "", List.of(a, b), Map.of()));
        return commands.applyAll("", changes).dsl();
    }

    private String identity(Artifact artifact) { return domain.businessId(connection, null, artifact); }
    private List<Identity> bindings(Map<String, Artifact> items) {
        return items.entrySet().stream().map(e -> new Identity(e.getKey(), identity(e.getValue()), null, "source-revision", "fingerprint",
                e.getValue(), e.getValue(), UUID.randomUUID(), false)).toList();
    }
    private WorkspaceDocument document(String dsl) {
        return new WorkspaceDocument(new State(context.repositoryWorkspaceScopeKey(), "checkpoint", 1), dsl);
    }
    private LifecycleIntegrationConnector connector(String version, Set<Capability> capabilities) {
        return new LifecycleIntegrationConnector() {
            @Override public IntegrationDescriptor descriptor() {
                return new IntegrationDescriptor("example-model", version, "External model", capabilities, Set.of("application/octet-stream"));
            }
            @Override public ExchangeDocument previewInbound(InboundRequest request) { throw new UnsupportedOperationException(); }
            @Override public ExchangeFile previewOutbound(OutboundRequest request) { throw new UnsupportedOperationException(); }
        };
    }
}
