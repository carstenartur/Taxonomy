#!/usr/bin/env bash
set -Eeuo pipefail

only_download=false
offline_mode=false
skip_webkit="${TAXONOMY_PLAYWRIGHT_SKIP_WEBKIT:-false}"
system_packages_only=false
chromium_only=false
browsers_only="${TAXONOMY_PLAYWRIGHT_BROWSERS_ONLY:-false}"
for option in "$@"; do
    case "$option" in
        --only-download) only_download=true ;;
        --offline-mode) offline_mode=true ;;
        --skip-webkit) skip_webkit=true ;;
        --system-packages-only) system_packages_only=true ;;
        --browsers-only) browsers_only=true ;;
        --chromium-only) chromium_only=true; skip_webkit=true; browsers_only=true ;;
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
source "$(dirname "$(realpath "${BASH_SOURCE[0]}")")/apt-packages.sh"

install_system_packages() (
    trap 'status=$?; echo "::error title=UI system packages / Paketinstallation::Package setup failed (exit $status); browser tests have not started." >&2; exit "$status"' ERR
    echo "UI system packages / Paketinstallation"
    if [[ "$offline_mode" != true ]]; then
        prefer_hosted_apt_mirror
    fi
    if [[ "$only_download" == true ]]; then
        prepare_package_cache "$packages_dir"
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
        write_package_checksums "$packages_dir"
    elif [[ "$offline_mode" == true ]]; then
        install_cached_packages "$packages_dir"
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
if [[ "$chromium_only" == true ]]; then
    browsers=(chromium)
fi
if [[ "$skip_webkit" != true ]]; then
    browsers+=(webkit)
fi
timeout --foreground 8m "$playwright_bin" install "${browsers[@]}"
