"""One-shot, exact-tree verification; never updates any branch reference."""
import base64, collections, gzip, hashlib, json, os, pathlib, subprocess, sys, urllib.request
import xml.etree.ElementTree as ET
BASE = '1b165cb0c25088d9b406871c89b1e3cdd55b0a98'
TREE = '8f664d51035f0b97b7b597fc33e86140b0458682'
INTERMEDIATE_TREE = '992a85d7ec24af8d3cf8b32d91b2b73269813d19'
CHECKSUM = 'c7f1089ad06cb8e6670d3486b8cd3f578d290759cabeb5bbcb515a9a15710b14'
E = pathlib.Path(os.environ['RUNNER_TEMP']) / 'ownership-evidence'
E.mkdir(exist_ok=True)
def git(*args, data=None):
    return subprocess.check_output(['git', *args], input=data)
def payload():
    return json.loads((E/'payload.json').read_text())
mode = sys.argv[1]
if mode == 'prepare':
    encoded = ''.join(pathlib.Path(f'.github/maintenance/ownership/part{i}').read_text() for i in range(4))
    packed = base64.b64decode(encoded, validate=True)
    assert hashlib.sha256(packed).hexdigest() == CHECKSUM
    p = json.loads(gzip.decompress(packed)); assert p['base'] == BASE and p['tree'] == '3b7e5d68852ffb601135dfad106eea2d9a6a2dd8'
    (E/'fixture-namespace.patch.gz').write_bytes(pathlib.Path('.github/maintenance/ownership/fixture-namespace.patch.gz').read_bytes())
    correction = gzip.decompress(pathlib.Path('.github/maintenance/ownership/correction.patch.gz').read_bytes())
    assert hashlib.sha256(correction).hexdigest() == '253af0cbce9456ef30b933351b8b4d4bb7bec917b259b922b1176f21c648b94b'
    p['retainedAppCases'] = p['baselineCases'].pop('com.taxonomy.interop.IntegrationRestartTest')
    p['moves'] = [m for m in p['moves'] if m['className'] != 'com.taxonomy.interop.IntegrationRestartTest']
    p['tree'] = INTERMEDIATE_TREE
    p['correctionPatch'] = correction.decode()
    (E/'payload.json').write_text(json.dumps(p))
    git('checkout', '--detach', BASE)
    git('apply', '--index', '--binary', data=p['patch'].encode())
    git('apply', '--index', '--binary', data=correction)
    git('diff', '--cached', '--check')
    assert git('write-tree').decode().strip() == INTERMEDIATE_TREE
    unchanged = 0
    for move in p['moves']:
        assert not pathlib.Path(move['source']).exists()
        if move['className'] != 'com.taxonomy.analysis.service.LlmRecordReplayServiceTest':
            assert git('hash-object', move['target']).decode().strip() == move['sha']
            unchanged += 1
    assert unchanged == 18
    (E/'tree-verification.json').write_text(json.dumps({'base':BASE, 'tree':INTERMEDIATE_TREE,'unchangedJavaSources':unchanged}, indent=2))
    print('EXACT_TREE_AND_SOURCE_IDENTITIES_OK')
elif mode == 'isolate-fixture':
    patch = gzip.decompress((E/'fixture-namespace.patch.gz').read_bytes())
    assert hashlib.sha256(patch).hexdigest() == '05c50bdfdd842d26ce371454613bdeb023d3b1e49af3456e3fa73366ee008c77'
    assert git('write-tree').decode().strip() == INTERMEDIATE_TREE
    git('apply', '--index', '--binary', data=patch)
    git('diff', '--cached', '--check')
    assert git('write-tree').decode().strip() == TREE
    p = payload(); p['fixtureIsolationPatch'] = patch.decode(); p['tree'] = TREE
    for move in p['moves']:
        if move['className'] == 'com.taxonomy.editor.EditorPersistenceFixture':
            move['target'] = move['target'].replace('java/com/taxonomy/editor/', 'java/testfixtures/taxonomy/editor/')
            move['className'] = 'testfixtures.taxonomy.editor.EditorPersistenceFixture'
    unchanged = 0
    for move in p['moves']:
        old = git('show', BASE+':'+move['source']).decode()
        new = pathlib.Path(move['target']).read_text()
        if old == new: unchanged += 1
        if move['className'].endswith('LlmRecordReplayServiceTest'): continue
        if move['className'].endswith('EditorPersistenceFixture'):
            new = new.replace('package testfixtures.taxonomy.editor;', 'package com.taxonomy.editor;')
            new = new.replace('\n\nimport com.taxonomy.editor.ArchitectureEditorService;\nimport com.taxonomy.editor.ArchitectureCheckpointWriter;', '')
        elif 'import testfixtures.taxonomy.editor.EditorPersistenceFixture;' in new:
            if 'import com.taxonomy.editor.EditorPersistenceFixture;' in old:
                new = new.replace('import testfixtures.taxonomy.editor.EditorPersistenceFixture;', 'import com.taxonomy.editor.EditorPersistenceFixture;')
            else: new = new.replace('\n\nimport testfixtures.taxonomy.editor.EditorPersistenceFixture;', '')
        assert old == new, move['target']
    assert unchanged == 12, unchanged
    (E/'payload.json').write_text(json.dumps(p))
    (E/'tree-verification.json').write_text(json.dumps({'base':BASE, 'tree':TREE, 'unchangedTestClasses':12,
        'movedImportOnlyClasses':5, 'retainedAppImportOnlyClasses':1,
        'fixtureOutsideProductionNamespace':True}, indent=2))
    print('FINAL_FIXTURE_ISOLATION_TREE_OK')
