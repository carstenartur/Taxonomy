import json,os,pathlib,re,sys,xml.etree.ElementTree as E
out=pathlib.Path(os.environ['EVIDENCE'])
mode=sys.argv[1]
if mode=='libraries':
    source=pathlib.Path('taxonomy-build/src/test/java/com/taxonomy/build/FeatureTestOwnershipContract.java').read_text()
    wanted=[(module,path[:-5].replace('/','.')) for path,module in re.findall(r'Map.entry\("([^"]+)", "([^"]+)"\)',source) if not path.endswith('EditorPersistenceFixture.java')]
elif mode=='installed':
    wanted=[('taxonomy-interop','com.taxonomy.interop.IntegrationJournalTest'),('taxonomy-interop','com.taxonomy.interop.IntegrationJournalRestartTest')]
elif mode=='application':
    wanted=[('taxonomy-app','com.taxonomy.'+x) for x in ['ArchiMateDiagramTests','VisioDiagramTests','MermaidExportTests','interop.IntegrationRestartTest']]
else:
    wanted=[('taxonomy-build','com.taxonomy.build.'+x) for x in ['FeatureTestOwnershipTest','ModuleOwnedTestPlacementTest','RepositoryResourcesTest']]
summary=[]
for module,cls in wanted:
    reports=[]
    for path in pathlib.Path(module,'target/surefire-reports').glob('TEST-*.xml'):
        root=E.parse(path).getroot();name=root.attrib['name']
        if name==cls or name.startswith(cls+'$'):
            entry={'name':name,**{k:int(root.get(k,'0')) for k in ['tests','failures','errors','skipped']},'time':root.get('time')}
            assert not any(entry[k] for k in ['failures','errors','skipped']),entry
            reports.append(entry)
    assert sum(r['tests'] for r in reports)>0,('missing successful tests',module,cls)
    summary.append({'module':module,'class':cls,'reports':reports})
(out/(mode+'-verified.json')).write_text(json.dumps(summary,indent=2));print(mode,len(summary),'classes verified; cases',sum(r['tests'] for c in summary for r in c['reports']))
