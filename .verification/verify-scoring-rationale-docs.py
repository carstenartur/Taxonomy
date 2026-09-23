"""Prepare and validate documentation only; do not change runtime code or branch refs."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

BASE = 'e73dcfe43964b68aaa6bc7d967471fe209da9a8a'
BASE_TREE = '0a678cc971d3615f4fa6ca183d1b6d94e68528d8'
ROOT = Path.cwd().resolve()
CODES = ('BP', 'BR', 'CP', 'CI', 'CO', 'CR', 'IP', 'UA')

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def replace_once(path, old, new):
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise AssertionError(f'Expected one exact editing anchor in {path}: {old[:80]}')
    file.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once('README.md', '## AI and local operation\n',
    '## AI and local operation\n\n'
    'For the actual grouping criteria, score calculations, limitations and rationale for each of the eight sub-taxonomies, see '
    '[Grouping and scoring](docs/en/TAXONOMY_SCORING.md) ([Deutsch](docs/de/TAXONOMY_SCORING.md)). '
    'It distinguishes implemented arithmetic from the target faceted navigation and necessity model.\n')
replace_once('docs/dev/hierarchy-context-and-navigation.md',
    '# Source hierarchy, classification and navigation\n',
    '# Source hierarchy, classification and navigation\n\n'
    'User-facing rationale and per-taxonomy pages: [English](../en/TAXONOMY_SCORING.md) / '
    '[Deutsch](../de/TAXONOMY_SCORING.md). These state the exact title-token grouping rules, current scoring transformations, '
    'singleton-root limitation and the still-unimplemented faceted target.\n')
replace_once('docs/dev/INFORMATION_PRODUCT_OVERLAY.md', '# Information Product catalogue overlay\n',
    '# Information Product catalogue overlay\n\n'
    '> **Interpretation update (23 September 2026):** This page records the existing overlay and scoring implementation, '
    'not semantic approval of its inferred parent assignments. See the [IP rationale](../en/taxonomies/IP.md) '
    '([Deutsch](../de/taxonomies/IP.md)) and [shared scoring contract](../en/TAXONOMY_SCORING.md). '
    'Title-only navigation proposals are not a completed semantic hierarchy; independent suitability and parent weighting '
    'do not establish conditional probabilities or product necessity.\n')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md', '# Analysis score semantics\n',
    '# Analysis score semantics\n\n'
    '> **Current-version arithmetic, not validated necessity or probability semantics.** '
    'Read [Grouping and scoring](../en/TAXONOMY_SCORING.md) ([Deutsch](../de/TAXONOMY_SCORING.md)) '
    'for source-pinned behavior and the separate target design. Category allocation already changes provider values; '
    'product thresholding can already turn a positive reply into zero. The regular singleton-root category call also '
    'normalizes a positive root value to 100. These limitations are not fixed by adding typed score metadata.\n')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    '| `ROOT_RELEVANCE` | Independent relevance of one taxonomy root | Comparable as root-level relevance; root values do not need to sum to 100 |',
    '| `ROOT_RELEVANCE` | Intended independent root relevance; ordinary singleton-root parsing currently normalizes positive values to 100 | Roots do not share a sum budget; do not mistake this implementation limit for preserved model relevance |')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    '| `HIERARCHICAL_RELEVANCE` | Absolute relevance carried through a parent budget | Comparable with other effective relevance values |',
    '| `HIERARCHICAL_RELEVANCE` | Category weight after parent-budget allocation | Used by generic ranking; not independently calibrated necessity or fulfilment |')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    '| `PRODUCT_SUITABILITY` | Independent suitability of one concrete `PRODUCT` conditional on its direct product family | Evidence only; never use directly as a hierarchy share, architecture anchor or relation score |',
    '| `PRODUCT_SUITABILITY` | Independent suitability of one concrete `PRODUCT` against the requirement, after thresholding | Not defined by the prompt as a conditional probability; do not reinterpret it as a hierarchy share or proof of a relationship |')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    'Generic downstream consumers need one comparable value. Taxonomy therefore retains raw product\nsuitability and derives effective relevance deterministically:',
    'Version 1 supplies generic downstream ranking with a deterministic weighting heuristic. It retains\nproduct suitability after thresholding and derives a separate value. The formula remains implemented,\nbut has not been justified as conditional-probability or necessity arithmetic:')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    '- `rawScores`: canonical provider evidence before product-relevance weighting; malformed legacy',
    '- `rawScores`: analysis-path values before additional product-relevance weighting; category values\n  may already be normalized and product values thresholded (consult the full LLM reply for original values); malformed legacy')
replace_once('docs/dev/ANALYSIS_SCORE_SEMANTICS.md',
    'Every implementation path must preserve this invariant:',
    'The current version-1 weighting paths preserve the following arithmetic. This is not a requirement\nto retain the same formula when the coordinated scoring semantics are redesigned:')
replace_once('docs/de/TAXONOMY_SCORING.md',
    'Fehlt der bewertete direkte Elternwert, wird der effektive Wert null und eine Warnung erzeugt.',
    'Fehlt der bewertete direkte Elternwert, wird der effektive Wert `0` und eine Warnung erzeugt.')

new_docs = []
for language in ('de', 'en'):
    overview = Path(f'docs/{language}/TAXONOMY_SCORING.md')
    new_docs.append(overview)
    contents = overview.read_text(encoding='utf-8')
    assert BASE in contents, f'Missing source scope in {overview}'
    for code in CODES:
        page = Path(f'docs/{language}/taxonomies/{code}.md')
        new_docs.append(page)
        assert f'(taxonomies/{code}.md)' in contents
        body = page.read_text(encoding='utf-8')
        assert body.startswith(f'# {code} ')
        assert len(body.split()) >= 120, f'Insufficient rationale in {page}'
        assert '../TAXONOMY_SCORING.md' in body
        assert f'prompts/{code}.txt' in body
        assert ('## Zielbild' if language == 'de' else '## Target design') in body
    ip = Path(f'docs/{language}/taxonomies/IP.md').read_text(encoding='utf-8')
    for literal in ('`hazard`', '`hazards`', '`warning`', '`warnings`', '`report`', '`reports`', '`plan`', '`plans`', '`request`', '`requests`', '`order`', '`orders`', '26', '222', '61', '48', '50', 'affectsScores=false', 'inheritsSemantics=false', 'createsArchitectureElement=false'):
        assert literal in ip, (language, literal)

checked_links = 0
for path in new_docs:
    text = path.read_text(encoding='utf-8')
    assert sum(line.startswith('```') for line in text.splitlines()) % 2 == 0, path
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\s]+)\)', text):
        if '://' in target or target.startswith('#'):
            continue
        destination = (path.parent / target.split('#')[0]).resolve()
        assert destination.is_relative_to(ROOT), (path, target)
        assert destination.exists(), (path, target)
        checked_links += 1

# Documentation examples only, not claims of executing the application parser.
assert 60 * 80 / 120 == 40 and 60 * 40 / 120 == 20
assert round(40 * 80 / 100) == 32
assert [value if value >= 50 else 0 for value in (49, 80, 90)] == [0, 80, 90]
assert 100 * 20 / 20 == 100

# Existing formulas and their actual call paths were inspected; keep all runtime bytes unchanged.
for path in ('.verification/verify-scoring-rationale-docs.py', '.github/workflows/verify-scoring-rationale-docs.yml'):
    subprocess.run(['git', 'rm', '-f', path], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
changed = git('diff', '--cached', '--name-only', BASE).splitlines()
expected = sorted([str(p) for p in new_docs] + ['README.md', 'docs/dev/ANALYSIS_SCORE_SEMANTICS.md', 'docs/dev/INFORMATION_PRODUCT_OVERLAY.md', 'docs/dev/hierarchy-context-and-navigation.md'])
assert changed == expected, (changed, expected)
assert len(changed) == 22 and all(p.endswith('.md') for p in changed)
local_tree = git('write-tree')
proof = {'scope': 'documentation only; no application tests or semantic approvals claimed', 'base': BASE, 'documentation_files': len(changed), 'new_pages': len(new_docs), 'resolved_relative_links': checked_links, 'product_tree': local_tree, 'files': {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in changed}}
Path('/tmp/scoring-docs-proof.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in proof.items() if k != 'files'}, indent=2))

def post(kind, body):
    request = urllib.request.Request('https://api.github.com/repos/carstenartur/Taxonomy/git/' + kind,
        data=json.dumps(body).encode(), method='POST', headers={
            'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

tree = post('trees', {'base_tree': BASE_TREE, 'tree': [{'path': p, 'mode': '100644', 'type': 'blob', 'content': Path(p).read_text(encoding='utf-8')} for p in changed]})
assert tree['sha'] == local_tree, (tree['sha'], local_tree)
commit = post('commits', {'tree': tree['sha'], 'parents': [BASE], 'message': 'docs(taxonomy): explain grouping, score arithmetic and facets for every sub-taxonomy\n\nDocument literal title-token proposals, distinguish shared category weights from IP suitability and necessity, expose singleton-root normalization and existing threshold/failure limits. Add German and English rationale pages without changing runtime code or catalogue data. Refs #1111.'})
print('PREPARED_COMMIT=' + commit['sha'])
print('PREPARED_TREE=' + tree['sha'])
