# Existing Rancher workload: startup, probes and persistent indexes

Use this guide when Taxonomy already runs as a Rancher-managed **Deployment** and
you update its image or environment variables directly. It requires no Helm
installation. The [Deployment patch](deployment-startup-patch.yaml) and optional
[new PVC manifest](persistent-volume-claim.yaml) are ready to adapt to the names
and storage already used by the workload.

An image update changes the application code. It does **not** apply
`deploy/helm/taxonomy/templates/resources.yaml`, change Kubernetes probes, attach
a volume, or change the Deployment strategy. Apply those workload settings in
Rancher or with `kubectl` as described below. For a Helm-managed Rancher App, keep
using its [chart values and upgrade lifecycle](../helm/taxonomy/RANCHER.md).

## What each readiness signal means

Taxonomy has two levels of readiness:

- **Web readiness** allows the login, application shell and startup status to be
  reached while background initialization continues. Kubernetes readiness uses
  Spring Boot's `readinessState` for this purpose.
- **Feature readiness** determines whether catalogue and search operations can
  run. Inspect the authenticated `/api/status/startup` response and the startup
  banner. A ready web pod does not prove that search indexes or embeddings are
  ready; dependent operations remain guarded until their required state is ready.

Use these probe endpoints relative to the application's actual servlet context:

| Kubernetes probe | Endpoint | Purpose |
|---|---|---|
| Startup | `/actuator/health/liveness` | Give the JVM and HTTP server time to start. |
| Liveness | `/actuator/health/liveness` | Restart an unhealthy process. |
| Readiness | `/actuator/health/readiness` | Route traffic once the web application can accept requests. |

Keep `taxonomy`, search, optional embedding models and the aggregate
`/actuator/health` endpoint out of startup/liveness probes. A slow index build or
an optional provider failure must not repeatedly kill an otherwise live JVM.
Keep catalogue/search health out of **web** readiness so users can see startup
progress. An Artemis **worker-only** workload retains
`readinessState,analysisBroker`; the patch here is for the web workload.

