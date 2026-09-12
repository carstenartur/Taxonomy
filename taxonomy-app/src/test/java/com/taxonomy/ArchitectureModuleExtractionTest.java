package com.taxonomy;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.taxonomy.ArchitectureModuleGraph.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs in the ordinary Surefire/CI test suite. Before extraction it reports the
 * real, possibly cyclic proposal; once a target module POM exists, every blocker
 * reachable from that module is enforced. No opt-in flag or waiver is involved.
 */
class ArchitectureModuleExtractionTest {

    // These are already separate libraries, not application contexts. Ownership
    // is still read class by class from their actual sources and compiled output;
    // no entire package (notably shared/export/dsl) is excluded from the graph.
    private static final Set<String> SUPPORT_MODULES = Set.of(
            "taxonomy-domain", "taxonomy-dsl", "taxonomy-export", "taxonomy-extension-api", "taxonomy-tooling");

    @Test
    void physicalFeatureModulesHaveNoExtractionBlockers() throws Exception {
        Path root = findRepositoryRoot();
        Evaluation evaluation = evaluateRepository(root);
        Path report = root.resolve("taxonomy-app/target/architecture-module-graph.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, evaluation.report());
        System.out.println(evaluation.report());
        assertThat(evaluation.violations()).withFailMessage(evaluation::report).isEmpty();
    }

    static Evaluation evaluateRepository(Path root) throws Exception {
        Policy policy = readPolicy(root.resolve(".github/architecture-contexts.json"));
        Map<String, Path> modules = discoverModules(root, policy);
        SortedMap<String, SortedSet<String>> classOwners = new TreeMap<>();
        Set<String> sources = new TreeSet<>();
        List<Path> outputs = new ArrayList<>();
        for (var module : modules.entrySet()) {
            Path sourceRoot = module.getValue().resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> sourceFiles = javaSources(sourceRoot);
            if (sourceFiles.isEmpty()) {
                continue;
            }
            for (Path source : sourceFiles) {
                String relative = portable(sourceRoot.relativize(source));
                if (!relative.startsWith("com/taxonomy/")) {
                    throw new IllegalStateException("Unmapped production source: " + source);
                }
                sources.add(module.getKey() + ":" + relative);
            }
            Path output = module.getValue().resolve("target/classes");
            if (!Files.isDirectory(output)) {
                throw new IllegalStateException("Production classes are missing for " + module.getKey()
                        + "; compile the complete reactor before evaluating extraction");
            }
            outputs.add(output);
            try (var files = Files.walk(output)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String relative = portable(output.relativize(file));
                    if (relative.startsWith("com/taxonomy/") && relative.endsWith(".class")
                            && !relative.endsWith("/package-info.class")) {
                        String className = relative.substring(0, relative.length() - ".class".length())
                                .replace('/', '.');
                        classOwners.computeIfAbsent(className, ignored -> new TreeSet<>()).add(module.getKey());
                    }
                }
            }
        }
        if (sources.stream().noneMatch(source -> source.startsWith(policy.compositionModule() + ":"))) {
            throw new IllegalStateException("No application production sources found; module graph would be vacuous");
        }
        JavaClasses imported = new ClassFileImporter().importPaths(outputs);
        Set<String> importedSources = new TreeSet<>();
        List<ClassDependency> dependencies = new ArrayList<>();
        for (JavaClass javaClass : imported) {
            if (!javaClass.getName().startsWith("com.taxonomy.") || javaClass.getSimpleName().equals("package-info")) {
                continue;
            }
            Set<String> physicalOwners = classOwners.get(javaClass.getName());
            if (physicalOwners == null) {
                throw new IllegalStateException("No compiled ownership for " + javaClass.getName());
            }
            for (String physical : physicalOwners) {
                String source = physical + ":" + javaClass.getPackageName().replace('.', '/') + "/"
                        + javaClass.getSourceCodeLocation().getSourceFileName();
                if (!sources.contains(source)) {
                    throw new IllegalStateException("Compiled class has no current source: " + javaClass.getName()
                            + " in " + physical + "; run a clean reactor build");
                }
                importedSources.add(source);
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                dependencies.add(new ClassDependency(javaClass.getName(),
                        dependency.getTargetClass().getBaseComponentType().getName()));
            }
        }
        SortedSet<String> missingSources = new TreeSet<>(sources);
        missingSources.removeAll(importedSources);
        if (!missingSources.isEmpty()) {
            throw new IllegalStateException("Production sources missing from imported bytecode: " + missingSources);
        }
        List<ClassOwner> ownership = new ArrayList<>();
        classOwners.forEach((name, physical) -> physical.forEach(module -> ownership.add(new ClassOwner(name, module))));
        return ArchitectureModuleGraph.evaluate(policy, SUPPORT_MODULES, modules.keySet(), ownership, dependencies,
                readProductionModuleDependencies(modules, policy));
    }

