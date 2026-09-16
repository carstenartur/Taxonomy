"""Temporary runner: apply reproduced corrections and prepare immutable candidates.

Never modifies a PR ref. Only explicit product paths become candidate trees.
"""
from pathlib import Path
import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

root = Path(sys.argv[2]).resolve()
mode = sys.argv[1]
GATE = 'taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleExtractionTest.java'
GRAPH_TEST = 'taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java'
CONTROLLER = 'taxonomy-workspace/src/main/java/com/taxonomy/workspace/controller/WorkspaceController.java'
SYNC = 'taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/GitNativeSyncIntegrationService.java'
WORKSPACE_TEST = 'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceCentralReadBoundaryTest.java'
PRODUCT = [GATE, GRAPH_TEST, CONTROLLER, SYNC, WORKSPACE_TEST]
BASE = '70d13cb1b5a549ef6f4847a2752359a7225db938'


def git(*args, text=True):
    result = subprocess.check_output(['git', '-C', str(root), *args], text=text)
    return result.strip() if text else result


def replace(path, old, new):
    file = root / path
    content = file.read_text()
    assert content.count(old) == 1, (path, old[:100], content.count(old))
    file.write_text(content.replace(old, new))


if mode == 'patch':
    assert git('rev-parse', 'HEAD') == BASE
    replace(GATE,
        '        SortedMap<String, SortedSet<String>> classOwners = new TreeMap<>();',
        '        SortedMap<String, SortedSet<String>> classOwners = new TreeMap<>();\n'
        '        Map<String, String> existingSourceFiles = new TreeMap<>();')
    replace(GATE,
        '                    classOwners.computeIfAbsent(className, ignored -> new TreeSet<>()).add(module.getKey());',
        '''                    // Containment was checked by repositoryFiles before opening these bytes.
                    String sourceFile = readCompiledSourceFile(file, className);
                    if (sourceFile != null) existingSourceFiles.put(module.getKey() + ":" + className, sourceFile);
                    classOwners.computeIfAbsent(className, ignored -> new TreeSet<>()).add(module.getKey());''')
    replace(GATE,
        'verifyCompiledBinaryInventory(sourceOwners, outputs, classOwners);',
        'verifyCompiledBinaryInventory(sourceOwners, outputs, classOwners, existingSourceFiles);')
    replace(GATE,
        'Map<String, SortedSet<String>> classOwners) throws IOException {',
        'Map<String, SortedSet<String>> classOwners,\n'
        '                                                                     Map<String, String> existingSourceFiles) throws IOException {')
    replace(GATE,
        '            // ArchUnit imports every freshly generated class before temporary output',
        '''            for (var entry : existingSourceFiles.entrySet()) {
                String sourceFile = expected.get(entry.getKey());
                if (!entry.getValue().equals(sourceFile)) {
                    throw new IllegalStateException("Compiled class SourceFile differs from current source: "
                            + entry.getKey() + "; found=" + entry.getValue() + ", expected=" + sourceFile);
                }
            }
            // ArchUnit imports every freshly generated class before temporary output''')
    replace(GATE,
        '    private record CompiledInventory(Map<String, String> sourceFiles, JavaClasses classes) {}',
        '''    /** Audit physical output identity without using stale output for dependency analysis. */
    private static String readCompiledSourceFile(Path file, String expectedName) {
        try (var input = Files.newInputStream(file)) {
            var reader = new org.springframework.asm.ClassReader(input);
            if (reader.readInt(0) != 0xCAFEBABE) {
                throw new IllegalArgumentException("Invalid class-file magic");
            }
            String actualName = reader.getClassName().replace('/', '.');
            if (!expectedName.equals(actualName)) {
                throw new IllegalArgumentException("Internal binary name is " + actualName);
            }
            String[] source = new String[1];
            reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.Opcodes.ASM9) {
                @Override public void visitSource(String name, String debug) { source[0] = name; }
            }, org.springframework.asm.ClassReader.SKIP_CODE | org.springframework.asm.ClassReader.SKIP_FRAMES);
            // SourceFile is optional (for example with -g:none). When present, it must match.
            return source[0];
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid compiled class " + expectedName + " at " + file
                    + ": " + failure.getMessage(), failure);
        }
    }

    private record CompiledInventory(Map<String, String> sourceFiles, JavaClasses classes) {}''')
    replace(CONTROLLER,
        '        if (WorkspaceContextResolver.requestedWorkspaceId() != null) {\n'
        '            return ResponseEntity.ok(workspaceManager.getWorkspaceMetadataInfo(',
        '''        String pinned = WorkspaceContextResolver.requestedWorkspaceId();
        if (pinned != null && pinned.isEmpty()) {
            // Explicit central read has no selected workspace and must not choose one implicitly.
            return ResponseEntity.noContent().build();
        }
        if (pinned != null) {
            return ResponseEntity.ok(workspaceManager.getWorkspaceMetadataInfo(''')
    replace(CONTROLLER,
        '            String pinned = WorkspaceContextResolver.requestedWorkspaceId();\n'
        '            UserWorkspace ws = pinned == null',
        '''            String pinned = WorkspaceContextResolver.requestedWorkspaceId();
            if (pinned != null && pinned.isEmpty()) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "Select a workspace before provisioning its repository");
            }
            UserWorkspace ws = pinned == null''')
    replace(SYNC,
        '                contextResolver.resolveRepositoryContextForUser(username);\n'
        '        if (persistent.workspaceId() == null) {',
        '''                contextResolver.resolveRepositoryContextForUser(username);
        // Preserve the explicit operation scope before adapting to legacy WorkspaceContext.
        // This shared entry covers pull, publish and every conflict-resolution strategy.
        if (persistent.scope() == RepositoryScope.CENTRAL_READ) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Synchronization requires a writable repository context");
        }
        if (persistent.workspaceId() == null) {''')
    subprocess.run(['git', '-C', str(root), 'diff', '--check'], check=True)
    print('Applied the three reproduced boundary corrections; valid aliases and writable contexts remain supported.')
