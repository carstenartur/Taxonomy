from pathlib import Path
import base64, json, os, subprocess, sys, urllib.request
import xml.etree.ElementTree as ET

BASE = 'c121be0701ad5dd4716e1e6922be23a6db2f6d9f'
TEMP = Path(os.environ['RUNNER_TEMP'])
BEHAVIOR = {'IntegrationArchitectureProjectionTest','IntegrationDiffTest','IntegrationFlowTest','IntegrationJournalTest','IntegrationJsonTest','IntegrationRestartTest','IntegrationServiceCheckpointConflictTest','IntegrationDownloadTest','OslcProviderProtocolTest','OslcProviderServiceTest','OslcRemoteProfilesTest','OslcTransportTest','PortfolioInteropAdapterTest'}

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def ratchet():
    report = Path('taxonomy-app/target/surefire-reports/TEST-com.taxonomy.ArchitectureContextDependencyRatchetTest.xml')
    assert report.is_file(), 'No native JUnit ratchet result'
    root = ET.parse(report).getroot()
    assert root.get('errors') == '0' and root.get('skipped') == '0', root.attrib
    failure = next(c.find('failure') for c in root.findall('testcase') if c.get('name') == 'managedContextDependenciesMatchReviewedBaseline')
    assert failure is not None, 'Expected a measured delta for the new explicit port'
    message = failure.get('message', '')
    marker = 'update the baseline to:'
    assert marker in message, message[:3000]
    payload = message.split(marker, 1)[1]
    new, _ = json.JSONDecoder().raw_decode(payload[payload.index('{'):])
    path = Path('.github/architecture-dependency-baseline.json')
    old = json.loads(path.read_text())
    def index(document):
        return {(e['fromContext'], e['fromPackage'], e['toContext'], e['toPackage']): e['classDependencyCount'] for e in document['edges']}
    before, after = index(old), index(new)
    delta = [(key, before.get(key, 0), after.get(key, 0)) for key in sorted(before.keys() | after.keys()) if before.get(key) != after.get(key)]
    for key, previous, current in delta:
        source, package, target, target_package = key
        assert (source == 'interop' and target in ('portfolio', 'workspace')) or (
            source == 'app-composition' and package == 'com.taxonomy.composition.interop'
            and target in ('portfolio', 'workspace', 'interop')), (key, previous, current)
        if source == 'interop' and target == 'portfolio': assert current == 0
    assert delta and not any(k[0] == 'interop' and k[2] == 'portfolio' for k in after)
    path.write_text(json.dumps(new, indent=2) + '\n')
    (TEMP / 'interop-dependency-delta.json').write_text(json.dumps(delta, indent=2))
    # The logical seam causes this class-level delta; pure file moves do not.
    # Include the measured baseline in the seam commit, not just its relocation child.
    env = dict(os.environ, GIT_INDEX_FILE=str(TEMP / 'interop-seam.index'))
    seam_path = Path('/tmp/interop-seam-tree')
    subprocess.run(['git','read-tree',seam_path.read_text()], env=env, check=True)
    blob = git('hash-object','-w',str(path))
    subprocess.run(['git','update-index','--add','--cacheinfo','100644,' + blob + ',' + str(path)], env=env, check=True)
    seam_path.write_text(subprocess.check_output(['git','write-tree'],env=env,text=True).strip())
    print('EXPECTED_DEPENDENCY_DELTA', json.dumps(delta))

def results(kind):
    reports = sorted(Path('.').glob('*/target/surefire-reports/TEST-*.xml'))
    if kind == 'behavior':
        reports = [p for p in reports if p.stem.split('.')[-1] in BEHAVIOR]
        assert {p.stem.split('.')[-1] for p in reports} == BEHAVIOR, [str(p) for p in reports]
    totals = dict.fromkeys(('tests','failures','errors','skipped'), 0)
    suites = {}
    for path in reports:
        suite = ET.parse(path).getroot()
        assert int(suite.get('tests','0')) > 0, str(path)
        suites[path.stem] = int(suite.get('tests'))
        for key in totals: totals[key] += int(suite.get(key, '0'))
    assert totals['tests'] > 0 and all(totals[k] == 0 for k in ('failures','errors','skipped')), totals
    print('NATIVE_RESULTS', kind, json.dumps(totals), json.dumps(suites))
    (TEMP / ('interop-' + kind + '-results.json')).write_text(json.dumps({'totals':totals,'suites':suites}, indent=2))

def publish():
    assert git('rev-parse','HEAD') == BASE
    assert not git('diff', BASE, '--', '.github/critical-coverage-policy.json', 'taxonomy-app/src/main/resources/db')
    git('diff','--check',BASE)
    git('add','-A')
    final_tree = git('write-tree')
    seam_tree = Path('/tmp/interop-seam-tree').read_text()
    def listing(tree):
        result = {}
        for line in git('ls-tree','-r',tree).splitlines():
            meta, path = line.split('\t',1)
            mode, kind, sha = meta.split()
            result[path] = (mode, kind, sha)
        return result
    original = listing(BASE)
    known = {v[2] for v in original.values()}
    def post(endpoint, body):
        request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/' + endpoint,
            data=json.dumps(body).encode(), headers={'Authorization':'Bearer ' + os.environ['GH_TOKEN'],
            'Accept':'application/vnd.github+json','Content-Type':'application/json'}, method='POST')
        with urllib.request.urlopen(request, timeout=60) as response: return json.load(response)
    def upload_tree(tree, parent):
        before, after = listing(parent), listing(tree)
        changes=[]
        for path in sorted(before.keys() | after.keys()):
            if before.get(path) == after.get(path): continue
            if path not in after:
                changes.append({'path':path,'mode':before[path][0],'type':'blob','sha':None}); continue
            mode, kind, sha = after[path]
            assert kind == 'blob'
            if sha not in known:
                data = subprocess.check_output(['git','cat-file','blob',sha])
                remote = post('git/blobs',{'content':base64.b64encode(data).decode(),'encoding':'base64'})['sha']
                assert remote == sha; known.add(sha)
            changes.append({'path':path,'mode':mode,'type':kind,'sha':sha})
        remote = post('git/trees',{'base_tree':git('rev-parse',parent + '^{tree}'),'tree':changes})['sha']
        assert remote == tree, (remote,tree)
        return changes
    seam_changes=upload_tree(seam_tree,BASE)
    final_changes=upload_tree(final_tree,seam_tree)
    renames=git('diff','--name-status','--find-renames=100%',seam_tree,final_tree)
    moves=[line for line in renames.splitlines() if line.startswith('R100\t')]
    source_count=len(list(Path('taxonomy-interop/src/main/java').rglob('*.java')))
    test_count=len(list(Path('taxonomy-interop/src/test/java').rglob('*.java')))
    assert len(moves) == source_count + test_count, (len(moves),source_count,test_count)
    assert not any('verification/' in e['path'] or '628-interop-extraction.yml' in e['path'] for e in seam_changes+final_changes)
    result={'base':BASE,'seam_tree':seam_tree,'tree':final_tree,'production_files':source_count,'moved_unit_test_files':test_count,'exact_moves':len(moves),
            'seam_changes':seam_changes,'final_changes':final_changes}
    (TEMP/'interop-verified-source.json').write_text(json.dumps(result,indent=2))
    print('VERIFIED_TREES', json.dumps({k:v for k,v in result.items() if k not in ('seam_changes','final_changes')}))
    print('FINAL_DIFF', git('diff','--stat',BASE,final_tree))

if sys.argv[1] == 'ratchet': ratchet()
elif sys.argv[1] == 'publish': publish()
else: results(sys.argv[1])
