#!/usr/bin/env python3
"""Add the small Helm profile verification and Rancher documentation for #625."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / "deploy/helm/taxonomy/verify.sh"
RANCHER = ROOT / "deploy/helm/taxonomy/RANCHER.md"
HARDENING = ROOT / ".github/scripts/check-delivery-hardening.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_verify(source: str) -> str:
    marker = """RANCHER_OUTPUT=\"${TMP_DIR}/rancher.yaml\"
"""
    block = r'''SMALL_OUTPUT="${TMP_DIR}/small.yaml"
SMALL_EVIDENCE="${ROOT_DIR}/target/taxonomy-helm-small-rendered.yaml"
helm lint "${CHART_DIR}" \
  --values "${CHART_DIR}/values-small.yaml" \
  "${COMMON_VALUES[@]}"
helm template taxonomy "${CHART_DIR}" \
  --namespace taxonomy \
  --values "${CHART_DIR}/values-small.yaml" \
  --set "image.tag=${VALID_TAG}" \
  --set existingSecret=taxonomy-secrets \
  >"${SMALL_OUTPUT}"
cp "${SMALL_OUTPUT}" "${SMALL_EVIDENCE}"
for required in \
  'cpu: 100m' \
  'cpu: 500m' \
  'memory: 768Mi' \
  'memory: 1536Mi' \
  'name: TAXONOMY_EMBEDDING_ENABLED' \
  'value: "false"' \
  'name: TAXONOMY_SEARCH_DIRECTORY_TYPE' \
  'value: local-heap' \
  'MaxRAMPercentage=65.0'; do
  if ! grep -Fq "${required}" "${SMALL_OUTPUT}"; then
    echo "Rendered small profile is missing required contract: ${required}" >&2
    exit 1
  fi
done
if grep -Fq 'cpu: "2"' "${SMALL_OUTPUT}"; then
  echo "Small profile must not inherit the universal two-CPU limit" >&2
  exit 1
fi

'''
    return replace_once(source, marker, block + marker, "small profile render verification")


def patch_rancher(source: str) -> str:
    source = replace_once(
        source,
        """`values-rancher-rke2.yaml` is the supported starting point for a Rancher-managed RKE2 cluster with ingress-nginx. It addresses two common deployment failures:
""",
        """`values-rancher-rke2.yaml` is the supported starting point for a Rancher-managed RKE2 cluster with ingress-nginx. `values-small.yaml` is the generic quota-compatible evaluation profile for clusters that do not need Rancher-specific ingress annotations. Both use the same 500-mCPU ceiling; the Rancher profile additionally publishes the application below `/taxonomy/`.

The profiles address two common deployment failures:
""",
        "document small versus Rancher profile",
    )
    source = replace_once(
        source,
        """## 1. Prepare the values

Replace `taxonomy.example.invalid` with the real host. Keep an additional private values file for storage class, TLS and environment-specific settings.
""",
        """## 1. Select and prepare the values

For a root-path evaluation deployment without ingress-specific overrides, layer the small profile:

```bash
helm template taxonomy deploy/helm/taxonomy \\
  --namespace taxonomy \\
  --values deploy/helm/taxonomy/values-small.yaml \\
  --set image.tag=sha-<full-commit-sha> \\
  --set existingSecret=taxonomy-secrets
```

For Rancher/RKE2 with ingress-nginx, use `values-rancher-rke2.yaml`. Replace `taxonomy.example.invalid` with the real host. Keep an additional private values file for storage class, TLS and environment-specific settings.
""",
        "document profile selection",
    )
    source = replace_once(
        source,
        """The profile requests `100m` CPU and limits the pod to `500m`. This reduces quota pressure but cannot create free quota. Inspect all allocations in the namespace:
""",
        """The small and Rancher profiles request `100m` CPU and limit the pod to `500m`; memory is requested at `768Mi` and limited to `1536Mi`. Optional embeddings and model download remain disabled. This reduces quota pressure but cannot create free quota. Inspect all allocations in the namespace:
""",
        "document small resource envelope",
    )
    source += """

## Profile scope

The small envelope is intended for one replica, demonstrations and functional validation with the in-memory search backend. It is not a claim that ONNX, embedding-model download, bulk imports or high-concurrency analysis fit this envelope. Measured standard/large profiles and live ResourceQuota validation are tracked in issue #638.

The browser base-path contract is verified independently: the bootstrap script derives `/taxonomy` from its own URL, rewrites root-relative application requests, and leaves already-prefixed or external URLs unchanged. Helm verification locks the matching ingress-nginx regex, rewrite target and `X-Forwarded-Prefix` annotation.
"""
    return source


def patch_hardening(source: str) -> str:
    source = replace_once(
        source,
        """RANCHER = ROOT / \"deploy\" / \"helm\" / \"taxonomy\" / \"values-rancher-rke2.yaml\"
""",
        """RANCHER = ROOT / \"deploy\" / \"helm\" / \"taxonomy\" / \"values-rancher-rke2.yaml\"
SMALL = ROOT / \"deploy\" / \"helm\" / \"taxonomy\" / \"values-small.yaml\"
""",
        "register small profile",
    )
    source = replace_once(
        source,
        """    rancher = RANCHER.read_text(encoding=\"utf-8\")
""",
        """    rancher = RANCHER.read_text(encoding=\"utf-8\")
    small = SMALL.read_text(encoding=\"utf-8\")
""",
        "read small profile",
    )
    source = replace_once(
        source,
        """    for needle in (
        'nginx.ingress.kubernetes.io/rewrite-target: \"/$2\"',
""",
        """    for needle in (
        'cpu: 100m',
        'cpu: \"500m\"',
        'memory: 768Mi',
        'memory: 1536Mi',
        'TAXONOMY_EMBEDDING_ENABLED: \"false\"',
        'TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: \"false\"',
        'TAXONOMY_SEARCH_DIRECTORY_TYPE: local-heap',
        'MaxRAMPercentage=65.0',
    ):
        require(small, needle, SMALL, failures)
    if 'cpu: \"2\"' in small:
        failures.append("values-small.yaml must not use the universal two-CPU limit")

    for needle in (
        'nginx.ingress.kubernetes.io/rewrite-target: \"/$2\"',
""",
        "lock small profile source contract",
    )
    return source


def main() -> None:
    verify = VERIFY.read_text(encoding="utf-8")
    if 'SMALL_OUTPUT="${TMP_DIR}/small.yaml"' in verify:
        print("Small/Rancher profile contract already applied.")
        return
    VERIFY.write_text(patch_verify(verify), encoding="utf-8")
    RANCHER.write_text(patch_rancher(RANCHER.read_text(encoding="utf-8")), encoding="utf-8")
    HARDENING.write_text(patch_hardening(HARDENING.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied small profile render, documentation and governance contract.")


if __name__ == "__main__":
    main()
