"""Disposable extraction driver; final PR contains source moves, not this helper."""
import collections, copy, hashlib, json, os, pathlib, re, subprocess, sys, urllib.request, xml.etree.ElementTree as ET, zipfile
BASE = 'c0d7bd654ba774daa0701b6e18dfa3fb85c3a92c'
root = pathlib.Path(sys.argv[2]).resolve()
out = pathlib.Path(os.environ['RUNNER_TEMP']) / 'workspace-extraction-evidence'
out.mkdir(exist_ok=True)
PARTS = ('workspace', 'versioning', 'editor')
GRAPH = 'taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java'

def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
assert git('rev-parse', 'HEAD') == BASE

def put(path, content):
    p = root / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)

def replace(path, old, new):
    p = root / path
    s = p.read_text()
    assert s.count(old) == 1, (path, old)
    p.write_text(s.replace(old, new, 1))

PHYSICAL_TEST = '''
    @Test
    void workspaceImplementationIsPhysicallyOwnedByItsMavenModule() throws Exception {
        Path root = checkoutRoot();
        Path module = root.resolve("taxonomy-workspace");
        assertThat(module.resolve("pom.xml")).as("physical workspace Maven module").isRegularFile();
        for (String part : List.of("workspace", "versioning", "editor")) {
            Path source = module.resolve("src/main/java/com/taxonomy/" + part);
            assertThat(source).isDirectory();
            try (var files = Files.walk(source)) {
                assertThat(files.filter(p -> p.toString().endsWith(".java")).count()).isPositive();
            }
            assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/" + part)).doesNotExist();
        }
        var modules = ArchitectureModuleExtractionTest.discoverModules(root,
                ArchitectureModuleExtractionTest.readPolicy(root.resolve(".github/architecture-contexts.json")));
        assertThat(modules).containsEntry("taxonomy-workspace", module);
        assertThat(module.resolve("target/classes/com/taxonomy/editor/ArchitectureEditorService.class")).isRegularFile();
        assertThat(module.resolve("target/classes/com/taxonomy/workspace/storage/DslGitRepository.class")).isRegularFile();
        assertThat(module.resolve("target/classes/com/taxonomy/versioning/service/RepositoryStateService.class")).isRegularFile();
    }
'''
# No additional parser API is needed: the existing extraction test independently
# verifies effective reactor membership and every reachable dependency blocker.
PHYSICAL_TEST = PHYSICAL_TEST.replace('''        var modules = ArchitectureModuleExtractionTest.discoverModules(root,
                ArchitectureModuleExtractionTest.readPolicy(root.resolve(".github/architecture-contexts.json")));
        assertThat(modules).containsEntry("taxonomy-workspace", module);
''','')

def red():
    p = root / GRAPH
    t = p.read_text()
    pos = t.rfind('\n}')
    assert pos > 0
    p.write_text(t[:pos] + '\n' + PHYSICAL_TEST + t[pos:])
    code = run('red', ['./mvnw','-B','-Parchitecture-tests','-Dsurefire.failIfNoSpecifiedTests=false','verify'])
    report = ET.parse(root/'taxonomy-build/target/surefire-reports/TEST-com.taxonomy.ArchitectureModuleGraphTest.xml').getroot()
    failures = [c for c in report.findall('testcase') if c.find('failure') is not None]
    assert code != 0 and len(failures) == 1 and failures[0].attrib['name'] == 'workspaceImplementationIsPhysicallyOwnedByItsMavenModule'
    assert 'physical workspace Maven module' in ET.tostring(failures[0], encoding='unicode')
    print('RED: missing physical workspace module reproduced; existing graph fixtures retained', flush=True)

