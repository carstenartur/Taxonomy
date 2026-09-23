"""Reconstruct the locally tested tree; temporary verification branch only."""
import base64
import bz2
import hashlib
from pathlib import Path
import subprocess

BASE = '409562e3eb739569aac3c56619207acd45bd09a8'
TREE = 'cd9be4de356bbe280685988134e6d373d5329082'
expected = ['64caff2334795cf21afb135652c5567f7bd8be4c', '277cb2f8bc596419cad3bc2c2ad80b6831e635b7', '0970d085f2006de38a5d60fa8f86abeed43664a7', '18ebfd773c37e387d7d0e6aca5d1f0ad5e2ca4fa']
parts = []
for index, sha in enumerate(expected, 1):
    part = Path(f'.verification-publication/part{index}.b64').read_bytes()
    if index == 3:
        # Undo the identified two-character transport transcription; verify original bytes below.
        part = part.replace(b'/HaHyUiQiQhIk', b'/HaHyUiQhIk')
    assert hashlib.sha1(f'blob {len(part)}\0'.encode() + part).hexdigest() == sha, index
    parts.append(part)
patch = bz2.decompress(base64.b64decode(b''.join(parts), validate=True))
assert hashlib.sha256(patch).hexdigest() == '3ccd63dad9ff6762e4ef74019a302367b6bf2ec92e3ed4aebfed15ec641aa563'
Path('/tmp/relation-downwalk.patch').write_bytes(patch)
subprocess.run(['git', 'switch', '--detach', BASE], check=True)
subprocess.run(['git', 'apply', '--check', '/tmp/relation-downwalk.patch'], check=True)
subprocess.run(['git', 'apply', '--index', '/tmp/relation-downwalk.patch'], check=True)
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
actual = subprocess.check_output(['git', 'write-tree'], text=True).strip()
assert actual == TREE, actual
print('Verified exact product tree:', actual)
