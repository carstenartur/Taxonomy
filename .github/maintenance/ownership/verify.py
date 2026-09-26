"""One-shot, exact-tree verification; never updates any branch reference."""
import base64, collections, gzip, hashlib, json, os, pathlib, subprocess, sys, urllib.request
import xml.etree.ElementTree as ET
BASE = '1b165cb0c25088d9b406871c89b1e3cdd55b0a98'
TREE = '3b7e5d68852ffb601135dfad106eea2d9a6a2dd8'
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
    p = json.loads(gzip.decompress(packed)); assert p['base'] == BASE and p['tree'] == TREE
    (E/'payload.json').write_text(json.dumps(p))
    git('checkout', '--detach', BASE)
    git('apply', '--index', '--binary', data=p['patch'].encode())
    git('diff', '--cached', '--check')
    assert git('write-tree').decode().strip() == TREE
    unchanged = 0
    for move in p['moves']:
        assert not pathlib.Path(move['source']).exists()
        if move['className'] != 'com.taxonomy.analysis.service.LlmRecordReplayServiceTest':
            assert git('hash-object', move['target']).decode().strip() == move['sha']
            unchanged += 1
    assert unchanged == 19
    (E/'tree-verification.json').write_text(json.dumps({'base':BASE, 'tree':TREE,'unchangedJavaSources':unchanged}, indent=2))
    print('EXACT_TREE_AND_SOURCE_IDENTITIES_OK')
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
    assert count == 126, count
    (E/'owner-tests.json').write_text(json.dumps({'preservedTestCases':count,'preservedClasses':19,'suites':summaries},indent=2))
    print(f'EXACT_TEST_INVENTORY_OK: {count} executed cases in 19 owning classes')
elif mode == 'publish-object':
    assert (E/'owner-tests.json').is_file()
    required = ['FeatureTestOwnershipTest','RepositoryResourcesTest','ReformulationCancellationAdapterTest','ReformulationCancellationControlsTest']
    roots = [ET.parse(f).getroot() for f in pathlib.Path('taxonomy-build/target/surefire-reports').glob('TEST-*.xml')]
    for name in required:
        matches = [r for r in roots if r.get('name','').endswith('.'+name)]
        assert len(matches) == 1 and int(matches[0].get('tests','0')) > 0, name
        assert all(int(matches[0].get(k,'0')) == 0 for k in ['failures','errors','skipped']), name
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
        'refactor(test): move 19 feature test classes into their owning modules\n\n'
        'Preserve 126 executed test cases and actual persistence/restart behavior. '
        'Publish only the shared editor persistence fixture as a test-only archive. '
        'Retain the LLM recording with its test and fail when missing. '
        'Fix both build-owned cancellation resource lookups. '
        'Verified exact base and tree, owning-module Maven tests and build guards; '
        'full PR CI remains required before merging. No build speedup claim.'})
    receipt = {'base':BASE,'tree':TREE,'commit':result['sha'],'preservedCases':126,'workflowRun':os.environ['GITHUB_RUN_ID']}
    (E/'receipt.json').write_text(json.dumps(receipt,indent=2)); print(json.dumps(receipt))
else: raise ValueError(mode)
