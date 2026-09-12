package com.taxonomy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Projects real class dependencies onto the proposed context modules. There is
 * deliberately no baseline or switch: the complete proposal is evaluated on
 * every invocation, and blockers become failures for physically present feature
 * modules. Existing support modules retain their exact physical class ownership.
 */
final class ArchitectureModuleGraph {

    record Context(String id, String targetModule, List<String> packages) {}

    record Policy(String compositionModule, Set<String> rootCompositionClasses, List<Context> contexts) {}

    record ClassOwner(String className, String physicalModule) {}

    record ClassDependency(String origin, String target) {}

    record ModuleDependency(String origin, String target) {}

    record Evaluation(String report, List<String> cycles, Map<String, List<String>> blockers,
                      List<String> violations) {}

    private record Ownership(String module, String physicalModule, String reason) {}

    private record ModuleEdge(String from, String to) implements Comparable<ModuleEdge> {
        @Override
        public int compareTo(ModuleEdge other) {
            int fromOrder = from.compareTo(other.from);
            return fromOrder == 0 ? to.compareTo(other.to) : fromOrder;
        }

        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    private record Evidence(String origin, String target, Ownership from, Ownership to) {
        String describe() {
            return origin + " -> " + target + " [physical: " + from.physicalModule() + " -> "
                    + to.physicalModule() + "; target: " + to.reason() + "]";
        }
    }

    private record Cycle(SortedSet<String> members, String witness) {}

    private static final Comparator<Evidence> EVIDENCE_ORDER = Comparator.comparing(Evidence::origin)
            .thenComparing(Evidence::target);

    static Evaluation evaluate(Policy policy, Set<String> supportModules, Set<String> presentModules,
                               List<ClassOwner> classes, List<ClassDependency> dependencies) {
        return evaluate(policy, supportModules, presentModules, classes, dependencies, List.of());
    }