TEST_MOVES = [
 'workspace/service/RepositoryContextTest.java',
 'workspace/service/WorkspaceArchitectureIntegrationPortTest.java',
 'workspace/service/RepositoryMembershipServiceTest.java',
 'workspace/service/WorkspaceAccessServiceTest.java',
 'workspace/service/WorkspaceContextResolverTest.java',
 'workspace/service/WorkspaceContextResolverRequestPinningTest.java',
 'workspace/service/SystemRepositoryServiceTest.java',
 'versioning/service/ContextCompareServiceTest.java',
 'versioning/service/ContextHistoryServiceTest.java',
 'versioning/service/ConflictDetectionServiceTest.java',
 'versioning/service/SemanticGitMergeServiceTest.java',
 'versioning/service/SemanticConflictDetectionServiceTest.java',
 'workspace/storage/ExpectedHeadDslCommitterTest.java',
 'workspace/storage/DslWorkspacePublicationAdapterTest.java',
]

def apply():
    before = {}
    for part in PARTS:
        old = root / 'taxonomy-app/src/main/java/com/taxonomy' / part
        for p in old.rglob('*'):
            if p.is_file(): before[str(p.relative_to(root))] = hashlib.sha256(p.read_bytes()).hexdigest()
        new = root / 'taxonomy-workspace/src/main/java/com/taxonomy' / part
        new.parent.mkdir(parents=True, exist_ok=True)
        old.rename(new)
    assert len(before) == 104, len(before)
    for path in TEST_MOVES:
        old = root / 'taxonomy-app/src/test/java/com/taxonomy' / path
        new = root / 'taxonomy-workspace/src/test/java/com/taxonomy' / path
        new.parent.mkdir(parents=True, exist_ok=True)
        old.rename(new)
    version = ET.parse(root/'pom.xml').getroot().find('{*}version').text
    def dep(group, artifact, version=None, scope=None):
        text = '        <dependency>\n            <groupId>'+group+'</groupId>\n            <artifactId>'+artifact+'</artifactId>\n'
        if version: text += '            <version>'+version+'</version>\n'
        if scope: text += '            <scope>'+scope+'</scope>\n'
        return text + '        </dependency>\n'
    dependencies = ''.join(dep('com.taxonomy', a, '${project.version}') for a in ('taxonomy-domain','taxonomy-dsl','taxonomy-export'))
    dependencies += ''.join(dep('org.springframework.boot', a) for a in ('spring-boot-starter-web','spring-boot-starter-data-jpa','spring-boot-starter-flyway','spring-boot-starter-security'))
    dependencies += dep('org.hibernate.search','hibernate-search-mapper-orm','${hibernate-search.version}')
    dependencies += dep('org.eclipse.jgit','org.eclipse.jgit','${jgit.version}')
    dependencies += dep('io.github.carstenartur','jgit-storage-hibernate-core','${jgit-storage-hibernate.version}')
    dependencies += dep('org.springdoc','springdoc-openapi-starter-common','${springdoc.version}')
    dependencies += dep('org.springframework.boot','spring-boot-starter-test',scope='test')
    dependencies += dep('org.springframework.security','spring-security-test',scope='test')
    put('taxonomy-workspace/pom.xml', '''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.taxonomy</groupId>
        <artifactId>taxonomy</artifactId>
        <version>'''+version+'''</version>
    </parent>
    <artifactId>taxonomy-workspace</artifactId>
    <name>Taxonomy Workspace</name>
    <description>Workspace authority, versioning, semantic editor history and JGit storage</description>
    <dependencies>
'''+dependencies+'''    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <executions>
                    <execution>
                        <id>enforce-workspace-boundary</id>
                        <phase>validate</phase>
                        <goals><goal>enforce</goal></goals>
                        <configuration>
                            <rules>
                                <bannedDependencies>
                                    <searchTransitive>true</searchTransitive>
                                    <excludes><exclude>com.taxonomy:taxonomy-app</exclude></excludes>
                                    <message>Workspace is a library; application composition must depend on workspace, never the reverse.</message>
                                </bannedDependencies>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
''')
    replace('pom.xml','        <module>taxonomy-app</module>','        <module>taxonomy-workspace</module>\n        <module>taxonomy-app</module>')
    for path in ('taxonomy-app/pom.xml','taxonomy-coverage/pom.xml'):
        replace(path,'    <dependencies>','    <dependencies>\n'+dep('com.taxonomy','taxonomy-workspace','${project.version}').rstrip())
    replace('Dockerfile','COPY taxonomy-app/pom.xml taxonomy-app/pom.xml','COPY taxonomy-workspace/pom.xml taxonomy-workspace/pom.xml\nCOPY taxonomy-app/pom.xml taxonomy-app/pom.xml')
    replace('Dockerfile','COPY taxonomy-app/src taxonomy-app/src','COPY taxonomy-workspace/src taxonomy-workspace/src\nCOPY taxonomy-app/src taxonomy-app/src')
    # Preserve all coverage thresholds and scopes, changing ownership only.
    path = root/'.github/critical-coverage-policy.json'
    original = json.loads(path.read_text())
    data = copy.deepcopy(original)
    for entry in data['criticalPackages']:
        if entry['package'].startswith(tuple('com/taxonomy/'+part+'/' for part in PARTS)) or entry['package'] in ['com/taxonomy/'+part for part in PARTS]:
            assert entry['module'] == 'taxonomy-app'
            entry['module'] = 'taxonomy-workspace'
    for n, prefix in enumerate(data['changedSourcePrefixes']):
        if prefix.startswith(tuple('taxonomy-app/src/main/java/com/taxonomy/'+part+'/' for part in PARTS)):
            data['changedSourcePrefixes'][n] = prefix.replace('taxonomy-app/', 'taxonomy-workspace/', 1)
    assert [e['minimums'] for e in data['criticalPackages']] == [e['minimums'] for e in original['criticalPackages']]
    # Minimal text changes retain formatting and threshold diffs legibility.
    text = path.read_text()
    for part in PARTS:
        text = text.replace('taxonomy-app/src/main/java/com/taxonomy/'+part+'/', 'taxonomy-workspace/src/main/java/com/taxonomy/'+part+'/')
        text = re.sub(r'"module": "taxonomy-app",(\s+"package": "com/taxonomy/'+part+r'(?:/[^\"]*)?")', r'"module": "taxonomy-workspace",\1', text)
    assert json.loads(text) == data
    path.write_text(text)
    # JaCoCo aggregation must contain the new shipped module, including app-run executions.
    for p in (root/'.github').glob('*coverage*policy.json'):
        if p.name == 'critical-coverage-policy.json': continue
        d = json.loads(p.read_text())
        if 'expectedGroups' in d:
            d['expectedGroups'].append('taxonomy-workspace')
            p.write_text(json.dumps(d, indent=2)+'\n')
    # All workflows that observe app inputs must observe the newly extracted code too.
    for p in (root/'.github/workflows').glob('*.yml'):
        s = p.read_text()
        lines = s.splitlines(keepends=True)
        result = []
        for line in lines:
            result.append(line)
            if re.match(r"\s*- ['\"]?taxonomy-app/", line):
                indent = re.match(r'\s*',line).group()
                replacement = indent+"- 'taxonomy-workspace/**'\n"
                if not result or len(result)<2 or result[-2] != replacement:
                    result.append(replacement)
        # Deduplicate added workspace filters within each paths block only.
        rebuilt=[]; seen=False
        for line in result:
            if re.match(r'\s*paths:',line): seen=False
            if "- 'taxonomy-workspace/**'" in line:
                if seen: continue
                seen=True
            rebuilt.append(line)
        new=''.join(rebuilt)
        if new != s: p.write_text(new)
    # Explicit current-path contracts follow moved sources; Java packages stay stable.
    for p in list((root/'taxonomy-app/src/test').rglob('*.java')):
        s=p.read_text(); t=s
        for part in PARTS:
            t=t.replace('taxonomy-app/src/main/java/com/taxonomy/'+part+'/', 'taxonomy-workspace/src/main/java/com/taxonomy/'+part+'/')
        if t!=s: p.write_text(t)
    # Keep property documentation discovery covering both production owners.
    replace('taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java',
      '''        Path javaRoot = root.resolve("taxonomy-app/src/main/java");
        try (Stream<Path> files = Files.walk(javaRoot)) {''',
      '''        for (String module : java.util.List.of("taxonomy-app", "taxonomy-workspace")) {
        Path javaRoot = root.resolve(module + "/src/main/java");
        try (Stream<Path> files = Files.walk(javaRoot)) {''')
    replace('taxonomy-app/src/test/java/com/taxonomy/config/ConfigurationReferenceContractTest.java',
      '        return Set.copyOf(variables);', '        }\n        return Set.copyOf(variables);')
    # The source-policy scan still checks composition root classes, and now walks
    # every extracted context root as well as the remaining application sources.
    p = root/'taxonomy-app/src/test/java/com/taxonomy/ArchitectureContextDependencyRatchetTest.java'
    s=p.read_text()
    start=s.index('        List<String> unclassifiedPackages;')
    end=s.index('\n        assertThat(unclassifiedPackages)',start)
    old=s[start:end]
    new=old.replace('        List<String> unclassifiedPackages;', '        List<String> unclassifiedPackages = new ArrayList<>();\n        for (String module : List.of("taxonomy-app", "taxonomy-workspace")) {\n        Path contextRoot = repositoryRoot.resolve(module + "/src/main/java/com/taxonomy");\n        assertThat(contextRoot).as("production context root %s", module).isDirectory();')
    new=new.replace('Files.walk(packageRoot)', 'Files.walk(contextRoot)').replace('unclassifiedPackages = sources', 'unclassifiedPackages.addAll(sources').replace('packageRoot::relativize','contextRoot::relativize').replace('.toList();','.toList());')+'\n        }\n'
    p.write_text(s[:start]+new+s[end:])
    put('taxonomy-workspace/README.md', '''# Workspace module

This library owns `com.taxonomy.workspace`, `com.taxonomy.versioning` and
`com.taxonomy.editor`: repository/workspace authority, Git storage, versioning,
semantic operation history, undo/redo and checkpoints.

`taxonomy-app` depends on this ordinary JAR and remains the only deployable
Spring Boot application. Existing package names, HTTP routes, entities and SQL
migration resources are unchanged. Application configuration and migrations
remain in `taxonomy-app`; component/entity/repository scanning uses the same
`com.taxonomy` package root across the classpath.

Module-local unit tests exercise repository context, access, membership,
version comparison, semantic Git merging and publication. Cross-context,
application restart, journal persistence and database integration tests remain
in `taxonomy-app`, exercising this library through its Maven dependency.
JaCoCo aggregation includes execution data from both modules and retains the
existing workspace/versioning package coverage floors.

Build from the repository root with `./mvnw verify`. The architecture profile
checks actual compiled ownership and rejects a dependency back to the app.
Other unextracted contexts may still contain cycles; this independent module
must not participate in or reach one. Issue #628 is not complete after this
first extraction.
''')
    for old, checksum in before.items():
        new=old.replace('taxonomy-app/','taxonomy-workspace/',1)
        assert not (root/old).exists() and hashlib.sha256((root/new).read_bytes()).hexdigest() == checksum
    assert git('diff',BASE,'--','taxonomy-app/src/main/resources/db') == ''
    (out/'moves.json').write_text(json.dumps({'base':BASE,'productionMoves':before,'unitTestsMoved':TEST_MOVES},indent=2)+'\n')
    print('EXTRACTED',len(before),'unchanged production files and',len(TEST_MOVES),'unchanged unit test files', flush=True)
    subprocess.run(['git','diff','--check'],cwd=root,check=True)


