#!/usr/bin/env python3
"""Disposable, bounded verification for #1062/#1063; never updates branch refs."""
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path.cwd()
TEMP = Path(os.environ['RUNNER_TEMP'])
HAS_TEMPLATES = Path('taxonomy-templates/pom.xml').is_file()
BASE = os.environ['SOURCE_BASE']


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def replace_once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new, 1)


def prepare():
    path = Path('taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java')
    text = path.read_text()
    start = text.index('    @ParameterizedTest(name = "{0}: {1}")')
    end = text.index('    private ContextPolicy readAndValidateContextPolicy', start)
    tests = r'''    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("featureModuleRootSources")
    void rejectsRootPackageSourcesInFeatureModules(
            String module, String fileName, @TempDir Path fixture) throws Exception {
        ContextPolicy policy = readAndValidateContextPolicy(
                findRepositoryRoot().resolve(".github/architecture-contexts.json"));
        for (String sourceModule : SOURCE_MODULES) {
            Path root = Files.createDirectories(fixture.resolve(
                    sourceModule + "/src/main/java/com/taxonomy"));
            if ("taxonomy-app".equals(sourceModule)) {
                for (String compositionFile : policy.rootCompositionClasses()) {
                    Files.writeString(root.resolve(compositionFile), "package com.taxonomy;\n");
                }
            } else {
                String context = sourceModule.substring("taxonomy-".length());
                Path packageRoot = Files.createDirectories(root.resolve(context));
                Files.writeString(packageRoot.resolve("Sample.java"),
                        "package com.taxonomy." + context + ";\n");
            }
        }
        // The application allow-list and classified feature packages remain valid.
        validateSourceCoverage(fixture, policy);
        Files.writeString(fixture.resolve(
                module + "/src/main/java/com/taxonomy/" + fileName), "package com.taxonomy;\n");
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> validateSourceCoverage(fixture, policy))
                .withMessageContaining(module)
                .withMessageContaining(fileName);
    }

    private static java.util.stream.Stream<Arguments> featureModuleRootSources() {
        return SOURCE_MODULES.stream()
                .filter(module -> !"taxonomy-app".equals(module))
                .flatMap(module -> java.util.stream.Stream.of(
                                "UnexpectedRoot.java", "AppConfig.java", "package-info.java")
                        .map(fileName -> Arguments.of(module, fileName)));
    }

'''
    text = text[:start] + tests + text[end:]
    start = text.index('            if (!"taxonomy-app".equals(module)) {')
    end = text.index('            try (var sources = Files.walk(contextRoot))', start)
    guard = '''            if (!"taxonomy-app".equals(module)) {
                try (var rootSources = Files.list(contextRoot)) {
                    assertThat(rootSources.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(fileName -> fileName.endsWith(".java"))
                            .sorted().toList())
                            .as("Root-package Java files are reserved for taxonomy-app, not %s", module)
                            .isEmpty();
                }
            }
'''
    text = text[:start] + guard + text[end:]
    text = text.replace('import org.junit.jupiter.params.provider.MethodSource;\nimport org.junit.jupiter.params.provider.Arguments;',
                        'import org.junit.jupiter.params.provider.Arguments;\nimport org.junit.jupiter.params.provider.MethodSource;')
    path.write_text(text)
    for lang in ('en', 'de'):
        path = Path('docs') / lang / 'MODULE_BOUNDARIES.md'
        text = path.read_text()
        heading = '## Migration order' if lang == 'en' else '## Migrationsreihenfolge'
        start = text.index(heading)
        end = text.index('\n## ', start + len(heading))
        if lang == 'en':
            status = ('physically extracted in this revision' if HAS_TEMPLATES else 'next independent extraction; not present in this revision')
            section = f'''## Migration order

Issue #628 remains the implementation parent. Extraction follows each candidate's actual dependencies; cycles in unrelated contexts do not block an independent library. The status below describes the code in this revision, not the merge status of a pull request.

1. Context map and dependency ratchet — implemented.
2. Workspace authority and storage ownership — separated from application orchestration.
3. `taxonomy-workspace` — physically extracted in this revision.
4. `taxonomy-templates` — {status}.
5. `taxonomy-knowledge` and `taxonomy-interop` — pending; stabilize their owned APIs and remove blocking implementation dependencies.
6. `taxonomy-architecture`, `taxonomy-analysis` and `taxonomy-portfolio` — pending; resolve their remaining cycles before each extraction.
7. Reassess `provenance` and `preferences` after those boundaries are stable.

Every extracted feature library must remain independent of `taxonomy-app`; the application is the only deployment/composition root.
'''
        else:
            status = ('in dieser Revision physisch ausgelagert' if HAS_TEMPLATES else 'nächste unabhängige Auslagerung; in dieser Revision noch nicht vorhanden')
            section = f'''## Migrationsreihenfolge

Issue #628 bleibt die übergeordnete Implementierungsaufgabe. Die Auslagerung folgt den tatsächlichen Abhängigkeiten des jeweiligen Kandidaten; Zyklen anderer Kontexte blockieren keine unabhängige Bibliothek. Der folgende Status beschreibt den Code dieser Revision, nicht den Merge-Status eines Pull Requests.

1. Context-Map und Dependency-Ratchet — implementiert.
2. Workspace-Autorität und Storage-Zuständigkeit — von der Anwendungsorchestrierung getrennt.
3. `taxonomy-workspace` — in dieser Revision physisch ausgelagert.
4. `taxonomy-templates` — {status}.
5. `taxonomy-knowledge` und `taxonomy-interop` — offen; eigene APIs stabilisieren und blockierende Implementierungsabhängigkeiten entfernen.
6. `taxonomy-architecture`, `taxonomy-analysis` und `taxonomy-portfolio` — offen; verbleibende Zyklen vor der jeweiligen Auslagerung auflösen.
7. `provenance` und `preferences` nach Stabilisierung dieser Grenzen erneut bewerten.

Jede ausgelagerte Fachbibliothek bleibt unabhängig von `taxonomy-app`; nur die Anwendung übernimmt Deployment und Komposition.
'''
        path.write_text(text[:start] + section + text[end:])
    git('diff', '--check')


