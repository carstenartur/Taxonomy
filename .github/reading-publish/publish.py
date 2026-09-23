"""One-time publication of an exact verified source delta; never writes main."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

BASE_TREE = '3601e99ddc37b8d5fe60436fad4046ad83153b71'
RESULT_TREE = 'd7def2805e7482cc6828ec3229067d915ccb5f1d'
DESTINATION = 'refs/heads/staging/reading-source-20260923a'

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def run(*args):
    subprocess.run(args, check=True)

payload = Path(__file__).resolve().parent
patch = b''.join(path.read_bytes() for path in sorted(payload.glob('part-*.patch')))
expected = (payload / 'sha256.txt').read_text().strip()
assert hashlib.sha256(patch).hexdigest() == expected, 'Patch checksum mismatch'
base = os.environ['BASE_REF']
assert git('rev-parse', base + '^{tree}') == BASE_TREE, 'Base tree mismatch'
# Refuse to replace any existing branch. The user-facing branch is created separately.
assert not git('ls-remote', '--heads', 'origin', DESTINATION), 'Destination already exists'
with tempfile.NamedTemporaryFile(suffix='.patch') as source:
    source.write(patch)
    source.flush()
    run('git', 'checkout', '--detach', base)
    run('git', 'apply', '--check', '--unidiff-zero', source.name)
    run('git', 'apply', '--unidiff-zero', source.name)
run('git', 'add', '--all')
run('git', 'diff', '--cached', '--check')
assert git('write-tree') == RESULT_TREE, 'Unexpected source changes'
for path in git('diff', '--cached', '--name-only').splitlines():
    if path.endswith(('.js', '.mjs', '.cjs')):
        run('node', '--check', path)
run('node', '--test', '.github/scripts/analysis-llm-log.test.mjs',
    '.github/scripts/sunburst-zero-relevance.test.mjs', '.github/scripts/taxonomy-state.test.mjs',
    '.github/scripts/analysis-session-draft.test.mjs', '.github/scripts/analysis-session-draft-serialization.test.mjs',
    '.github/scripts/product-score-streaming.test.mjs', '.github/scripts/analysis-live-progress.test.mjs')
run('git', '-c', 'user.name=github-actions[bot]', '-c',
    'user.email=41898282+github-actions[bot]@users.noreply.github.com',
    'commit', '-m', 'fix(ui): retain analysis duration and reading context; clarify decision report contents')
assert git('rev-parse', 'HEAD^{tree}') == RESULT_TREE
run('git', 'push', 'origin', 'HEAD:' + DESTINATION)
print('Published source commit:', git('rev-parse', 'HEAD'))
print('Published source tree:', RESULT_TREE)