    static Evaluation evaluate(Policy policy, Set<String> supportModules, Set<String> presentModules,
                               List<ClassOwner> classes, List<ClassDependency> dependencies,
                               List<ModuleDependency> declaredDependencies) {
        validatePolicy(policy);
        SortedSet<String> featureModules = new TreeSet<>();
        for (Context context : policy.contexts()) {
            if (context.targetModule() != null && !context.targetModule().equals(policy.compositionModule())) {
                featureModules.add(context.targetModule());
            }
        }
        SortedSet<String> presentFeatures = new TreeSet<>(featureModules);
        presentFeatures.retainAll(presentModules);

        SortedSet<String> violations = new TreeSet<>();
        SortedMap<String, Ownership> owners = new TreeMap<>();
        for (ClassOwner classOwner : classes.stream().sorted(Comparator.comparing(ClassOwner::className)
                .thenComparing(ClassOwner::physicalModule)).toList()) {
            Ownership owner = ownerOf(classOwner, policy, supportModules, presentModules, violations);
            Ownership previous = owners.putIfAbsent(classOwner.className(), owner);
            if (previous != null && !previous.physicalModule().equals(owner.physicalModule())) {
                violations.add(classOwner.className() + " has multiple physical owners: "
                        + previous.physicalModule() + ", " + owner.physicalModule());
            }
        }
        if (owners.isEmpty()) {
            violations.add("No production com.taxonomy classes were supplied to the module graph");
        }

        SortedMap<ModuleEdge, SortedSet<Evidence>> edges = new TreeMap<>();
        for (ClassDependency dependency : dependencies) {
            String originName = componentClass(dependency.origin());
            String targetName = componentClass(dependency.target());
            if (!targetName.startsWith("com.taxonomy.")) {
                continue;
            }
            Ownership origin = requireOwner(originName, owners, policy, violations);
            Ownership target = requireOwner(targetName, owners, policy, violations);
            if (!origin.module().equals(target.module())) {
                edges.computeIfAbsent(new ModuleEdge(origin.module(), target.module()),
                        ignored -> new TreeSet<>(EVIDENCE_ORDER))
                        .add(new Evidence(originName, targetName, origin, target));
            }
        }

        SortedMap<String, SortedSet<String>> graph = new TreeMap<>();
        featureModules.forEach(module -> graph.put(module, new TreeSet<>()));
        owners.values().forEach(owner -> graph.computeIfAbsent(owner.module(), ignored -> new TreeSet<>()));
        edges.keySet().forEach(edge -> graph.get(edge.from()).add(edge.to()));
        SortedSet<ModuleEdge> pomEdges = new TreeSet<>();
        for (ModuleDependency dependency : declaredDependencies) {
            ModuleEdge edge = new ModuleEdge(dependency.origin(), dependency.target());
            for (String module : List.of(edge.from(), edge.to())) {
                if (!module.equals(policy.compositionModule()) && !supportModules.contains(module)
                        && !featureModules.contains(module)) {
                    violations.add("Unmapped internal production POM dependency: " + edge);
                }
                if (supportModules.contains(module) && !presentModules.contains(module)) {
                    violations.add("Internal production POM dependency names a support module absent from the reactor: " + edge);
                }
            }
            pomEdges.add(edge);
            graph.computeIfAbsent(edge.from(), ignored -> new TreeSet<>()).add(edge.to());
            graph.computeIfAbsent(edge.to(), ignored -> new TreeSet<>());
        }
        List<Cycle> cycles = cycles(graph);

        SortedMap<String, List<String>> blockers = new TreeMap<>();
        for (String feature : featureModules) {
            SortedMap<String, List<String>> paths = pathsFrom(feature, graph);
            SortedSet<String> reasons = new TreeSet<>();
            if (presentFeatures.contains(feature) && owners.values().stream().noneMatch(owner ->
                    owner.module().equals(feature) && owner.physicalModule().equals(feature))) {
                reasons.add("has no physical production classes; extraction evaluation would be vacuous");
            }
            for (Map.Entry<String, List<String>> reachable : paths.entrySet()) {
                String module = reachable.getKey();
                if (module.equals(policy.compositionModule())) {
                    List<String> path = reachable.getValue();
                    ModuleEdge lastEdge = new ModuleEdge(path.get(path.size() - 2), module);
                    String evidence = edges.containsKey(lastEdge) ? edges.get(lastEdge).first().describe()
                            : "declared production POM dependency " + lastEdge;
                    reasons.add("requires application via " + path(path) + ": " + evidence);
                } else if (!module.equals(feature) && featureModules.contains(module)
                        && !presentModules.contains(module)) {
                    reasons.add("requires unextracted owner via " + path(reachable.getValue()));
                }
            }
            for (Cycle cycle : cycles) {
                cycle.members().stream().filter(paths::containsKey).findFirst().ifPresent(member -> reasons.add(
                        "reaches cycle via " + path(paths.get(member)) + ": " + cycle.witness()
                                + " (cyclic group: " + String.join(", ", cycle.members()) + ")"));
            }
            // A new module POM alone is not physical extraction. Check the class
            // inventory, including same-context references hidden by aggregation.
            for (Map.Entry<String, Ownership> entry : owners.entrySet()) {
                Ownership owner = entry.getValue();
                if (paths.containsKey(owner.module()) && presentFeatures.contains(owner.module())
                        && owner.physicalModule().equals(policy.compositionModule())) {
                    reasons.add("requires " + entry.getKey() + " via " + path(paths.get(owner.module()))
                            + ": still physically owned by " + policy.compositionModule());
                }
            }
            blockers.put(feature, List.copyOf(reasons));
            if (presentFeatures.contains(feature)) {
                reasons.forEach(reason -> violations.add(feature + ": " + reason));
            }
        }

        String report = render(policy, owners, edges, pomEdges, cycles, blockers, presentFeatures, violations);
        return new Evaluation(report, cycles.stream().map(Cycle::witness).toList(),
                java.util.Collections.unmodifiableSortedMap(blockers), List.copyOf(violations));
    }

