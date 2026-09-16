"""Isolated owner-POM regression runner; never included in product trees."""
from pathlib import Path
import base64, hashlib, json, os, subprocess, sys, urllib.request
mode, root = sys.argv[1], Path(sys.argv[2]).resolve()
FILE='taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java'
BASES=[('gate',1054,'c65883786eecdc5e6f3b66f99a6bc82d705cf586'),
       ('workspace',1062,'54fadbb1bbe757be10fdc211c945273ee9048370'),
       ('templates',1063,'30301d834a8c849d9a2906cd99fb9fab0bba0d75'),
       ('interop',1064,'a690921d849fc8da58b509b105d4c31ec20647bd')]

def git(*args): return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def replace(old,new,count=1):
    file=root/FILE; text=file.read_text(); assert text.count(old)==count,(old[:100],text.count(old))
    file.write_text(text.replace(old,new))

if mode=='tests':
    # Expose the already-known checkout to the same private assertion, without yet
    # changing its read behavior. Existing fixtures and assertions stay intact.
    replace('assertModuleGateOwnerDependencies(root.resolve("taxonomy-build/pom.xml"))',
            'assertModuleGateOwnerDependencies(root, root.resolve("taxonomy-build/pom.xml"))')
    file=root/FILE; text=file.read_text()
    old='assertModuleGateOwnerDependencies(temporaryRepository.resolve("taxonomy-build/pom.xml"))'
    assert text.count(old)==3,text.count(old)
    text=text.replace(old,'assertModuleGateOwnerDependencies(temporaryRepository, temporaryRepository.resolve("taxonomy-build/pom.xml"))')
    text=text.replace('private static void assertModuleGateOwnerDependencies(Path pom)',
                      'private static void assertModuleGateOwnerDependencies(Path checkout, Path pom)')
    tests=r'''

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void ownerPomCannotParseOutsideCheckout(String linkKind) throws Exception {
        Path outside = externalFixture("outside-owner-pom");
        Files.writeString(outside.resolve("pom.xml"), "EXTERNAL_CONTENT_MUST_NOT_BE_PARSED");
        Path build = temporaryRepository.resolve("taxonomy-build");
        if (linkKind.equals("file")) {
            Files.createDirectories(build);
            Files.createSymbolicLink(build.resolve("pom.xml"), outside.resolve("pom.xml"));
        } else {
            Files.createSymbolicLink(build, outside);
        }
        assertThatThrownBy(() -> assertModuleGateOwnerDependencies(temporaryRepository, build.resolve("pom.xml")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Owner POM outside checkout");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void ownerPomAcceptsContainedAliases(String linkKind) throws Exception {
        pom("owner-alias-target", "taxonomy-build", moduleGateOwnerDependencies("classified-extras"));
        Path target = temporaryRepository.resolve("owner-alias-target");
        Path build = temporaryRepository.resolve("taxonomy-build");
        if (linkKind.equals("file")) {
            Files.createDirectories(build);
            Files.createSymbolicLink(build.resolve("pom.xml"), target.resolve("pom.xml"));
        } else {
            Files.createSymbolicLink(build, target);
        }
        assertThatCode(() -> assertModuleGateOwnerDependencies(temporaryRepository, build.resolve("pom.xml")))
                .doesNotThrowAnyException();
    }
'''
    index=text.rfind('\n}'); assert index>0
    file.write_text(text[:index]+tests+text[index:])
elif mode=='patch':
    replace('''    private static void assertModuleGateOwnerDependencies(Path checkout, Path pom) throws Exception {
        Map<String, String> dependencies = directProjectDependencies(pom);''',
        '''    private static void assertModuleGateOwnerDependencies(Path checkout, Path pom) throws Exception {
        Path physicalPom = pom.toRealPath();
        if (!physicalPom.startsWith(checkout.toRealPath()) || !Files.isRegularFile(physicalPom)) {
            throw new IllegalStateException("Owner POM outside checkout: " + pom);
        }
        Map<String, String> dependencies = directProjectDependencies(physicalPom);''')
    subprocess.run(['git','-C',str(root),'diff','--check'],check=True)
elif mode=='publish-candidates':
    out=Path(sys.argv[3]);out.mkdir(parents=True,exist_ok=True)
    assert git('rev-parse','HEAD')==BASES[-1][2]
    assert git('diff','--name-only')==FILE
    assert git('ls-files','--others','--exclude-standard')==''
    for _,_,base in BASES: assert git('show',base+':'+FILE)==git('show',BASES[-1][2]+':'+FILE)
    headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json','Content-Type':'application/json'}
    def api(path,data):
        req=urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/'+path,headers=headers,
            data=json.dumps(data).encode(),method='POST')
        with urllib.request.urlopen(req,timeout=60) as response:return json.load(response)
    data=(root/FILE).read_bytes();blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    assert api('git/blobs',{'content':base64.b64encode(data).decode(),'encoding':'base64'})['sha']==blob
    (out/'tested-graph-test.java').write_bytes(data)
    candidates=[];previous=None
    for stage,pr,base in BASES:
        tree=api('git/trees',{'base_tree':git('rev-parse',base+'^{tree}'),
            'tree':[{'path':FILE,'mode':'100644','type':'blob','sha':blob}]})['sha']
        parents=[base]+([] if previous is None else [previous])
        commit=api('git/commits',{'tree':tree,'parents':parents,'message':
            'test(architecture): contain the owner-POM read before parsing ('+stage+')\n\n'
            'Validate real file identity against the explicit checkout at the owner\n'
            'dependency assertion. Cover external file/directory links with unreadable\n'
            'sentinels and retain contained aliases and all dependency assertions.\n'
            'Only the shared test fixture changes; production and policy stay intact.'})['sha']
        api('git/refs',{'ref':'refs/heads/verify/628-owner-pom-'+os.environ['GITHUB_RUN_ID']+'-'+stage,'sha':commit})
        candidates.append({'stage':stage,'pr':pr,'base':base,'sha':commit,'tree':tree,'parents':parents});previous=commit
    doc={'run':os.environ['GITHUB_RUN_ID'],'path':FILE,'blob':blob,'candidates':candidates}
    (out/'candidates.json').write_text(json.dumps(doc,indent=2)+'\n')
    print(json.dumps(doc,indent=2))
else:raise SystemExit('Unknown operation')
