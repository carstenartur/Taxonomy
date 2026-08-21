#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_BRANCH:?TARGET_BRANCH must be set}"

git fetch --no-tags origin main
git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
git merge --no-edit origin/main
INTEGRATION_BASE=$(git rev-parse HEAD)

cat .github/tmp/decision-report-package/part-*.b64 \
  | base64 --decode > /tmp/decision-report-source.tar.gz
echo 'ff99f3d070e4067b27428ed759b713e9041e089d2677809b8822859f64599cd4  /tmp/decision-report-source.tar.gz' \
  | sha256sum --check --strict
mkdir -p /tmp/decision-report-source
tar -xzf /tmp/decision-report-source.tar.gz -C /tmp/decision-report-source
python3 /tmp/decision-report-source/apply_decision_report.py . --allow-different-base

