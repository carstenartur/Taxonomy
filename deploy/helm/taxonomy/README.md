# Taxonomy Helm chart

This chart deploys the same Taxonomy image on Rancher/RKE2, K3s, OpenShift and generic Kubernetes. It deliberately contains no Rancher-specific application code. Published releases are also distributed as versioned OCI Helm artifacts for installation and upgrade through Rancher.

## Prerequisites

- Kubernetes 1.27 or newer
- Helm 3 for command-line rendering, either on an administrator workstation or in CI
- an externally managed PostgreSQL database
- an existing Kubernetes Secret for credentials
- an ingress controller when ingress is enabled
- the Prometheus Operator CRDs only when `serviceMonitor.enabled=true`

## Secure installation

Create the namespace and credentials without committing secret values:

```bash
kubectl create namespace taxonomy
kubectl -n taxonomy create secret generic taxonomy-secrets \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.example:5432/taxonomy' \
  --from-literal=SPRING_DATASOURCE_USERNAME='taxonomy' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='replace-me' \
  --from-literal=ADMIN_PASSWORD='replace-with-a-long-random-login-password' \
  --from-literal=ADMIN_TOKEN='replace-with-a-different-long-random-machine-token'
```

The Secret key `ADMIN_PASSWORD` is mapped to the application variable `TAXONOMY_ADMIN_PASSWORD` and bootstraps the interactive local administrator account. The distinct `ADMIN_TOKEN` key is mapped to the application variable `ADMIN_PASSWORD` and is used for Actuator/admin-token checks and the optional ServiceMonitor. Never reuse the interactive login credential as the machine token. Existing installations that do not enable the ServiceMonitor may omit the optional `ADMIN_TOKEN` key. Optional LLM keys may be added to the same Secret; database and login credentials remain mandatory.

Install or upgrade a source-tree chart with an immutable image reference:

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8
```

Supported explicit image references are:

- `image.tag=vX.Y.Z` or a prerelease such as `v1.3.0-rc.1`;
- `image.tag=sha-<7-40 lowercase hexadecimal commit characters>`;
- `image.digest=sha256:<64 lowercase hexadecimal characters>`.

The source/SNAPSHOT chart rejects an empty image reference, `latest`, arbitrary mutable tags, Docker-invalid SemVer build metadata and simultaneous tag/digest configuration. A packaged release chart has an exact stable `appVersion` and derives `image.tag=v<appVersion>` when neither a tag nor a digest is supplied. An explicit digest remains supported for environments that pin the deployment to the image manifest rather than the release tag.

## Upgrade safety and database migrations

Taxonomy runs Flyway migrations during application startup and then lets Hibernate validate the resulting schema. The default Deployment strategy is therefore:

```yaml
upgrade:
  strategy: Recreate
```

`Recreate` stops the old application pod before Kubernetes starts the new application version. This prevents the normal Helm upgrade path from leaving an old Taxonomy process active while the new process changes PostgreSQL. The controlled restart window is deliberate.

A rolling deployment is fail-closed. It is accepted only when an operator records release-specific proof that both application versions are compatible with every intermediate and final database state:

```yaml
upgrade:
  strategy: RollingUpdate
  allowConcurrentApplicationVersions: true
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

This acknowledgement is not a generic compatibility claim. Persistence still rejects `RollingUpdate` because a single ReadWriteOnce volume cannot safely participate in a surge rollout.

Before every production upgrade:

1. create and verify a restorable PostgreSQL backup;
2. use an immutable chart version and image reference;
3. retain `upgrade.strategy=Recreate` unless the selected release explicitly documents rolling compatibility;
4. wait for the Deployment and readiness endpoint;
5. treat a database restore, not a Helm rollback alone, as the recovery boundary for an irreversible migration.

## Fresh installation

A fresh installation means a new Taxonomy release connected to an empty PostgreSQL database or empty dedicated schema. Deleting only the Pod, Deployment, namespace or Helm release while reusing the same database is an upgrade or redeployment, not a fresh installation.

