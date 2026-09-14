"""Disposable integration corrections, applied before verification; not shipped."""
from pathlib import Path
import json, re

app = Path('taxonomy-app/pom.xml')
module = Path('taxonomy-interop/pom.xml')
block = '''        <!-- Validate DNS at connection establishment for scoped OSLC transport. -->
        <dependency>
            <groupId>org.apache.httpcomponents.client5</groupId>
            <artifactId>httpclient5</artifactId>
        </dependency>
'''
assert app.read_text().count(block) == 1
assert 'httpclient5' not in module.read_text()
app.write_text(app.read_text().replace(block, '', 1))
module.write_text(module.read_text().replace('    <dependencies>\n', '    <dependencies>\n' + block, 1))
for p in Path('taxonomy-app/src/main/java').rglob('*.java'):
    assert not re.search(r'^import org\.apache\.hc\.', p.read_text(), re.M), p

for lang in ('en', 'de'):
    p = Path('docs') / lang / 'MODULE_BOUNDARIES.md'
    s = p.read_text()
    if lang == 'en':
        s = s.replace('Its thirteen selected test classes', 'Its fourteen selected test classes')
        old = '''5. `taxonomy-knowledge` and `taxonomy-interop` — pending; stabilize their owned APIs and remove blocking implementation dependencies.
6. `taxonomy-architecture`, `taxonomy-analysis` and `taxonomy-portfolio` — pending; resolve their remaining cycles before each extraction.
7. Reassess `provenance` and `preferences` after those boundaries are stable.'''
        new = '''5. `taxonomy-interop` — physically extracted through a scoped portfolio port in this revision.
6. `taxonomy-knowledge` — pending; stabilize its owned APIs and remove blocking implementation dependencies.
7. `taxonomy-architecture`, `taxonomy-analysis` and `taxonomy-portfolio` — pending; resolve their remaining cycles before each extraction.
8. Reassess `provenance` and `preferences` after those boundaries are stable.'''
        s = s.replace('With templates extracted below, five planned feature modules remain to be extracted.',
                      'With templates and interoperability extracted below, four planned feature modules remain to be extracted.')
    else:
        s = s.replace('Die dreizehn ausgewählten Testklassen', 'Die vierzehn ausgewählten Testklassen')
        old = '''5. `taxonomy-knowledge` und `taxonomy-interop` — offen; eigene APIs stabilisieren und blockierende Implementierungsabhängigkeiten entfernen.
6. `taxonomy-architecture`, `taxonomy-analysis` und `taxonomy-portfolio` — offen; verbleibende Zyklen vor der jeweiligen Auslagerung auflösen.
7. `provenance` und `preferences` nach Stabilisierung dieser Grenzen erneut bewerten.'''
        new = '''5. `taxonomy-interop` — in dieser Revision über einen gescopten Portfolio-Port physisch ausgelagert.
6. `taxonomy-knowledge` — offen; eigene APIs stabilisieren und blockierende Implementierungsabhängigkeiten entfernen.
7. `taxonomy-architecture`, `taxonomy-analysis` und `taxonomy-portfolio` — offen; verbleibende Zyklen vor der jeweiligen Auslagerung auflösen.
8. `provenance` und `preferences` nach Stabilisierung dieser Grenzen erneut bewerten.'''
        s = s.replace('Nach der folgenden Templates-Auslagerung sind noch fünf geplante Fachmodule auszulagern.',
                      'Nach der folgenden Templates- und Interoperabilitätsauslagerung sind noch vier geplante Fachmodule auszulagern.')
    assert s.count(old) == 1, (lang, 'migration list')
    p.write_text(s.replace(old, new, 1))

# Source discovery must not treat a test fixture as an application source change.
assert len(json.loads(Path('.mvn/verification-suites.json').read_text())['profiles']['architecture-tests']['test'].split(',')) == 14
print('INTEGRATION: HTTP dependency moved without a version change; module counts and migration status aligned')
