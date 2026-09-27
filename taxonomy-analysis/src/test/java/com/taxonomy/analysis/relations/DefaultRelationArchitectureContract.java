package com.taxonomy.analysis.relations;

import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RelationSearchContract.check;

/** Executable production-boundary regressions, also discovered by ordinary JUnit. */
public final class DefaultRelationArchitectureContract {
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var method : DefaultRelationArchitectureContract.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try { method.invoke(null); passed++; }
            catch (ReflectiveOperationException error) { failed.add(method.getName() + ": " + error.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Default relation architecture contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }

    public static void testDefaultSpringConfigurationEnablesEvidenceSearch() {
        try (var context = context(Map.of())) {
            check(context.getBean(RequirementRelationSearchService.class).isEnabled(),
                    "An installation with no override must select evidence search");
        }
    }

    public static void testExplicitLegacyOverrideRemainsAvailable() {
        try (var context = context(Map.of("taxonomy.analysis.relations.hierarchical.enabled", "false"))) {
            check(!context.getBean(RequirementRelationSearchService.class).isEnabled(),
                    "Only an explicit operator override may select the legacy path");
        }
    }

    public static void testPlainServiceHasTheSameSafeDefault() {
        check(new RequirementRelationSearchService(null, null, null, null).isEnabled(),
                "Programmatic construction must agree with Spring's default");
    }

    public static void testReadOnlyApplicationCanDiscoverInformationAccess() {
        var report = application("The application reads evidence.");
        check(types(report).equals(Set.of("CONSUMES")),
                "An application may read information without inventing write access");
        check(report.result().edges().getFirst().sourceId().equals("process"),
                "The application remains the actual relationship source");
    }

    public static void testReadWriteApplicationRetainsDistinctAccessSemantics() {
        check(types(application("The application reads and writes evidence.")).equals(Set.of("CONSUMES", "PRODUCES")),
                "Read and write are independently justified, not collapsed into generic usage");
    }

    public static void testRuntimeAndDslBothAdmitApplicationReadsButNotReversedReads() {
        var rules = new RelationCompatibilityMatrix();
        check(rules.isCompatible("UA", "IP", RelationType.CONSUMES), "Runtime admits application reads");
        check(DslValidator.relationTypeRules().get("CONSUMES").getOrDefault("UA", Set.of()).contains("IP"),
                "Canonical DSL must not reject a relationship that analysis can produce");
        check(!rules.isCompatible("IP", "UA", RelationType.CONSUMES), "Do not reverse access semantics");
        check(!DslValidator.relationTypeRules().get("CONSUMES").getOrDefault("IP", Set.of()).contains("UA"),
                "The DSL keeps the same negative direction contract");
    }

    public static void testMissingPositiveSourcesAreNotACompleteArchitectureAssessment() {
        for (Map<String,Integer> scores : List.of(Map.<String,Integer>of(), Map.of("process", 0))) {
            var report = RequirementRelationSearchContract.session(prompt -> {
                throw new AssertionError("No model call without a concrete assessed source");
            }).search(RequirementRelationSearchContract.READ, scores, RequirementRelationSearchContract.OPTIONS);
            check(report.totalCalls() == 0 && report.result().edges().isEmpty(), "No fabricated work or relationships");
            check(!report.isSearchExhausted() && report.warnings().stream().anyMatch(w -> w.startsWith("SOURCE_DISCOVERY_REQUIRED")),
                    "Missing source coverage must be explicit, not semantic proof of absence");
        }
    }

    public static void testLimitedBudgetReachesDifferentSourcesBeforeExhaustingOneSource() {
        Node first = new Node("first", "BP", "First process", "", false);
        Node second = new Node("second", "BP", "Second process", "", false);
        var base = RequirementRelationSearchContract.catalogue();
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals("first") ? first : id.equals("second") ? second : base.find(id); }
            public List<Node> roots() { return base.roots(); }
            public List<Node> children(Node node) { return base.children(node); }
        };
        var search = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(),
                RequirementRelationSearchContract::answer, () -> {});
        var report = search.search("Each process reads and writes evidence.", Map.of("first", 100, "second", 50),
                new RequirementRelationSearch.Options(new Limits(7, 8, 10, 128), 16));
        Set<String> sources = new HashSet<>();
        report.result().edges().forEach(edge -> sources.add(edge.contribution().source().id()));
        check(sources.equals(Set.of("first", "second")),
                "Finish a promising branch, then admit the next source before unrelated relation types of the first source");
        check(report.totalCalls() == 7 && !report.isSearchExhausted(), "The existing total budget and partial status are retained");
    }

    private static RelationSearchReport application(String original) {
        var base = RequirementRelationSearchContract.catalogue();
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals("process")
                    ? new Node("process", "UA", "Evidence application", "Read or edit evidence", false) : base.find(id); }
            public List<Node> roots() { return base.roots(); }
            public List<Node> children(Node node) { return base.children(node); }
        };
        return new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(),
                RequirementRelationSearchContract::answer, () -> {}).search(original, Map.of("process", 1),
                RequirementRelationSearchContract.OPTIONS);
    }
    private static Set<String> types(RelationSearchReport report) {
        Set<String> types = new HashSet<>(); report.result().edges().forEach(edge -> types.add(edge.type())); return types;
    }
    private static AnnotationConfigApplicationContext context(Map<String,Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("contract", properties));
        // Construct only the subject bean: the test exercises real Spring property
        // binding and startup validation without bootstrapping unrelated JPA adapters.
        context.registerBean(RequirementRelationSearchService.class,
                () -> new RequirementRelationSearchService(null, null, null, null));
        context.refresh();
        return context;
    }
}
