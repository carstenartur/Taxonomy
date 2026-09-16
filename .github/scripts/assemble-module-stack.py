"""Assemble five pinned PR candidates; never write a product ref."""
import base64
import gzip
import hashlib
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

OUT = Path(os.environ.get('STACK_OUTPUT', '/tmp/stack-candidates'))
OUT.mkdir(parents=True, exist_ok=True)
MAIN = '118a03441d0d72b280acdbf7ad9c37ab55eae964'
BASES = dict(gate='c0d7bd654ba774daa0701b6e18dfa3fb85c3a92c',
             assessment='f8bb39fc7f76672d7cd5754d1e5476180458ed05',
             workspace='37a6eedfb64067667985650bddd50102b21ddad3',
             templates='7443b78555accf6196e774b4cbad02098454982d',
             interop='906468dbdce5de18be202b731d58c3234b8b77c6')
TREES = dict(gate='08910bdd6fa1b761c1220015710299843f250757',
             assessment='b45479571d5264d838fdeac3d1a5e28a765e0cba',
             workspace='5bb7bdb004cc7708cd3735b347ce4d14185d9855',
             templates='bd2dd659402f6000b2e40118c99c0f4b88158fa5',
             interop='b37460d87f00197a3f56da627c24ab436e8090d6')

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def run(*args):
    subprocess.run(['git', *args], check=True)

def blob(sha):
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
               'Accept': 'application/vnd.github+json'}
    request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/git/blobs/' + sha, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        data = base64.b64decode(json.load(response)['content'])
    assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == sha
    return data

def commit(message):
    run('add', '-u')
    run('diff', '--cached', '--check')
    run('-c', 'user.name=Taxonomy integration', '-c', 'user.email=integration@invalid.example',
        'commit', '-m', message)
    return git('rev-parse', 'HEAD')

def start(label):
    assert git('status', '--porcelain') == ''
    run('switch', '--detach', BASES[label])

def merge(sha):
    result = subprocess.run(['git', 'merge', '--no-commit', '--no-ff', sha])
    assert result.returncode in (0, 1), result.returncode
    return git('diff', '--name-only', '--diff-filter=U').splitlines()

def resolve_selectors():
    root = Path('.')
    extras = [guard for module, guard in (
        ('workspace', 'ArchitectureWorkspaceModuleTest'),
        ('templates', 'ArchitectureTemplatesModuleTest'),
        ('interop', 'ArchitectureInteropModuleTest')) if (root / ('taxonomy-' + module) / 'pom.xml').exists()]
    for name in ('pom.xml', '.mvn/verification-suites.json', 'docs/en/MODULE_BOUNDARIES.md', 'docs/de/MODULE_BOUNDARIES.md'):
        path = root / name
        def choose(match):
            for text in (match[2], match[1]):
                if 'ArchitectureModuleGraphTest' in text:
                    return text
            raise AssertionError((name, match[0]))
        path.write_text(re.sub(r'^<<<<<<<[^\n]*\n(.*?)^=======\n(.*?)^>>>>>>>[^\n]*\n', choose, path.read_text(), flags=re.M | re.S))
    selection = ','.join(GUARDS + extras)
    path = root / 'pom.xml'
    text, count = re.subn(r'(<id>architecture-tests</id>\s*<properties><test>)[^<]*(</test>)', lambda m: m[1] + selection + m[2], path.read_text())
    assert count == 1
    path.write_text(text)
    path = root / '.mvn/verification-suites.json'
    text, count = re.subn(r'("architecture-tests": \{"test": ")[^"]*(")', lambda m: m[1] + selection + m[2], path.read_text())
    assert count == 1
    path.write_text(text)
    path = root / 'taxonomy-build/src/test/java/com/taxonomy/ArchitectureSelectorSynchronizationTest.java'
    text = path.read_text()
    start = text.index('    private static final List<String> EXPECTED = List.of(')
    end = text.index(';', start) + 1
    declaration = '    private static final List<String> EXPECTED = List.of(\n' + ',\n'.join('            "' + g + '"' for g in GUARDS + extras) + ');'
    path.write_text(text[:start] + declaration + text[end:])
    names = ', '.join('`' + g + '`' for g in extras)
    path = root / 'docs/en/MODULE_BOUNDARIES.md'
    text = re.sub(r'Its \w+ selected test classes', 'Its ' + str(len(GUARDS + extras)) + ' selected test classes', path.read_text())
    text = text.replace('silently disable it. Full CI verification remains', 'silently disable it. The extracted-module guards (' + names + ') are also included. Full CI verification remains')
    path.write_text(re.sub(r'The extracted-module guards \([^)]*\)', 'The extracted-module guards (' + names + ')', text))
    path = root / 'docs/de/MODULE_BOUNDARIES.md'
    text = re.sub(r'Die \w+ ausgewählten Testklassen', 'Die ' + str(len(GUARDS + extras)) + ' ausgewählten Testklassen', path.read_text())
    text = text.replace('sie nicht durch das Löschen ihrer Testklasse unbemerkt entfällt.', 'sie nicht durch das Löschen ihrer Testklasse unbemerkt entfällt. Auch die Modul-Guards (' + names + ') sind enthalten.') if 'Auch die Modul-Guards' not in text else text
    path.write_text(re.sub(r'Auch die Modul-Guards \([^)]*\)', 'Auch die Modul-Guards (' + names + ')', text))
    run('add', 'pom.xml', '.mvn/verification-suites.json', 'docs/en/MODULE_BOUNDARIES.md', 'docs/de/MODULE_BOUNDARIES.md', 'taxonomy-build')

