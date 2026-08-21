#!/usr/bin/env bash
set -euo pipefail

# Playwright delegates Linux runtime setup to apt. Keep that operation retryable
# but bounded so a mutable package mirror cannot consume the complete CI job.
sudo tee /etc/apt/apt.conf.d/99taxonomy-playwright >/dev/null <<'EOF'
Acquire::Retries "4";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
DPkg::Lock::Timeout "120";
EOF

playwright_bin="./node_modules/.bin/playwright"
if [[ ! -x "${playwright_bin}" ]]; then
    echo "Pinned Playwright executable is missing; npm ci must run first." >&2
    exit 1
fi

# The hosted runner already launches Chromium and Firefox, while the acceptance
# evidence proves that WebKit still needs GTK/GStreamer and related libraries.
timeout --foreground 20m "${playwright_bin}" install --with-deps webkit

# Browser binaries remain pinned by package-lock.json. Avoid repeating OS package
# setup for Chromium and Firefox after the targeted WebKit dependency install.
timeout --foreground 20m "${playwright_bin}" install chromium firefox