def run(name, command):
    print('COMMAND',name,' '.join(command),flush=True)
    with (out/(name+'.log')).open('w') as log:
        result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
    reports=[]
    for xml in root.glob('*/target/surefire-reports/TEST-*.xml'):
        suite=ET.parse(xml).getroot()
        reports.append({'module':xml.parts[-4], 'class':suite.attrib['name'], **{k:int(suite.attrib.get(k,0)) for k in ('tests','failures','errors','skipped')}})
    (out/(name+'-tests.json')).write_text(json.dumps(reports,indent=2)+'\n')
    counts={k:sum(s[k] for s in reports) for k in ('tests','failures','errors','skipped')}
    print('RESULT',name,result.returncode,counts,flush=True)
    for s in reports:
        if s['failures'] or s['errors']: print('FAILING_SUITE',s,flush=True)
    if result.returncode: print((out/(name+'.log')).read_text()[-22000:],flush=True)
    return result.returncode


def package_check():
    jars=list((root/'taxonomy-app/target').glob('taxonomy-app-*.jar'))
    assert len(jars)==1,jars
    with zipfile.ZipFile(jars[0]) as jar:
        names=jar.namelist()
        assert len([n for n in names if n.startswith('BOOT-INF/lib/taxonomy-workspace-') and n.endswith('.jar')])==1
        assert not any(n.startswith(tuple('BOOT-INF/classes/com/taxonomy/'+p+'/' for p in PARTS)) for n in names)
        assert any(n.startswith('BOOT-INF/classes/db/migration/') for n in names)
    print('PACKAGING: one workspace JAR, no duplicate application classes, application migrations retained',flush=True)


