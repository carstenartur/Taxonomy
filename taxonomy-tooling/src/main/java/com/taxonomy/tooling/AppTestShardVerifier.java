package com.taxonomy.tooling;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.w3c.dom.Element;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/** Source-bound, fail-closed inventory of Maven application test shard evidence. */
final class AppTestShardVerifier {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "ArchTest");
    private static final Set<String> FAILURE_ELEMENTS = Set.of("failure", "error", "flakyFailure", "flakyError", "rerunFailure", "rerunError");
    private AppTestShardVerifier() { }

    static int run(String[] raw, Map<String, String> env, Path cwd, PrintStream out, PrintStream err) {
        try {
            var args = TaxonomyTooling.Arguments.parse(raw);
            Path root = args.path("root", cwd).toAbsolutePath().normalize();
            String commit = args.optionalOrDefault("commit", env.getOrDefault("GITHUB_SHA", ""));
            if (commit.isBlank()) commit = GitSupport.require(root, "rev-parse", "HEAD").strip();
            String tree = args.optional("source-tree");
            if (tree == null) tree = GitSupport.require(root, "rev-parse", commit + "^{tree}").strip();
            if (args.booleanValue("stage", false)) {
                String shard = args.required("shard");
                String build = args.optionalOrDefault("build-id", env.get("GITHUB_RUN_ID") + "." + env.get("GITHUB_RUN_ATTEMPT") + "." + shard);
                Map<String, Object> manifest = stage(root, shard, commit, tree, build);
                out.println(shard + ": " + list(manifest.get("reports")).size() + " reports, " + object(manifest.get("timing")).get("durationSeconds") + "s, coverage SHA-256 " + object(manifest.get("coverage")).get("sha256"));
                return 0;
            }
            boolean preflight = args.booleanValue("verify-inputs", false);
            if (!preflight && !args.booleanValue("verify", false)) throw invalid("expected --stage true, --verify-inputs true or --verify true");
            Map<String, Object> plan = object(read(root.resolve(".mvn/verification-suites.json")).get("applicationTestShards"));
            Map<String, Object> result = verify(root, root.resolve("taxonomy-app/src/test/java"), root.resolve("target/app-test-shards"), plan, commit, tree, preflight);
            if (preflight) {
                out.println("Validated shard inputs: " + list(result.get("classes")).size() + " test classes in " + result.get("groups") + ".");
            } else {
                write(root.resolve("target/app-test-shards/timings.json"), result);
                Map<String, Object> tests = object(result.get("tests"));
                String summary = "Executed inventory: " + tests.get("classes") + " source classes, " + tests.get("suites") + " JUnit suites, " + tests.get("cases") + " cases in " + list(result.get("coverage")).size() + " isolated groups; " + tests.get("skipped") + " skipped cases and " + tests.get("excludedClasses") + " profile-excluded classes.";
                out.println("Application test inventory: " + summary);
                StringBuilder markdown = new StringBuilder("### Application test shards\n\n" + summary + "\n\n| Group | Reports and cases | Suite seconds |\n|---|---:|---:|\n");
                for (String group : object(plan.get("groups")).keySet()) {
                    Map<String, Object> stats = object(object(result.get("groupStatistics")).get(group));
                    int reportCount = ((Number) stats.get("reports")).intValue();
                    int cases = ((Number) stats.get("cases")).intValue();
                    int skipped = ((Number) stats.get("skipped")).intValue();
                    double duration = ((Number) object(result.get("groupDurations")).get(group)).doubleValue();
                    out.println("  group " + group + ": " + reportCount + " reports, " + cases + " cases, " + skipped + " skipped, " + duration + "s");
                    markdown.append("| ").append(group).append(" | ").append(reportCount).append(" / ").append(cases).append(" cases (").append(skipped).append(" skipped) | ").append(duration).append(" |\n");
                }
                markdown.append("\nSlowest suites:\n");
                for (Map<String, Object> suite : listOfObjects(result.get("slowSuites"))) {
                    out.println("  " + suite.get("group") + ": " + suite.get("className") + " " + suite.get("durationSeconds") + "s");
                    markdown.append("- ").append(suite.get("group")).append(": `").append(suite.get("className")).append("` (").append(suite.get("durationSeconds")).append("s)\n");
                }
                if (env.containsKey("GITHUB_STEP_SUMMARY")) Files.writeString(Path.of(env.get("GITHUB_STEP_SUMMARY")), markdown, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            err.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    static Map<String, Object> stage(Path root, String shard, String commit, String tree, String build) throws IOException {
        if (!shard.matches("[a-z][a-z0-9-]*")) throw invalid("invalid shard name: " + shard);
        requireIds(commit, tree);
        requireBuild(shard, build);
        Path target = root.resolve("taxonomy-app/target");
        Path destination = root.resolve("target/app-test-shards").resolve(shard);
        delete(destination);
        List<Map<String, Object>> reports = new ArrayList<>();
        List<Map<String, Object>> timings = new ArrayList<>();
        double duration = 0;
        for (String directory : List.of("surefire-reports", "failsafe-reports")) {
            for (Path file : files(target.resolve(directory))) {
                if (!isReport(file)) continue;
                String relative = "tests/" + slash(target.relativize(file));
                Path output = destination.resolve(relative);
                Files.createDirectories(output.getParent());
                Files.copy(file, output);
                Suite suite = parseSuite(output, true);
                reports.add(map("path", relative, "className", suite.className, "sha256", digest(output), "tests", suite.tests, "skipped", suite.skipped, "failures", suite.failures, "errors", suite.errors, "failureElements", suite.failureElements));
                timings.add(map("className", suite.className, "durationSeconds", suite.duration));
                duration += suite.duration;
            }
        }
        if (reports.isEmpty()) throw invalid(shard + " produced no JUnit reports");
        Path coverage = target.resolve("jacoco.exec");
        if (!Files.isRegularFile(coverage, LinkOption.NOFOLLOW_LINKS)) throw invalid(shard + " JaCoCo execution data is missing");
        Files.copy(coverage, destination.resolve("coverage.exec"));
        timings.sort(Comparator.comparingDouble(row -> -((Number) row.get("durationSeconds")).doubleValue()));
        Map<String, Object> result = map("schemaVersion", 1, "shard", shard, "commit", commit.toLowerCase(Locale.ROOT), "sourceTree", tree.toLowerCase(Locale.ROOT), "buildId", build, "reports", reports,
                "coverage", map("path", "coverage.exec", "sha256", digest(destination.resolve("coverage.exec"))), "timing", map("durationSeconds", round(duration), "suites", timings));
        write(destination.resolve("manifest.json"), result);
        return result;
    }

    static Map<String, Object> verify(Path root, Path sourcesPath, Path evidence, Map<String, Object> plan, String commit, String tree, boolean preflight) throws IOException {
        root = root.toAbsolutePath().normalize(); sourcesPath = sourcesPath.toAbsolutePath().normalize(); evidence = evidence.toAbsolutePath().normalize();
        if (!inside(root, sourcesPath) || !inside(root, evidence)) throw invalid("test sources and shard evidence must be inside the repository");
        requireIds(commit, tree);
        Map<String, Source> sources = sources(sourcesPath);
        Map<String, Object> groups = object(plan.get("groups"));
        String defaultGroup = text(plan.get("defaultGroup"));
        if (groups.values().stream().map(AppTestShardVerifier::object).filter(group -> Boolean.TRUE.equals(group.get("default"))).count() != 1 || !Boolean.TRUE.equals(object(groups.get(defaultGroup)).get("default"))) throw invalid("expected exactly one default shard");
        List<String> excludedTags = strings(plan.getOrDefault("excludedTags", List.of()));
        List<Map<String, Object>> executions = listOfObjects(plan.getOrDefault("requiredExecutions", List.of()));
        for (Map<String, Object> execution : executions) {
            if (text(execution.get("className")).isBlank() || strings(execution.get("identities")).isEmpty()) throw invalid("required app test execution identity is malformed");
        }
        Map<String, String> assignments = assignments(sources, groups, defaultGroup);
        List<String> requiredGroups = groups.keySet().stream().filter(group -> !preflight || !group.equals(defaultGroup)).toList();
        Set<String> artifacts = new TreeSet<>();
        if (Files.isDirectory(evidence)) try (var dirs = Files.list(evidence)) { dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> artifacts.add(path.getFileName().toString())); }
        for (String artifact : artifacts) if (!groups.containsKey(artifact)) throw invalid("unknown shard artifact: " + artifact);
        for (String group : requiredGroups) if (!artifacts.contains(group)) throw invalid("missing shard artifact: " + group);
        Map<String, List<Report>> reports = new LinkedHashMap<>();
        Set<String> reportedSources = new HashSet<>(), runIds = new HashSet<>();
        Map<String, Integer> casesBySource = new HashMap<>();
        List<Map<String, Object>> timings = new ArrayList<>();
        Map<String, Object> groupDurations = new LinkedHashMap<>();
        Map<String, Object> groupStatistics = new LinkedHashMap<>();
        int skipped = 0, count = 0, cases = 0, excluded = 0;
        double duration = 0;
        for (String group : requiredGroups) {
            Path directory = evidence.resolve(group);
            Map<String, Object> manifest;
            try { manifest = read(safe(directory, "manifest.json")); }
            catch (IOException | IllegalArgumentException error) { throw invalid(group + " shard manifest is missing or malformed: " + error.getMessage()); }
            if (!(manifest.get("schemaVersion") instanceof Number schema) || schema.doubleValue() != 1 || !group.equals(manifest.get("shard")) || list(manifest.get("reports")).isEmpty()) throw invalid(group + " shard manifest is incomplete");
            if (!commit.equals(manifest.get("commit"))) throw invalid(group + " commit mismatch");
            if (!tree.equals(manifest.get("sourceTree"))) throw invalid(group + " source tree mismatch");
            String build = text(manifest.get("buildId")); requireBuild(group, build);
            runIds.add(build.substring(0, build.lastIndexOf('.')));
            Set<String> actual = new HashSet<>();
            files(directory.resolve("tests")).stream().filter(AppTestShardVerifier::isReport).forEach(path -> actual.add(slash(directory.relativize(path))));
            Set<String> listed = new HashSet<>();
            double groupDuration = 0;
            int groupCases = 0, groupSkipped = 0;
            for (Map<String, Object> report : listOfObjects(manifest.get("reports"))) {
                String relative = text(report.get("path"));
                if (!listed.add(relative) || relative.isBlank()) throw invalid(group + " contains a duplicate or invalid report entry");
                if (!actual.contains(relative)) throw invalid(group + " missing report: " + relative);
                Path file = safe(directory, relative);
                if (!digest(file).equals(report.get("sha256"))) throw invalid(group + " report digest mismatch: " + relative);
                Suite suite = parseSuite(file, false);
                if (!suite.className.equals(report.get("className"))) throw invalid(group + " report source mismatch: " + relative);
                if (!suite.executionId.equals("default") && executions.stream().noneMatch(execution -> suite.className.equals(execution.get("className")) && strings(execution.get("identities")).contains(suite.executionId))) throw invalid(suite.className + " has an unauthorized execution identity: " + suite.executionId);
                String source = sourceFor(suite.className, sources);
                if (source == null) throw invalid(group + " report has no matching test source: " + suite.className);
                if (suite.tests == 0 && !sources.get(source).containerOnly) throw invalid("empty required test execution: " + relative);
                if (!Collections.disjoint(sources.get(source).tags, excludedTags)) throw invalid(group + " reported profile-excluded test class: " + source);
                var records = reports.computeIfAbsent(suite.className, key -> new ArrayList<>());
                if (records.stream().anyMatch(record -> !group.equals(record.group))) throw invalid(suite.className + " appears in more than one shard");
                records.add(new Report(group, suite.executionId));
                if (!group.equals(assignments.get(source))) throw invalid(suite.className + " was reported by " + group + "; it belongs to " + assignments.get(source));
                reportedSources.add(source);
                casesBySource.merge(source, suite.tests, Integer::sum);
                groupCases += suite.tests; groupSkipped += suite.skipped;
                cases += suite.tests; skipped += suite.skipped; count++; duration += suite.duration; groupDuration += suite.duration;
                timings.add(map("className", suite.className, "group", group, "durationSeconds", suite.duration));
            }
            if (!actual.equals(listed)) throw invalid(group + " has missing or unlisted JUnit reports");
            for (var entry : assignments.entrySet()) if (entry.getValue().equals(group) && Collections.disjoint(sources.get(entry.getKey()).tags, excludedTags)) {
                if (!reportedSources.contains(entry.getKey())) throw invalid(group + " missing report for " + entry.getKey());
                if (sources.get(entry.getKey()).containerOnly && casesBySource.getOrDefault(entry.getKey(), 0) == 0) throw invalid("empty required test execution: " + entry.getKey());
            }
            Map<String, Object> coverage = object(manifest.get("coverage"));
            Path coverageFile;
            try { coverageFile = safe(directory, text(coverage.get("path"))); }
            catch (IOException | IllegalArgumentException error) { throw invalid(group + " coverage execution is missing: " + error.getMessage()); }
            if (!digest(coverageFile).equals(coverage.get("sha256"))) throw invalid(group + " coverage execution digest mismatch");
            groupDurations.put(group, round(groupDuration));
            groupStatistics.put(group, map("reports", listed.size(), "cases", groupCases, "skipped", groupSkipped));
        }
        if (runIds.size() != 1) throw invalid("shard artifacts do not belong to the same workflow run");
        for (var execution : executions) {
            String className = text(execution.get("className"));
            if (preflight && !requiredGroups.contains(assignments.get(sourceFor(className, sources)))) continue;
            List<String> expected = strings(execution.get("identities")).stream().sorted().toList();
            List<String> actual = reports.getOrDefault(className, List.of()).stream().map(Report::executionId).sorted().toList();
            if (!actual.equals(expected)) throw invalid(className + " execution identities mismatch: expected " + expected + ", found " + actual);
        }
        for (var entry : reports.entrySet()) if (entry.getValue().size() > 1) {
            List<String> allowed = executions.stream().filter(execution -> entry.getKey().equals(execution.get("className"))).findFirst().map(execution -> strings(execution.get("identities"))).orElse(List.of());
            List<String> actual = entry.getValue().stream().map(Report::executionId).toList();
            if (actual.size() != allowed.size() || new HashSet<>(actual).size() != actual.size() || !allowed.containsAll(actual)) throw invalid(entry.getKey() + " has duplicate report execution identities");
        }
        for (Source source : sources.values()) if (!reportedSources.contains(source.name) && !Collections.disjoint(source.tags, excludedTags)) excluded++;
        timings.sort(Comparator.comparingDouble(row -> -((Number) row.get("durationSeconds")).doubleValue()));
        if (preflight) return map("groups", requiredGroups.stream().sorted().toList(), "classes", reports.keySet().stream().sorted().toList(), "slowSuites", timings);
        return map("commit", commit, "sourceTree", tree, "tests", map("classes", reportedSources.size(), "suites", reports.size(), "reports", count, "cases", cases, "skipped", skipped, "excludedClasses", excluded), "coverage", requiredGroups, "durationSeconds", round(duration), "slowSuites", timings.stream().limit(10).toList(), "groupDurations", groupDurations, "groupStatistics", groupStatistics);
    }

    private static Map<String, String> assignments(Map<String, Source> sources, Map<String, Object> groups, String fallback) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            Map<String, Object> group = object(entry.getValue());
            if (!entry.getKey().equals(fallback) && !(group.get("tests") instanceof List<?>)) throw invalid("unknown shard selector for " + entry.getKey());
            for (String name : strings(group.getOrDefault("tests", List.of()))) {
                if (!sources.containsKey(name)) throw invalid(entry.getKey() + " selector has no matching source: " + name);
                if (result.putIfAbsent(name, entry.getKey()) != null) throw invalid(name + " assigned more than once");
            }
        }
        for (Source source : sources.values()) {
            List<String> matching = groups.entrySet().stream().filter(entry -> source.tags.contains(text(object(entry.getValue()).get("tag")))).map(Map.Entry::getKey).toList();
            if (matching.size() > 1) throw invalid(source.name + " assigned more than once");
            if (matching.size() == 1 && !matching.getFirst().equals(result.get(source.name))) throw invalid(source.name + " shard tag is missing from the " + matching.getFirst() + " catalogue selector");
            result.putIfAbsent(source.name, fallback);
            String tag = text(object(groups.get(result.get(source.name))).get("tag"));
            if (!tag.isEmpty() && !source.tags.contains(tag)) throw invalid(source.name + " has no source tag for shard " + result.get(source.name));
        }
        return result;
    }

    static List<String> discover(Path sourceRoot) throws IOException { return sources(sourceRoot).keySet().stream().sorted().toList(); }

    /** Parse Java syntax only: no class loading, compilation, external dependencies or annotation processing. */
    private static Map<String, Source> sources(Path sourceRoot) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw invalid("a JDK is required to discover application test sources");
        List<Path> paths = files(sourceRoot).stream().filter(path -> path.toString().endsWith(".java")).toList();
        Map<String, Source> types = new LinkedHashMap<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(paths));
            for (CompilationUnitTree unit : task.parse()) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                String fileName = Path.of(unit.getSourceFile().toUri()).getFileName().toString();
                new TreeScanner<Void, String>() {
                    @Override public Void visitClass(ClassTree node, String outer) {
                        String simple = node.getSimpleName().toString();
                        if (simple.isEmpty()) return null;
                        String name = outer == null ? (pkg.isEmpty() ? simple : pkg + "." + simple) : outer + "$" + simple;
                        Set<String> tags = new HashSet<>();
                        for (AnnotationTree annotation : node.getModifiers().getAnnotations()) if (annotationName(annotation).equals("Tag")) {
                            for (ExpressionTree argument : annotation.getArguments()) {
                                if (argument instanceof AssignmentTree assignment) argument = assignment.getExpression();
                                if (argument instanceof LiteralTree literal && literal.getValue() instanceof String tag) tags.add(tag);
                            }
                        }
                        boolean[] tests = {false};
                        for (Tree member : node.getMembers()) {
                            if (member instanceof ClassTree) continue;
                            new TreeScanner<Void, Void>() {
                                @Override public Void visitClass(ClassTree nested, Void unused) { return null; }
                                @Override public Void visitAnnotation(AnnotationTree annotation, Void unused) { if (TEST_ANNOTATIONS.contains(annotationName(annotation))) tests[0] = true; return null; }
                            }.scan(member, null);
                        }
                        Source source = new Source(name, simple, pkg, outer, fileName.equals(simple + ".java") && fileName.matches("(?:Test.*|.*Test|.*Tests|.*TestCase|.*IT)\\.java"), node.getModifiers().getFlags().contains(Modifier.ABSTRACT), tests[0], node.getExtendsClause() == null ? "" : node.getExtendsClause().toString(), tags);
                        if (types.putIfAbsent(name, source) != null) throw invalid("duplicate test source identity: " + name);
                        return super.visitClass(node, name);
                    }
                }.scan(unit, null);
            }
        }
        for (var diagnostic : diagnostics.getDiagnostics()) if (diagnostic.getKind() == Diagnostic.Kind.ERROR) throw invalid("malformed test source: " + diagnostic);
        boolean changed;
        do {
            changed = false;
            for (Source source : types.values()) if (!source.hasTests && !source.parent.isEmpty()) {
                String parent = source.parent.replaceAll("<.*>", "");
                Source inherited = types.get(parent);
                if (inherited == null) inherited = types.get(source.pkg + "." + parent);
                if (inherited == null && !parent.contains(".")) {
                    List<Source> matches = types.values().stream().filter(candidate -> candidate.simple.equals(parent)).toList();
                    if (matches.size() == 1) inherited = matches.getFirst();
                }
                if (inherited != null && inherited.hasTests) { source.hasTests = true; changed = true; }
            }
        } while (changed);
        Map<String, Source> result = new LinkedHashMap<>();
        for (Source source : types.values()) if (source.candidate && !source.isAbstract) {
            boolean nested = types.values().stream().anyMatch(child -> child.name.startsWith(source.name + "$") && child.hasTests);
            source.containerOnly = !source.hasTests && nested;
            if (source.hasTests || nested) result.put(source.name, source);
        }
        return result;
    }

    private static String annotationName(AnnotationTree annotation) { String name = annotation.getAnnotationType().toString(); return name.substring(name.lastIndexOf('.') + 1); }
    private static final class Source {
        final String name, simple, pkg, outer, parent;
        final boolean candidate, isAbstract;
        final Set<String> tags;
        boolean hasTests, containerOnly;
        Source(String name, String simple, String pkg, String outer, boolean candidate, boolean isAbstract, boolean hasTests, String parent, Set<String> tags) { this.name = name; this.simple = simple; this.pkg = pkg; this.outer = outer; this.candidate = candidate; this.isAbstract = isAbstract; this.hasTests = hasTests; this.parent = parent; this.tags = tags; }
    }

    static Suite parseSuite(Path file, boolean allowFailures) throws IOException {
        Element root = XmlSupport.parse(file).getDocumentElement();
        if (!root.getTagName().equals("testsuite")) throw invalid("malformed JUnit report: " + file);
        String name = required(root, "name", file), className = name, suffix = "";
        int index = name.lastIndexOf('(');
        if (index > 0 && name.endsWith(")")) { className = name.substring(0, index); suffix = name.substring(index + 1, name.length() - 1); }
        String base = file.getFileName().toString().replaceFirst("\\.xml$", ""), prefix = "TEST-" + className;
        if (!base.equals(prefix) && !base.startsWith(prefix + "-")) throw invalid("JUnit report filename does not identify " + className + ": " + file);
        String identity = base.equals(prefix) ? "default" : base.substring(prefix.length() + 1);
        if (!(identity.equals("default") ? suffix.isEmpty() : identity.equals(suffix))) throw invalid("JUnit report execution suffix mismatch: " + file);
        int tests = count(root, "tests", file), failures = count(root, "failures", file), errors = count(root, "errors", file), skipped = count(root, "skipped", file);
        var cases = XmlSupport.descendants(root, "testcase");
        List<String> failureElements = new ArrayList<>();
        int actualSkipped = 0;
        for (Element testCase : cases) {
            // JUnit engines may use simple names or @DisplayName labels here.
            // Source identity is bound by the suite, filename and manifest instead.
            for (String element : FAILURE_ELEMENTS) for (Element ignored : XmlSupport.descendants(testCase, element)) failureElements.add(element);
            if (!XmlSupport.descendants(testCase, "skipped").isEmpty()) actualSkipped++;
        }
        if (!allowFailures && (failures > 0 || errors > 0 || !failureElements.isEmpty())) throw invalid(file + " contains failure evidence: " + failureElements);
        if (tests != cases.size() || skipped != actualSkipped) throw invalid("inconsistent JUnit testcase evidence: " + file);
        double duration;
        try { duration = root.hasAttribute("time") ? Double.parseDouble(root.getAttribute("time")) : 0; }
        catch (NumberFormatException error) { throw invalid("invalid JUnit duration: " + file); }
        if (!Double.isFinite(duration) || duration < 0) throw invalid("invalid JUnit duration: " + file);
        return new Suite(className, identity, tests, failures, errors, skipped, failureElements, duration);
    }

    record Suite(String className, String executionId, int tests, int failures, int errors, int skipped, List<String> failureElements, double duration) { }
    private record Report(String group, String executionId) { }
    private static String required(Element node, String key, Path file) { if (node.getAttribute(key).isEmpty()) throw invalid("JUnit report has no " + key + ": " + file); return node.getAttribute(key); }
    private static int count(Element node, String key, Path file) { String value = required(node, key, file); if (!value.matches("[0-9]+")) throw invalid("invalid JUnit " + key + ": " + file); try { return Integer.parseInt(value); } catch (NumberFormatException error) { throw invalid("invalid JUnit " + key + ": " + file); } }
    private static String sourceFor(String name, Map<String, Source> sources) { while (name != null) { if (sources.containsKey(name)) return name; int index = name.lastIndexOf('$'); name = index < 0 ? null : name.substring(0, index); } return null; }
    private static void requireIds(String commit, String tree) { if (commit == null || tree == null || !commit.matches("(?i)[a-f0-9]{40}") || !tree.matches("(?i)[a-f0-9]{40}")) throw invalid("expected commit and source tree must be full Git object IDs"); }
    private static void requireBuild(String shard, String build) { if (build == null || !build.matches("[0-9]+\\.[0-9]+\\." + java.util.regex.Pattern.quote(shard))) throw invalid(shard + " build identity is invalid"); }
    private static boolean inside(Path parent, Path child) { return !parent.equals(child) && child.startsWith(parent); }
    private static String slash(Path path) { return path.toString().replace('\\', '/'); }
    private static boolean isReport(Path file) { return file.getFileName().toString().matches("TEST-.+\\.xml"); }
    private static Path safe(Path directory, String relative) throws IOException {
        if (relative.isBlank() || relative.contains("\\") || Path.of(relative).isAbsolute() || Arrays.asList(relative.split("/")).contains("..")) throw invalid("invalid shard artifact path: " + relative);
        Path file = directory.resolve(relative).normalize();
        if (!inside(directory, file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("shard artifact is missing: " + relative);
        if (!inside(directory.toRealPath(), file.toRealPath())) throw invalid("invalid shard artifact symlink: " + relative);
        return file;
    }
    private static List<Path> files(Path directory) throws IOException { if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of(); try (Stream<Path> walk = Files.walk(directory)) { return walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList(); } }
    static void delete(Path path) throws IOException { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) try (Stream<Path> walk = Files.walk(path)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
    static String digest(Path path) throws IOException { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    static Map<String, Object> read(Path path) throws IOException { return FlatJson.parseObject(Files.readString(path)); }
    static void write(Path path, Object value) throws IOException { Files.createDirectories(path.getParent()); Files.writeString(path, FlatJson.pretty(value) + "\n"); }
    static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { if (!(value instanceof Map<?, ?>)) throw invalid("expected JSON object in shard catalogue or manifest"); return (Map<String, Object>) value; }
    static List<?> list(Object value) { if (!(value instanceof List<?> values)) throw invalid("expected JSON array in shard catalogue or manifest"); return values; }
    static List<Map<String, Object>> listOfObjects(Object value) { return list(value).stream().map(AppTestShardVerifier::object).toList(); }
    static List<String> strings(Object value) { return list(value).stream().map(item -> { if (!(item instanceof String text)) throw invalid("expected string in shard catalogue"); return text; }).toList(); }
    private static String text(Object value) { return value instanceof String text ? text : ""; }
    private static double round(double value) { return Math.round(value * 1000) / 1000.0; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