def capture(label):
    assert git('status', '--porcelain') == ''
    tree = git('rev-parse', 'HEAD^{tree}')
    assert tree == TREES[label], (label, tree, TREES[label])
    HEADS[label] = git('rev-parse', 'HEAD')
    run('update-ref', 'refs/heads/stack-prepared/' + label, HEADS[label])
    (OUT / (label + '.patch')).write_bytes(subprocess.check_output(['git', 'diff', '--binary', '--full-index', BASES[label], 'HEAD']))

HEADS = {}
run('config', 'user.name', 'Taxonomy integration')
run('config', 'user.email', 'integration@invalid.example')
run('fetch', '--no-tags', 'origin', *BASES.values(), MAIN)
start('gate')
patch = gzip.decompress(blob('11d62afd07d58eb8476abe02589310452c2834a6') + blob('d96cdfbce76faed53abd33e6dff4ce71413ca7ac'))
assert hashlib.sha256(patch).hexdigest() == '5263cb61f9a56a07036267a172837acae6efe9df72fa7b7fda6188002307dff4'
(OUT / 'gate-correction.patch').write_bytes(patch)
run('apply', '--index', '--unidiff-zero', str(OUT / 'gate-correction.patch'))
commit('fix(architecture): use fresh dependency bytecode and contain selector policy inputs')
assert merge(MAIN) == []
commit('Merge current main into the tested architecture gate')
GUARDS = json.loads(Path('.mvn/verification-suites.json').read_text())['profiles']['architecture-tests']['test'].split(',')
capture('gate')
start('assessment')
assert merge(HEADS['gate']) == []
commit('Integrate corrected gate and current main into historical workspace assessment')
path = Path('docs/superpowers/plans/2026-09-13-workspace-graph-assessment.md')
text = path.read_text().replace('root has fresh native architecture evidence and exact regenerated-baseline comparison.', 'the root supplied historical native architecture evidence and an exact regenerated-baseline comparison for the recorded snapshot, not a new run for this documentation head.')
path.write_text(text)
path = Path('docs/dev/WORKSPACE_DEPENDENCY_ASSESSMENT.md')
text = path.read_text().replace('It classifies existing boundaries; it changes no runtime behavior, ownership map, baseline or enforcement rule.', 'It classifies boundaries at that historical snapshot; it changes no runtime behavior, ownership map, baseline or enforcement rule. Its counts and readiness statements are not a current inventory or an approval of later extraction branches.')
for old, new, filename in [('Dependency baseline', 'Snapshot dependency baseline', 'architecture-dependency-baseline.json'), ('Context ownership map', 'Snapshot context ownership map', 'architecture-contexts.json')]:
    text = text.replace('[' + old + '](../../.github/' + filename + ')', '[' + new + '](https://github.com/carstenartur/Taxonomy/blob/bd430ff41788289567de65d78d7d8b8f4adc02a8/.github/' + filename + ')')
