"""Correct only the three accessor calls in the already reproduced recovery patch."""
from pathlib import Path

source = Path(__file__).with_name('628-recovery-patch.py').read_text()
assert source.count('context.branch()') == 3
source = source.replace('context.branch()', 'context.currentBranch()')
exec(compile(source, '628-recovery-patch.py', 'exec'))
