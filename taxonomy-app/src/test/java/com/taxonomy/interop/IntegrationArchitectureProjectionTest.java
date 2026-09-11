package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.State;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.WorkspaceDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationArchitectureProjectionTest {
    private final IntegrationDomainAdapter domain = new IntegrationDomainAdapter(null, null, new IntegrationJson(JsonMapper.builder().build()));
    private final ArchiMateExchangeCodec codec = new ArchiMateExchangeCodec();
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private final RepositoryContext context = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
    private final Connection connection = new Connection(UUID.randomUUID(), "USER:alice", "Archi", ArchiMateExchangeCodec.PROFILE, "1",
            AuthorityMode.BIDIRECTIONAL, new ExternalScope("Archi", "model", null), null, null, 0, null, null, "alice");
    private WorkspaceDocument document(String dsl) { return new WorkspaceDocument(new State(RelationDecisionProjection.scopeKeyFor(context.workspaceId()), "checkpoint", 1), dsl); }
    private ExchangeDocument source() {
        List<Artifact> objects = List.of(
                new Artifact("system", ArtifactKind.ELEMENT, "ApplicationComponent", "System", "Body", Map.of(), Map.of("canonicalType", "System", "taxonomy:x-owner", "Alice")),
                new Artifact("component", ArtifactKind.ELEMENT, "ApplicationComponent", "Component", "", Map.of(), Map.of("canonicalType", "Component")),
                new Artifact("view", ArtifactKind.VIEW, "Diagram", "View", "View description", Map.of(), Map.of("connectionsXml",
                        "<connections xmlns=\"" + ArchiMateExchangeCodec.NS + "\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><connection identifier=\"visual-relation\" xsi:type=\"Relationship\" source=\"node-system\" target=\"node-component\" relationshipRef=\"relation\"/></connections>")));
        var input = new ExchangeDocument(ArchiMateExchangeCodec.PROFILE, "1", "v1", true, "", objects,
                List.of(new Relation("relation", "Composition", "system", "component", Map.of(), Map.of("canonicalType", "CONTAINS", "taxonomy:status", "accepted"))),
                List.of(new Placement("node-system", "view", null, "system", 0, Map.of("x", "0", "y", "0", "w", "160", "h", "70")),
                        new Placement("node-component", "view", null, "component", 1, Map.of("x", "180", "y", "0", "w", "160", "h", "70"))), Map.of(), List.of());
        return codec.read(codec.write(input), "v1", true);
    }
    private List<Identity> identities(Map<String, Artifact> items) {
        return items.entrySet().stream().map(e -> new Identity(e.getKey(), e.getValue().kind() == ArtifactKind.RELATION
                        ? domain.relationBusinessId(e.getValue(), connection, items, List.of()) : domain.businessId(connection, null, e.getValue()),
                null, "v1", "fingerprint", e.getValue(), e.getValue(), UUID.randomUUID(), false)).toList();
    }
    @Test void unsupportedNativeRelationsRemainVisibleForExplicitExportReview() {
        String dsl = commands.apply("", new CreateArchitectureElement("arch-a", "CoreService", Map.of("title", "First"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitectureElement("arch-b", "CoreService", Map.of("title", "Second"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitectureRelation(new RelationKey("arch-a", "DEPENDS_ON", "arch-b"), "accepted")).dsl();
        var current = domain.snapshot(context, connection, List.of(), document(dsl));
        var export = domain.exportDocument(connection, current, document(dsl), List.of(), null);
        assertEquals(1, export.relations().size());
        assertTrue(export.losses().stream().anyMatch(loss -> loss.disposition() == LossDisposition.UNSUPPORTED && loss.artifactId().equals(export.relations().getFirst().id())));
        assertThrows(com.taxonomy.exchange.ExchangeFormatException.class, () -> codec.write(export), "An unsupported relation must not silently become Association");
    }
    @Test void localPropertiesAndViewMembershipOverrideOldEvidenceAndKeepUnmappedLocalMembers() {
        var source = source(); Map<String, Artifact> baseline = ExchangeItems.flatten(source); List<Identity> mappings = identities(baseline);
        String dsl = "# Preserve unrelated source\n";
        for (var command : domain.architectureCommands(connection, dsl, Map.of(), baseline, List.of())) dsl = commands.apply(dsl, command).dsl();
        String system = domain.businessId(connection, null, baseline.get("ELEMENT:system"));
        String view = domain.businessId(connection, null, baseline.get("VIEW:view"));
        assertEquals("Alice", ArchitectureSemanticPatch.index(dsl).get("element:" + system).property("x-owner"));
        assertEquals("View description", ArchitectureSemanticPatch.index(dsl).get("view:" + view).property("description"));
        dsl = commands.apply(dsl, new ClearArchitectureElementProperties(system, Set.of("x-owner"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitectureElement("arch-local", "Component", Map.of("title", "Local addition"))).dsl();
        dsl = commands.apply(dsl, new UpsertArchitectureView(view, "Local view", "Local description", List.of(system, "arch-local"), Map.of())).dsl();
        var current = domain.snapshot(context, connection, mappings, document(dsl));
        var exported = domain.exportDocument(connection, current, document(dsl), mappings, source);
        var returned = codec.read(codec.write(exported), null, true);
        Artifact returnedSystem = returned.artifacts().stream().filter(a -> a.id().equals("system")).findFirst().orElseThrow();
        assertFalse(returnedSystem.extensions().containsKey("taxonomy:x-owner"), "Cleared properties must not be resurrected from old XML");
        assertEquals("Local description", returned.artifacts().stream().filter(a -> a.id().equals("view")).findFirst().orElseThrow().text());
        assertFalse(returned.placements().stream().anyMatch(p -> p.containerId().equals("view") && p.artifactId().equals("component")));
        assertTrue(exported.losses().stream().anyMatch(l -> l.artifactId().equals("visual-relation") && l.code().equals("LOCAL_DEPENDENCY_REMOVED")));
        Artifact nativeElement = returned.artifacts().stream().filter(a -> a.title().equals("Local addition")).findFirst().orElseThrow();
        assertTrue(returned.placements().stream().anyMatch(p -> p.containerId().equals("view") && p.artifactId().equals(nativeElement.id())));
        var selected = new TreeMap<>(current.items()); Artifact remoteView = selected.get("VIEW:view");
        selected.put("VIEW:view", new Artifact(remoteView.id(), remoteView.kind(), remoteView.type(), "Remote view title", remoteView.text(), remoteView.attributes(), remoteView.extensions()));
        for (var command : domain.architectureCommands(connection, dsl, current.items(), selected, mappings)) dsl = commands.apply(dsl, command).dsl();
        assertTrue(ArchitectureSemanticPatch.index(dsl).get("view:" + view).propertyValues("include").contains("arch-local"));
        assertTrue(dsl.startsWith("# Preserve unrelated source\n"));
    }

    @Test void removingAViewParentRetainsItsChildAndReportsTheLayoutTransformation() {
        var original = source();
        var nested = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), true, original.source(),
                original.artifacts(), original.relations(), original.placements().stream().map(p -> p.id().equals("node-component")
                        ? new Placement(p.id(), p.containerId(), "node-system", p.artifactId(), p.position(), p.attributes()) : p).toList(), original.metadata(), original.losses());
        nested = codec.read(codec.write(nested), "v1", true);
        Map<String, Artifact> baseline = ExchangeItems.flatten(nested); List<Identity> mappings = identities(baseline);
        String dsl = "";
        for (var command : domain.architectureCommands(connection, dsl, Map.of(), baseline, List.of())) dsl = commands.apply(dsl, command).dsl();
        String component = domain.businessId(connection, null, baseline.get("ELEMENT:component"));
        String view = domain.businessId(connection, null, baseline.get("VIEW:view"));
        dsl = commands.apply(dsl, new UpsertArchitectureView(view, "View", "View description", List.of(component), Map.of())).dsl();
        var current = domain.snapshot(context, connection, mappings, document(dsl));
        var exported = domain.exportDocument(connection, current, document(dsl), mappings, nested);
        assertTrue(exported.losses().stream().anyMatch(l -> l.artifactId().equals("node-component") && l.code().equals("VIEW_OCCURRENCE_REPARENTED")));
        var returned = codec.read(codec.write(exported), null, true);
        assertEquals(1, returned.placements().stream().filter(p -> p.containerId().equals("view")).count());
        var child = returned.placements().stream().filter(p -> p.id().equals("node-component")).findFirst().orElseThrow();
        assertNull(child.parentId()); assertEquals("component", child.artifactId());
        assertEquals(nested.placements().stream().filter(p -> p.id().equals(child.id())).findFirst().orElseThrow().attributes().get("x"), child.attributes().get("x"));
    }
}
