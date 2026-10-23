#!/usr/bin/env bash
set -euo pipefail

only_download=false
offline_mode=false
skip_webkit="${TAXONOMY_PLAYWRIGHT_SKIP_WEBKIT:-false}"
system_packages_only=false
browsers_only="${TAXONOMY_PLAYWRIGHT_BROWSERS_ONLY:-false}"
for option in "$@"; do
    case "$option" in
        --only-download) only_download=true ;;
        --offline-mode) offline_mode=true ;;
        --skip-webkit) skip_webkit=true ;;
        --system-packages-only) system_packages_only=true ;;
        --browsers-only) browsers_only=true ;;
        *) echo "Unknown option: $option" >&2; exit 2 ;;
    esac
done
if [[ "$only_download" == true && ( "$offline_mode" == true || "$browsers_only" == true || "$skip_webkit" == true ) ]] ||
   [[ "$browsers_only" == true && ( "$offline_mode" == true || "$system_packages_only" == true ) ]]; then
    echo "Incompatible Playwright installation options." >&2
    exit 2
fi

playwright_bin="./node_modules/.bin/playwright"
if [[ ! -x "${playwright_bin}" ]]; then
    echo "Pinned Playwright executable is missing; npm ci must run first." >&2
    exit 1
fi

packages_dir="$(realpath -m "${TAXONOMY_PLAYWRIGHT_PACKAGES_DIR:-../.cache/playwright-system-packages}")"
apt_options=(-o Acquire::Retries=4 -o Acquire::http::Timeout=30
    -o Acquire::https::Timeout=30 -o DPkg::Lock::Timeout=120)

install_system_packages() (
    trap 'status=$?; echo "::error title=UI system packages / Paketinstallation::Package setup failed (exit $status); browser tests have not started." >&2; exit "$status"' ERR
    echo "UI system packages / Paketinstallation"
    # A slow Azure mirror can make progress without hitting apt's data timeout.
    # Prefer the existing official HTTPS archive on disposable hosted runners;
    # preserve all fallback entries, signed metadata and offline installation.
    if [[ "$offline_mode" != true && "${GITHUB_ACTIONS:-}" == true &&
          "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]; then
        mirrors="${TAXONOMY_APT_MIRROR_LIST:-/etc/apt/apt-mirrors.txt}"
        if [[ -f "$mirrors" ]]; then
            sudo sed -i -E \
                '/^https:\/\/archive\.ubuntu\.com\/ubuntu\/?[[:space:]]/s/([[:space:]])priority:[0-9]+/\1priority:0/' \
                "$mirrors"
        fi
    fi
    if [[ "$only_download" == true ]]; then
        mkdir -p "$packages_dir"
        rm -f "$packages_dir"/*.deb "$packages_dir/SHA256SUMS"
        sudo apt-get "${apt_options[@]}" update

        # The pinned Playwright CLI simulates apt's complete dependency closure.
        # Exit 1 means packages are missing, not that the simulation failed.
        status=0
        report=$("$playwright_bin" install-deps --dry-run webkit) || status=$?
        printf '%s\n' "$report"
        mapfile -t packages < <(printf '%s\n' "$report" | sed -n 's/^  \([a-z0-9][a-z0-9+.:-]*\)$/\1/p')
        if [[ "$status" == 1 && ${#packages[@]} -gt 0 &&
              "$report" == *"Missing system dependencies (${#packages[@]}):"* ]]; then
            sudo apt-get "${apt_options[@]}" install --download-only -y --no-install-recommends \
                -o "Dir::Cache::archives=$packages_dir" "${packages[@]}"
        elif [[ "$status" != 0 || "$report" != "All system dependencies are installed." ]]; then
            echo "Unable to resolve the pinned Playwright system packages." >&2
            exit 1
        fi
        cd "$packages_dir"
        shopt -s nullglob
        archives=(*.deb)
        if [[ ${#packages[@]} -gt 0 && ${#archives[@]} == 0 ]]; then
            echo "apt did not retain the downloaded system packages." >&2
            exit 1
        fi
        : > SHA256SUMS
        if [[ ${#archives[@]} -gt 0 ]]; then
            sha256sum -- "${archives[@]}" > SHA256SUMS
        fi
    elif [[ "$offline_mode" == true ]]; then
        cd "$packages_dir"
        if [[ ! -f SHA256SUMS ]]; then
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
            if [[ "$archive" != "${listed_archives[index]}" ||
                  ! -f "$archive" || -L "$archive" ]]; then
                echo "Unexpected, unverified or symlinked archive: $archive" >&2
                exit 1
            fi
            verified_archives+=("$packages_dir/$archive")
        done
        if [[ ${#verified_archives[@]} -gt 0 ]]; then
            sha256sum --check --strict SHA256SUMS
            sudo apt-get "${apt_options[@]}" install --no-download -y --no-install-recommends \
                -o "Dir::Cache::archives=$packages_dir" "${verified_archives[@]}"
        fi
    else
        timeout --foreground 8m "$playwright_bin" install-deps webkit
    fi
)

# Hosted runners already support Chromium/Firefox. Only WebKit needs extra apt
# packages. CI prepares those once, installs offline, then lets Maven fetch binaries.
if [[ "$browsers_only" != true && "$skip_webkit" != true ]]; then
    install_system_packages
fi
if [[ "$only_download" == true || "$system_packages_only" == true ]]; then
    exit 0
fi

browsers=(chromium firefox)
if [[ "$skip_webkit" != true ]]; then
    browsers+=(webkit)
fi
timeout --foreground 8m "$playwright_bin" install "${browsers[@]}"
