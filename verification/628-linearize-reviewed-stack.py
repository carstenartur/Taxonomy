"""Disposable history transport. Creates Git objects, never updates a remote reference."""
from pathlib import Path
import base64, json, os, subprocess, urllib.request

REPO = 'carstenartur/Taxonomy'
BASE = '29b9ef036882f5dbfc62924d9aca63038c098d4e'
OLD_BASE = '8cd4395d93ba56c80ae6ea5dff031944772e7771'
HEADS = {1062: 'b654ee72d80c78a8a310721f97e9318e518476cf',
         1063: '558541521c1f6af032828e0f474f4e9986f03a57',
         1064: '619e3550930830359b31c5a70a58a9c8882e768c'}
EXPECTED = {1062: '3658d198d092c034ea2517407d6e308ae91c3494',
            1063: '35a3f0fab05c3cb37170c94f0959233393cea10a',
            1064: 'f02094c0dc08ff44136e2fea89cfb39e7f89cb20'}
UPDATES = {
    '.github/workflows/catalogue-overlay-proposal.yml': '3723a57e3ea59e23c87ee626bad2fe172a0a777d',
    'taxonomy-app/src/test/java/com/taxonomy/workspace/controller/WorkspaceAccessSecurityIT.java': 'bfead19f73c75433242ba2a07608709165b2177b',
    'taxonomy-workspace/src/main/java/com/taxonomy/workspace/repository/UserWorkspaceRepository.java': '1299cce663e0708d7af0723845a55a418fe0853f'}
OUT = Path(os.environ['RUNNER_TEMP']) / 'linear-candidates'
OUT.mkdir()

def run(*args, cwd=None, check=True):
    p = subprocess.run(['git', *args], cwd=cwd, capture_output=True, text=True,
                       env={**os.environ, 'GIT_EDITOR': 'true'})
    if check and p.returncode:
        raise RuntimeError(p.stdout + p.stderr)
    return p

def git(*args, cwd=None):
    return run(*args, cwd=cwd).stdout.strip()

def api(path, data=None):
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + '/' + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

def resolution_path(path):
    return (path.startswith('docs/') or '/src/test/' in path
            or path == '.github/workflows/catalogue-overlay-proposal.yml'
            or path == '.mvn/verification-suites.json'
            or path == 'pom.xml' or path.endswith('/pom.xml'))

# Only proceed after the independent native test job actually completed.
job = api('actions/jobs/103968796615')
assert job['head_sha'] == 'abfe56411e1b8ee8366b4c8f9ab0a68db3d7bfbe'
assert job['conclusion'] == 'success', job['conclusion']
for number, head in HEADS.items():
    assert api('pulls/' + str(number))['head']['sha'] == head
assert api('git/ref/heads/main')['object']['sha'] == BASE

git('config', 'user.name', 'Taxonomy integration automation')
git('config', 'user.email', 'noreply@github.com')
old_parent, new_parent = OLD_BASE, BASE
result = {}
for number, head in HEADS.items():
    control = OUT / (str(number) + '-control')
    work = OUT / str(number)
    git('worktree', 'add', '--detach', str(control), head)
    for path, sha in UPDATES.items():
        git('update-index', '--add', '--cacheinfo', '100644,' + sha + ',' + path, cwd=control)
    expected_tree = git('write-tree', cwd=control)
    assert expected_tree == EXPECTED[number]
    git('worktree', 'add', '--detach', str(work), head)
    attempt = run('rebase', '--onto', new_parent, old_parent, cwd=work, check=False)
    resolved = []
    for _ in range(12):
        if attempt.returncode == 0:
            break
        conflicts = git('diff', '--name-only', '--diff-filter=U', cwd=work).splitlines()
        assert conflicts and all(resolution_path(p) for p in conflicts), (number, conflicts, attempt.stderr)
        print('REPLAY CONFLICTS', number, conflicts, flush=True)
        git('restore', '--source=' + expected_tree, '--staged', '--worktree', '--', *conflicts, cwd=work)
        resolved.extend(conflicts)
        command = '--skip' if run('diff', '--cached', '--quiet', cwd=work, check=False).returncode == 0 else '--continue'
        attempt = run('rebase', command, cwd=work, check=False)
    assert attempt.returncode == 0, (number, attempt.stdout, attempt.stderr)
    # Rebase drops merge-only resolutions. Retain exactly those previously reviewed
    # test/documentation/build changes in an explicit integration commit, not silently.
    missing = git('diff', '--name-only', 'HEAD', expected_tree, cwd=work).splitlines()
    print('MERGE-RESOLUTION DELTA', number, missing, flush=True)
    print(git('diff', '--stat', 'HEAD', expected_tree, cwd=work), flush=True)
    assert all(resolution_path(p) for p in missing), (number, missing)
    if missing:
        (OUT / (str(number) + '-resolution.patch')).write_text(
            run('diff', 'HEAD', expected_tree, cwd=work).stdout)
        git('restore', '--source=' + expected_tree, '--staged', '--worktree', '--', *missing, cwd=work)
        git('diff', '--cached', '--check', cwd=work)
        git('commit', '-m', 'build: retain reviewed module resolutions and scope catalogue checks\n\n'
            'Recover the existing test/documentation/build merge resolutions when\n'
            'replaying the module stack linearly. Remove unrelated feature path\n'
            'triggers from the catalogue workflow; its job and commands are unchanged.\n'
            'The complete source tree is verified against the tested integration\n'
            'candidate; no production implementation is changed by this resolution.', cwd=work)
    linear = git('rev-parse', 'HEAD', cwd=work)
    assert git('rev-parse', 'HEAD^{tree}', cwd=work) == expected_tree
    assert not git('rev-list', '--merges', BASE + '..HEAD', cwd=work)
    git('merge-base', '--is-ancestor', BASE, 'HEAD', cwd=work)
    renamed = [line.split('\t')[1:] for line in git('diff', '--find-renames', '--name-status', BASE, 'HEAD', cwd=work).splitlines() if line.startswith('R')]
    assert len(renamed) == {1062: 118, 1063: 161, 1064: 188}[number], (number, len(renamed))
    history = []
    for old, new in renamed:
        before = git('log', '--follow', '--format=%H', BASE, '--', old, cwd=work).splitlines()
        after = git('log', '--follow', '--format=%H', 'HEAD', '--', new, cwd=work).splitlines()
        assert before and set(before) <= set(after), (number, new, set(before) - set(after))
        history.append({'old': old, 'new': new, 'retained_main_commits': len(before)})
    result[str(number)] = {'old_head': head, 'native_head': linear, 'tree': expected_tree,
        'resolved_paths': sorted(set(resolved + missing)), 'file_histories': history,
        'slice_commits': git('rev-list', '--reverse', new_parent + '..HEAD', cwd=work).splitlines()}
    print('VERIFIED', number, expected_tree, len(history), 'complete file histories', flush=True)
    (OUT / 'manifest.json').write_text(json.dumps(result, indent=2))
    old_parent, new_parent = head, linear