def application():
    paths = list(Path('taxonomy-app/target').glob('taxonomy-app-*.jar'))
    assert len(paths) == 1, paths
    return paths[0]


def cases():
    result = [('workspace-class', 'WorkspaceModulePackagingIT', 'taxonomy-workspace',
               'com/taxonomy/editor/EditorJournal.class')]
    if HAS_TEMPLATES:
        result += [('template-resource', 'TemplatesModulePackagingIT', 'taxonomy-templates',
                    'document-templates/decision-rationale-report.dotx'),
                   ('template-class', 'TemplatesModulePackagingIT', 'taxonomy-templates',
                    'com/taxonomy/templates/DocumentTemplateGitRepository.class')]
    return result


def poison(module, entry):
    path = application()
    backup = TEMP / 'real-application.jar'
    if not backup.exists():
        shutil.copy2(path, backup)
    shutil.copy2(backup, path)
    with zipfile.ZipFile(path) as archive:
        owners = [name for name in archive.namelist()
                  if name.startswith('BOOT-INF/lib/' + module + '-') and name.endswith('.jar')]
        assert len(owners) == 1, owners
        with zipfile.ZipFile(io.BytesIO(archive.read(owners[0]))) as library:
            content = library.read(entry)
    duplicate = io.BytesIO()
    with zipfile.ZipFile(duplicate, 'w') as library:
        library.writestr(entry, content)
    with zipfile.ZipFile(path, 'a') as archive:
        archive.writestr('BOOT-INF/lib/duplicate-owner-fixture.jar', duplicate.getvalue())


def run_contract(selector, log_name, expected_failures):
    for path in Path('taxonomy-build/target/surefire-reports').glob('TEST-*ModulePackagingIT.xml'):
        path.unlink()
    command = ['./mvnw', '-B', '-ntp', '-pl', 'taxonomy-build', '-am',
               '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=' + selector, 'test']
    with (TEMP / log_name).open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
    suites = []
    for name in selector.split(','):
        path = Path('taxonomy-build/target/surefire-reports') / ('TEST-com.taxonomy.build.' + name + '.xml')
        if not path.exists():
            print((TEMP / log_name).read_text()[-12000:])
            raise AssertionError('Missing JUnit evidence: ' + str(path))
        suites.append(ET.parse(path).getroot())
    totals = {key: sum(int(s.get(key, '0')) for s in suites)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] == len(suites) and totals['failures'] == expected_failures, totals
    assert totals['errors'] == 0 and totals['skipped'] == 0, totals
    assert (result.returncode == 0) == (expected_failures == 0), result.returncode
    if expected_failures:
        for suite in suites:
            failure = suite.find('.//failure')
            assert failure is not None and 'duplicate-owner-fixture.jar' in failure.get('message', '')
    print(log_name, totals, flush=True)


def controls(phase):
    for case, selector, module, entry in cases():
        poison(module, entry)
        run_contract(selector, phase + '-' + case + '.log', 0 if phase == 'old' else 1)
    shutil.copy2(TEMP / 'real-application.jar', application())


