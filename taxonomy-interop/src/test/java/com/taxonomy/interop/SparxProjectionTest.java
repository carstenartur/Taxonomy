package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.exchange.sparx.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SparxProjectionTest {
    private final IntegrationDomainAdapter domain = new IntegrationDomainAdapter(null, new IntegrationJson(JsonMapper.builder().build()));
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private final Connection connection = new Connection(UUID.randomUUID(), "USER:alice", "Sparx", SparxMappingProfile.PROFILE, "1",
            AuthorityMode.BIDIRECTIONAL, new ExternalScope("SPARX", "ea-model", null), null, null, 0, null, null, "alice");
    private final RepositoryContext context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");

    @Test void nativeExportBindsStableGuidsToOriginalElementsAndRelations() {
        String dsl = commands.apply("", new CreateArchitectureElement("arch-system", "Component", Map.of("title", "System"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitectureElement("arch-component", "Component", Map.of("title", "Component"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitectureRelation(new RelationKey("arch-system", "DEPENDS_ON", "arch-component"), "accepted")).dsl();
        var doc = document(dsl);
        var snapshot = domain.snapshot(context, connection, List.of(), doc);
        var outgoing = domain.exportDocument(connection, snapshot, doc, List.of(), null);
        var codec = new SparxXmiCodec();
        var normalized = codec.read(codec.write(outgoing), "v1", true);
        var bindings = domain.exportBindings(connection, snapshot, doc, ExchangeItems.flatten(normalized));
        assertEquals(Set.of("arch-system", "arch-component"), bindings.values().stream().map(Applied -> Applied.businessIdentity())
                .filter(id -> id.startsWith("arch-") && !id.contains(" ")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("DEPENDS_ON", normalized.relations().getFirst().extensions().get("canonicalType"));
        assertEquals(outgoing.artifacts().stream().map(Artifact::id).toList(),
                domain.exportDocument(connection, snapshot, doc, List.of(), null).artifacts().stream().map(Artifact::id).toList());
    }

    @Test void localTypeAndDescriptionEditsSurviveRoundTripWithoutArchiMateCoercion() {
        String id = "{11111111-1111-4111-8111-111111111111}";
        var original = new Artifact(id, ArtifactKind.ELEMENT, "Class", "System", "Body", Map.of(), Map.of("canonicalType", "System", "stereotype", "System"));
        var items = Map.of(ExchangeItems.key(original), original);
        String dsl = "";
        for (var command : domain.architectureCommands(connection, dsl, Map.of(), items, List.of())) dsl = commands.apply(dsl, command).dsl();
        String internal = domain.businessId(connection, null, original);
        var mapping = new Identity(ExchangeItems.key(original), internal, null, "v1", "fp", original, original, UUID.randomUUID(), false);
        dsl = commands.apply(dsl, new UpdateArchitectureElement(internal, "Process", Map.of("title", "Local name", "description", "Local body"))).dsl();
        var current = domain.snapshot(context, connection, List.of(mapping), document(dsl));
        var updated = current.items().get(ExchangeItems.key(original));
        assertEquals("Activity", updated.type());
        assertEquals("Process", updated.attributes().get("tag:taxonomy.elementType"));
        assertEquals("Local body", updated.text());
    }

    @Test void importedRelationsAreRemovedFromSnapshotAfterLocalDeletion() {
        var a = new Artifact("{11111111-1111-4111-8111-111111111111}", ArtifactKind.ELEMENT, "Component", "A", "", Map.of(), Map.of("canonicalType", "Component"));
        var b = new Artifact("{22222222-2222-4222-8222-222222222222}", ArtifactKind.ELEMENT, "Component", "B", "", Map.of(), Map.of("canonicalType", "Component"));
        var r = new Artifact("{44444444-4444-4444-8444-444444444444}", ArtifactKind.RELATION, "Dependency", "", "", Map.of(),
                Map.of("canonicalType", "DEPENDS_ON", "source", a.id(), "target", b.id(), "direction", "Source -> Destination"));
        var items = Map.of(ExchangeItems.key(a), a, ExchangeItems.key(b), b, ExchangeItems.key(r), r);
        String dsl = "";
        for (var command : domain.architectureCommands(connection, dsl, Map.of(), items, List.of())) dsl = commands.apply(dsl, command).dsl();
        List<Identity> mappings = items.values().stream().map(v -> new Identity(ExchangeItems.key(v), v.kind() == ArtifactKind.RELATION
                ? domain.relationBusinessId(v, connection, items, List.of()) : domain.businessId(connection, null, v), null, "v1", "fp", v, v, UUID.randomUUID(), false)).toList();
        String relation = domain.relationBusinessId(r, connection, items, List.of());
        String[] parts = relation.split(" ");
        dsl = commands.apply(dsl, new DeleteArchitectureRelation(new RelationKey(parts[0], parts[1], parts[2]))).dsl();
        assertFalse(domain.snapshot(context, connection, mappings, document(dsl)).items().containsKey(ExchangeItems.key(r)));
    }

    @Test void reverseDirectionProjectsTheCorrectNativeEndpointsAndBidirectionalRequiresReview() {
        var a = new Artifact("a", ArtifactKind.ELEMENT, "Component", "A", "", Map.of(), Map.of("canonicalType", "Component"));
        var b = new Artifact("b", ArtifactKind.ELEMENT, "Component", "B", "", Map.of(), Map.of("canonicalType", "Component"));
        var reverse = new Artifact("r", ArtifactKind.RELATION, "Dependency", "", "", Map.of(),
                Map.of("canonicalType", "DEPENDS_ON", "source", "a", "target", "b", "direction", "Destination -> Source"));
        var selected = Map.of(ExchangeItems.key(a), a, ExchangeItems.key(b), b, ExchangeItems.key(reverse), reverse);
        assertEquals(domain.businessId(connection, null, b) + " DEPENDS_ON " + domain.businessId(connection, null, a),
                domain.relationBusinessId(reverse, connection, selected, List.of()));
        var both = new Artifact(reverse.id(), reverse.kind(), reverse.type(), "", "", Map.of(),
                Map.of("canonicalType", "DEPENDS_ON", "source", "a", "target", "b", "direction", "Bi-Directional"));
        assertEquals("SPARX_DIRECTION_UNMAPPED", assertThrows(IntegrationProblem.class,
                () -> domain.relationBusinessId(both, connection, selected, List.of())).code());
    }

    @Test void canonicalTypeChangesUpdateTheTagEvenWhenTheUmlTypeIsUnchanged() {
        var original = new Artifact("{11111111-1111-4111-8111-111111111111}", ArtifactKind.ELEMENT, "Component", "A", "",
                Map.of("tag:taxonomy.elementType", "Component"), Map.of("canonicalType", "Component"));
        var mapping = new Identity(ExchangeItems.key(original), "arch-a", null, "v1", "fp", original, original, UUID.randomUUID(), false);
        var document = document(commands.apply("", new CreateArchitectureElement("arch-a", "System", Map.of("title", "A"))).dsl());
        var current = domain.snapshot(context, connection, List.of(mapping), document);
        var outgoing = domain.exportDocument(connection, current, document, List.of(mapping), null);
        var returned = new SparxXmiCodec().read(new SparxXmiCodec().write(outgoing), "v2", true);
        assertEquals("System", returned.artifacts().getFirst().extensions().get("canonicalType"));
    }

    @Test void nativeExportWithLossesFreezesTheSameIdentityAsTheDeliveredFile() {
        var document = document(commands.apply("", new CreateArchitectureElement("arch-a", "Component", Map.of("title", "A"))).dsl());
        var current = domain.snapshot(context, connection, List.of(), document);
        var template = new ExchangeDocument(SparxMappingProfile.PROFILE, "1", null, true, "", List.of(), List.of(), List.of(),
                Map.of("identifier", "{00000000-0000-4000-8000-000000000000}"),
                List.of(new MappingLoss(null, "diagrams", "SPARX_LAYOUT_EXCLUDED", LossDisposition.UNSUPPORTED, "Excluded layout")));
        var outgoing = domain.exportDocument(connection, current, document, List.of(), template);
        var returned = com.taxonomy.interop.sparx.SparxSnapshots.identify(
                new SparxXmiCodec().read(new SparxXmiCodec().write(outgoing), "v1", true), connection.id());
        var mappings = ExchangeItems.flatten(outgoing).values().stream()
                .map(a -> new Identity(ExchangeItems.key(a), "arch-a", null, null, "fp", null, a, UUID.randomUUID(), false)).toList();
        var diff = new IntegrationDiff(new IntegrationJson(JsonMapper.builder().build()));
        var changes = diff.compare(returned, AuthorityMode.BIDIRECTIONAL, mappings, ExchangeItems.flatten(outgoing));
        assertTrue(changes.stream().noneMatch(c -> c.kind() == ChangeKind.CONFLICT), () -> changes.toString());
    }

    private WorkspaceDocument document(String dsl) { return new WorkspaceDocument(new State("scope", "head", 1), dsl); }
}
