#!/usr/bin/env bash
set -Eeuo pipefail

mode="${1:-}"
variant="${2:-writer}"
if [[ $# -gt 2 || ( "$mode" != --only-download && "$mode" != --offline-mode ) ||
      ( "$variant" != writer && "$variant" != writer-draw ) ]]; then
    echo "Usage: $0 {--only-download|--offline-mode} [writer|writer-draw]" >&2
    exit 2
fi
source "$(dirname "$(realpath "${BASH_SOURCE[0]}")")/apt-packages.sh"
packages_dir="$(realpath -m "${TAXONOMY_DOCUMENT_PACKAGES_DIR:-.cache/document-system-packages}")"
packages=(libreoffice-writer poppler-utils)
if [[ "$variant" == writer-draw ]]; then
    packages+=(libreoffice-draw)
fi
trap 'status=$?; echo "::error title=Document rendering packages::Package setup failed (exit $status); document verification has not started." >&2; exit "$status"' ERR

if [[ "$mode" == --only-download ]]; then
    prefer_hosted_apt_mirror
    prepare_package_cache "$packages_dir"
    sudo apt-get "${apt_options[@]}" update
    sudo apt-get "${apt_options[@]}" install --download-only -y --no-install-recommends \
        -o "Dir::Cache::archives=$packages_dir" "${packages[@]}"
    write_package_checksums "$packages_dir"
else
    install_cached_packages "$packages_dir" "${packages[@]}"
    soffice --version
    pdftoppm -v
fi