path.write_text(text)
commit('docs: bind workspace assessment evidence to its recorded snapshot')
capture('assessment')
start('workspace')
RECOVERY = {
    'taxonomy-app/src/test/java/com/taxonomy/workspace/service/WorkspaceProvisioningClaimIT.java': '4114af11ec6dfdfd2f7b42cc3f7659438e4875c4',
    'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/GitNativeSyncIntegrationService.java': 'a98753f9e8617ee36cd3edc550e42f6f56272192',
    'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java': 'b8d2bbcececf9bd6f17d949b07456089202147c2',
    'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceDivergedStrategyScopeTest.java': '3cd3ac84b940d71487ab0942ee3c8c987b06b558',
    'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceFactoryRetryRecoveryTest.java': '56ac522fbdc0b579b3b5e845fc71b8478ed413a6'}
for filename, sha in RECOVERY.items():
    path = Path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(blob(sha))
run('add', *RECOVERY)
assert git('write-tree') == 'edfd3d82562b260755d2bdfbd36f04ebd81541aa'
commit('fix(workspace): retain selected-repository conflict choices and recover provisioning without reseeding')
resolver = 'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceContextResolver.java'
assert merge(MAIN) == [resolver]
path = Path(resolver)
text = path.read_text()
a = text.index('<<<<<<< HEAD:')
b = text.index('\n', text.index('>>>>>>>', a))
text = text[:a] + text[b+1:]
text = text.replace('        String requestedWorkspaceId = requestedWorkspaceId();\n        UserWorkspace workspace = requestedWorkspaceId == null', '        String requestedWorkspaceId = requestedWorkspaceId();\n        // A missing pin follows the active workspace. A present empty pin is an\n        // explicit central read and must not trigger any implicit metadata lookup.\n        if (requestedWorkspaceId != null && requestedWorkspaceId.isEmpty()) return null;\n        UserWorkspace workspace = requestedWorkspaceId == null')
path.write_text(text)
run('add', resolver)
commit('Merge current analysis main while preserving workspace readiness and explicit central pins')
assert set(merge(HEADS['gate'])) == {'pom.xml', '.mvn/verification-suites.json', 'docs/en/MODULE_BOUNDARIES.md', 'docs/de/MODULE_BOUNDARIES.md'}
resolve_selectors()
commit('Integrate extraction gate with all fifteen workspace architecture guards')
capture('workspace')
for label, parent, module in [('templates', 'workspace', 'taxonomy-templates'), ('interop', 'templates', 'taxonomy-interop')]:
    start(label)
    conflicts = merge(HEADS[parent])
    allowed = {'pom.xml', '.mvn/verification-suites.json', 'docs/en/MODULE_BOUNDARIES.md', 'docs/de/MODULE_BOUNDARIES.md', 'taxonomy-app/src/test/java/com/taxonomy/workspace/service/WorkspaceProvisioningClaimIT.java'}
    assert set(conflicts) <= allowed, conflicts
    claim = Path('taxonomy-app/src/test/java/com/taxonomy/workspace/service/WorkspaceProvisioningClaimIT.java')
    if str(claim) in conflicts:
        claim.write_bytes(blob(RECOVERY[str(claim)]))
        run('add', str(claim))
    resolve_selectors()
    commit('Integrate checked dependency stack retaining all ' + label + ' module guards')
    run('diff', '--exit-code', BASES[label], 'HEAD', '--', module)
    capture(label)
manifest = dict(heads=HEADS, trees=TREES, bases=BASES, main=MAIN)
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
run('bundle', 'create', str(OUT / 'candidates.bundle'), *['refs/heads/stack-prepared/' + x for x in HEADS], *['^' + x for x in BASES.values()], '^' + MAIN)
print(json.dumps(manifest, indent=2))
