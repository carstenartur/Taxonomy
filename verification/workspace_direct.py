"""Apply the already-specified relocation directly to main; no branch mutation."""
from pathlib import Path
import sys, subprocess, json, hashlib, zipfile, io
MAIN = '8cd4395d93ba56c80ae6ea5dff031944772e7771'
source = Path(__file__).with_name('physical_workspace.py').read_text().split('\nmode=sys.argv[1]')[0]
source = source.replace("BASE = 'c0d7bd654ba774daa0701b6e18dfa3fb85c3a92c'", "BASE = '" + MAIN + "'")
ns = {}
exec(compile(source, 'physical_workspace.py', 'exec'), ns)
root, out = ns['root'], ns['out']
original_replace = ns['replace']

def scoped_replace(path, old, new):
    if path.endswith('/ConfigurationReferenceContractTest.java') and old == '        return Set.copyOf(variables);':
        file = root / path
        text = file.read_text()
        begin = text.index('        for (String module : java.util.List.of("taxonomy-app", "taxonomy-workspace")) {')
        at = text.index(old, begin)
        assert text[begin:at].count('collectConfigurationProperties(source, explicitPropertyVariables, variables);') == 1
        file.write_text(text[:at] + new + text[at + len(old):])
    else:
        original_replace(path, old, new)
ns['replace'] = scoped_replace

PACKAGING = '''package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceModulePackagingIT {
    @Test
    void bootApplicationContainsOneWorkspaceLibraryAndNoDuplicateWorkspaceClasses() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        List<Path> applications;
        try (var files = Files.list(root.resolve("taxonomy-app/target"))) {
            applications = files.filter(p -> p.getFileName().toString().startsWith("taxonomy-app-")
                    && p.getFileName().toString().endsWith(".jar")).toList();
        }
        assertThat(applications).hasSize(1);
        try (var jar = new ZipFile(applications.getFirst().toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-")
                    && n.endsWith(".jar")).toList()).hasSize(1);
            for (String part : List.of("workspace", "versioning", "editor")) {
                assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/" + part + "/"))).isFalse();
            }
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
    }
}
'''

