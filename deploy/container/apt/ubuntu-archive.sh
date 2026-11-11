#!/usr/bin/env bash
set -euo pipefail

# This mirror carries Ubuntu's signed archive, including the security suites.
# Prioritize the independently resolved, signed mirror: failed Canonical DNS
# otherwise consumes the whole bounded package-install budget per index.
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
for service in archive security; do
    printf '%s\tpriority:0\nhttps://%s.ubuntu.com/ubuntu/\tpriority:10\n' \
        "$fallback" "$service" > "$config/$service.mirrors"
done
shopt -s nullglob
touch "$config/sources.list"
for source in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [[ -f "$source" ]] || continue
    sed -E \
        -e "s@https?://archive\\.ubuntu\\.com/ubuntu/?([[:space:]]|$)@mirror+file:$config/archive.mirrors\\1@g" \
        -e "s@https?://security\\.ubuntu\\.com/ubuntu/?([[:space:]]|$)@mirror+file:$config/security.mirrors\\1@g" \
        "$source" > "$config/${source#/etc/apt/}"
done

apt_sources=(-o "Dir::Etc::sourcelist=$config/sources.list" -o "Dir::Etc::sourceparts=$config/sources.list.d")
apt-get "${apt_sources[@]}" -o APT::Update::Error-Mode=any update
apt-get "${apt_sources[@]}" install -y --no-install-recommends "$@"
rm -rf /var/lib/apt/lists/*
