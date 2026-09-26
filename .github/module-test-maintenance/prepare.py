import base64, hashlib, json, lzma, os, pathlib, subprocess, sys
B='1b165cb0c25088d9b406871c89b1e3cdd55b0a98'
M='7778cf5436735b5b8513fb1d872e09915afda9f1'
T='afe9174618655787065db89a02bd4e9b99326094'
HERE=pathlib.Path(__file__).resolve().parent
OUT=pathlib.Path(os.environ['EVIDENCE']);OUT.mkdir(parents=True,exist_ok=True)
def git(*args):
    return subprocess.check_output(['git',*args],text=True).strip()
binary=base64.b64decode(''.join((HERE/f'part{i}').read_text() for i in range(3)),validate=True)
assert hashlib.sha256(binary).hexdigest()=='bf096d59b80c7875fee20bd1fb10b95df656d9186e0e03fc6992f622b21d80cd'
payload=json.loads(lzma.decompress(binary))
assert payload['baseline']==B and payload['main']==M and payload['candidateTree']==T
assert payload['baselineTree']=='cbd66b2c734e2bee83d1d550048df2b9e7a6a075'
subprocess.run(['git','checkout','--detach',B],check=True)
assert git('rev-parse','HEAD^{tree}')==payload['baselineTree']
patch=OUT/'verified.patch';patch.write_text(payload['patch'])
subprocess.run(['git','apply','--index',str(patch)],check=True)
subprocess.run(['git','diff','--cached','--check'],check=True)
assert git('write-tree')==T
subprocess.run(['git','apply','--index',str(HERE/'correction.patch')],check=True)
T='4211c6f6fc9d045e56dcc7e04d8c7a19f3c58aa0'
assert git('write-tree')==T
os.environ.update(GIT_AUTHOR_NAME='ChatGPT',GIT_AUTHOR_EMAIL='chatgpt@users.noreply.github.com',
                  GIT_COMMITTER_NAME='ChatGPT',GIT_COMMITTER_EMAIL='chatgpt@users.noreply.github.com',
                  GIT_AUTHOR_DATE='2026-09-26T19:40:00Z',GIT_COMMITTER_DATE='2026-09-26T19:40:00Z')
intermediate=git('commit-tree',T,'-p',B,'-m','Prepare verified module test relocations')
subprocess.run(['git','reset','--hard',intermediate],check=True)
merge=subprocess.run(['git','merge','--no-commit','--no-ff',M],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(OUT/'merge.log').write_text(merge.stdout)
assert merge.returncode==0,merge.stdout
assert not git('diff','--name-only','--diff-filter=U')
subprocess.run(['git','diff','--cached','--check'],check=True)
tree=git('write-tree')
message='refactor(test): place 206 feature test methods with their owning modules\n\nPreserve existing test bodies and retain HTTP/full-application restart checks.\nReuse the workspace persistence helper only as a classified test JAR.\nMove the replay recording and the existing test-only Structurizr parser with their tests.\nIntegrate main 7778cf5 without changing its production code.\nNo measured build-time improvement is claimed.'
commit=git('commit-tree',tree,'-p',B,'-p',M,'-m',message)
subprocess.run(['git','reset','--hard',commit],check=True)
paths=git('diff','--name-only',M,commit).splitlines()
allowed=['taxonomy-extension-api/pom.xml','taxonomy-workspace/pom.xml','taxonomy-interop/pom.xml','taxonomy-app/pom.xml','taxonomy-export/pom.xml','docs/qa/sparx-implementation-validation.md','docs/qa/standards-interoperability.md']
assert paths and all('/src/test/' in p or p in allowed for p in paths),paths
subprocess.run(['git','diff','--check',M,commit],check=True)
receipt={'baseline':B,'main':M,'tree':tree,'commit':commit,'changedPaths':paths}
(OUT/'candidate.json').write_text(json.dumps(receipt,indent=2))
(OUT/'main-diff.patch').write_text(git('diff','--binary','--full-index',M,commit)+'\n')
print(json.dumps(receipt,indent=2))