# Retain only Git objects. Publishing/updating any actual PR ref remains an
# explicit connector action after review of this manifest and backup creation.
def entries(tree):
    values = {}
    data = subprocess.check_output(['git', 'ls-tree', '-rz', tree])
    for raw in data.split(b'\0'):
        if not raw:
            continue
        meta, path = raw.split(b'\t', 1)
        mode, kind, sha = meta.decode().split()
        assert kind == 'blob', (kind, path)
        values[path.decode()] = (mode, kind, sha)
    return values

known_blobs = set()
originals = git('rev-list', *HEADS.values(), BASE, '^' + OLD_BASE).splitlines() + [OLD_BASE, BASE]
for commit in originals:
    known_blobs.update(v[2] for v in entries(commit).values())
remote = {BASE: BASE}
for number, info in result.items():
    for commit in info['slice_commits']:
        parent = git('rev-parse', commit + '^')
        before, after = entries(parent), entries(commit)
        changes = []
        for path in sorted(before.keys() | after.keys()):
            if before.get(path) == after.get(path):
                continue
            if path not in after:
                changes.append({'path': path, 'mode': before[path][0], 'type': 'blob', 'sha': None})
                continue
            mode, kind, sha = after[path]
            if sha not in known_blobs:
                content = subprocess.check_output(['git', 'cat-file', 'blob', sha])
                response = api('git/blobs', {'encoding': 'base64', 'content': base64.b64encode(content).decode()})
                assert response['sha'] == sha
                known_blobs.add(sha)
            changes.append({'path': path, 'mode': mode, 'type': kind, 'sha': sha})
        tree = git('rev-parse', commit + '^{tree}')
        retained = api('git/trees', {'base_tree': git('rev-parse', parent + '^{tree}'), 'tree': changes})
        assert retained['sha'] == tree
        values = git('show', '-s', '--format=%aN%x00%aE%x00%aI%x00%cN%x00%cE%x00%cI', commit).split('\0')
        message = subprocess.check_output(['git', 'cat-file', 'commit', commit]).split(b'\n\n', 1)[1].decode()
        retained = api('git/commits', {'message': message, 'tree': tree, 'parents': [remote[parent]],
            'author': dict(zip(('name', 'email', 'date'), values[:3])),
            'committer': dict(zip(('name', 'email', 'date'), values[3:]))})
        assert retained['tree']['sha'] == tree and [p['sha'] for p in retained['parents']] == [remote[parent]]
        remote[commit] = retained['sha']
        print('RETAINED COMMIT', commit, retained['sha'], tree, flush=True)
    info['published_candidate'] = remote[info['native_head']]
    (OUT / 'manifest.json').write_text(json.dumps(result, indent=2))
    print('CANDIDATE HEAD', number, info['published_candidate'], info['tree'], flush=True)
print('No remote reference, protected branch, review state or repository rule was modified.', flush=True)
