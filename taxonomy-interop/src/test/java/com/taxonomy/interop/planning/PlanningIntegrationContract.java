package com.taxonomy.interop.planning;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.planning.*;
import com.taxonomy.exchange.PlanningEnvelope;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.IntegrationPortfolioPort.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureIntegrationPort.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlanningIntegrationContract {
    private static int count;
    public static void main(String[] args) { run(); System.out.println("PlanningIntegrationContract: " + count + " assertions passed"); }
    public static void run() {
        count = 0;
        String base = "requirement R {\n  title: \"Title\";\n  text: \"Original\";\n  x-project-key: \"P\";\n  x-requirement-key: \"REQ\";\n}\n";
        var registry = new PlanningInformation();
        String dsl = registry.upsert(base, "R", new PlanningEntry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2031")), false);
        String old = PlanningEnvelope.write(List.of(new PlanningEnvelope.Entry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2030"))));
        Artifact artifact = new Artifact("external", ArtifactKind.REQUIREMENT, "taxonomy-object", "Title", "Original", Map.of(), Map.of(PlanningEnvelope.EXTENSION, old));
        var req = new RequirementData(42L, "REQ", "Title", "APPROVED", 1L, Instant.EPOCH, new VersionData("Original", Instant.EPOCH));
        IntegrationPortfolioPort portfolio = (IntegrationPortfolioPort) Proxy.newProxyInstance(IntegrationPortfolioPort.class.getClassLoader(), new Class<?>[]{IntegrationPortfolioPort.class}, (proxy, method, args) -> switch (method.getName()) {
            case "listRequirements" -> List.of(req);
            case "getProject" -> new ProjectData(1L, "Project", "P");
            case "getRequirement" -> req;
            case "planRequirementApply" -> new RequirementApplyPlan("P", "REQ", "R");
            default -> throw new AssertionError("Unexpected portfolio operation " + method.getName());
        });
        var adapter = new IntegrationDomainAdapter(portfolio, new IntegrationJson(new tools.jackson.databind.ObjectMapper()));
        var context = RepositoryContext.workspace("repo", "workspace", "main", "alice");
        var connection = new Connection(UUID.randomUUID(), "org", "Test", ReqifExchangeCodec.PROFILE, "1", AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("test", "repository", "current"), 1L, null, 0L, null, null, "alice");
        var identity = new Identity("REQUIREMENT:external", "REQ", 42L, "remote-v1", "fingerprint", artifact, artifact, UUID.randomUUID(), false);
        var document = new WorkspaceDocument(new State("workspace", null, 1L), dsl);
        var snapshot = adapter.snapshot(context, connection, List.of(identity), document);
        var current = snapshot.items().get("REQUIREMENT:external");
        equal("2031", PlanningEnvelope.read(current.extensions().get(PlanningEnvelope.EXTENSION)).getFirst().values().get("value"));
        equal("P", snapshot.projectKey());
        equal("2030", PlanningEnvelope.read(identity.external().extensions().get(PlanningEnvelope.EXTENSION)).getFirst().values().get("value"));
        var noPlanning = new Artifact(artifact.id(), artifact.kind(), artifact.type(), artifact.title(), artifact.text(), Map.of(), Map.of());
        var retained = PlanningExchangeBridge.retainOmissions(noPlanning, current);
        equal("2031", PlanningEnvelope.read(retained.extensions().get(PlanningEnvelope.EXTENSION)).getFirst().values().get("value"));
        check(!noPlanning.extensions().containsKey(PlanningEnvelope.EXTENSION), "External observation was mutated");
        var imported = new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", "incoming", false, "", List.of(noPlanning), List.of(), List.of(), Map.of(), List.of());
        var described = PlanningExchangeBridge.describeInbound(imported, snapshot.items());
        check(described.losses().stream().anyMatch(l -> l.code().equals("PLANNING_OMISSION_RETAINED")), "Omission loss missing");
        equal(imported.artifacts(), described.artifacts());
        var selected = Map.of("REQUIREMENT:external", retained);
        var plans = adapter.planRequirements(context, connection, selected, List.of(identity), dsl);
        String applied = base;
        for (ArchitectureCommand command : PlanningExchangeBridge.commands(selected, plans)) applied = new ArchitectureDslCommands().apply(applied, command).dsl();
        equal("2031", registry.read(applied, "R").getFirst().entry().values().get("value"));
        var exported = adapter.exportDocument(connection, snapshot, document, List.of(identity), null);
        var roundTrip = new ReqifExchangeCodec().read(new ReqifExchangeCodec().write(exported), "return", true);
        var returned = roundTrip.artifacts().stream().filter(a -> a.kind() == ArtifactKind.REQUIREMENT).findFirst().orElseThrow();
        equal("2031", PlanningEnvelope.read(returned.extensions().get(PlanningEnvelope.EXTENSION)).getFirst().values().get("value"));
        check(!registry.read(applied, "R").getFirst().entry().values().containsKey("operational"), "Goal upgraded to operation");
        // A format without a planning mapping must report omission for this project,
        // not silently drop the data or report another project's requirements.
        String foreignBase = base.replace("requirement R {", "requirement OTHER {")
                .replace("x-project-key: \"P\"", "x-project-key: \"OTHER-PROJECT\"");
        String scopedDsl = registry.upsert(dsl + foreignBase, "OTHER", new PlanningEntry(
                "other-launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2040")), false);
        var unsupported = new Connection(connection.id(), "org", "Test", "test-unmapped-format", "1",
                AuthorityMode.BIDIRECTIONAL, connection.externalScope(), 1L, null, 0L, null, null, "alice");
        var plainIdentity = new Identity("REQUIREMENT:external", "REQ", 42L, "remote-v1", "fingerprint",
                noPlanning, noPlanning, UUID.randomUUID(), false);
        var scopedDocument = new WorkspaceDocument(new State("workspace", null, 1L), scopedDsl);
        var unmappedSnapshot = adapter.snapshot(context, unsupported, List.of(plainIdentity), scopedDocument);
        var unmappedExport = adapter.exportDocument(unsupported, unmappedSnapshot, scopedDocument,
                List.of(plainIdentity), null);
        var planningLosses = unmappedExport.losses().stream()
                .filter(loss -> "PLANNING_FORMAT_UNSUPPORTED".equals(loss.code())).toList();
        equal(1, planningLosses.size());
        equal("R", planningLosses.getFirst().artifactId());
        equal(LossDisposition.UNSUPPORTED, planningLosses.getFirst().disposition());
        equal("P", unmappedSnapshot.projectKey());
        // A missing project key must never match an unscoped canonical requirement.
        String unscoped = dsl.replace("  x-project-key: \"P\";\n", "");
        rejects(() -> new PlanningExchangeBridge(unscoped).overlay(artifact, null, "REQ"));
        // Metadata-free imports must keep the pre-existing requirements-only flow.
        check(!PlanningExchangeBridge.hasEntries(Map.of("REQUIREMENT:external", noPlanning)), "Empty import enables planning commands");
        check(PlanningExchangeBridge.hasEntries(selected), "Planning import was not detected");
        // Retained LOCAL data is not authority to generate incoming planning commands.
        var textOnlyChange = new IntegrationChange("change", "REQUIREMENT:external", ChangeKind.UPDATE,
                java.util.Set.of("text"), "before", "after", current, noPlanning, List.of());
        var acceptedText = PlanningExchangeBridge.acceptedIncoming(List.of(textOnlyChange),
                Map.of("change", Decision.ACCEPT), selected);
        equal(Map.of(), acceptedText);
        equal(List.of(), PlanningExchangeBridge.commands(acceptedText, Map.of()));
        var planningChange = new IntegrationChange("change", "REQUIREMENT:external", ChangeKind.UPDATE,
                java.util.Set.of("planning"), "before", "after", current, artifact, List.of());
        for (Decision decision : List.of(Decision.REJECT, Decision.KEEP_INTERNAL))
            equal(Map.of(), PlanningExchangeBridge.acceptedIncoming(List.of(planningChange), Map.of("change", decision), selected));
        equal(Map.of(), PlanningExchangeBridge.acceptedIncoming(List.of(planningChange), Map.of(), selected));
        var localExtra = new Artifact("external", artifact.kind(), artifact.type(), "Reviewed title", "Reviewed text",
                Map.of(), Map.of(PlanningEnvelope.EXTENSION, PlanningEnvelope.write(List.of(
                        new PlanningEnvelope.Entry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2031")),
                        new PlanningEnvelope.Entry("local-only", "other", "1", "MANUAL", Map.of())))));
        var mixedSelection = Map.of("REQUIREMENT:external", localExtra, "REQUIREMENT:other", current);
        for (Decision decision : List.of(Decision.ACCEPT, Decision.TAKE_EXTERNAL)) {
            var incoming = PlanningExchangeBridge.acceptedIncoming(List.of(planningChange), Map.of("change", decision), mixedSelection);
            equal(java.util.Set.of("REQUIREMENT:external"), incoming.keySet());
            equal("Reviewed title", incoming.get("REQUIREMENT:external").title());
            equal(old, incoming.get("REQUIREMENT:external").extensions().get(PlanningEnvelope.EXTENSION));
            var incomingCommands = PlanningExchangeBridge.commands(incoming, plans);
            equal(1, incomingCommands.size());
            equal("launch", ((ArchitectureCommand.ImportRequirementPlanning) incomingCommands.getFirst()).entry().id());
        }
        var emptyRemote = new Artifact("external", artifact.kind(), artifact.type(), "Title", "Original", Map.of(),
                Map.of(PlanningEnvelope.EXTENSION, PlanningEnvelope.write(List.of())));
        var emptyChange = new IntegrationChange("change", "REQUIREMENT:external", ChangeKind.UPDATE,
                java.util.Set.of("planning"), "before", "after", current, emptyRemote, List.of());
        equal(Map.of(), PlanningExchangeBridge.acceptedIncoming(List.of(emptyChange), Map.of("change", Decision.ACCEPT), selected));
        rejects(() -> PlanningExchangeBridge.acceptedIncoming(List.of(planningChange), Map.of("change", Decision.ACCEPT), Map.of()));
        // Neither local omission retention nor review extraction rewrites remote observations.
        check(!textOnlyChange.after().extensions().containsKey(PlanningEnvelope.EXTENSION), "Remote omission was acknowledged");
        equal(old, planningChange.after().extensions().get(PlanningEnvelope.EXTENSION));
        // Entry order is not semantic: the same valid envelope may arrive unsorted.
        String reversedPayload = """
                {"schema":1,"entries":[
                  {"id":"z","profile":"other","version":"1","origin":"external","values":{}},
                  {"id":"a","profile":"other","version":"1","origin":"external","values":{}}
                ]}
                """;
        var reordered = new Artifact("external", ArtifactKind.REQUIREMENT, "taxonomy-object", "Title", "Original",
                Map.of(), Map.of(PlanningEnvelope.EXTENSION, reversedPayload));
        var reorderedDocument = new ExchangeDocument(ReqifExchangeCodec.PROFILE, "1", "incoming", false, "",
                List.of(reordered), List.of(), List.of(), Map.of(), List.of());
        var reorderedDescription = PlanningExchangeBridge.describeInbound(reorderedDocument,
                Map.of("REQUIREMENT:external", PlanningExchangeBridge.retainOmissions(reordered, reordered)));
        equal(0L, reorderedDescription.losses().stream().filter(l -> "PLANNING_OMISSION_RETAINED".equals(l.code())).count());
        equal(reversedPayload, reorderedDescription.artifacts().getFirst().extensions().get(PlanningEnvelope.EXTENSION));
        // Limit the total number of per-entry commands before any accepted mutation.
        var many = new java.util.LinkedHashMap<String, Artifact>();
        var manyPlans = new java.util.LinkedHashMap<String, RequirementApplyPlan>();
        var entries = new java.util.ArrayList<PlanningEnvelope.Entry>();
        for (int i = 0; i < 32; i++) entries.add(new PlanningEnvelope.Entry("p" + i, "other", "1", "external", Map.of()));
        for (int i = 0; i < 63; i++) {
            String id = "R" + i;
            many.put("REQUIREMENT:" + id, new Artifact(id, ArtifactKind.REQUIREMENT, "", "", "", Map.of(), Map.of(PlanningEnvelope.EXTENSION, PlanningEnvelope.write(entries))));
            manyPlans.put("REQUIREMENT:" + id, new RequirementApplyPlan("P", id, id));
        }
        rejects(() -> PlanningExchangeBridge.commands(many, manyPlans));
    }
    private static void rejects(Runnable action) { count++; try { action.run(); } catch (IntegrationProblem expected) { return; } throw new AssertionError("Expected a bounded integration error"); }
    private static void check(boolean condition, String message) { count++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + " but got " + actual); }
}