elif mode == 'reports':
    p = payload(); by_class = collections.defaultdict(list); summaries = []
    for path in pathlib.Path('.').glob('taxonomy-*/target/surefire-reports/TEST-*.xml'):
        root = ET.parse(path).getroot()
        summaries.append({'module':path.parts[0], **root.attrib})
        for case in root.findall('testcase'):
            by_class[case.get('classname')].append((path.parts[0], case))
    count = 0
    for name, expected in p['baselineCases'].items():
        owner = next(m['target'].split('/')[0] for m in p['moves'] if m['className'] == name)
        rows = [(module,case) for cls,entries in by_class.items() if cls == name or cls.startswith(name+'$') for module,case in entries]
        assert rows, f'Missing executed class {name}'
        actual = []
        for module,case in rows:
            assert module == owner, (name, module, owner)
            assert not any(case.find(tag) is not None for tag in ['failure','error','skipped']), ET.tostring(case)
            actual.append([case.get('classname'),case.get('name')])
        assert sorted(actual) == sorted(expected), (name,actual,expected)
        count += len(actual)
    assert count == 124, count
    (E/'owner-tests.json').write_text(json.dumps({'preservedTestCases':count,'preservedClasses':18,'suites':summaries},indent=2))
    print(f'EXACT_TEST_INVENTORY_OK: {count} executed cases in 18 owning classes')
elif mode == 'publish-object':
    assert (E/'owner-tests.json').is_file()
    required = ['FeatureTestOwnershipTest','RepositoryResourcesTest','ReformulationCancellationAdapterTest','ReformulationCancellationControlsTest']
    roots = [ET.parse(f).getroot() for f in pathlib.Path('taxonomy-build/target/surefire-reports').glob('TEST-*.xml')]
    for name in required:
        matches = [r for r in roots if r.get('name','').endswith('.'+name)]
        assert len(matches) == 1 and int(matches[0].get('tests','0')) > 0, name
        assert all(int(matches[0].get(k,'0')) == 0 for k in ['failures','errors','skipped']), name
    architecture = ['ArchitectureEditorBoundaryTest', 'ArchitectureTest', 'ArchitectureContextDependencyRatchetTest',
        'ArchitectureWorkspaceStorageOwnershipTest','ArchitectureCycleBoundaryTest','ArchitectureDslCompositionBoundaryTest',
        'ArchitectureWorkspaceAuthorityBoundaryTest','ArchitectureApplicationSchemaCompositionTest']
    app_roots = [ET.parse(f).getroot() for f in pathlib.Path('taxonomy-app/target/surefire-reports').glob('TEST-*.xml')]
    for name in architecture:
        matches = [r for r in app_roots if r.get('name','').endswith('.'+name)]
        assert matches and sum(int(r.get('tests','0')) for r in matches)>0, name
        assert all(int(r.get(k,'0'))==0 for r in matches for k in ['failures','errors','skipped']), name
    app_path = pathlib.Path('taxonomy-app/target/surefire-reports/TEST-com.taxonomy.interop.IntegrationRestartTest.xml')
    app_cases = ET.parse(app_path).getroot().findall('testcase')
    assert sorted([[c.get('classname'),c.get('name')] for c in app_cases]) == sorted(payload()['retainedAppCases'])
    assert not any(c.find(tag) is not None for c in app_cases for tag in ['failure','error','skipped'])
    assert git('write-tree').decode().strip() == TREE
    git('diff','--exit-code'); git('diff','--cached','--check')
    def api(path, obj):
        request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy'+path,
            data=json.dumps(obj).encode(), method='POST', headers={
            'Authorization':'Bearer '+os.environ['GH_TOKEN'], 'Accept':'application/vnd.github+json',
            'Content-Type':'application/json', 'X-GitHub-Api-Version':'2022-11-28'})
        with urllib.request.urlopen(request, timeout=60) as response: return json.load(response)
    changes = git('diff','--cached','--name-status','--no-renames',BASE).decode().splitlines()
    entries = []
    for line in changes:
        status,path = line.split('\t')
        if status == 'D': entries.append({'path':path,'mode':'100644','type':'blob','sha':None}); continue
        mode, sha, _ = git('ls-files','--stage',path).decode().split()[:3]
        data = pathlib.Path(path).read_bytes()
        created = api('/git/blobs',{'content':base64.b64encode(data).decode(),'encoding':'base64'})
        assert created['sha'] == sha
        entries.append({'path':path,'mode':mode,'type':'blob','sha':sha})
    base_tree = git('rev-parse',BASE+'^{tree}').decode().strip()
    tree = api('/git/trees', {'base_tree':base_tree,'tree':entries}); assert tree['sha'] == TREE
    result = api('/git/commits',{'tree':TREE,'parents':[BASE], 'message':
        'refactor(test): move 18 feature test classes into their owning modules\n\n'
        'Preserve 124 moved test cases plus both retained application restart cases. '
        'Publish only the shared editor persistence fixture as a test-only archive. '
        'Retain the LLM recording with its test and fail when missing. '
        'Fix both build-owned cancellation resource lookups. '
        'Verified exact base and tree, owning-module Maven tests and build guards; '
        'production architecture checks pass without changing their rules. The shared fixture lives outside the production namespace. '
        'Full PR CI remains required before merging. No build speedup claim.'})
    receipt = {'base':BASE,'tree':TREE,'commit':result['sha'],'movedCases':124,'retainedAppCases':2,'workflowRun':os.environ['GITHUB_RUN_ID']}
    (E/'receipt.json').write_text(json.dumps(receipt,indent=2)); print(json.dumps(receipt))
else: raise ValueError(mode)