A parallel clean installation can use a separate namespace, database and host:

```bash
kubectl create namespace taxonomy-fresh
kubectl -n taxonomy-fresh create secret generic taxonomy-secrets \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.example:5432/taxonomy_fresh' \
  --from-literal=SPRING_DATASOURCE_USERNAME='taxonomy_fresh' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='replace-me' \
  --from-literal=ADMIN_PASSWORD='replace-with-a-long-random-login-password' \
  --from-literal=ADMIN_TOKEN='replace-with-a-different-long-random-machine-token'

helm upgrade --install taxonomy-fresh deploy/helm/taxonomy \
  --namespace taxonomy-fresh \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8 \
  --set ingress.hosts[0].host=taxonomy-fresh.example.org
```

On an empty PostgreSQL database the released JGit storage migrations and Taxonomy application migrations construct their complete schemas automatically. Unknown partial or contradictory legacy schemas remain fail-closed.

## Rancher installation from the OCI chart

Every successful non-dry-run release is followed by an independent workflow that:

1. resolves the exact release from the successful release workflow evidence;
2. packages the chart with matching chart and application versions;
3. verifies that the packaged chart derives the matching immutable Taxonomy image and defaults to `Recreate`;
4. publishes or safely reuses the OCI version;
5. pulls the chart back from the registry and proves that it renders identically to the release source;
6. archives checksums and JSON evidence.

Add this single-chart OCI repository in Rancher:

```text
oci://ghcr.io/carstenartur/charts/taxonomy
```

In Rancher select **Apps → Repositories → Create**, choose **OCI Repository**, and enter the URL above. A private GHCR package requires BasicAuth or an authentication Secret with a GitHub user and token that can read packages. A public package needs no registry credential. Select the repository under **Apps → Charts**, choose the immutable version, and install it into the intended namespace.

The OCI package includes `questions.yaml` for the required credential Secret and safety-sensitive storage/upgrade choices. Rancher-specific ingress, quota and NetworkPolicy values remain in `values-rancher-rke2.yaml`; review and paste those values in Rancher's values editor, replacing the example host and controller selectors with the actual cluster configuration.

The Rancher/RKE2 profile publishes:

```text
https://<host>/taxonomy/
```

Ingress-nginx strips `/taxonomy` before forwarding the request and supplies `X-Forwarded-Prefix: /taxonomy`. The application preserves that public prefix for generated links, redirects and legacy root-relative browser API calls.

## Rancher without a visible Helm command

There are three supported deployment paths:

1. install the published OCI chart through Rancher's Apps UI;
2. run `helm upgrade --install` from an administrator workstation or CI runner with cluster access;
3. render the manifests and import the resulting YAML through Rancher's **Import YAML** action.

Example rendering:

```bash
helm template taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8 \
  > taxonomy-rendered.yaml
```

Create the Secret separately before importing rendered YAML. Never insert real credentials into a rendered file or ordinary Rancher chart values. Importing YAML creates Kubernetes resources, but it does not create a Helm release with Rancher's normal chart-upgrade history; prefer the OCI App path for managed lifecycle operations.

## OpenShift restricted security context

OpenShift commonly assigns an arbitrary non-root UID through the `restricted-v2` Security Context Constraint. The image assigns application paths to group `0` for read access while granting group write only to `/app/data`; the supplied profile removes fixed UID/GID/fsGroup values while retaining `runAsNonRoot`, seccomp, dropped capabilities, no privilege escalation and a read-only root filesystem.

Install with the OpenShift profile:

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-openshift.yaml \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8
```

The profile does not request a custom SCC, privileged execution or a fixed UID range. The cluster remains authoritative for the assigned UID and supplemental groups. Use the generic Kubernetes `Ingress` resources from this chart when an ingress controller supports them, or disable `ingress.enabled` and expose the Service through an independently managed OpenShift Route.

Do not combine the OpenShift profile with command-line overrides that restore `podSecurityContext.runAsUser`, `runAsGroup` or `fsGroup` unless the cluster administrator has explicitly allocated those identities.

## Ingress and TLS

Example values:

```yaml
ingress:
  enabled: true
  className: traefik
  hosts:
    - host: taxonomy.example.org
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: taxonomy-tls
      hosts:
        - taxonomy.example.org

