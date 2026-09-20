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

    @Test void v2PackagesAreNativeAndV1RetainsEvidenceOnly() {
        var pkg = new Artifact("pkg", ArtifactKind.SPECIFICATION, "Package", "Package", "Notes", Map.of(), Map.of());
        var selected = Map.of(ExchangeItems.key(pkg), pkg);
        assertTrue(domain.architectureCommands(connection, "", Map.of(), selected, List.of()).isEmpty());
        var v2 = new Connection(connection.id(), connection.organizationId(), connection.displayName(), connection.connectorId(), "2",
                connection.authority(), connection.externalScope(), null, null, 0, null, null, "alice");
        var planned = domain.architectureCommands(v2, "", Map.of(), selected, List.of());
        assertTrue(planned.stream().anyMatch(c -> c instanceof CreateArchitecturePackage));
    }

    @Test void requirementEndpointsRequireExplicitTypedProjectionAndRespectDirection() {
        var requirement = new Artifact("r", ArtifactKind.REQUIREMENT, "Class", "Need", "Body", Map.of(), Map.of());
        var element = new Artifact("e", ArtifactKind.ELEMENT, "Component", "E", "", Map.of(), Map.of("canonicalType", "Component"));
        var relation = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "r", "target", "e", "direction", "Source -> Destination"));
        var selected = Map.of(ExchangeItems.key(requirement), requirement, ExchangeItems.key(element), element, ExchangeItems.key(relation), relation);
        var plans = Map.of("REQUIREMENT:r", new IntegrationPortfolioPort.RequirementApplyPlan("P", "R", "P__R"));
        var endpoints = domain.indexEndpoints(connection, selected, List.of(), plans);
        assertEquals("SPARX_ENDPOINT_MAPPING_REQUIRED", assertThrows(IntegrationProblem.class,
                () -> domain.architectureCommands(connection, "", Map.of(), selected, List.of(), endpoints, Map.of())).code());
        String elementId = domain.businessId(connection, null, element);
        var choice = new EndpointOverride("P__R", elementId, RelationProjection.REQUIREMENT_MAPPING, null);
        var planned = domain.architectureCommands(connection, "", Map.of(), selected, List.of(), endpoints, Map.of("RELATION:c", choice));
        assertTrue(planned.stream().anyMatch(c -> c instanceof UpsertRequirementMapping));
        assertFalse(planned.stream().anyMatch(c -> c instanceof CreateArchitectureRelation));
        var preserved = domain.architectureCommands(connection, "", Map.of(), selected, List.of(), endpoints,
                Map.of("RELATION:c", new EndpointOverride(null, null, RelationProjection.PRESERVE_ONLY, null)));
        assertFalse(preserved.stream().anyMatch(c -> c instanceof UpsertRequirementMapping || c instanceof CreateArchitectureRelation));
    }

    @Test void oldReviewJsonAndConstructorKeepAnEmptyEndpointMap() {
        var json = new IntegrationJson(JsonMapper.builder().build());
        var old = new ReviewedChangeSet(UUID.randomUUID(), "fp", Map.of(), "Reviewed");
        assertTrue(old.endpoints().isEmpty());
        String legacy = "{\"operationId\":\"" + old.operationId() + "\",\"previewFingerprint\":\"fp\",\"decisions\":{},\"rationale\":\"Reviewed\"}";
        assertTrue(json.read(legacy, ReviewedChangeSet.class).endpoints().isEmpty());
    }

    @Test void finalPlanRejectsOwnedMappingsBeforeAnyPortfolioMutation() {
        var domain = new IntegrationDomainAdapter(org.mockito.Mockito.mock(IntegrationPortfolioPort.class), new IntegrationJson(JsonMapper.builder().build()));
        String dsl = "element e type Component {\n title: \"E\";\n}\nrequirement r {\n title: \"R\";\n}\nmapping r -> e {\n source: \"analysis-snapshot\";\n x-portfolio-managed: \"true\";\n}\n";
        assertEquals("REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT", assertThrows(IntegrationProblem.class,
                () -> domain.validateCompletePlan(dsl, List.of(new UpsertRequirementMapping("r", "e", "external", Map.of("x-exchange-id", "c"))), Map.of())).code());
    }

    @Test void endpointKindsCannotBeCoercedAndReverseAndBidirectionalRequireCorrectReview() {
        var index = new IntegrationDomainAdapter.EndpointIndex(Map.of(
                "r", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.REQUIREMENT, "req", null),
                "e", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.ARCHITECTURE_ELEMENT, "element", null),
                "e2", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.ARCHITECTURE_ELEMENT, "other", null),
                "p", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.PACKAGE, "package", null)));
        var relation = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "r", "target", "e", "direction", "Destination -> Source"));
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class, () -> domain.relationBusinessId(relation, index,
                new EndpointOverride("req", "element", RelationProjection.REQUIREMENT_MAPPING, null))).code());
        var validReverse = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "e", "target", "r", "direction", "Destination -> Source"));
        assertEquals("req -> element", domain.relationBusinessId(validReverse, index, new EndpointOverride("req", "element", RelationProjection.REQUIREMENT_MAPPING, null)));
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class, () -> domain.relationBusinessId(validReverse, index,
                new EndpointOverride("req", "other", RelationProjection.REQUIREMENT_MAPPING, null))).code());
        var packageRelation = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "p", "target", "e"));
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class, () -> domain.relationBusinessId(packageRelation, index,
                new EndpointOverride("element", "element", RelationProjection.ARCHITECTURE_RELATION, "RELATED_TO"))).code());
        var requirementPair = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "r", "target", "r"));
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class, () -> domain.relationBusinessId(requirementPair, index,
                new EndpointOverride("req", "req", RelationProjection.REQUIREMENT_MAPPING, null))).code());
        var both = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "r", "target", "e", "direction", "Bi-Directional"));
        assertEquals("SPARX_DIRECTION_UNMAPPED", assertThrows(IntegrationProblem.class, () -> domain.relationBusinessId(both, index,
                new EndpointOverride("req", "element", RelationProjection.REQUIREMENT_MAPPING, null))).code());
        assertEquals("preserved:c", domain.relationBusinessId(both, index, new EndpointOverride(null, null, RelationProjection.PRESERVE_ONLY, null)));
    }

    @Test void incomingProjectionEvidenceCannotGrantOrClearLocalApproval() {
        var asserted = new Artifact("r", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of(
                "source", "r", "target", "e", "nativeProjection", "REQUIREMENT_MAPPING", "nativeSource", "req", "nativeTarget", "element"));
        var unapproved = IntegrationService.reviewedEndpointAuthority(asserted, null);
        assertFalse(unapproved.extensions().containsKey("nativeProjection"));
        var incoming = new Artifact("r", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of("source", "r", "target", "e"));
        var retained = IntegrationService.reviewedEndpointAuthority(incoming, asserted);
        assertEquals("REQUIREMENT_MAPPING", retained.extensions().get("nativeProjection"));
        assertEquals("req", retained.extensions().get("nativeSource"));
        assertEquals("element", retained.extensions().get("nativeTarget"));
    }

    @Test void canonicalRelationTypeIsTheEffectiveTypeAfterRetainedEndpointApproval() {
        var index = new IntegrationDomainAdapter.EndpointIndex(Map.of(
                "a", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.ARCHITECTURE_ELEMENT, "native-a", null),
                "b", new IntegrationDomainAdapter.EndpointRef(IntegrationDomainAdapter.EndpointKind.ARCHITECTURE_ELEMENT, "native-b", null)));
        var remapped = new Artifact("c", ArtifactKind.RELATION, "Dependency", "", "", Map.of(), Map.of(
                "source", "a", "target", "b", "canonicalType", "DEPENDS_ON",
                "nativeProjection", "ARCHITECTURE_RELATION", "nativeSource", "native-a", "nativeTarget", "native-b", "nativeType", "RELATED_TO"));
        assertEquals("native-a DEPENDS_ON native-b", domain.relationBusinessId(remapped, index, null));
        assertEquals("native-a DEPENDS_ON native-b", domain.relationBusinessId(remapped, index,
                new EndpointOverride("native-a", "native-b", RelationProjection.ARCHITECTURE_RELATION, null)));
    }

    private WorkspaceDocument document(String dsl) { return new WorkspaceDocument(new State("scope", "head", 1), dsl); }
}
