#!/usr/bin/env bash
# Configure the Docker Hub cache before any runner containers are started.
set -euo pipefail

config=${1:-/etc/docker/daemon.json}
mirror=https://mirror.gcr.io
candidate=$(mktemp)
trap 'rm -f "$candidate"' EXIT

if sudo test -f "$config"; then
  sudo cat "$config"
else
  printf '{}\n'
fi | jq --arg mirror "$mirror" '
  if type != "object" then error("Docker configuration must be an object")
  elif has("registry-mirrors") and (."registry-mirrors" | type != "array")
    then error("registry-mirrors must be an array")
  else ."registry-mirrors" = ([$mirror] + ((."registry-mirrors" // []) | map(select(. != $mirror))))
  end
' > "$candidate"

# Keep the existing configuration intact if Docker rejects the merged settings.
sudo dockerd --validate --config-file "$candidate"
if ! sudo test -f "$config" || ! sudo cmp -s "$candidate" "$config"; then
  sudo install -D -m 0644 "$candidate" "$config"
  sudo systemctl restart docker
fi

# Check the running daemon, not only the file written above.
docker info --format '{{json .RegistryConfig.Mirrors}}' \
  | jq -e --arg mirror "$mirror" 'any(.[]; rtrimstr("/") == $mirror)' >/dev/null
echo "Docker Hub cache is active: $mirror"