def publish():
    assert (out/'moves.json').exists()
    subprocess.run(['git','add','-A'],cwd=root,check=True)
    paths=git('diff','--cached','--name-only','--no-renames',BASE).splitlines()
    assert not any(p.startswith(('verification/', '.github/workflows/628-')) for p in paths),paths
    def api(endpoint,data):
        req=urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/'+endpoint,
           data=json.dumps(data).encode(),headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json','Content-Type':'application/json'},method='POST')
        with urllib.request.urlopen(req,timeout=30) as response: return json.load(response)
    original={}
    for line in git('ls-tree','-r',BASE).splitlines():
        meta,path=line.split('\t',1); mode,kind,sha=meta.split(); original[path]=(mode,sha)
    entries=[]
    for path in paths:
        file=root/path
        if not file.exists():
            entries.append({'path':path,'mode':original[path][0],'type':'blob','sha':None}); continue
        sha=git('hash-object',path)
        if sha not in {v[1] for v in original.values()}:
            created=api('git/blobs',{'content':file.read_text(),'encoding':'utf-8'})['sha']
            assert sha==created,path
        mode=git('ls-files','-s','--',path).split()[0]
        entries.append({'path':path,'mode':mode,'type':'blob','sha':sha})
    tree=api('git/trees',{'base_tree':git('rev-parse',BASE+'^{tree}'),'tree':entries})['sha']
    assert tree==git('write-tree')
    (out/'published-tree.json').write_text(json.dumps({'base':BASE,'tree':tree,'files':paths},indent=2)+'\n')
    print('PREPARED_SOURCE_TREE',tree,'FILES',len(paths),flush=True)

mode=sys.argv[1]
if mode=='red': red()
elif mode=='apply': apply()
elif mode=='architecture': sys.exit(run('architecture',['./mvnw','-B','-Parchitecture-tests','-Dsurefire.failIfNoSpecifiedTests=false','clean','verify']))
elif mode=='verify':
    code=run('verify',['./mvnw','-B','verify','-DexcludedGroups=real-llm'])
    if code==0: package_check()
    sys.exit(code)
elif mode=='publish': publish()
else: raise ValueError(mode)
