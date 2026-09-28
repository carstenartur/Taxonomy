package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import java.util.List;
import java.util.Map;

/** The JUnit wrapper and offline JDK runner execute the same behavioral contract. */
public final class PlanningProfileContract {
    private static final String BASE = "// retained header\nrequirement R {\n  title: \"Original\";\n  text: \"Original requirement text\";\n}\n";
    private static int assertions;
    public static void main(String[] args) { run(); System.out.println("PlanningProfileContract: " + assertions + " assertions passed"); }
    public static void run() {
        assertions = 0;
        PlanningInformation profiles = new PlanningInformation();
        PlanningEntry year = new PlanningEntry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2030"));
        String dsl = profiles.upsert(BASE, "R", year, false);
        equal(year, profiles.read(dsl, "R").getFirst().entry());
        check(dsl.contains("Original requirement text") && dsl.startsWith("// retained header"), "Original content changed");
        check(!dsl.contains("2030-01-01"), "Year was coerced");
        String reloaded = new TaxDslSerializer().serialize(new TaxDslParser().parse(dsl));
        equal(year, new PlanningInformation().read(reloaded, "R").getFirst().entry());
        equal(dsl, profiles.upsert(dsl, "R", year, false));
        check(ArchitectureSemanticPatch.between(BASE, dsl).stream().allMatch(c -> c.id().equals("requirement:R")), "Unexpected model changes");
        for (Map<String, String> invalid : List.of(Map.of("precision", "YEAR", "value", "2030-01-01"),
                Map.of("precision", "DATE", "value", "2030"), Map.of("precision", "DATE", "value", "2030-02-29"),
                Map.of("precision", "YEAR", "value", "0000"), Map.of("precision", "YEAR", "value", "2030", "compliant", "true")))
            rejects(() -> profiles.upsert(BASE, "R", new PlanningEntry("x", "go-live", "1", "MANUAL", invalid), false));
        var leap = new PlanningEntry("launch", "go-live", "1", "MANUAL", Map.of("precision", "DATE", "value", "2032-02-29"));
        equal(leap, profiles.read(profiles.upsert(BASE, "R", leap, false), "R").getFirst().entry());
        String goalDsl = dsl;
        rejects(() -> profiles.upsert(BASE, "OTHER", year, false));
        var future = new PlanningEntry("future", "go-live", "99", "external-A", Map.of("futureMeaning", "not known"));
        rejects(() -> profiles.upsert(BASE, "R", future, false));
        String unknown = profiles.upsert(BASE, "R", future, true);
        var unknownView = profiles.read(unknown, "R").getFirst();
        equal(future, unknownView.entry()); check(!unknownView.supported(), "Unknown version interpreted");
        equal("UNSUPPORTED_PROFILE_VERSION", unknownView.problem());
        rejects(() -> profiles.upsert(unknown, "R", new PlanningEntry("future", "go-live", "1", "MANUAL", year.values()), false));
        var ref = new PlanningEntry("norm", "standard-reference", "1", "requirement:R", Map.of("identifier", "INTERNAL-TEST-NORM", "edition", "2026", "section", "4.2"));
        String referenced = profiles.upsert(goalDsl, "R", ref, false);
        equal(ref, profiles.read(referenced, "R").stream().filter(v -> v.entry().id().equals("norm")).findFirst().orElseThrow().entry());
        var model = new ArchitectureDslCommands().model(referenced);
        equal(1, model.getSources().size()); equal(1, model.getSourceVersions().size()); equal(1, model.getSourceFragments().size()); equal(1, model.getRequirementSourceLinks().size());
        equal("references", model.getRequirementSourceLinks().getFirst().getLinkType());
        check(!referenced.contains("compliant") && !referenced.contains("certified"), "Norm reference upgraded to compliance");
        var stored = PlanningInformation.decode(model.getRequirements().getFirst().getExtensions()).stream().filter(e -> e.id().equals("norm")).findFirst().orElseThrow();
        equal(java.util.Set.of("linkId"), stored.values().keySet());
        equal(referenced, profiles.upsert(referenced, "R", ref, false));
        equal(goalDsl, new ArchitectureDslCommands().inverse(referenced, goalDsl, referenced).dsl());
        // An opaque newer version cannot delete the last authoritative source link.
        var futureReference = new PlanningEntry("norm", "standard-reference", "99", "external-A",
                Map.of("futureMeaning", "uninterpreted"));
        String newerUnknown = profiles.upsert(referenced, "R", futureReference, true);
        equal(1, new ArchitectureDslCommands().model(newerUnknown).getRequirementSourceLinks().size());
        String linkKey = "requirementSourceLink:" + model.getRequirementSourceLinks().getFirst().getId();
        equal(ArchitectureSemanticPatch.index(referenced).get(linkKey).getProperties().stream().map(p -> p.key() + "=" + p.value()).toList(),
                ArchitectureSemanticPatch.index(newerUnknown).get(linkKey).getProperties().stream().map(p -> p.key() + "=" + p.value()).toList());
        var futureView = profiles.read(newerUnknown, "R").stream().filter(v -> v.entry().id().equals("norm")).findFirst().orElseThrow();
        equal(futureReference, futureView.entry());
        check(!futureView.supported(), "Future reference must not be interpreted by v1");
        check(ArchitectureSemanticPatch.between(referenced, newerUnknown).stream()
                .allMatch(c -> "requirement:R".equals(c.id())), "Opaque import changed a canonical source object");
        equal(newerUnknown, profiles.upsert(newerUnknown, "R", futureReference, true));
        var unknownEdition = new PlanningEntry("norm", "standard-reference", "1", "MANUAL", Map.of("identifier", "TEST-DIRECTIVE", "section", "A.1"));
        String noEdition = profiles.upsert(BASE, "R", unknownEdition, false);
        equal(0, new ArchitectureDslCommands().model(noEdition).getSourceVersions().size());
        equal(unknownEdition, profiles.read(noEdition, "R").getFirst().entry());
        String removed = profiles.remove(referenced, "R", "norm");
        equal(0, new ArchitectureDslCommands().model(removed).getRequirementSourceLinks().size());
        equal(1, new ArchitectureDslCommands().model(removed).getSources().size()); // Never delete shared sources.
        equal(year, profiles.read(removed, "R").getFirst().entry());

        // Existing link evidence must survive a planning edit, and changed meaning must not be downgraded.
        String noted = referenced.replace("linkType: \"references\";", "linkType: \"references\";\n  note: \"Reviewed elsewhere\";");
        String changedRef = profiles.upsert(noted, "R", new PlanningEntry("norm", "standard-reference", "1", "MANUAL",
                Map.of("identifier", "INTERNAL-TEST-NORM", "edition", "2027", "section", "5.1")), false);
        equal("Reviewed elsewhere", new ArchitectureDslCommands().model(changedRef).getRequirementSourceLinks().getFirst().getNote());
        String reassessed = referenced.replace("linkType: \"references\";", "linkType: \"satisfies\";");
        rejects(() -> profiles.upsert(reassessed, "R", ref, false));
        // A later edition sharing the newly created source prevents undo from stranding that edition.
        String shared = referenced + "\nsourceVersion later-edition {\n  source: \"" + model.getSources().getFirst().getId()
                + "\";\n  versionLabel: \"2028\";\n}\n";
        rejects(() -> new ArchitectureDslCommands().inverse(shared, goalDsl, referenced));
        var broken = new ArchitectureDslCommands().model(referenced);
        broken.getSources().getFirst().setCanonicalIdentifier(null);
        var brokenView = profiles.read(broken, "R").stream().filter(v -> v.entry() != null && v.entry().id().equals("norm")).findFirst().orElseThrow();
        check(!brokenView.supported(), "Incomplete canonical source must remain unassessed");
        equal("INVALID_PROFILE_DATA", brokenView.problem());
        String malformed = BASE.replace("title: \"Original\";", "title: \"Original\";\n  x-planning.invalid.version: \"1\";");
        var malformedView = profiles.read(malformed, "R").getFirst();
        equal("INVALID_PLANNING_NAMESPACE", malformedView.problem());
        check(!malformedView.supported() && malformedView.entry() == null, "Invalid namespace must not invent an editable entry");
        // A third profile needs only an implementation and registry registration, not changes to storage/orchestration.
        PlanningProfile test = new PlanningProfile() {
            public Descriptor descriptor() { return new Descriptor("test-capacity", "1", List.of(new Field("count", true, List.of()))); }
            public void validate(Map<String, String> values) { if (!values.getOrDefault("count", "").matches("[1-9][0-9]*")) throw new IllegalArgumentException("count"); }
        };
        PlanningInformation extended = new PlanningInformation(List.of(new GoLiveProfile(), new StandardReferenceProfile(), test));
        var third = new PlanningEntry("capacity", "test-capacity", "1", "MANUAL", Map.of("count", "42"));
        String thirdDsl = extended.upsert(BASE, "R", third, false);
        equal(third, extended.read(thirdDsl, "R").getFirst().entry());
        check(!profiles.read(thirdDsl, "R").getFirst().supported(), "Uninstalled profile interpreted");
        equal(third, profiles.read(thirdDsl, "R").getFirst().entry());
        rejects(() -> new PlanningInformation(List.of(test, test)));
        rejects(() -> new PlanningEntry("bad.id", "go-live", "1", "MANUAL", Map.of()));
        rejects(() -> new PlanningEntry("bad", "go-live", "1", "MANUAL\nforged", Map.of()));
        var generated = ArchitectureSemanticPatch.index(BASE).get("requirement:R");
        var old = ArchitectureSemanticPatch.index(referenced).get("requirement:R");
        var retained = PlanningInformation.retain(generated, old);
        equal(2, PlanningInformation.decode(retained.getExtensions()).size());
        equal(generated.property("text"), retained.property("text"));
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + " but got " + actual); }
    private static void rejects(Runnable action) {
        assertions++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
}