    private static Ownership ownerOf(ClassOwner source, Policy policy, Set<String> supportModules,
                                     Set<String> presentModules, Set<String> violations) {
        String className = source.className();
        String physical = source.physicalModule();
        if (!className.matches("com\\.taxonomy(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+")) {
            violations.add("Invalid production class name: " + className);
        }
        if (!physical.equals(policy.compositionModule()) && !presentModules.contains(physical)) {
            violations.add(className + " has physical owner absent from the reactor: " + physical);
        }
        if (supportModules.contains(physical)) {
            return new Ownership(physical, physical, "existing support module");
        }
        String packageName = className.substring(0, Math.max(0, className.lastIndexOf('.')));
        Context context = null;
        for (Context candidate : policy.contexts()) {
            if (candidate.packages().stream().anyMatch(pattern -> matches(pattern, packageName))) {
                context = candidate;
                break;
            }
        }
        String planned;
        String reason;
        String topLevelClass = className.substring(className.lastIndexOf('.') + 1).split("\\$", 2)[0] + ".java";
        if (packageName.equals("com.taxonomy") && policy.rootCompositionClasses().contains(topLevelClass)) {
            planned = policy.compositionModule();
            reason = "root composition " + topLevelClass;
        } else if (context == null) {
            planned = policy.compositionModule();
            reason = "unmapped production class";
            violations.add("Unmapped production class: " + className + " (physical owner " + physical + ")");
        } else {
            planned = context.targetModule() == null ? policy.compositionModule() : context.targetModule();
            reason = "context " + context.id() + (context.targetModule() == null
                    ? " has unresolved targetModule; remains application-owned" : "");
        }
        if (!physical.equals(policy.compositionModule()) && !physical.equals(planned)) {
            violations.add(className + " has invalid physical owner " + physical + "; planned owner is " + planned);
        }
        return new Ownership(planned, physical, reason);
    }

    private static Ownership requireOwner(String name, Map<String, Ownership> owners, Policy policy,
                                          Set<String> violations) {
        return owners.computeIfAbsent(name, ignored -> {
            violations.add("No production class ownership found for dependency class: " + name);
            return new Ownership(policy.compositionModule(), policy.compositionModule(), "missing class ownership");
        });
    }

    private static String componentClass(String name) {
        while (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
        }
        if (name.startsWith("[")) {
            int objectStart = name.indexOf('L');
            return objectStart < 0 ? name : name.substring(objectStart + 1, name.length() - 1).replace('/', '.');
        }
        return name;
    }

    private static SortedMap<String, List<String>> pathsFrom(String start, Map<String, SortedSet<String>> graph) {
        SortedMap<String, List<String>> paths = new TreeMap<>();
        paths.put(start, List.of(start));
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            for (String target : graph.getOrDefault(current, new TreeSet<>())) {
                if (!paths.containsKey(target)) {
                    List<String> nextPath = new ArrayList<>(paths.get(current));
                    nextPath.add(target);
                    paths.put(target, List.copyOf(nextPath));
                    queue.add(target);
                }
            }
        }
        return paths;
    }

    /** One deterministic witness per strongly connected group, without exponential cycle enumeration. */
    private static List<Cycle> cycles(SortedMap<String, SortedSet<String>> graph) {
        SortedMap<String, SortedMap<String, List<String>>> reachable = new TreeMap<>();
        graph.keySet().forEach(module -> reachable.put(module, pathsFrom(module, graph)));
        Set<String> visited = new HashSet<>();
        List<Cycle> cycles = new ArrayList<>();
        for (String first : graph.keySet()) {
            if (!visited.add(first)) {
                continue;
            }
            SortedSet<String> members = new TreeSet<>();
            for (String candidate : graph.keySet()) {
                if (reachable.get(first).containsKey(candidate) && reachable.get(candidate).containsKey(first)) {
                    members.add(candidate);
                }
            }
            visited.addAll(members);
            if (members.size() > 1 || graph.get(first).contains(first)) {
                String next = graph.get(first).stream().filter(members::contains).findFirst().orElseThrow();
                List<String> witness = new ArrayList<>(List.of(first));
                witness.addAll(reachable.get(next).get(first));
                cycles.add(new Cycle(members, path(witness)));
            }
        }
        return List.copyOf(cycles);
    }

