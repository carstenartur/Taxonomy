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
        // A missing project key must never match an unscoped canonical requirement.
        String unscoped = dsl.replace("  x-project-key: \"P\";\n", "");
        rejects(() -> new PlanningExchangeBridge(unscoped).overlay(artifact, null, "REQ"));
        // Metadata-free imports must keep the pre-existing requirements-only flow.
        check(!PlanningExchangeBridge.hasEntries(Map.of("REQUIREMENT:external", noPlanning)), "Empty import enables planning commands");
        check(PlanningExchangeBridge.hasEntries(selected), "Planning import was not detected");
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
