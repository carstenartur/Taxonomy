"""Carry only physical extraction changes onto main; preserve all main guards."""
import hashlib, json, os, pathlib, subprocess, sys, urllib.request, xml.etree.ElementTree as ET, zipfile
MAIN='8cd4395d93ba56c80ae6ea5dff031944772e7771'
GATE='c0d7bd654ba774daa0701b6e18dfa3fb85c3a92c'
root=pathlib.Path(sys.argv[2]).resolve()
out=pathlib.Path(os.environ['RUNNER_TEMP'])/'workspace-main-evidence'
out.mkdir(exist_ok=True)

def git(*args, binary=False):
    data=subprocess.check_output(['git','-C',str(root),*args])
    return data if binary else data.decode().strip()
assert git('rev-parse','HEAD')==MAIN

def prepare():
    extracted=os.environ['EXTRACTED_SHA']
    subprocess.run(['git','fetch','--no-tags','origin',GATE,extracted],cwd=root,check=True,stdout=subprocess.DEVNULL)
    assert git('diff','--name-only',MAIN,GATE,'--','taxonomy-app/src/main','taxonomy-domain','taxonomy-dsl','taxonomy-export','taxonomy-extension-api')=='', 'Production snapshots must be identical'
    patch=git('diff','--binary','--no-renames',GATE,extracted,'--','.',':(exclude)taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java',binary=True)
    subprocess.run(['git','apply','--index','-'],cwd=root,input=patch,check=True)
    # Reuse the physical-ownership assertion, not the unmerged general POM evaluator.
    old=git('show',extracted+':taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java')
    start=old.index('    @Test\n    void workspaceImplementationIsPhysicallyOwnedByItsMavenModule()')
    method=old[start:old.rfind('\n}')]
    content='''package com.taxonomy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression contract for the first physically extracted application context. */
class ArchitectureWorkspaceModuleTest {
'''+method+'''
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
    p=root/'taxonomy-app/src/test/java/com/taxonomy/ArchitectureWorkspaceModuleTest.java'
    p.write_text(content)
    for name in ('pom.xml','.mvn/verification-suites.json'):
        p=root/name; text=p.read_text()
        anchor='ArchitectureWorkspaceStorageOwnershipTest'
        assert text.count(anchor)==1,name
        p.write_text(text.replace(anchor,anchor+',ArchitectureWorkspaceModuleTest'))
    # Apply the remaining inventory/ownership changes needed by the physical move.
    moved=json.loads((out.parent/'physical-source-moves.json').read_text()) if (out.parent/'physical-source-moves.json').exists() else None
    # Preserve readable Java indentation in the two multi-owner source scans.
    for name,first,last in [
      ('taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java','        for (String module : java.util.List.of("taxonomy-app", "taxonomy-workspace")) {','        return Set.copyOf(variables);'),
      ('taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java','        for (String module : List.of("taxonomy-app", "taxonomy-workspace")) {','        assertThat(unclassifiedPackages)')]:
        p=root/name; s=p.read_text(); a=s.index(first)+len(first); b=s.index(last,a)
        block=s[a:b].splitlines(); close=max(i for i,l in enumerate(block) if l.strip()=='}')
        block=[('    '+line if 0<i<close and line.strip() else line) for i,line in enumerate(block)]
        p.write_text(s[:a]+'\n'.join(block)+'\n'+s[b:])
    # Report selectors identify class names; explicit per-module report paths must
    # follow any moved tests wherever such paths are declared.
    test_moves=[]
    for line in git('diff','--name-status','--find-renames=100%',GATE,extracted).splitlines():
        fields=line.split('\t')
        if len(fields)==3 and fields[0]=='R100' and '/src/test/java/' in fields[1]: test_moves.append((fields[1],fields[2]))
    for p in list((root/'.github').rglob('*'))+list((root/'.mvn').rglob('*')):
        if not p.is_file() or p.suffix not in ('.json','.yml','.sh','.mjs'): continue
        s=p.read_text(); t=s
        for old,new in test_moves:
            fqcn=old.split('/src/test/java/',1)[1][:-5].replace('/','.')
            t=t.replace('taxonomy-app/target/surefire-reports/TEST-'+fqcn+'.xml','taxonomy-workspace/target/surefire-reports/TEST-'+fqcn+'.xml')
        if t!=s: p.write_text(t)
    # Full suite verifies the packaged ordinary JAR and absence of duplicate owners.
    p=root/'taxonomy-build/src/test/java/com/taxonomy/build/WorkspaceModulePackagingIT.java'
    p.write_text('''package com.taxonomy.build;

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
            applications = files.filter(p -> p.getFileName().toString().matches("taxonomy-app-.*\\\\.jar")).toList();
        }
        assertThat(applications).hasSize(1);
        try (var jar = new ZipFile(applications.getFirst().toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-") && n.endsWith(".jar")).toList()).hasSize(1);
            for (String part : List.of("workspace", "versioning", "editor")) {
                assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/" + part + "/"))).isFalse();
            }
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
    }
}
''')
    # Update current module-boundary documentation, not historical assessment files.
    for lang,label in [('en','## Physical workspace extraction'),('de','## Physische Workspace-Auslagerung')]:
        p=root/'docs'/lang/'MODULE_BOUNDARIES.md'; s=p.read_text()
        if lang=='en':
            s=s.replace('Its eleven selected test classes','Its twelve selected test classes')
            s+='\n'+label+'\n\n`taxonomy-workspace` now contains the workspace, versioning and editor production packages.\n`taxonomy-app` depends on its ordinary JAR; application configuration and SQL migrations\nremain in the application. Existing package names and behavior are unchanged.\nThe architecture profile also runs `ArchitectureWorkspaceModuleTest` to require\nphysical ownership. The other six planned feature modules are not yet extracted.\n'
        else:
            s=s.replace('Die elf ausgewählten Testklassen','Die zwölf ausgewählten Testklassen')
            s+='\n'+label+'\n\n`taxonomy-workspace` enthält jetzt die Production-Packages für Workspace, Versionierung\nund Editor. `taxonomy-app` bindet das normale JAR ein; Anwendungskonfiguration und\nSQL-Migrationen bleiben in der Anwendung. Package-Namen und Verhalten bleiben unverändert.\nDas Architekturprofil führt zusätzlich `ArchitectureWorkspaceModuleTest` für die\nphysische Eigentümerschaft aus. Die sechs anderen geplanten Fachmodule sind noch nicht ausgelagert.\n'
        p.write_text(s)
    subprocess.run(['git','diff','--check'],cwd=root,check=True)
    subprocess.run(['git','add','-A'],cwd=root,check=True)
    assert not (root/'taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java').exists()
    for old in git('ls-tree','-r','--name-only',MAIN,'--','taxonomy-app/src/main/java/com/taxonomy/workspace','taxonomy-app/src/main/java/com/taxonomy/versioning','taxonomy-app/src/main/java/com/taxonomy/editor').splitlines():
        assert (root/old.replace('taxonomy-app/','taxonomy-workspace/',1)).read_bytes()==git('show',MAIN+':'+old,binary=True)
    assert git('diff','--cached','--name-only',MAIN,'--','taxonomy-app/src/main/resources')==''
    print('MAIN EXTRACTION PREPARED: no dependency on unmerged #1054/#1061; all 104 production source bytes preserved',flush=True)


def verify(mode):
    command=['./mvnw','-B']
    if mode=='architecture': command += ['-Parchitecture-tests','-Dsurefire.failIfNoSpecifiedTests=false','clean','verify']
    else: command += ['verify','-DexcludedGroups=real-llm']
    print('COMMAND',' '.join(command),flush=True)
    with (out/(mode+'.log')).open('w') as log: result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
    reports=[]
    for xml in root.glob('*/target/surefire-reports/TEST-*.xml'):
        s=ET.parse(xml).getroot(); r={'module':xml.parts[-4],'name':s.attrib['name'],**{k:int(s.attrib.get(k,0)) for k in ('tests','failures','errors','skipped')}}; reports.append(r)
        if r['failures'] or r['errors']: print('FAIL',r,flush=True)
    (out/(mode+'-tests.json')).write_text(json.dumps(reports,indent=2)+'\n')
    counts={k:sum(r[k] for r in reports) for k in ('tests','failures','errors','skipped')}
    print('RESULT',mode,result.returncode,counts,flush=True)
    if result.returncode: print((out/(mode+'.log')).read_text()[-24000:],flush=True)
    else:
        with zipfile.ZipFile(next((root/'taxonomy-app/target').glob('taxonomy-app-*.jar'))) as jar:
            assert sum(n.startswith('BOOT-INF/lib/taxonomy-workspace-') and n.endswith('.jar') for n in jar.namelist())==1
        print('BOOT JAR includes physical workspace library',flush=True)
    return result.returncode


def publish():
    subprocess.run(['git','add','-A'],cwd=root,check=True)
    paths=git('diff','--cached','--name-only','--no-renames',MAIN).splitlines()
    assert not any(p.startswith('verification/') or '/628-' in p for p in paths)
    original={}
    for line in git('ls-tree','-r',MAIN).splitlines():
        meta,path=line.split('\t',1); mode,kind,sha=meta.split(); original[path]=(mode,sha)
    known={v[1] for v in original.values()}
    def api(endpoint,data):
        req=urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/'+endpoint,data=json.dumps(data).encode(),headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json','Content-Type':'application/json'},method='POST')
        with urllib.request.urlopen(req,timeout=30) as r: return json.load(r)
    entries=[]
    for path in paths:
        file=root/path
        if not file.exists(): entries.append({'path':path,'mode':original[path][0],'type':'blob','sha':None}); continue
        sha=git('hash-object',path)
        if sha not in known: assert api('git/blobs',{'content':file.read_text(),'encoding':'utf-8'})['sha']==sha
        entries.append({'path':path,'mode':git('ls-files','-s','--',path).split()[0],'type':'blob','sha':sha})
    tree=api('git/trees',{'base_tree':git('rev-parse',MAIN+'^{tree}'),'tree':entries})['sha']
    assert tree==git('write-tree')
    data={'base':MAIN,'tree':tree,'files':paths}
    (out/'prepared-tree.json').write_text(json.dumps(data,indent=2)+'\n')
    (out/'source.patch').write_bytes(git('diff','--cached','--binary','--find-renames=100%',MAIN,binary=True))
    print('MAIN_SOURCE_TREE',tree,'FILES',len(paths),flush=True)

mode=sys.argv[1]
if mode=='prepare': prepare()
elif mode in ('architecture','verify'): sys.exit(verify(mode))
elif mode=='publish': publish()
else: raise ValueError(mode)
