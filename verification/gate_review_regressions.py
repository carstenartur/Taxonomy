"""Disposable verification for PR 1054; publish tested blobs, never move refs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = '2ec4c52fb929838832123b855edc28a9c154590a'
PREFIX = 'taxonomy-build/src/test/java/com/taxonomy/'
SYNC = PREFIX + 'ArchitectureSelectorSynchronizationTest.java'
GRAPH = PREFIX + 'ArchitectureModuleGraphTest.java'
ADAPTER = PREFIX + 'ArchitectureModuleExtractionTest.java'
ALLOWED = {SYNC, GRAPH, ADAPTER}
root = Path(sys.argv[2]).resolve()
out = Path(os.environ['RUNNER_TEMP']) / 'gate-review-evidence'
out.mkdir(exist_ok=True)

def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()

assert git('rev-parse', 'HEAD') == BASE

def replace(path, old, new):
    file = root / path
    text = file.read_text()
    assert text.count(old) == 1, (path, old)
    file.write_text(text.replace(old, new, 1))

SELECTOR_TESTS = r'''    @ParameterizedTest
    @MethodSource("selectedGuards")
    void renamedSelectedGuardDeclarationIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Files.writeString(sourcePath(fixture, guard), "package com.taxonomy; class RenamedGuard {}\n");
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"comment", "nested", "wrong-package", "interface", "malformed"})
    void aGuardFilenameDoesNotSubstituteForItsDeclaration(String kind) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        String source = switch (kind) {
            case "comment" -> "package com.taxonomy; /* class " + guard + " {} */";
            case "nested" -> "package com.taxonomy; class Other { class " + guard + " {} }";
            case "wrong-package" -> "package other; class " + guard + " {}";
            case "interface" -> "package com.taxonomy; interface " + guard + " {}";
            case "malformed" -> "package com.taxonomy; class " + guard + " {";
            default -> throw new IllegalArgumentException(kind);
        };
        Files.writeString(sourcePath(fixture, guard), source);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void annotatedPackagePrivateGuardWithCommentsIsAccepted() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Files.writeString(sourcePath(fixture, guard), """
                /* class NotTheGuard {} */
                package com.taxonomy;
                @Deprecated
                class ArchitectureCycleBoundaryTest {
                    String example = "class Another {}";
                }
                """);
        assertThatCode(() -> assertSelectors(fixture)).doesNotThrowAnyException();
    }

'''

PARENT_TESTS = r'''    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"child-first", "parent-first", "external-child-first"})
    void propertyReactorParentLookupDoesNotDependOnDeclarationOrder(String order) throws Exception {
        String group = order.startsWith("external") ? "org.example.build" : "com.taxonomy";
        String modules = order.equals("parent-first")
                ? "<module>build-parent</module><module>taxonomy-a</module>"
                : "<module>taxonomy-a</module><module>build-parent</module>";
        pom("", "taxonomy", "<modules><module>taxonomy-app</module>" + modules + "</modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>%s</groupId><artifactId>build-parent</artifactId><version>1</version>
                  <relativePath/></parent>
                <groupId>com.taxonomy</groupId><version>1</version>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-domain</artifactId>
                  <version>1</version></dependency></dependencies>
                """.formatted(group));
        pom("build-parent", group, "build-${parent.name}", """
                <packaging>pom</packaging>
                <properties><parent.name>parent</parent.name></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                <dependencyManagement><dependencies><dependency><groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy-domain</artifactId><version>1</version><scope>test</scope>
                </dependency></dependencies></dependencyManagement>
                """);

        assertThatCode(() -> {
            var discovered = ArchitectureModuleExtractionTest.discoverModules(temporaryRepository, POLICY);
            assertThat(discovered).containsEntry(A, temporaryRepository.resolve("taxonomy-a"))
                    .containsEntry("build-parent", temporaryRepository.resolve("build-parent"));
            assertThat(ArchitectureModuleExtractionTest.readProductionModuleDependencies(
                    temporaryRepository, discovered, POLICY)).containsExactly(new ModuleDependency(A, APP));
        }).doesNotThrowAnyException();
    }

    @Test
    void laterPropertyParentCanInheritItsOwnArtifactPropertyThroughAnotherReactorParent() throws Exception {
        pom("", "taxonomy", "<modules><module>taxonomy-app</module><module>taxonomy-a</module>"
                + "<module>build-parent</module><module>build-grandparent</module></modules>");
        pom("taxonomy-app", APP, "");
        pom("taxonomy-a", "${feature.module}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>build-parent</artifactId><version>1</version>
                  <relativePath/></parent>
                <properties><feature.module>taxonomy-a</feature.module></properties>
                """);
        pom("build-parent", "build-${parent.name}", """
                <parent><groupId>com.taxonomy</groupId><artifactId>build-grandparent</artifactId><version>1</version>
                  <relativePath/></parent><packaging>pom</packaging>
                """);
        pom("build-grandparent", "build-${grandparent.name}", """
                <packaging>pom</packaging>
                <properties><grandparent.name>grandparent</grandparent.name><parent.name>parent</parent.name></properties>
                <dependencies><dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>
                  <version>1</version><scope>runtime</scope></dependency></dependencies>
                """);
        assertThatCode(() -> assertThat(fixturePomDependencies())
                .containsExactly(new ModuleDependency(A, APP))).doesNotThrowAnyException();
    }

'''

def red():
    replace(SYNC, '    private static Stream<String> selectedGuards() {',
            SELECTOR_TESTS + '    private static Stream<String> selectedGuards() {')
    replace(GRAPH, '    private void externalParentPoms(String artifact, String relative) throws Exception {',
            PARENT_TESTS + '    private void externalParentPoms(String artifact, String relative) throws Exception {')

DECLARATION_CHECK = r'''    private static void assertGuardDeclaration(Path source, String guard) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK parser for selected architecture guard %s", guard).isNotNull();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT,
                java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(new java.io.StringWriter(), files,
                    diagnostics, List.of("--release", "21", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(List.of(source)));
            List<String> declarations = new ArrayList<>();
            for (var unit : task.parse()) {
                if (unit.getPackageName() != null && unit.getPackageName().toString().equals("com.taxonomy")) {
                    for (var declaration : unit.getTypeDecls()) {
                        if (declaration instanceof com.sun.source.tree.ClassTree type
                                && type.getKind() == com.sun.source.tree.Tree.Kind.CLASS) {
                            declarations.add(type.getSimpleName().toString());
                        }
                    }
                }
            }
            assertThat(diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR).toList())
                    .as("valid Java declaration for selected architecture guard %s", guard).isEmpty();
            assertThat(declarations).as("selected architecture guard %s must be declared in com.taxonomy", guard)
                    .contains(guard);
        }
    }

'''

def green():
    replace(SYNC, '''                    .as("selected architecture guard %s must remain inside the checkout", guard)
                    .isTrue();''', '''                    .as("selected architecture guard %s must remain inside the checkout", guard)
                    .isTrue();
            assertGuardDeclaration(source, guard);''')
    replace(SYNC, '    private static List<String> selectors(String value) {',
            DECLARATION_CHECK + '    private static List<String> selectors(String value) {')
    replace(ADAPTER, '''        // Local reactor parents may be declared after the child that inherits
        // its artifact property. Collect constant coordinates before resolving.
        Map<Path, LocalPom> declarationModels = new TreeMap<>();
        for (Path pom : propertyModulePoms) {
            LocalPom model = localPom(pom, root, modules, declarationModels, new HashSet<>());''', '''        // Keep every declared POM available while resolving coordinates. A
        // later property-artifact parent must be visible even with relativePath disabled.
        List<Path> declaredPoms = new ArrayList<>(propertyModulePoms);
        modules.values().forEach(directory -> declaredPoms.add(directory.resolve("pom.xml")));
        Map<Path, LocalPom> declarationModels = new TreeMap<>();
        for (Path pom : propertyModulePoms) {
            LocalPom model = localPom(pom, root, modules, declarationModels, new HashSet<>(), declaredPoms);''')
    replace(ADAPTER, '''    private static LocalPom localPom(Path file, Path root, Map<String, Path> modules, Map<Path, LocalPom> cache,
                                     Set<Path> resolving) throws Exception {
        file = repositoryPomPath(file, root);''', '''    private static LocalPom localPom(Path file, Path root, Map<String, Path> modules, Map<Path, LocalPom> cache,
                                     Set<Path> resolving) throws Exception {
        return localPom(file, root, modules, cache, resolving,
                modules.values().stream().map(directory -> directory.resolve("pom.xml")).toList());
    }

    private static LocalPom localPom(Path file, Path root, Map<String, Path> modules, Map<Path, LocalPom> cache,
                                     Set<Path> resolving, List<Path> declaredPoms) throws Exception {
        file = repositoryPomPath(file, root);''')
    replace(ADAPTER, '''                for (Path candidate : candidates) {
                    candidate = repositoryPomPath(candidate, root);''', '''                for (Path declared : declaredPoms) {
                    Path candidate = repositoryPomPath(declared, root);
                    // A registry fallback is not a self-parent declaration. Explicit
                    // relative/ref self-cycles still go through the normal cycle check.
                    if (!Files.isSameFile(candidate, file)) {
                        candidates.add(candidate);
                    }
                }
                for (Path candidate : candidates.stream().distinct().toList()) {
                    candidate = repositoryPomPath(candidate, root);''')
    replace(ADAPTER, 'LocalPom possible = localPom(candidate, root, modules, cache, resolving);',
            'LocalPom possible = localPom(candidate, root, modules, cache, resolving, declaredPoms);')
    replace(ADAPTER, '                        matchingReactorParent |= registeredReactorParent;',
            '                        matchingReactorParent |= registeredReactorParent || declaredPoms.contains(candidate);')


def run(phase):
    command = ['./mvnw', '-B', '-Parchitecture-tests', '-Dsurefire.failIfNoSpecifiedTests=false', 'clean', 'verify']
    print('COMMAND', phase, ' '.join(command), flush=True)
    with (out / (phase + '.log')).open('w') as stream:
        result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    reports = {}
    for file in root.glob('*/target/surefire-reports/TEST-*.xml'):
        suite = ET.parse(file).getroot()
        reports[suite.attrib['name']] = suite
    required = ['com.taxonomy.ArchitectureSelectorSynchronizationTest', 'com.taxonomy.ArchitectureModuleGraphTest']
    assert all(name in reports for name in required), (out / (phase + '.log')).read_text()[-24000:]
    totals = {key: sum(int(suite.attrib[key]) for suite in reports.values())
              for key in ('tests', 'failures', 'errors', 'skipped')}
    print(phase.upper(), 'TOTALS', json.dumps(totals), 'CLASSES', len(reports), flush=True)
    failures = []
    for name, suite in reports.items():
        for case in suite.findall('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                failures.append((name, case.attrib['name'], failure.attrib.get('message', '') + (failure.text or '')))
    if phase == 'red':
        expected = ('renamedSelectedGuardDeclarationIsRejected', 'aGuardFilenameDoesNotSubstituteForItsDeclaration',
                    'propertyReactorParentLookupDoesNotDependOnDeclarationOrder',
                    'laterPropertyParentCanInheritItsOwnArtifactPropertyThroughAnotherReactorParent')
        assert result.returncode != 0 and totals['errors'] == 0 and totals['skipped'] == 0, totals
        assert len(failures) >= 21, failures
        assert all(any(case.startswith(method) for method in expected) for _, case, _ in failures), failures
        assert any('Cannot resolve local reactor parent' in detail for _, _, detail in failures), failures
        for name, case, detail in failures:
            print('REPRODUCED', name, case, detail[:250], flush=True)
        (out / 'red-results.json').write_text(json.dumps({'totals': totals, 'failedCases': failures}, indent=2))
    else:
        assert result.returncode == 0, (out / (phase + '.log')).read_text()[-30000:]
        assert totals == {'tests': 196, 'failures': 0, 'errors': 0, 'skipped': 0} and len(reports) == 14, totals
        subprocess.run(['git', 'diff', '--check'], cwd=root, check=True)
        assert set(git('diff', '--name-only').splitlines()) == ALLOWED
        for name in required:
            suite = reports[name]
            (out / (name + '.xml')).write_bytes(ET.tostring(suite))
        graph = root / 'taxonomy-build/target/architecture-module-graph.txt'
        digest = hashlib.sha256(graph.read_bytes()).hexdigest()
        assert digest == '54218255ab78eb0fc9a65dcdcf9d0e8e0415589690f66a89331ba03d43e39352', digest
        (out / 'change.patch').write_text(git('diff', '--binary') + '\n')
        (out / 'PASSED').write_text(json.dumps({'base': BASE, 'totals': totals, 'classes': len(reports), 'graphSha256': digest}))
        print('VERIFIED', (out / 'PASSED').read_text(), flush=True)
        print(git('diff', '--stat'), flush=True)


def publish():
    verified = json.loads((out / 'PASSED').read_text())
    assert verified['base'] == BASE
    assert set(git('diff', '--name-only').splitlines()) == ALLOWED
    entries = []
    for path in sorted(ALLOWED):
        req = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/git/blobs',
                data=json.dumps({'content': (root / path).read_text(), 'encoding': 'utf-8'}).encode(),
                headers={'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'], 'Accept': 'application/vnd.github+json',
                         'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as response:
            sha = json.load(response)['sha']
        assert sha == git('hash-object', path)
        entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': sha})
    subprocess.run(['git', 'add', '--', *sorted(ALLOWED)], cwd=root, check=True)
    manifest = {'base': BASE, 'tree': git('write-tree'), 'entries': entries}
    (out / 'blobs.json').write_text(json.dumps(manifest, indent=2))
    print('VERIFIED_BLOBS', json.dumps(manifest), flush=True)

mode = sys.argv[1]
if mode == 'red':
    red()
    run(mode)
elif mode == 'green':
    green()
    run(mode)
elif mode == 'publish':
    publish()
else:
    raise ValueError(mode)