def patch_packaging():
    path = Path('taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java')
    text = path.read_text()
    text = replace_once(text, 'import java.util.List;', 'import java.util.HashSet;\nimport java.util.List;')
    text = replace_once(text, 'import java.util.zip.ZipFile;', 'import java.util.zip.ZipFile;\nimport java.util.zip.ZipInputStream;')
    old = '''            assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-")
                    && n.endsWith(".jar")).toList()).hasSize(1);'''
    new = '''            var libraries = names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-")
                    && n.endsWith(".jar")).toList();
            assertThat(libraries).hasSize(1);
            var ownedClasses = new HashSet<String>();
            for (String name : names) {
                if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) {
                    continue;
                }
                try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(name)))) {
                    for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                        String path = entry.getName();
                        if (path.endsWith(".class") && List.of("workspace", "versioning", "editor").stream()
                                .anyMatch(part -> path.startsWith("com/taxonomy/" + part + "/"))) {
                            assertThat(name).as("library owning %s", path).isEqualTo(libraries.getFirst());
                            assertThat(ownedClasses.add(path)).as("single occurrence of %s", path).isTrue();
                        }
                    }
                }
            }
            assertThat(ownedClasses).contains("com/taxonomy/editor/EditorJournal.class",
                    "com/taxonomy/workspace/storage/DslGitRepository.class",
                    "com/taxonomy/versioning/service/RepositoryStateService.class");'''
    path.write_text(replace_once(text, old, new))
    if HAS_TEMPLATES:
        path = Path('taxonomy-build/src/test/java/com/taxonomy/build/TemplatesModulePackagingIT.java')
        text = path.read_text()
        old = '''            try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(libraries.getFirst())))) {
                for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                    entries.add(entry.getName());
                }
            }'''
        new = '''            for (String name : names) {
                if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) {
                    continue;
                }
                try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(name)))) {
                    for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                        String path = entry.getName();
                        if ((path.startsWith("com/taxonomy/templates/") && path.endsWith(".class"))
                                || path.equals("document-templates/decision-rationale-report.dotx")) {
                            assertThat(name).as("library owning %s", path).isEqualTo(libraries.getFirst());
                            assertThat(entries.add(path)).as("single occurrence of %s", path).isTrue();
                        }
                    }
                }
            }'''
        path.write_text(replace_once(text, old, new))
    git('diff', '--check')


def clean_contracts():
    shutil.copy2(TEMP / 'real-application.jar', application())
    selector = 'WorkspaceModulePackagingIT'
    if HAS_TEMPLATES:
        selector += ',TemplatesModulePackagingIT'
    run_contract(selector, 'real-packaging-green.log', 0)


def publish():
    allowed = {'taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java',
               'taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java',
               'taxonomy-build/src/test/java/com/taxonomy/build/TemplatesModulePackagingIT.java',
               'docs/en/MODULE_BOUNDARIES.md', 'docs/de/MODULE_BOUNDARIES.md', 'docs/dev/REACTOR_COVERAGE.md'}
    paths = git('diff', BASE, '--name-only').splitlines()
    assert set(paths).issubset(allowed), paths
    assert git('diff', BASE, '--name-only', '--', '*/src/main/', '*.xml', '.github/', '.mvn/') == ''
    def post(endpoint, body):
        req = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/' + endpoint,
                data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    entries = []
    for path in paths:
        sha = post('git/blobs', {'content': Path(path).read_text(), 'encoding': 'utf-8'})['sha']
        assert sha == git('hash-object', path)
        entries.append({'path': path, 'type': 'blob', 'mode': git('ls-files', '-s', '--', path).split()[0], 'sha': sha})
    tree = post('git/trees', {'base_tree': git('rev-parse', BASE + '^{tree}'), 'tree': entries})['sha']
    git('add', '--', *paths)
    assert git('write-tree') == tree
    result = {'parent': BASE, 'tree': tree, 'entries': entries}
    print('VERIFIED_CHANGE', json.dumps(result), flush=True)
    print(git('diff', '--cached', '--stat', BASE), flush=True)
    (TEMP / 'verified-packaging-change.json').write_text(json.dumps(result, indent=2))


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'prepare':
        prepare()
    elif mode in ('old', 'new'):
        controls(mode)
    elif mode == 'patch':
        patch_packaging()
    elif mode == 'clean':
        clean_contracts()
    elif mode == 'publish':
        publish()
    else:
        raise SystemExit('Unknown mode: ' + mode)
