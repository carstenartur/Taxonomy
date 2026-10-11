package com.taxonomy.analysis.demo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies a scenario against the application's actual catalogue. Codes are never
 * invented: every scored, expanded, justified or related code must exist with the
 * recorded title, declared children must be real children, and every positively
 * scored non-leaf must have an authored expansion (otherwise replay would stop).
 */
public final class DemoCatalogueValidator {

    /** Read-only catalogue projection. */
    public interface CatalogueView {
        Optional<String> title(String code);
        List<String> children(String code);
    }

    private DemoCatalogueValidator() { }

    public static List<String> problems(DemoScenario scenario, CatalogueView catalogue) {
        var problems = new ArrayList<String>();
        scenario.catalogueAnchors().forEach((code, expected) -> {
            var actual = catalogue.title(code);
            if (actual.isEmpty()) problems.add("Unknown catalogue code " + code);
            else if (!expected.equals(actual.get()))
                problems.add("Catalogue mismatch for " + code + ": expected '" + expected + "' but found '" + actual.get()
                        + "' (scenario recorded " + scenario.catalogueSource() + ")");
        });
        for (var iteration : scenario.iterations().values()) {
            Set<String> used = new LinkedHashSet<>(iteration.roots().keySet());
            iteration.expansions().forEach((parent, children) -> {
                used.add(parent);
                used.addAll(children.keySet());
                var actualChildren = catalogue.children(parent);
                for (String child : children.keySet()) {
                    if (!actualChildren.contains(child))
                        problems.add(iteration.name() + ": " + child + " is not a catalogue child of " + parent);
                }
            });
            used.addAll(iteration.justifications().keySet());
            for (var relation : iteration.relations()) {
                used.add(relation.source());
                used.addAll(relation.path());
                for (int i = 1; i < relation.path().size(); i++) {
                    if (!catalogue.children(relation.path().get(i - 1)).contains(relation.path().get(i)))
                        problems.add(iteration.name() + ": relation path " + relation.path() + " is not a catalogue path");
                }
            }
            for (String code : used) {
                if (catalogue.title(code).isEmpty()) problems.add(iteration.name() + ": unknown catalogue code " + code);
                else if (!DemoScenario.ROOTS.contains(code) && !scenario.catalogueAnchors().containsKey(code))
                    problems.add(iteration.name() + ": code " + code + " has no recorded catalogue anchor");
            }
            iteration.declaredScores().forEach((code, score) -> {
                if (score > 0 && !catalogue.children(code).isEmpty() && !iteration.expansions().containsKey(code))
                    problems.add(iteration.name() + ": positive non-leaf " + code + " has no authored expansion");
            });
        }
        return List.copyOf(problems);
    }

    public static void validate(DemoScenario scenario, CatalogueView catalogue) {
        var problems = problems(scenario, catalogue);
        if (!problems.isEmpty())
            throw new IllegalStateException("DEMO_SCENARIO_CATALOGUE_MISMATCH: " + String.join("; ", problems));
    }
}
