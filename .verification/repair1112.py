from pathlib import Path
import json, subprocess
assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip() == '37142420865bd76b6492908b46ae9c8f060a7d51'
p=Path('.github/architecture-dependency-baseline.json');data=json.loads(p.read_text());matches=[e for e in data['edges'] if e['fromPackage']=='com.taxonomy.analysis.relations' and e['toPackage']=='com.taxonomy.catalog.model'];assert len(matches)==1 and matches[0]['classDependencyCount']==2;matches[0]['classDependencyCount']=1;p.write_text(json.dumps(data,indent=2)+'\n')
p=Path('docs/dev/requirement-relation-downwalk.md');text=p.read_text();start=text.index('The hierarchical adapter reuses',text.index('## Reviewed module dependencies'));text=text[:start]+'''The hierarchical adapter reuses the existing analysis-to-knowledge direction; it
introduces no reverse dependency or new Maven module. The authoritative ArchUnit
inventory counts one class pair to `com.taxonomy.catalog.model`, two to
`com.taxonomy.catalog.service`, and two to `com.taxonomy.relations.service`.
The scalar `TaxonomyNode` conversion belongs to `RequirementRelationSearchService`;
compiler-generated references in its anonymous catalogue adapter do not constitute
a second class pair under the existing inventory policy. The former jdeps-based
count of six was incorrect. The baseline now records the five actual pairs and
keeps every unrelated entry and the exact dependency ratchet unchanged.
''';p.write_text(text)
sections={
'en':'''## Requirement-scoped relationship search

This additional phase is opt-in and uses the selected generative provider. These
are server startup properties, not repository-backed Preferences fields. Limits
bound this phase's logical evaluation attempts (contribution extraction,
navigation and verification), not earlier category scoring, physical HTTP retries
or cumulative billed tokens. Exhausted limits preserve completed evidence and
explicitly report unfinished work; they do not certify that no relationship exists.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_ENABLED` | `taxonomy.analysis.relations.hierarchical.enabled` | `false` | Enables requirement-scoped relationship discovery instead of score-only inference. Original requirements and active architecture are not automatically adopted or overwritten. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_CALLS` | `taxonomy.analysis.relations.hierarchical.max-calls` | `24` | Maximum logical evaluation attempts in this phase, 0–10000. Zero is supported and leaves sources explicitly unassessed. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_DEPTH` | `taxonomy.analysis.relations.hierarchical.max-depth` | `8` | Navigation depth limit, 0–100. A depth-limited branch remains unfinished rather than becoming a negative finding. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_BATCH_SIZE` | `taxonomy.analysis.relations.hierarchical.batch-size` | `10` | Offered source or sibling candidates per evaluation, 1–100. It is not a total-run call budget. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_WORK_ITEMS` | `taxonomy.analysis.relations.hierarchical.max-work-items` | `512` | Maximum admitted search work items, 1–100000. Includes navigation and verification work; deferred work is reported. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_SOURCES` | `taxonomy.analysis.relations.hierarchical.max-sources` | `32` | Maximum concrete positively scored source nodes considered for contribution extraction, 1–256. Omitted sources are reported. |

''',
'de':'''## Anforderungsbezogene Beziehungssuche

Diese zusätzliche Phase ist ausdrücklich zuschaltbar und nutzt den ausgewählten
generativen Provider. Die Werte sind Server-Startparameter, keine im Repository
gespeicherten Preferences-Felder. Die Grenzen betreffen logische Prüfversuche
dieser Phase (Beitragsextraktion, Navigation und Verifikation), nicht die vorherige
Kategoriebewertung, physische HTTP-Wiederholungen oder kumulierte Rechnungstokens.
Erreichte Grenzen erhalten fertige Belege und kennzeichnen unerledigte Arbeit;
sie sind kein Nachweis, dass keine Beziehung existiert.

| Variable | Spring-Eigenschaft / Geltungsbereich | Standard | Bedeutung |
|---|---|---|---|
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_ENABLED` | `taxonomy.analysis.relations.hierarchical.enabled` | `false` | Aktiviert anforderungsbezogene Beziehungssuche statt reiner Score-Ableitung. Originalanforderung und aktive Architektur werden nicht automatisch übernommen oder überschrieben. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_CALLS` | `taxonomy.analysis.relations.hierarchical.max-calls` | `24` | Maximale logische Prüfversuche dieser Phase, 0–10000. Null ist zulässig und lässt Quellen ausdrücklich unbewertet. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_DEPTH` | `taxonomy.analysis.relations.hierarchical.max-depth` | `8` | Maximale Navigationstiefe, 0–100. Ein dadurch begrenzter Zweig bleibt unerledigt statt als irrelevant zu gelten. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_BATCH_SIZE` | `taxonomy.analysis.relations.hierarchical.batch-size` | `10` | Angebotene Quell- oder Geschwisterkandidaten pro Prüfung, 1–100. Kein Aufrufbudget für den gesamten Lauf. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_WORK_ITEMS` | `taxonomy.analysis.relations.hierarchical.max-work-items` | `512` | Maximale zugelassene Suchaufgaben, 1–100000. Umfasst Navigation und Verifikation; zurückgestellte Arbeit wird ausgewiesen. |
| `TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_MAX_SOURCES` | `taxonomy.analysis.relations.hierarchical.max-sources` | `32` | Maximale konkrete positiv bewertete Quellknoten für die Beitragsextraktion, 1–256. Ausgelassene Quellen werden gemeldet. |

'''}
for language,section in sections.items():
 p=Path(f'docs/{language}/CONFIGURATION_REFERENCE.md');t=p.read_text();assert 'TAXONOMY_ANALYSIS_RELATIONS_HIERARCHICAL_ENABLED' not in t
 idx=t.index('\n## ',t.index('TAXONOMY_ANALYSIS_PRODUCT_MIN_SCORE'))+1
 p.write_text(t[:idx]+section+t[idx:])
files=['.github/architecture-dependency-baseline.json','docs/de/CONFIGURATION_REFERENCE.md','docs/en/CONFIGURATION_REFERENCE.md','docs/dev/requirement-relation-downwalk.md']
assert set(subprocess.check_output(['git','diff','--name-only'],text=True).splitlines()) == set(files)
subprocess.run(['git','diff','--check'],check=True)
subprocess.run(['git','add',*files],check=True)
assert subprocess.check_output(['git','write-tree'],text=True).strip() == '9d6ef7223714aa5a5364131fe010a3dfb99d9cfb'
print('Verified four-file repair tree 9d6ef7223714aa5a5364131fe010a3dfb99d9cfb')
