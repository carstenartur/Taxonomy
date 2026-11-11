#!/usr/bin/env bash

# Probe the signed release once per candidate. Package downloads then use just
# the chosen origin, instead of retrying an unreachable host for every index.
select_ubuntu_archive() {
    local directory=$1 suite=$2 keyring=$3
    shift 3
    local uri probe
    local -a releases
    for uri; do
        probe=$(mktemp -d "$directory/probe-$suite.XXXXXXXX") || return 1
        chmod 755 "$probe" || return 1
        mkdir -p "$probe/lists/partial" || return 1
        cat > "$probe/release.sources" <<EOF
Types: deb
URIs: $uri
Suites: $suite
Components: main
Targets: InRelease
Signed-By: $keyring
EOF
        # An isolated list directory precludes a cached success. APT checks
        # the Ubuntu signature and validity period before storing InRelease.
        if timeout --kill-after=2s 12s apt-get -qq \
            -o "Dir::Etc::sourcelist=$probe/release.sources" -o Dir::Etc::sourceparts=- \
            -o "Dir::State::lists=$probe/lists" -o Dir::Cache::pkgcache= -o Dir::Cache::srcpkgcache= \
            -o Acquire::Retries=0 -o Acquire::https::Timeout=6 -o APT::Update::Error-Mode=any update >&2; then
            releases=("$probe"/lists/*_InRelease)
            if ((${#releases[@]} == 1)) && [[ -f ${releases[0]} ]] \
                && grep -Fxq 'Origin: Ubuntu' "${releases[0]}" && grep -Fxq "Suite: $suite" "${releases[0]}"; then
                printf '%s\n' "$uri"
                return 0
            fi
        fi
    done
    printf 'No candidate served a verified Ubuntu release for %s\n' "$suite" >&2
    return 1
}

configure_ubuntu_archives() {
    local directory=$1 codename=$2 keyring=$3 fallback=$4 service suite pattern selected source
    local -a sources=("$directory/sources.list")
    for source in "$directory"/sources.list.d/*.list "$directory"/sources.list.d/*.sources; do
        if [[ -f "$source" ]]; then sources+=("$source"); fi
    done
    for service in archive security; do
        pattern="https?://$service\\.ubuntu\\.com/ubuntu/?([[:space:]]|$)"
        if awk -v pattern="$pattern" '/^[[:space:]]*(deb(-src)?[[:space:]]|URIs:[[:space:]])/ && $0 ~ pattern { seen=1 } END { exit !seen }' "${sources[@]}"; then
            suite=${codename:?Ubuntu image must identify its suite}
            if [[ $service == security ]]; then suite+=-security; fi
            selected=$(select_ubuntu_archive "$directory" "$suite" "$keyring" \
                "https://$service.ubuntu.com/ubuntu/" "$fallback") || return 1
            sed -Ei "/^[[:space:]]*(deb(-src)?[[:space:]]|URIs:[[:space:]])/s@$pattern@$selected\\1@g" "${sources[@]}"
        fi
    done
}

if [[ ${BASH_SOURCE[0]} != "$0" ]]; then return; fi
set -euo pipefail

# This mirror also carries the signed Ubuntu security suites.
fallback='https://mirrors.kernel.org/ubuntu/'

if [[ ${1:-} == --hosted-list ]]; then
    mirrors=$2
    [[ -f "$mirrors" ]] || exit 0
    if grep -Eq '^https://archive\.ubuntu\.com/ubuntu/?[[:space:]]' "$mirrors"; then
        sed -i -E '/^https:\/\/archive\.ubuntu\.com\/ubuntu\/?[[:space:]]/s/([[:space:]])priority:[0-9]+/\1priority:10/' "$mirrors"
        sed -i -E '/^https:\/\/mirrors\.kernel\.org\/ubuntu\/?[[:space:]]/s/([[:space:]])priority:[0-9]+/\1priority:0/' "$mirrors"
        if ! awk -v url="$fallback" '$1 == url { found = 1 } END { exit !found }' "$mirrors"; then
            # The runner's mirror list is newline-delimited.
            if [[ -n $(tail -c 1 "$mirrors") ]]; then printf '\n' >> "$mirrors"; fi
            printf '%s\tpriority:0\n' "$fallback" >> "$mirrors"
        fi
    fi
    exit 0
fi

# Keep the image's suites, components, architectures and Signed-By options.
# Only these apt invocations use the temporary list. Other package sources,
# including the Ubuntu ports archive on ARM, retain their original URIs.
config=$(mktemp -d)
trap 'rm -rf "$config"' EXIT
chmod 755 "$config"
mkdir "$config/sources.list.d"
shopt -s nullglob
touch "$config/sources.list"
for source in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [[ -f "$source" ]] || continue
    cp "$source" "$config/${source#/etc/apt/}"
done
source /etc/os-release
configure_ubuntu_archives "$config" "${VERSION_CODENAME:-}" \
    /usr/share/keyrings/ubuntu-archive-keyring.gpg "$fallback"

apt_sources=(-o "Dir::Etc::sourcelist=$config/sources.list" -o "Dir::Etc::sourceparts=$config/sources.list.d")
apt-get "${apt_sources[@]}" -o APT::Update::Error-Mode=any update
apt-get "${apt_sources[@]}" install -y --no-install-recommends "$@"
rm -rf /var/lib/apt/lists/*