def prepare():
    ns['apply']()
    physical = '''package com.taxonomy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Requires actual source and compiled ownership of the extracted context. */
class ArchitectureWorkspaceModuleTest {
''' + ns['PHYSICAL_TEST'] + '''
    private static Path checkoutRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Repository root not found");
        return root;
    }
}
'''
    ns['put']('taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkspaceModuleTest.java', physical)
    ns['put']('taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java', PACKAGING)
    for name in ('pom.xml', '.mvn/verification-suites.json'):
        original_replace(name, 'ArchitectureWorkspaceStorageOwnershipTest', 'ArchitectureWorkspaceStorageOwnershipTest,ArchitectureWorkspaceModuleTest')
    # Preserve the readable indentation of the two extended production source scans.
    for name, start, end in [
        ('taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java',
         '        for (String module : java.util.List.of("taxonomy-app", "taxonomy-workspace")) {',
         '        return Set.copyOf(variables);'),
        ('taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java',
         '        for (String module : List.of("taxonomy-app", "taxonomy-workspace")) {',
         '        assertThat(unclassifiedPackages)')]:
        p = root / name
        text = p.read_text()
        begin = text.index(start) + len(start)
        finish = text.index(end, begin)
        lines = text[begin:finish].splitlines()
        closing = max(i for i, line in enumerate(lines) if line.strip() == '}')
        lines = [('    ' + line if 0 < i < closing and line.strip() else line) for i, line in enumerate(lines)]
        p.write_text(text[:begin] + '\n'.join(lines) + '\n' + text[finish:])
    # Current documentation follows the physical implementation; historical records are not rewritten.
    for lang in ('en', 'de'):
        p = root / 'docs' / lang / 'MODULE_BOUNDARIES.md'
        text = p.read_text()
        if lang == 'en':
            text = text.replace('Its eleven selected test classes', 'Its twelve selected test classes')
            text += '''\n## Physical workspace module\n\n`taxonomy-workspace` owns the workspace, versioning and editor production packages.\n`taxonomy-app` depends on its ordinary JAR; application configuration and SQL migrations\nremain in the application. Java package names and runtime contracts are unchanged.\n`ArchitectureWorkspaceModuleTest` is included in both architecture selectors and\nrequires physical source and compiled ownership. Maven enforces no dependency back\nto the application. The other six planned feature modules remain to be extracted.\n'''
        else:
            text = text.replace('Die elf ausgewählten Testklassen', 'Die zwölf ausgewählten Testklassen')
            text += '''\n## Physisches Workspace-Modul\n\n`taxonomy-workspace` enthält die Production-Packages für Workspace, Versionierung\nund Editor. `taxonomy-app` bindet das normale JAR ein; Anwendungskonfiguration und\nSQL-Migrationen bleiben in der Anwendung. Java-Packages und Laufzeitverträge sind unverändert.\n`ArchitectureWorkspaceModuleTest` ist in beiden Architektur-Selektoren enthalten und\nprüft die physische Source- und Klassen-Zuordnung. Maven verbietet Rückabhängigkeiten\nauf die Anwendung. Die sechs anderen geplanten Fachmodule sind noch auszulagern.\n'''
        p.write_text(text)
    # The CI scope check is a shell path list, not a YAML paths entry.
    ci = root / '.github/workflows/ci-cd.yml'
    text = ci.read_text()
    text = text.replace('-- pom.xml Dockerfile taxonomy-app/pom.xml taxonomy-app/src/main ',
                        '-- pom.xml Dockerfile taxonomy-workspace/pom.xml taxonomy-workspace/src/main taxonomy-app/pom.xml taxonomy-app/src/main ')
    ci.write_text(text)
    # Ensure report manifests follow any module-local test paths they explicitly name.
    for p in list((root / '.github').rglob('*')) + list((root / '.mvn').rglob('*')):
        if not p.is_file() or p.suffix not in ('.json', '.yml', '.sh', '.mjs'): continue
        text = p.read_text(); changed = text
        for name in ns['TEST_MOVES']:
            fqcn = 'com.taxonomy.' + name[:-5].replace('/', '.')
            changed = changed.replace('taxonomy-app/target/surefire-reports/TEST-' + fqcn + '.xml',
                                      'taxonomy-workspace/target/surefire-reports/TEST-' + fqcn + '.xml')
        if changed != text: p.write_text(changed)
    assert not (root / ns['GRAPH']).exists(), 'No unmerged module-gate implementation in this extraction'
    subprocess.run(['git', 'diff', '--check'], cwd=root, check=True)
    print('DIRECT MAIN EXTRACTION: 104 unchanged production sources, 14 moved test classes, no stacked PR dependency', flush=True)


def verify(mode):
    command = ['./mvnw', '-B']
    if mode == 'architecture': command += ['-Parchitecture-tests', '-Dsurefire.failIfNoSpecifiedTests=false', 'clean', 'verify']
    else: command += ['verify', '-DexcludedGroups=real-llm']
    result = ns['run'](mode, command)
    if result == 0:
        ns['package_check']()
        with zipfile.ZipFile(next((root / 'taxonomy-app/target').glob('taxonomy-app-*.jar'))) as boot:
            entry = next(n for n in boot.namelist() if n.startswith('BOOT-INF/lib/taxonomy-workspace-') and n.endswith('.jar'))
            with zipfile.ZipFile(io.BytesIO(boot.read(entry))) as module:
                for cls in ('editor/ArchitectureEditorService', 'workspace/storage/DslGitRepository', 'versioning/service/RepositoryStateService'):
                    assert 'com/taxonomy/' + cls + '.class' in module.namelist(), cls
        print('PACKAGING: all three representative implementations are inside the nested workspace library', flush=True)
    return result

mode = sys.argv[1]
if mode == 'prepare': prepare()
elif mode in ('architecture', 'verify'): sys.exit(verify(mode))
elif mode == 'publish': ns['publish']()
else: raise ValueError(mode)