    private static String render(Policy policy, Map<String, Ownership> owners,
                                 SortedMap<ModuleEdge, SortedSet<Evidence>> edges, Set<ModuleEdge> pomEdges, List<Cycle> cycles,
                                 SortedMap<String, List<String>> blockers, Set<String> presentFeatures,
                                 Collection<String> violations) {
        StringBuilder report = new StringBuilder("Module extraction graph (production class dependencies)\n")
                .append("Composition module: ").append(policy.compositionModule()).append('\n')
                .append("Root composition files: ").append(String.join(", ", new TreeSet<>(policy.rootCompositionClasses()))).append('\n')
                .append("Classified production classes: ").append(owners.size()).append('\n')
                .append("Physical modules: ").append(String.join(", ", owners.values().stream()
                        .map(Ownership::physicalModule).distinct().sorted().toList())).append('\n')
                .append("Present feature modules: ").append(presentFeatures.isEmpty() ? "none"
                        : String.join(", ", presentFeatures)).append('\n')
                .append("\nPlanned module edges:\n");
        edges.forEach((edge, evidence) -> report.append("- ").append(edge).append("\n  ")
                .append(evidence.first().describe()).append('\n'));
        if (edges.isEmpty()) {
            report.append("- none\n");
        }
        report.append("\nDeclared production POM edges:\n");
        if (pomEdges.isEmpty()) {
            report.append("- none\n");
        }
        pomEdges.forEach(edge -> report.append("- ").append(edge).append('\n'));
        report.append("\nCycles in the complete proposal (one witness per cyclic group):\n");
        if (cycles.isEmpty()) {
            report.append("- none\n");
        }
        cycles.forEach(cycle -> report.append("- ").append(cycle.witness())
                .append("\n  cyclic group: ").append(String.join(", ", cycle.members())).append('\n'));
        report.append("\nApplication dependencies:\n");
        SortedSet<ModuleEdge> appEdges = new TreeSet<>(edges.keySet());
        appEdges.addAll(pomEdges);
        appEdges.removeIf(edge -> !edge.to().equals(policy.compositionModule()));
        if (appEdges.isEmpty()) {
            report.append("- none\n");
        }
        appEdges.forEach(edge -> {
            report.append("- ").append(edge).append("\n  ").append(edges.containsKey(edge)
                    ? edges.get(edge).first().describe() : "declared production POM dependency").append('\n');
        });
        report.append("\nApplication-owned contexts without a targetModule:\n");
        List<String> unassigned = policy.contexts().stream().filter(context -> context.targetModule() == null)
                .map(Context::id).sorted().toList();
        report.append("- ").append(unassigned.isEmpty() ? "none" : String.join(", ", unassigned)).append('\n');
        report.append("\nExtraction readiness (evaluated for every planned feature module):\n");
        blockers.forEach((module, reasons) -> {
            report.append("- ").append(module).append(reasons.isEmpty() ? ": ready\n" : ": blocked\n");
            reasons.forEach(reason -> report.append("  - ").append(reason).append('\n'));
        });
        report.append("\nEnforced violations:\n");
        if (violations.isEmpty()) {
            report.append("- none\n");
        }
        violations.forEach(violation -> report.append("- ").append(violation).append('\n'));
        return report.toString();
    }

    private static String path(List<String> modules) {
        return String.join(" -> ", modules);
    }

    private static boolean matches(String pattern, String packageName) {
        String prefix = pattern.substring(0, pattern.length() - 2);
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    private static void validatePolicy(Policy policy) {
        if (!"taxonomy-app".equals(policy.compositionModule()) || policy.rootCompositionClasses().isEmpty()) {
            throw new IllegalArgumentException("Policy must identify taxonomy-app and its root composition classes");
        }
        for (String rootClass : policy.rootCompositionClasses()) {
            if (!rootClass.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\.java")) {
                throw new IllegalArgumentException("Invalid root composition class file: " + rootClass);
            }
        }
        Set<String> ids = new HashSet<>();
        Set<String> targets = new HashSet<>();
        List<String> patterns = new ArrayList<>();
        for (Context context : policy.contexts()) {
            if (context.id() == null || context.id().isBlank() || !ids.add(context.id())) {
                throw new IllegalArgumentException("Invalid or duplicate context id: " + context.id());
            }
            if (context.targetModule() != null && (!context.targetModule().matches("taxonomy-[a-z0-9-]+")
                    || !targets.add(context.targetModule()))) {
                throw new IllegalArgumentException("Invalid or duplicate targetModule: " + context.targetModule());
            }
            if (context.packages().isEmpty()) {
                throw new IllegalArgumentException("No packages for context: " + context.id());
            }
            for (String pattern : context.packages()) {
                if (!pattern.matches("com\\.taxonomy(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\.\\.")) {
                    throw new IllegalArgumentException("Invalid context package pattern: " + pattern);
                }
                for (String previous : patterns) {
                    if (matches(previous, pattern.substring(0, pattern.length() - 2))
                            || matches(pattern, previous.substring(0, previous.length() - 2))) {
                        throw new IllegalArgumentException("Overlapping context package patterns: " + previous + ", " + pattern);
                    }
                }
                patterns.add(pattern);
            }
        }
        if (!targets.contains(policy.compositionModule()) || targets.size() < 2) {
            throw new IllegalArgumentException("Policy must include composition and planned feature modules");
        }
    }

    private ArchitectureModuleGraph() {}
}