networkPolicy:
  ingressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: kube-system
      podSelector:
        matchLabels:
          app.kubernetes.io/name: traefik
```

Use the actual namespace and pod labels of the installed ingress controller. The chart fails closed when ingress and NetworkPolicy are enabled but no ingress-controller rule is supplied.

TLS terminates at the ingress controller. The Kubernetes profile respects trusted forwarded headers so HTTPS redirects and OIDC callback URLs retain the public origin. Configure the ingress controller to overwrite, rather than blindly append, client-supplied forwarding headers.

For outbound TLS that requires a private CA, mount a truststore with `extraVolumes`/`extraVolumeMounts` and add the corresponding JVM properties through `JAVA_OPTS`. Keep truststores in Secrets and never bake environment-specific trust material into the image.

## Health, shutdown and recovery

The chart uses:

- startup: `/actuator/health/liveness`;
- readiness: `/actuator/health/readiness`;
- liveness: `/actuator/health/liveness`;
- a configurable pre-stop delay, default 5 seconds;
- Spring Boot graceful shutdown, default 30 seconds;
- a 45-second pod termination grace period;
- migration-safe `Recreate` upgrades by default;
- `ExitOnOutOfMemoryError`, allowing Kubernetes to restart a failed JVM.

The startup probe permits up to five minutes for initialization. Increase it for unusually large catalogues or model loading.

## Read-only filesystem and persistence

The portable Kubernetes default runs as numeric user/group `10001:10001`, drops all capabilities and expects a read-only root filesystem. The OpenShift profile delegates the numeric identity to the cluster while preserving the same effective restrictions. Writable locations are explicit:

- `/tmp`: ephemeral `emptyDir`;
- `/app/data`: `emptyDir` by default, or a PVC when `persistence.enabled=true`;
- additional explicitly configured mounts.

PostgreSQL is the authoritative persistent store. The default in-memory Lucene directory is rebuilt per pod. When `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`, the chart requires a PVC and restricts the deployment to one replica.

The same `Recreate` strategy that prevents mixed-version schema access also avoids ReadWriteOnce multi-attach failures. Enabling persistence does not change the strategy because the safe strategy is already the default.

## Scaling and disruption budgets

The supported default is one replica. Multiple replicas remain an explicit operator decision because the following must first be verified for the selected configuration:

1. HTTP sessions are stateless or shared;
2. taxonomy initialization is idempotent under concurrent starts;
3. search indexes are independently reproducible or coordinated;
4. background jobs use leader election or database-backed claiming;
5. every local file dependency is removed or replicated safely.

The chart requires `scaling.allowMultipleReplicas=true` before accepting more than one replica, and still rejects multi-replica local-filesystem indexing or persistence. This switch records an explicit operational acknowledgement; it is not a support claim.

A PodDisruptionBudget is accepted only with at least two replicas:

```yaml
replicaCount: 2
scaling:
  allowMultipleReplicas: true
podDisruptionBudget:
  enabled: true
  minAvailable: 1
```

## Embedding model

Runtime model download is disabled by default in the chart, and embedding is initially disabled. Production deployments should either:

- mount a verified model directory and set `TAXONOMY_EMBEDDING_MODEL_DIR`, or
- use a prebuilt image containing an approved model, or
- deliberately enable downloading with suitable egress, storage and supply-chain controls.

## Prometheus metrics

The application protects `/actuator/prometheus` with the separate admin token. Enable the optional ServiceMonitor as follows:

```yaml
serviceMonitor:
  enabled: true
  additionalLabels:
    release: kube-prometheus-stack
  authorization:
    enabled: true
    secretKey: ADMIN_TOKEN