    static Map<String, Path> discoverModules(Path repositoryRoot, Policy policy) throws Exception {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        SortedMap<String, Path> modules = new TreeMap<>();
        collectModules(root.resolve("pom.xml"), root, modules, new HashSet<>());
        if (!modules.containsKey(policy.compositionModule())) {
            throw new IllegalStateException("Composition module is missing from the reactor: " + policy.compositionModule());
        }
        Set<String> featureNames = new HashSet<>();
        policy.contexts().stream().map(Context::targetModule).filter(target -> target != null
                && !target.equals(policy.compositionModule())).forEach(featureNames::add);
        // Detect a target POM even when it has not been added to <modules> yet.
        // Ignoring build/dependency directories keeps this a repository-source scan.
        List<Path> poms = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return Set.of(".git", "target", "node_modules").contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().equals("pom.xml")) {
                    poms.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path pom : poms.stream().sorted().toList()) {
            String artifact = childText(readPom(pom), "artifactId");
            if (featureNames.contains(artifact) && !pom.getParent().equals(modules.get(artifact))) {
                throw new IllegalStateException("Feature module " + artifact + " has a POM outside the declared reactor: " + pom);
            }
        }
        return java.util.Collections.unmodifiableSortedMap(modules);
    }

    static List<ModuleDependency> readProductionModuleDependencies(Map<String, Path> modules, Policy policy) throws Exception {
        Set<String> origins = new HashSet<>(SUPPORT_MODULES);
        origins.add(policy.compositionModule());
        policy.contexts().stream().map(Context::targetModule).filter(target -> target != null).forEach(origins::add);
        Map<Path, LocalPom> models = new TreeMap<>();
        Map<String, String> groups = new TreeMap<>();
        for (var module : modules.entrySet()) {
            LocalPom model = localPom(module.getValue().resolve("pom.xml"), modules, models, new HashSet<>());
            groups.put(module.getKey(), resolved(model.group(), model.values(), "project groupId", module.getKey()));
        }
        Set<String> internalGroups = new HashSet<>(groups.values());
        internalGroups.add("com.taxonomy");
        Set<ModuleDependency> result = new HashSet<>();
        for (var module : modules.entrySet()) {
            if (!origins.contains(module.getKey())) {
                continue; // Reactor aggregators/build tooling are not shipped context libraries.
            }
            LocalPom model = models.get(module.getValue().resolve("pom.xml").toAbsolutePath().normalize());
            Map<String, String> values = model.values();
            for (PomDependency dependency : model.dependencies()) {
                String group = interpolate(dependency.group(), values, new HashSet<>());
                if (group.contains("${")) {
                    if (internalGroups.stream().anyMatch(candidate -> couldResolveTo(group, candidate))) {
                        throw new IllegalStateException("Unresolved potentially internal groupId in " + module.getKey() + ": " + group);
                    }
                    continue;
                }
                if (!internalGroups.contains(group)) {
                    continue;
                }
                String artifact = resolved(dependency.artifact(), values, "artifactId", module.getKey());
                if (!group.equals("com.taxonomy") && !group.equals(groups.get(artifact))) {
                    continue;
                }
                PomDependency effective = dependency.resolve(values, module.getKey());
                Set<String> scopes = new TreeSet<>();
                if (!effective.scope().isBlank()) {
                    scopes.add(effective.scope());
                } else {
                    for (PomDependency managed : model.managed()) {
                        if (managed.couldManage(effective, values)) {
                            PomDependency defaults = managed.resolve(values, module.getKey());
                            scopes.add(defaults.scope().isBlank() ? "compile" : defaults.scope());
                        }
                    }
                }
                if (scopes.isEmpty()) {
                    scopes.add("compile");
                }
                if (!Set.of("compile", "provided", "runtime", "system", "test").containsAll(scopes)) {
                    throw new IllegalStateException("Unsupported internal dependency scope in " + module.getKey() + ": " + scopes);
                }
                if (scopes.stream().anyMatch(scope -> !scope.equals("test"))) {
                    result.add(new ModuleDependency(module.getKey(), artifact));
                }
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing(ModuleDependency::origin)
                .thenComparing(ModuleDependency::target)).toList();
    }

    private record PomDependency(String group, String artifact, String type, String classifier, String scope) {
        String key() {
            return group + ":" + artifact + ":" + type + ":" + classifier;
        }

        PomDependency withScope(String inheritedScope) {
            return new PomDependency(group, artifact, type, classifier, inheritedScope);
        }

        PomDependency resolve(Map<String, String> values, String module) {
            return new PomDependency(resolved(group, values, "groupId", module),
                    resolved(artifact, values, "artifactId", module), resolved(type, values, "type", module),
                    optionalResolved(classifier, values, "classifier", module), optionalResolved(scope, values, "scope", module));
        }

        boolean couldManage(PomDependency dependency, Map<String, String> values) {
            return couldResolveTo(interpolate(group, values, new HashSet<>()), dependency.group())
                    && couldResolveTo(interpolate(artifact, values, new HashSet<>()), dependency.artifact())
                    && couldResolveTo(interpolate(type, values, new HashSet<>()), dependency.type())
                    && couldResolveTo(interpolate(classifier, values, new HashSet<>()), dependency.classifier());
        }
    }

    /** Raw expressions are retained until inheritance and child property overrides have been assembled. */
    private record LocalPom(String group, String artifact, String version, Map<String, String> properties,
                            String parentGroup, String parentArtifact, String parentVersion,
                            List<PomDependency> dependencies, List<PomDependency> managed) {
        Map<String, String> values() {
            Map<String, String> values = new TreeMap<>(properties);
            for (String prefix : List.of("project.", "pom.", "")) {
                values.put(prefix + "groupId", group);
                values.put(prefix + "artifactId", artifact);
                values.put(prefix + "version", version);
                values.put(prefix + "parent.groupId", parentGroup);
                values.put(prefix + "parent.artifactId", parentArtifact);
                values.put(prefix + "parent.version", parentVersion);
            }
            return values;
        }
    }

    private static LocalPom localPom(Path file, Map<String, Path> modules, Map<Path, LocalPom> cache,
                                     Set<Path> resolving) throws Exception {
        file = file.toAbsolutePath().normalize();
        if (cache.containsKey(file)) {
            return cache.get(file);
        }
        if (!resolving.add(file)) {
            throw new IllegalStateException("Cyclic local parent POM inheritance: " + file);
        }
        Element project = readPom(file);
        Map<String, String> ownProperties = pomProperties(project);
        List<Element> parents = children(project, "parent");
        Element parent = parents.isEmpty() ? null : parents.getFirst();
        String parentArtifact = parent == null ? "" : resolved(childText(parent, "artifactId"), ownProperties, "parent artifactId", file.toString());
        String parentGroup = parent == null ? "" : resolved(childText(parent, "groupId"), ownProperties, "parent groupId", file.toString());
        String parentVersion = parent == null ? "" : resolved(childText(parent, "version"), ownProperties, "parent version", file.toString());
        LocalPom inherited = null;
        if (parent != null) {
            List<Path> candidates = new ArrayList<>();
            String relative = children(parent, "relativePath").isEmpty() ? "../pom.xml" : childText(parent, "relativePath");
            if (!relative.isEmpty()) {
                Path candidate = file.getParent().resolve(resolved(relative, ownProperties, "parent relativePath", file.toString())).normalize();
                candidates.add(Files.isDirectory(candidate) ? candidate.resolve("pom.xml") : candidate);
            }
            if (modules.containsKey(parentArtifact)) {
                candidates.add(modules.get(parentArtifact).resolve("pom.xml"));
            }
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate) || !childText(readPom(candidate), "artifactId").equals(parentArtifact)) {
                    continue;
                }
                LocalPom possible = localPom(candidate, modules, cache, resolving);
                if (resolved(possible.group(), possible.values(), "parent groupId", candidate.toString()).equals(parentGroup)
                        && resolved(possible.version(), possible.values(), "parent version", candidate.toString()).equals(parentVersion)) {
                    inherited = possible;
                    break;
                }
            }
            if (inherited == null && (parentGroup.equals("com.taxonomy") || modules.containsKey(parentArtifact))) {
                throw new IllegalStateException("Cannot resolve local reactor parent " + parentGroup + ":" + parentArtifact
                        + ":" + parentVersion + " for " + file);
            }
        }
        Map<String, String> properties = new TreeMap<>();
        if (inherited != null) {
            properties.putAll(inherited.properties());
        }
        properties.putAll(ownProperties);
        // All profile dependencies are checked conservatively. A profile-specific
        // property could select another internal edge; do not silently assume an
        // activation state when its value differs from the base model.
        for (Element profiles : children(project, "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                pomProperties(profile).forEach((name, value) -> {
                    if (!value.equals(properties.get(name))) {
                        properties.put(name, "${unresolved-profile-property:" + name + "}");
                    }
                });
            }
        }
        List<PomDependency> dependencies = inheritedDependencies(inherited == null ? List.of() : inherited.dependencies(), project, false);
        List<PomDependency> managed = inheritedDependencies(inherited == null ? List.of() : inherited.managed(), project, true);
        String group = childText(project, "groupId");
        String version = childText(project, "version");
        LocalPom model = new LocalPom(group.isBlank() ? parentGroup : group, childText(project, "artifactId"),
                version.isBlank() ? parentVersion : version, Map.copyOf(properties), parentGroup, parentArtifact, parentVersion,
                dependencies, managed);
        cache.put(file, model);
        resolving.remove(file);
        return model;
    }

    private static Map<String, String> pomProperties(Element project) {
        Map<String, String> properties = new TreeMap<>();
        for (Element container : children(project, "properties")) {
            for (Node node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element property) {
                    properties.put(property.getLocalName(), property.getTextContent().trim());
                }
            }
        }
        return properties;
    }

    private static List<PomDependency> inheritedDependencies(List<PomDependency> inherited, Element project, boolean managed) {
        List<PomDependency> base = mergeDependencies(inherited, pomDependencies(project, managed));
        Set<PomDependency> allProfiles = new java.util.LinkedHashSet<>(base);
        for (Element profiles : children(project, "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                List<PomDependency> declarations = pomDependencies(profile, managed);
                if (managed) {
                    // A managed test scope present only in a profile cannot erase
                    // the base model's compile default. Activation-dependent
                    // management needs explicit support instead of a guessed scope.
                    declarations.stream().filter(dependency -> !base.contains(dependency)).forEach(dependency ->
                            allProfiles.add(dependency.withScope("${unresolved-profile-managed-scope:activation}")));
                } else {
                    allProfiles.addAll(mergeDependencies(base, declarations));
                }
            }
        }
        return List.copyOf(allProfiles);
    }

    private static List<PomDependency> mergeDependencies(List<PomDependency> inherited, List<PomDependency> declared) {
        List<PomDependency> result = new ArrayList<>(inherited);
        for (PomDependency child : declared) {
            List<PomDependency> previous = result.stream().filter(dependency -> dependency.key().equals(child.key())).toList();
            result.removeAll(previous);
            if (child.scope().isBlank() && !previous.isEmpty()) {
                previous.forEach(parent -> result.add(child.withScope(parent.scope())));
            } else {
                result.add(child);
            }
        }
        return result;
    }

    private static List<PomDependency> pomDependencies(Element project, boolean managed) {
        List<PomDependency> result = new ArrayList<>();
        for (Element source : managed ? children(project, "dependencyManagement") : List.of(project)) {
            for (Element dependencies : children(source, "dependencies")) {
                for (Element dependency : children(dependencies, "dependency")) {
                    String type = childText(dependency, "type");
                    result.add(new PomDependency(childText(dependency, "groupId"), childText(dependency, "artifactId"),
                            type.isBlank() ? "jar" : type, childText(dependency, "classifier"), childText(dependency, "scope")));
                }
            }
        }
        return result;
    }

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private static String interpolate(String text, Map<String, String> values, Set<String> resolving) {
        Matcher matcher = PROPERTY.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = matcher.group();
            if (values.containsKey(name) && resolving.add(name)) {
                replacement = interpolate(values.get(name), values, resolving);
                resolving.remove(name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(result).toString();
    }

    private static String resolved(String text, Map<String, String> values, String field, String module) {
        String result = optionalResolved(text, values, field, module);
        if (result.isBlank()) {
            throw new IllegalStateException("Unresolved " + field + " in " + module + ": " + text);
        }
        return result;
    }

    private static String optionalResolved(String text, Map<String, String> values, String field, String module) {
        String result = interpolate(text, values, new HashSet<>());
        if (result.contains("${")) {
            throw new IllegalStateException("Unresolved " + field + " in " + module + ": " + result);
        }
        return result;
    }

    private static boolean couldResolveTo(String expression, String target) {
        Matcher matcher = PROPERTY.matcher(expression);
        StringBuilder pattern = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            pattern.append(Pattern.quote(expression.substring(previous, matcher.start()))).append(".*");
            previous = matcher.end();
        }
        return target.matches(pattern.append(Pattern.quote(expression.substring(previous))).toString());
    }

    private static void collectModules(Path pom, Path root, Map<String, Path> modules, Set<Path> visited) throws Exception {
        pom = pom.toAbsolutePath().normalize();
        if (!pom.startsWith(root) || !Files.isRegularFile(pom)) {
            throw new IllegalStateException("Reactor module POM is missing or outside the repository: " + pom);
        }
        if (!visited.add(pom)) {
            throw new IllegalStateException("Duplicate or cyclic reactor module declaration: " + pom);
        }
        Element project = readPom(pom);
        String artifact = childText(project, "artifactId");
        if (artifact.isBlank() || artifact.contains("${") || modules.putIfAbsent(artifact, pom.getParent()) != null) {
            throw new IllegalStateException("Invalid or duplicate reactor artifactId: " + artifact + " in " + pom);
        }
        List<Element> moduleLists = new ArrayList<>(children(project, "modules"));
        // Profile-declared feature modules are also extraction attempts. An
        // inactive profile must not act as an optional architecture-gate switch.
        for (Element profiles : children(project, "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                moduleLists.addAll(children(profile, "modules"));
            }
        }
        for (Element list : moduleLists) {
            for (Element module : children(list, "module")) {
                String directory = module.getTextContent().trim();
                if (directory.isEmpty() || directory.contains("${")) {
                    throw new IllegalStateException("Unresolved reactor module directory: " + directory + " in " + pom);
                }
                collectModules(pom.getParent().resolve(directory).resolve("pom.xml"), root, modules, visited);
            }
        }
    }

    private static Element readPom(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static String childText(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? "" : matches.getFirst().getTextContent().trim();
    }

    private static Policy readPolicy(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(file));
        if (root.path("schemaVersion").asInt() != 1 || !root.path("catchAllAdapterModuleAllowed").isBoolean()
                || root.path("catchAllAdapterModuleAllowed").asBoolean()) {
            throw new IllegalArgumentException("Unsupported or permissive architecture context policy: " + file);
        }
        Set<String> rootClasses = new TreeSet<>();
        for (JsonNode name : requiredArray(root, "rootCompositionClasses")) {
            if (!rootClasses.add(name.asString())) {
                throw new IllegalArgumentException("Duplicate root composition class: " + name.asString());
            }
        }
        List<Context> contexts = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "contexts")) {
            List<String> packages = new ArrayList<>();
            for (JsonNode pattern : requiredArray(node, "packages")) {
                packages.add(pattern.asString());
            }
            JsonNode target = node.path("targetModule");
            contexts.add(new Context(node.path("id").asString(), target.isNull() || target.isMissingNode()
                    ? null : target.asString(), List.copyOf(packages)));
        }
        return new Policy(root.path("compositionModule").asString(), Set.copyOf(rootClasses), List.copyOf(contexts));
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            throw new IllegalArgumentException("Architecture policy field must be an array: " + field);
        }
        return array;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !Set.of("package-info.java", "module-info.java").contains(file.getFileName().toString()))
                    .sorted().toList();
        }
    }

    private static String portable(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(".github/architecture-contexts.json"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate architecture context policy in repository root");
        }
        return current;
    }
}
