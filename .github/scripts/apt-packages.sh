#!/usr/bin/env bash
# Shared native-package policy for the Playwright and document installers.
# Source this file from a caller using set -Eeuo pipefail.
apt_options=(-o Acquire::Retries=4 -o Acquire::http::Timeout=30
    -o Acquire::https::Timeout=30 -o DPkg::Lock::Timeout=120)

prefer_hosted_apt_mirror() {
    # A slow Azure mirror can keep making progress without hitting apt's data
    # timeout. Preserve signed metadata and fallback entries, but prefer the
    # existing official HTTPS archive on disposable hosted runners.
    if [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]; then
        local mirrors="${TAXONOMY_APT_MIRROR_LIST:-/etc/apt/apt-mirrors.txt}"
        if [[ -f "$mirrors" ]]; then
            sudo sed -i -E \
                '/^https:\/\/archive\.ubuntu\.com\/ubuntu\/?[[:space:]]/s/([[:space:]])priority:[0-9]+/\1priority:0/' \
                "$mirrors"
        fi
    fi
}

prepare_package_cache() {
    mkdir -p "$1"
    rm -f "$1"/*.deb "$1/SHA256SUMS"
}

write_package_checksums() (
    cd "$1"
    shopt -s nullglob
    archives=(*.deb)
    : > SHA256SUMS
    if [[ ${#archives[@]} -gt 0 ]]; then
        sha256sum -- "${archives[@]}" > SHA256SUMS
    fi
)

install_cached_packages() (
    packages_dir="$1"
    shift
    cd "$packages_dir"
    if [[ ! -f SHA256SUMS || -L SHA256SUMS ]]; then
        echo "System package cache is missing; offline installation cannot continue." >&2
        exit 1
    fi
    # Reject any unlisted, modified or symlinked archive before invoking apt.
    # The manifest and glob order are the same (sorted by filename).
    shopt -s nullglob
    archives=(*.deb)
    mapfile -t listed_archives < <(
        sed -n -E 's/^[[:xdigit:]]{64}  ([^/]+\.deb)$/\1/p' SHA256SUMS
    )
    if [[ ${#archives[@]} -ne ${#listed_archives[@]} ]]; then
        echo "Unexpected or unlisted system package archives in the cache." >&2
        exit 1
    fi
    verified_archives=()
    for index in "${!archives[@]}"; do
        archive="${archives[index]}"
        if [[ "$archive" != "${listed_archives[index]}" || ! -f "$archive" || -L "$archive" ]]; then
            echo "Unexpected, unverified or symlinked archive: $archive" >&2
            exit 1
        fi
        verified_archives+=("$packages_dir/$archive")
    done
    if [[ ${#verified_archives[@]} -gt 0 ]]; then
        sha256sum --check --strict SHA256SUMS
    fi
    if [[ ${#verified_archives[@]} -gt 0 || $# -gt 0 ]]; then
        sudo apt-get "${apt_options[@]}" install --no-download -y --no-install-recommends \
            -o "Dir::Cache::archives=$packages_dir" "$@" "${verified_archives[@]}"
    fi
)