The example startup budget is `60 × 5 = 300` seconds. It protects server startup,
including schema migration before HTTP is available. Increase it only when
measured server startup requires more time. Background indexing can continue
after the startup probe has succeeded. Kubernetes suppresses readiness and
liveness checks until the startup probe succeeds; readiness failure subsequently
removes traffic without itself restarting the container. See the upstream
[probe behavior](https://kubernetes.io/docs/concepts/workloads/pods/probes/).

## Supported environment variables

Enter these as ordinary environment variables in the Taxonomy container. Keep
database credentials, administrator credentials and provider keys in the existing
Secret references.

| Variable | Value in the example | Effect |
|---|---|---|
| `TAXONOMY_INIT_ASYNC` | `true` | Load/reuse the catalogue in the background after the web server starts. Also the default of the `kubernetes` profile. |
| `TAXONOMY_INIT_RELOAD_EXISTING` | `false` | Reuse the retained catalogue; avoid an intentional destructive catalogue refresh. |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | `local-filesystem` | Store Hibernate Search Lucene indexes on disk. |
| `TAXONOMY_SEARCH_DIRECTORY_ROOT` | `/app/data/lucene-index` | Put those indexes under the persistent application-data mount. |
| `TAXONOMY_SEARCH_SYNC_STRATEGY` | `write-sync` | Use the Kubernetes profile's disk-write synchronization policy. |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true` | Expose Actuator probe groups; already enabled by the application. |
| `MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE` | `livenessState` | Keep startup/liveness independent of catalogue/search work. |
| `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` | `readinessState` | Make web availability independent of catalogue/search readiness. |
| `SERVER_SERVLET_CONTEXT_PATH` | `/taxonomy` | Set a real application context path; use the path rules below. |

Retain the actual database and authentication profiles in `SPRING_PROFILES_ACTIVE`.
For an existing PostgreSQL deployment, `postgres,kubernetes` enables the Kubernetes
runtime defaults, including graceful shutdown; keep any existing authentication
profiles as well. Do not switch the database profile or JDBC URL just to change
probes. Adding the `kubernetes` profile also selects `ddl-auto=validate`, so the
database must already satisfy the supported migration/schema contract. The patch
does not change profile selection, database configuration, Secrets or the image.

There is no supported `TAXONOMY_BRANCH_INDEX_DIRECTORY` setting. Branch graph
vectors are retained as Lucene archives in the application database, scoped to
repository, workspace and branch. Their durability requires persistent database
storage and database backups. The PVC below protects the separate Hibernate Search
filesystem indexes and other application files under `/app/data`. Commit-history
search uses that same Hibernate Search root; its source rows and Git history
remain in the database.

## Choose the correct `/taxonomy` arrangement

Kubernetes HTTP probes contact the pod directly and bypass the Ingress. Set probe
paths for the path the **application** receives, not merely the browser's URL.

| Setting | Real servlet context (patch default) | Ingress rewrite (existing Helm Rancher profile) |
|---|---|---|
| Public URL | `https://host/taxonomy/` | `https://host/taxonomy/` |
| `SERVER_SERVLET_CONTEXT_PATH` | `/taxonomy` | Empty/root context |
| Ingress routing | Preserve `/taxonomy` when forwarding | Strip `/taxonomy`, send `X-Forwarded-Prefix: /taxonomy` |
| Startup/liveness probe path | `/taxonomy/actuator/health/liveness` | `/actuator/health/liveness` |
| Readiness probe path | `/taxonomy/actuator/health/readiness` | `/actuator/health/readiness` |
| Direct startup-status path | `/taxonomy/api/status/startup` | `/api/status/startup` |

The supplied patch uses a real servlet context at `/taxonomy`. Its Ingress must
forward that prefix unchanged. If your existing ingress-nginx configuration
already strips the prefix, keep the root servlet context: set the patch's
`SERVER_SERVLET_CONTEXT_PATH` value to `""` and remove `/taxonomy` from all three
probe paths. Keep forwarded-header handling configured for the trusted ingress;
the `kubernetes` profile uses `TAXONOMY_FORWARD_HEADERS_STRATEGY=framework`.

## Persistent storage and one writer

Use an existing application-data PVC where one already exists. Only create
`persistent-volume-claim.yaml` for a new claim. The manifest requests 10 GiB and
omits `storageClassName` so the cluster's default can apply; select an available
class explicitly if necessary. Binding can wait until a pod uses the claim with
`WaitForFirstConsumer`. Confirm the mounted directory is writable by the container
user. The standard image uses UID/GID `10001`; the patch adds `fsGroup: 10001`.
Adapt that group to cluster policy and storage-driver support on platforms that
assign their own identities, including OpenShift.

The directory `/app/data/lucene-index` must resolve inside the retained mount on
every restart. `local-filesystem` alone does not make a container's writable layer
persistent, and `emptyDir` survives container restarts within a pod but not pod
replacement. A new mount hides existing container files; it does not copy them.
Before changing an existing `/app/data` mount, preserve its database, retained
uploads and other files using the [data retention procedure](../../docs/en/DATABASE_SETUP.md#persistent-installation).
Do not stop an in-memory database with valuable state before a verified transfer
exists. An application PVC does not protect a separate PostgreSQL database.

Keep **one Taxonomy writer**, `replicas: 1`, and Deployment strategy `Recreate` for
the filesystem index. Stop or reconfigure any HPA, GitOps controller, second
Deployment or old workload that could restore another writer. Do not mount the
same writable Lucene directory in the web pod and Artemis worker pods. A shared
RWX filesystem does not coordinate Lucene writers or database migrations.

`ReadWriteOnce` restricts a volume to a node; it can still be used by several pods
on that node. It is not a single-process lock. A supported CSI driver's
`ReadWriteOncePod` can enforce one pod, but it does not replace correct rollout and
application ownership. `Recreate` orders old and new pods during Deployment
upgrades; manually deleting a pod can let its ReplicaSet create a replacement
while the old pod is terminating. For maintenance, scale down the owning workload
and verify the old writer has stopped before bringing it back. See Kubernetes'
[access modes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#access-modes)
and [Recreate semantics](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#recreate-deployment).

Retain the PVC during image updates. Review its PV reclaim policy and keep tested
backups before deleting a claim, namespace or application. Retain database
backups, Secrets/configuration and filesystem data together for recovery.

## Apply to the existing Deployment

In Rancher, select the namespace and the workload, inspect its YAML, then either
edit the corresponding fields there or use the cluster's kubectl shell. The
patch is **not** a complete Deployment for **Import YAML** or `kubectl apply`.

1. Check the Deployment, Taxonomy container, application-data volume and PVC
   names. Copy the patch to a private working file and match those names exactly.
   If the container is not named `taxonomy`, change `containers[0].name`; otherwise
   a strategic merge would try to add a different container. Preserve any existing
   volume name, claim name and subpath that contain retained data.
2. Check the context-path arrangement above and the actual application port.
   Change all three `httpGet.port` values if it is not `8080`.
3. Prepare the existing/new PVC and its permissions. Keep database/authentication
   Secret references and current resource requests/limits.
4. Review the resulting Deployment with the server dry run, then apply the patch.
   The template change restarts the workload with `Recreate` and causes a brief
   maintenance window.

From the repository root, make the working copy first:

```bash
cp deploy/rancher/deployment-startup-patch.yaml /tmp/taxonomy-startup-patch.yaml
```

Edit that copy using the checks above. Then, after selecting the actual namespace
and Deployment name, review and apply it:

```bash
TAXONOMY_NAMESPACE=taxonomy
TAXONOMY_DEPLOYMENT=taxonomy

kubectl -n "$TAXONOMY_NAMESPACE" get deployment "$TAXONOMY_DEPLOYMENT" -o yaml
kubectl -n "$TAXONOMY_NAMESPACE" get pvc

# Only when a new claim is needed; adapt its name/class/capacity first.
kubectl -n "$TAXONOMY_NAMESPACE" apply -f deploy/rancher/persistent-volume-claim.yaml

kubectl -n "$TAXONOMY_NAMESPACE" patch deployment "$TAXONOMY_DEPLOYMENT" \
  --type strategic --patch-file /tmp/taxonomy-startup-patch.yaml \
  --dry-run=server -o yaml

kubectl -n "$TAXONOMY_NAMESPACE" patch deployment "$TAXONOMY_DEPLOYMENT" \
  --type strategic --patch-file /tmp/taxonomy-startup-patch.yaml

kubectl -n "$TAXONOMY_NAMESPACE" rollout status \
  deployment/"$TAXONOMY_DEPLOYMENT" --timeout=15m
```

`valueFrom: null` clears a prior ConfigMap/Secret source for the specific non-secret
variable being set. `$patch: replace` replaces each whole probe, removing an
incompatible old TCP/exec handler. The volume's `emptyDir: null` and `hostPath: null`
remove those old sources when reusing the volume name; remove any other old volume
source explicitly when adapting the patch. When editing full YAML in Rancher
instead, replace the corresponding objects, remove obsolete fields and omit the
`$patch` directives and null keys. Kubernetes documents the
[patch operation](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/update-api-object-kubectl-patch/).

## Verify the result

First inspect the effective Deployment and pod events in Rancher. Confirm one
replica, the `Recreate` strategy, the expected PVC/mount and all three probe paths.
Check pod restart counts and previous-container logs if a restart occurred.

For a direct pod check, port-forward the Deployment in one terminal:

```bash
kubectl -n "$TAXONOMY_NAMESPACE" port-forward \
  deployment/"$TAXONOMY_DEPLOYMENT" 18080:8080
```

In a second terminal, for the real `/taxonomy` servlet-context variant:

```bash
curl -fsS http://127.0.0.1:18080/taxonomy/actuator/health/liveness
curl -fsS http://127.0.0.1:18080/taxonomy/actuator/health/readiness
```

For the ingress-rewrite variant, use the unprefixed `/actuator/...` paths in this
direct check. Both probes should report `UP` once the web server is ready. Then
log in through the normal public URL and inspect the startup banner and
authenticated `/api/status/startup` response. Check catalogue/search completion
separately, then perform a representative search. A successful rollout alone is
not proof of search readiness.

After a controlled restart that retains the same database and PVC, verify the
same mount and index location are used, readiness becomes healthy, and a
representative search returns the expected data. Check initialization logs to
distinguish retained-index reuse from a rebuild.

Retained node and relation search indexes are checked against the complete current
database projection using versioned per-document fingerprints. Matching document
counts alone are insufficient. Only missing or stale scopes are rebuilt, and the
result is verified before catalogue/search readiness is published. An index from
an older format without the current validation metadata needs a rebuild once.
Optional embedding reuse additionally requires the active embedding model's
identity and complete vector coverage. Catalogue completion schedules that work;
it does not occupy a worker while polling for catalogue readiness.

Commit-history search is verified independently against every persisted projection,
including its repository/workspace/branch scope and searchable content. A second
database observation detects source changes during verification. Stale, missing or
extra documents require a rebuild of that history scope and a complete recheck.
The startup response exposes catalogue/full-text, semantic and history readiness
separately; a ready catalogue does not imply that the history or semantic phase
has finished.

A timeout or failed validation is not readiness evidence. The web interface can
remain reachable so the failure can be diagnosed, while affected feature
operations stay unavailable. Branch-vector validity is checked against its selected
repository/workspace/branch and source/model identity, including the retained
archive checksum, not merely the existence of persisted bytes.