```

The application and ServiceMonitor read `ADMIN_TOKEN` from `existingSecret` by default. The chart maps that key to the application's historic `ADMIN_PASSWORD` environment variable while keeping the interactive login password under the distinct `ADMIN_PASSWORD` Secret key and `TAXONOMY_ADMIN_PASSWORD` environment variable. `serviceMonitor.authorization.secretName` can point to a different Secret; in that case the operator must ensure that its selected token value exactly matches the application token. Prometheus must be permitted to read the Secret in the release namespace, and its ServiceMonitor selector must match `additionalLabels`.

## Network policy

The chart now defaults to **restricted egress**. It permits only same-namespace pod traffic, selected CoreDNS/kube-dns pods in `kube-system` on TCP/UDP port 53, and additional rules that the operator adds explicitly under `networkPolicy.egress`.

External PostgreSQL, LLM, OIDC, OTLP or model-mirror destinations therefore require reviewed `ipBlock`, `namespaceSelector` and/or `podSelector` entries. Portable Kubernetes NetworkPolicy cannot resolve provider FQDNs; use a controlled egress proxy or a separately managed CNI-specific FQDN policy when public service addresses rotate.

Example for a PostgreSQL service in a controlled private CIDR:

```yaml
networkPolicy:
  egressMode: restricted
  egress:
    - to:
        - ipBlock:
            cidr: 10.40.0.0/16
      ports:
        - protocol: TCP
          port: 5432
```

`networkPolicy.egressMode=open` remains an explicit diagnostic or legacy escape hatch and renders unrestricted egress visibly. It is not used by the supported small, Rancher/RKE2 or constrained-smoke profiles. An empty `{}` rule is rejected in restricted mode.

When enabling ingress, add the ingress-controller selectors as shown above. The same mechanism can allow a Prometheus namespace if the Prometheus instance does not scrape through a same-namespace agent. See [CAPACITY.md](CAPACITY.md) for the exact constrained-cluster evidence contract, supported deployment floor and remaining benchmark boundary.

## Resource tuning

The generic defaults reserve 768 MiB and limit the pod to 2 GiB. The Rancher/RKE2 profile requests `100m` CPU and `512Mi` memory and limits the pod to `500m` CPU and `1536Mi` memory. `JAVA_OPTS` sizes heap as a percentage so native memory remains available for Lucene, ONNX, thread stacks and metaspace.

Only the constrained single-replica floor is currently proven through a live quota-bound cluster test. The default and larger profiles remain declarations until the workload matrix in [CAPACITY.md](CAPACITY.md) is measured. Do not infer throughput, concurrency or local-model support from requests and limits alone.

## Reproducible validation

Run the same chart checks as CI:

```bash
bash deploy/helm/taxonomy/verify.sh
```

The script performs linting, renders Kubernetes, Rancher and OpenShift variants, packages a simulated release, verifies automatic release-image selection, proves the default `Recreate` strategy, exercises the guarded rolling override, and demonstrates that unsafe configurations are rejected.

The live constrained-cluster equivalent is:

```bash
kind create cluster --name taxonomy-smoke --wait 180s
KIND_CLUSTER_NAME=taxonomy-smoke \
  SOURCE_SHA="$(git rev-parse HEAD)" \
  bash deploy/helm/taxonomy/constrained-smoke.sh
kind delete cluster --name taxonomy-smoke
```

The smoke test generates a fresh namespace-local credential Secret for the run and excludes Secret objects and values from retained evidence.

After deployment:

```bash
kubectl -n taxonomy rollout status deployment/taxonomy-taxonomy
kubectl -n taxonomy port-forward service/taxonomy-taxonomy 8080:80
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

For a protected metrics check:

```bash
TOKEN=$(kubectl -n taxonomy get secret taxonomy-secrets \
  -o jsonpath='{.data.ADMIN_TOKEN}' | base64 --decode)
curl --fail -H "Authorization: Bearer ${TOKEN}" \
  http://127.0.0.1:8080/actuator/prometheus
```