elif mode == 'prepare':
    out = Path(sys.argv[3]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    assert git('rev-parse', 'HEAD') == BASE
    changed = set(git('diff', '--name-only').splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
    assert changed == set(PRODUCT), changed
    bases = [
        ('gate', 1054, '128857daa117a8c9d6d9611e6ca49a9e2564a67f'),
        ('workspace', 1062, 'ad1f01d9637f6a96d45bcad53ca9c63e31d16495'),
        ('templates', 1063, '60a9d22b10f86269670adfe9e0f2b1f14ad60249'),
        ('interop', 1064, BASE),
    ]
    # Check the complete common input, not just textual patch anchors, on every target.
    for stage, pr, sha in bases:
        for path in PRODUCT[:2] if stage == 'gate' else PRODUCT[:-1]:
            assert git('show', sha + ':' + path, text=False) == git('show', BASE + ':' + path, text=False), (stage, path)
    api_root = 'https://api.github.com/repos/carstenartur/Taxonomy/'
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'}
    def api(path, data=None):
        request = urllib.request.Request(api_root + path, headers=headers,
            data=None if data is None else json.dumps(data).encode(), method='GET' if data is None else 'POST')
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    files = {}
    for path in PRODUCT:
        data = (root / path).read_bytes()
        sha = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        assert api('git/blobs', {'content': base64.b64encode(data).decode(), 'encoding': 'base64'})['sha'] == sha
        files[path] = sha
    candidates = []
    previous = None
    for stage, pr, base in bases:
        selected = PRODUCT[:2] if stage == 'gate' else PRODUCT
        original = api('git/commits/' + base)
        entries = [{'path': path, 'mode': '100644', 'type': 'blob', 'sha': files[path]} for path in selected]
        tree = api('git/trees', {'base_tree': original['tree']['sha'], 'tree': entries})['sha']
        parents = [base] + ([] if previous is None else [previous])
        commit = api('git/commits', {
            'message': 'fix: validate physical class identity and preserve central-read boundaries (' + stage + ')\n\n'
                       'Retain existing history and the corrected dependency stack. No verification\n'
                       'helper, policy waiver, test exclusion or baseline change is included.\n'
                       'Candidate prepared for complete exact-source CI after reproduced regressions.',
            'tree': tree, 'parents': parents})['sha']
        ref = 'refs/heads/verify/628-boundary-' + os.environ['GITHUB_RUN_ID'] + '-' + stage
        api('git/refs', {'ref': ref, 'sha': commit})
        candidates.append({'stage': stage, 'pr': pr, 'sha': commit, 'tree': tree, 'base': base,
                           'parents': parents, 'files': {path: files[path] for path in selected}})
        previous = commit
    matrix = {'include': [{k: entry[k] for k in ('stage', 'pr', 'sha', 'tree', 'base')} for entry in candidates]}
    manifest = {'redRun': 35057338219, 'preparationRun': os.environ['GITHUB_RUN_ID'], 'candidates': candidates}
    (out / 'candidates.json').write_text(json.dumps(manifest, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('matrix=' + json.dumps(matrix, separators=(',', ':')) + '\n')
    print(json.dumps(manifest, indent=2))
else:
    raise SystemExit('Unknown mode: ' + mode)
