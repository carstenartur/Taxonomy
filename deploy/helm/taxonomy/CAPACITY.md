# Kubernetes capacity and constrained-cluster evidence

This document separates **verified deployment envelopes** from resource declarations that have not yet completed representative workload benchmarking.

## Supported 1.4.0 deployment floor

The release-candidate floor is the single-replica constrained/evaluation profile. It proves that the packaged production image can be scheduled, started and used for a minimal HTTP workflow inside a namespace with an explicit `ResourceQuota`, `LimitRange`, read-only root filesystem and restricted NetworkPolicy egress.

| Profile | Purpose | Requests | Limits | Optional embeddings | Database | Validation status |
|---|---|---:|---:|---|---|---|
| `values-constrained-smoke.yaml` | automated cluster proof only | 100 mCPU / 512 MiB | 500 mCPU / 1 GiB | disabled | packaged HSQLDB | live kind smoke on every relevant change |
| `values-small.yaml` | evaluation and functional validation | 100 mCPU / 768 MiB | 500 mCPU / 1536 MiB | disabled | operator-supplied | chart-verified; constrained envelope, not throughput capacity |
| `values-rancher-rke2.yaml` | Rancher/RKE2 evaluation behind `/taxonomy/` | 100 mCPU / 512 MiB | 500 mCPU / 1536 MiB | disabled by default | operator-supplied | chart-verified; ingress selectors and restricted egress tested |
| default values | starting point for a standard single replica | 250 mCPU / 768 MiB | 2 CPU / 2 GiB | disabled by default | operator-supplied | declaration only; not yet a measured standard workload envelope |
| large | import/analysis/optional local-model workload | not fixed | not fixed | workload-dependent | operator-supplied | unsupported until the remaining #638 benchmark matrix is complete |

Do not advertise the default or a locally invented large override as a measured production capacity envelope. The remaining work must measure startup, import, search, standard analysis and optional ONNX workloads before those profiles receive supported throughput or concurrency claims.

## Reproducible constrained-cluster test

The `Kubernetes Constrained Smoke` workflow performs these operations on one exact source SHA:

1. installs checksum-verified, version-pinned `kind`, `kubectl` and Helm binaries;
2. builds the production `Dockerfile` and records its immutable local image ID;
3. loads that image into an isolated kind cluster;
4. applies `constrained-smoke-prerequisites.yaml`, including namespace, quota and limit range;
5. renders and installs the chart with `values-constrained-smoke.yaml`;
6. waits for startup, readiness and rollout completion;
7. verifies the readiness endpoint and the application home page through the Kubernetes Service;
8. records source commit/tree, image IDs, Kubernetes/kind/Helm versions, readiness duration, restart count and rendered/cluster resources;
9. uploads the complete evidence directory even when the smoke test fails.

The local equivalent is:

```bash
kind create cluster --name taxonomy-smoke --wait 180s
HELM_VERSION=v3.21.0 bash .github/scripts/install-helm.sh
KIND_CLUSTER_NAME=taxonomy-smoke \
  SOURCE_SHA="$(git rev-parse HEAD)" \
  bash deploy/helm/taxonomy/constrained-smoke.sh
kind delete cluster --name taxonomy-smoke
```

The evidence is written below `target/kubernetes-smoke/`. A passing `evidence.json` contains both the exact Git source identity and the immutable local image ID used by the pod. Release publication later binds the equivalent proof to the published OCI digest.

## Explicit NetworkPolicy egress

The portable chart now defaults to:

```yaml
networkPolicy:
  enabled: true
  egressMode: restricted
  allowSameNamespaceEgress: true
  dns:
    enabled: true
  egress: []
```

This generates only:

- same-namespace pod egress, needed for an in-cluster database or other explicitly colocated dependency;
- DNS to selected CoreDNS/kube-dns pods in `kube-system` on TCP/UDP port 53;
- additional reviewed rules supplied under `networkPolicy.egress`.

An external PostgreSQL example using a controlled private CIDR is:

```yaml
networkPolicy:
  egress:
    - to:
        - ipBlock:
            cidr: 10.40.0.0/16
      ports:
        - protocol: TCP
          port: 5432
```

Equivalent explicit rules can be added for:

- an in-cluster Keycloak/OIDC namespace and pod selector;
- an OTLP collector namespace and port;
- a private LLM gateway;
- an operator-controlled model mirror.

Portable Kubernetes NetworkPolicy does not support provider FQDNs. Public SaaS endpoints with rotating addresses require one of:

- a controlled egress proxy with a stable CIDR;
- a CNI-specific, separately managed FQDN policy;
- an explicitly reviewed `ipBlock` lifecycle outside the chart.

Do not encode unstable public provider IP ranges into release values.

`networkPolicy.egressMode=open` remains an explicit diagnostic/legacy escape hatch. It renders unrestricted egress visibly and is not used by the supported small, Rancher or constrained-smoke profiles. An empty `{}` rule is rejected when restricted mode is selected.

## Diagnosing resource pressure

### Scheduling failure

Typical indicators:

- `exceeded quota` in pod events;
- `Insufficient cpu` or `Insufficient memory` from the scheduler;
- a `LimitRange` default that conflicts with chart requests/limits.

Inspect:

```bash
kubectl -n taxonomy describe resourcequota
kubectl -n taxonomy describe limitrange
kubectl -n taxonomy describe pod -l app.kubernetes.io/name=taxonomy
```

### CPU throttling

Symptoms include slow startup without increasing resident memory, long analysis latency and elevated container CPU throttling counters. Do not increase concurrency first. Compare requested/limited CPU with measured steady-state and peak evidence, then change one profile at a time.

### Memory pressure or OOM

Symptoms include exit code 137, `OOMKilled`, repeated startup and missing readiness. The JVM heap is only part of container memory; Lucene, metaspace, native libraries, thread stacks and filesystem cache also consume the limit. Preserve headroom rather than setting `MaxRAMPercentage` near 100%.

### Readiness timeout

Distinguish a scheduling delay from application initialization:

```bash
kubectl -n taxonomy get pods -w
kubectl -n taxonomy describe pod <pod>
kubectl -n taxonomy logs <pod>
kubectl -n taxonomy get events --sort-by=.lastTimestamp
```

The automated evidence archive retains the same diagnostics.

## Remaining benchmark work

Issue #638 remains open until all of the following are measured and reproducible for named standard and large profiles:

- startup and readiness distribution;
- representative taxonomy import;
- full-text and graph/search latency;
- standard LLM-backed analysis with a controlled mock/provider boundary;
- optional local ONNX/model loading;
- CPU throttling, resident/anonymous memory and lifetime peak;
- steady-state and peak requests/limits derived from the evidence;
- RKE2/K3s and ingress-controller label variants beyond the kind release floor.

Any future capacity table must name source SHA, immutable image digest, cluster version, node capacity, workload fixture and measurement method. A resource declaration without those fields is not benchmark evidence.
