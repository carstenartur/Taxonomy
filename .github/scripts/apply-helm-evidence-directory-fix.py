#!/usr/bin/env python3
"""Ensure fixed Helm evidence paths exist independently of OUTPUT_FILE."""

from pathlib import Path

path = Path('deploy/helm/taxonomy/verify.sh')
text = path.read_text(encoding='utf-8')
old = 'mkdir -p "$(dirname "${OUTPUT_FILE}")"\n'
new = 'mkdir -p "$(dirname "${OUTPUT_FILE}")" "${ROOT_DIR}/target"\n'
if text.count(old) != 1:
    raise SystemExit(f'Expected one output directory creation, found {text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Ensured the repository evidence directory exists for custom output paths.')
