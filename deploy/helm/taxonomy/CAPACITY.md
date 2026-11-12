# Kubernetes capacity and constrained-cluster evidence

This document separates **verified deployment envelopes** from resource declarations that have not yet completed representative workload benchmarking.

## Supported 1.4.0 deployment floor

The release-candidate floor is the single-replica constrained/evaluation profile. It proves that the packaged production image can be scheduled, started and used for a minimal HTTP workflow inside a namespace with an explicit `ResourceQuota`, `LimitRange`, read-only root filesystem and restricted NetworkPolicy egress.

| Profile | Purpose | Requests | Limits | Optional embeddings | Database | Validation status |
|---|---|---:|---:|---|---|---|
| `values-constrained-smoke.yaml` | automated cluster proof only | 100 mCPU / 512 MiB | 500 mCPU / 1 GiB | disabled | packaged HSQLDB | live kind smoke on every relevant change |
| `values-artemis-smoke.yaml` | additive distributed acceptance fixture | per-role declarations below | namespace ceiling 5 GiB | disabled | disposable PostgreSQL 16 | live coordinator/worker loss, provider cancellation and journal/PVC recovery in the [hosted run](https://github.com/carstenartur/Taxonomy/actions/runs/43200181349) |
| `values-small.yaml` | evaluation and functional validation | 100 mCPU / 768 MiB | 500 mCPU / 1536 MiB | disabled | operator-supplied | live bounded catalogue search and in-flight HTTPS cancellation using packaged HSQLDB in the fixture |
| `values-rancher-rke2.yaml` | Rancher/RKE2 evaluation behind `/taxonomy/` | 100 mCPU / 512 MiB | 500 mCPU / 1536 MiB | disabled by default | operator-supplied | chart-verified; ingress selectors and restricted egress tested |
| default values | starting point for a standard single replica | 250 mCPU / 768 MiB | 2 CPU / 2 GiB | disabled by default | operator-supplied | declaration only; not yet a measured standard workload envelope |
| large | import/analysis/optional local-model workload | not fixed | not fixed | workload-dependent | operator-supplied | unmeasured; wider profiles are a later follow-up to [#1351](https://github.com/carstenartur/Taxonomy/issues/1351) |

Do not advertise the default or a locally invented large override as a measured production capacity envelope. The remaining work must measure startup, import, search, standard analysis and optional ONNX workloads before those profiles receive supported throughput or concurrency claims.

## Reproducible constrained-cluster test

The `Kubernetes Constrained Smoke` workflow performs these operations on one exact source SHA:

1. installs checksum-verified, version-pinned `kind`, `kubectl` and Helm binaries;
2. builds the production `Dockerfile` and records its immutable local image ID;
3. loads that image into an isolated kind cluster;
4. applies `constrained-smoke-prerequisites.yaml`, containing the namespace, quota and limit range;
5. creates a fresh namespace-local Kubernetes Secret with generated test credentials, uses only Secret references in the rendered workload, and excludes Secret objects and decoded values from retained evidence;
6. renders and installs the chart with `values-constrained-smoke.yaml`;
7. waits for startup, readiness and rollout completion;
8. verifies the readiness endpoint and the application home page through the Kubernetes Service;
9. records source commit/tree, image IDs, Kubernetes/kind/Helm versions, deployment-to-readiness duration, total smoke duration, restart count and non-secret rendered/cluster resources;
10. uploads the evidence directory even when the smoke test fails.

The local equivalent is:

```bash
kind create cluster --name taxonomy-smoke --wait 180s
HELM_VERSION=v3.21.0 bash .github/scripts/install-helm.sh
KIND_CLUSTER_NAME=taxonomy-smoke \
  SOURCE_SHA="$(git rev-parse HEAD)" \
  bash deploy/helm/taxonomy/constrained-smoke.sh
kind delete cluster --name taxonomy-smoke
```

The smoke script uses a temporary kubeconfig from the named kind cluster for Kubernetes and Helm commands. It requires a fresh `taxonomy-smoke` namespace and asks Kubernetes to match the recorded UID during cleanup. When `KEEP_RESOURCES=true` retains the namespace, use a new fixture cluster or remove the retained fixture before rerunning.

The evidence is written below `target/kubernetes-smoke/`. Each invocation clears the prior success marker before preflight. A passing `evidence.json` contains the exact Git source, immutable local image ID, Kubernetes context and fixture namespace UID. `workload.readinessSeconds` measures from immediately before Helm installation until the Deployment is ready. `totalSmokeSeconds` includes image construction, cluster loading and prerequisite setup. The evidence records whether the fixture Secret existed and excludes its resource and values. The local image ID and Pod image reference identify the fixture image. A published image requires a matching immutable digest or separate verified equivalence.

## Small-profile catalogue search

The same workflow sets `MEASURE_SMALL_SEARCH=true` to resize the owned fixture after the original smoke proof. It installs `values-small.yaml` over the HSQLDB fixture values (100 mCPU/768 MiB requested, 500 mCPU/1536 MiB limited) with a 64 MiB ephemeral-storage request and 512 MiB limit. Embeddings and model downloads are disabled. To exercise the workload locally, set `MEASURE_SMALL_SEARCH=true` on the constrained smoke command above.

After catalogue search becomes available, the fixture samples a ten-second idle interval and then three batches of three authenticated, bounded searches. The artifact under `target/kubernetes-smoke/small-search/` contains the admitted Pod resources, readiness duration, cgroup CPU and throttling counters, sampled memory and kubelet Pod ephemeral storage. A restart, an OOM kill, a missing counter, a changed Pod UID or a mismatched runtime image configuration digest fails the check. CPU figures are interval averages and memory/storage maxima apply only to the sample times. The receipt records the chart tree, rendered manifest hash, runtime image and original cluster/tool identities.

The workload then makes a real `CUSTOM_OPENAI` HTTPS call to a namespace-local test provider. It requests cancellation on the active operation ID, replaces the provider Pod while the HTTP request is pending, verifies terminal cancellation on the same operation and checks the replacement DNS/TLS endpoint with its fixture CA. It captures counters before and during the in-flight request and after recovery. It checks DNS access for the provider and a denied connection to the independently verified CoreDNS metrics listener on port 9153. A second inference request after the replacement fails the fixture. No external provider credentials are used.

### Measured small-profile workload on 12 November 2026

The admitted application requested 100 mCPU, 768 MiB memory and 64 MiB ephemeral storage. Its limits were 500 mCPU, 1536 MiB and 512 MiB. The namespace quota allowed 1500 mCPU and 2 GiB of memory limits. The Pod became ready in 80 seconds. Three batches of three searches each returned five results.

| Measurement | Idle and catalogue search | In-flight provider cancellation and recovery |
|---|---:|---:|
| CPU | 15.31 mCPU idle, 423.24 mCPU peak interval | 444.63 mCPU from before to during inference, 91.26 mCPU through recovery |
| Memory | 759,111,680 B idle, 765,616,128 B sampled peak | 819,003,392 B sampled peak |
| Pod ephemeral storage | 180,224 B sampled peak | 184,320 B sampled peak |
| CPU throttling | 23 periods, 1,896,543 µs | 61 periods, 7,948,841 µs from before inference to recovery |
| Restarts / OOM kills | 0 / 0 | 0 / 0 |

The HTTPS operation waited 1 ms in the application queue and reached terminal cancellation in 4,292 ms. One request reached the original provider. The replacement accepted the fixture CA and received zero inference requests. The workload completed within its fixture deadlines. DNS and provider TLS succeeded, and egress to the verified CoreDNS metrics listener on port 9153 was denied. The chosen small configuration uses packaged HSQLDB with OIDC, telemetry export and optional model downloads disabled.

The runner used kind 0.31.0, Kubernetes 1.35.0 and Helm 3.21.0 on one node. It built source `a817737e2c75e1b463f1b2a46c2f586c6a09bbc3` (tree `637ec27e0989d331b842cb0ea74234f6dca13465`), chart tree `1e485710e564ebc05d8a3329e7dd8939ca98c981`, rendered small manifest SHA-256 `51c0d1e634d062483aa7e67d600172a0fa09c016f0d732416c5a59b6417f3975`. The local image output was `sha256:67c57209ca92d074397492f80bb6f5f3d51242e3e4bf9775d28b65cc80a82333`. The runtime image configuration was `sha256:e3767e2835b0661bbd53c2c3edfa81f54958d051505b8fa880001ad071fa18e9`. Node allocatable totals and release-image equivalence were outside this workload measurement. The archive includes raw counters and admitted resources. [Hosted job](https://github.com/carstenartur/Taxonomy/actions/runs/43200181349/job/131121239769) · [Evidence archive](https://github.com/carstenartur/Taxonomy/actions/runs/43200181349/artifacts/13941176320)

## Additive external-Artemis acceptance case

The same workflow next runs `artemis-constrained-smoke.sh` against its existing kind cluster and exact production image. The original single-pod smoke remains unchanged. The additional namespace contains a coordinator, independently scalable CP and IP worker sets, a shared PostgreSQL database and an external Artemis broker with generated TLS material. PostgreSQL and Artemis use digest-pinned images. PostgreSQL uses one namespace-owned 512 MiB PVC with the default StorageClass. Artemis uses a separate namespace-owned 512 MiB RWO claim for its instance and journal. Broker management is a ClusterIP port accessed by an authenticated localhost port-forward; no public ingress is created.

The fixture initially starts one worker for each root, records authenticated JVM heap metrics, `/proc/1/status` RSS and each pod's role, shards, resources and image ID, then stops workers before admitting a two-root MOCK analysis. Index mode is explicitly `local-heap`; the evidence does not equate an absent disk index with zero index memory. Initial application limits are 1 GiB for the coordinator and 768 MiB for each worker. The final one-coordinator, two-CP, one-IP topology plus PostgreSQL and Artemis declares 4224 MiB and 3 CPU of limits under the namespace's 5 GiB/4 CPU quota. These are acceptance-fixture settings, with no production memory or throughput promise.

The live assertions require all of the following:

- Every application pod reaches PostgreSQL on 5432 and the TLS broker on 61617, while a TCP probe to the same live broker's management port 8161 times out. A reachable management port or another probe error fails the case; rendering a NetworkPolicy never counts as enforcement. The workflow's pinned kind 0.31 includes NetworkPolicy enforcement.
- Admission persists both selected-root tasks while worker replicas are zero. A test-owned PostgreSQL transaction locks the admitted operation row before a CP worker starts. Only after Artemis reports one unacknowledged delivery does the harness delete that worker.
- The row lock stays held until the old broker consumer and every database connection from the lost pod disappear. No CP start, result or completion effect may have committed. Replacement workers must persist a CP delivery attempt of at least two, complete both roots, leave exactly two completion-ledger entries, and publish contiguous progress revisions. The broker must acknowledge the single CP message exactly once.
- CP reaches two ready replicas while IP remains at one. The original HTTP request succeeds and its public run observation agrees with the durable completion state.
- After settlement the fixture replaces the PostgreSQL Pod, checks its new UID and retained PVC UID, then compares the original durable ledger and scoped HTTP result against the replacement.

Run the original smoke first to build the exact image, retain its cluster, then run the additive scenario:

```bash
kind create cluster --name taxonomy-smoke --wait 180s
SOURCE_SHA="$(git rev-parse HEAD)" KEEP_RESOURCES=true \
  bash deploy/helm/taxonomy/constrained-smoke.sh
SOURCE_SHA="$(git rev-parse HEAD)" \
  bash deploy/helm/taxonomy/artemis-constrained-smoke.sh
kind delete cluster --name taxonomy-smoke
```

Besides the original prerequisites, the additive case needs OpenSSL and JDK 25 `keytool`. It binds Kubernetes and Helm operations to `kind-${KIND_CLUSTER_NAME}` and rejects a pre-existing `taxonomy-artemis-smoke` namespace. Cleanup only deletes the namespace created by that invocation. `KEEP_RESOURCES=true` retains it for inspection; inspect and remove that fixture namespace before rerunning. Fresh random credentials and trust stores are temporary and excluded from the evidence archive.

For local chart validation without Docker or Kubernetes:

```bash
bash deploy/helm/taxonomy/artemis-constrained-smoke.sh --render-only
```

Render-only mode never creates a passing live evidence file. Each invocation removes any old live success marker before validation. A successful live run writes `target/kubernetes-smoke/artemis/evidence.json`, broker counters before loss/after disconnect/after completion, the durable root ledger, startup measurements and non-secret Kubernetes resources into the existing workflow artifact. Local implementation checks cover shell syntax, actual Helm rendering, fixture budgets and preflight isolation. The [hosted run](https://github.com/carstenartur/Taxonomy/actions/runs/43200181349/job/131121239769) completed both the MOCK coordinator/worker failure sequence and the PostgreSQL replacement. The replacement Pod retained claim UID `85bd2ed6-408a-4ea9-912a-500fee5ac32a` and returned the same scoped HTTP result and two-root ledger for operation `aecc1883-89de-4a3d-8abe-18860ecfd6cc`. The database was replaced after settlement. Availability during an active transaction requires a separate case. The following controlled transport phase uses a separate operation and records its own provider-capacity evidence. Database high availability, clustered broker failover and production throughput require their own executions.

### Controlled provider capacity after transport loss

After the completed two-root MOCK operation and PostgreSQL replacement, the fixture upgrades the same application roles with `values-artemis-provider-fault.yaml`. A private HTTPS provider holds one real CUSTOM_OPENAI request. The fixture provisions one physical permit and a limit of ten requests per minute and per day. Its admission, interruption and cancellation use the normal product APIs and broker protocol. During HTTP the broker must report one delivered permit, and the quota ledger must contain one started debit. After cancellation and provider replacement the fixture retires the CP worker. Once its old delivery has rolled back, the administrative browser must return exactly one unavailable receipt. The fixture starts a replacement worker, replaces the broker pod on its original claim, confirms the new worker is consuming the drained CP task queue and checks the same permit, attempt and debit identities. The archive includes the permit queue counts at all three boundaries. Only identity hashes and bounded counts are retained in `artemis/provider-capacity/evidence.json`. The provider and database remain fixture-local. The [same hosted archive](https://github.com/carstenartur/Taxonomy/actions/runs/43200181349/artifacts/13941176320) records operation `b23a2e0e-a5b6-40b1-9082-592f0fe5912d` reaching terminal cancellation in 4,593 ms after 1,607 ms in the queue. A single request reached the original provider. The replacement CP worker consumed the drained task queue after broker restart. The broker retained PVC UID `adf56b2a-00c9-404c-b29c-20bdd195e675`. At the three boundaries the permit queue contained one message, with delivery counts of 1, 0 and 0. The same unavailable permit/attempt pair and the same single started RPM/RPD debit survived replacement. No inference reached the replacement provider. The quota was 10 RPM and 10 RPD. TPM, multi-node broker failover and production throughput require their own measurements.

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

`networkPolicy.egressMode=open` remains an explicit diagnostic/legacy escape hatch. It renders unrestricted egress visibly and is not used by the supported small, Rancher or constrained-smoke profiles. In restricted mode, every custom rule must constrain destinations, ports or both; `{}`, `{to: []}`, `{ports: []}` and equivalent rules with both sections empty are rejected.

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

The automated evidence archive retains the same diagnostics without copying credential Secrets.

## Remaining benchmark work

The [selected small workload and distributed failure case](https://github.com/carstenartur/Taxonomy/issues/1351) cover the configurations measured above. Rancher/RKE2 throughput, optional OIDC/telemetry/model egress and database interruption during active provider work remain unmeasured. A standard or large profile needs its own reproducible measurements before it can claim the following:

- startup and readiness distribution;
- representative taxonomy import;
- full-text and graph/search latency;
- standard LLM-backed analysis with a controlled mock/provider boundary;
- optional local ONNX/model loading;
- CPU throttling, resident/anonymous memory and lifetime peak;
- steady-state and peak requests/limits derived from the evidence;
- RKE2/K3s and ingress-controller label variants beyond the kind release floor.

Any future capacity table must name source SHA, immutable image digest, cluster version, node capacity, workload fixture and measurement method. A resource declaration without those fields is not benchmark evidence.
