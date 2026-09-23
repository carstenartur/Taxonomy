from pathlib import Path
import base64, gzip, hashlib, subprocess

BASE = '1bb12dbb3ef5a6708ad6558b27e7d1460aff270e'
TREE = 'e64fddf13fadc4d6fca64a0750dfc6a923e19d15'
FILES = [
    '.github/scripts/product-score-streaming.test.mjs',
    'docs/dev/shared-child-assessment.md',
    'taxonomy-analysis/src/main/java/com/taxonomy/analysis/relations/RelationSearchEngine.java',
    'taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmResponseParser.java',
    'taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/relations/RelationSearchContract.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/CategoryAssessmentContractTest.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/LlmDiagnosticChecks.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/LlmResponseParserTest.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/LlmServiceBranchCoverageTest.java',
    'taxonomy-analysis/src/test/java/com/taxonomy/analysis/service/SharedChildAssessmentParserTest.java',
    'taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js',
]

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

assert git('rev-parse', 'HEAD') == BASE
assert git('rev-parse', 'HEAD^{tree}') == '657ae72887e39bd7eb686975641bdb9c354fc3e4'
# Correct one independently reproduced transport-encoding insertion. The decoded
# patch must match the exact locally tested bytes before git can consume it.
encoded = base64.b64encode(Path('/tmp/category-increment.payload').read_bytes()).decode()
assert encoded.count('WHeWZZklYE') == 1
encoded = encoded.replace('WHeWZZklYE', 'WHeWZklYE')
encoded += '=' * (-len(encoded) % 4)
patch = gzip.decompress(base64.b64decode(encoded))
assert hashlib.sha256(patch).hexdigest() == '478dff7c74d93a5ba645e2a5f16931a05cc01ca199478eb2953ddefc9f297256'
subprocess.run(['git', 'apply', '--check', '-'], input=patch, check=True)
subprocess.run(['git', 'apply', '--index', '-'], input=patch, check=True)
assert git('diff', '--cached', '--name-only').splitlines() == FILES
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
assert git('write-tree') == TREE
print('EXACT_PRODUCT_TREE=' + TREE)
