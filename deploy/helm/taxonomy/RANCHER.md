# Rancher / RKE2 deployment

`values-rancher-rke2.yaml` is the supported starting point for a Rancher-managed RKE2 cluster with ingress-nginx. `values-small.yaml` is the generic quota-compatible evaluation profile for clusters that do not need Rancher-specific ingress annotations. Both use a 500-mCPU ceiling; the Rancher profile additionally publishes the application below `/taxonomy/`.

The profile addresses two common deployment failures:

- namespace CPU quotas that reject the generic `limits.cpu: 2`; and
- HTTP 404 responses when the application is published below `/taxonomy/` without stripping that external prefix.

## Managed Rancher installation through OCI

Taxonomy releases publish a versioned Helm OCI chart at:

```text
oci://ghcr.io/carstenartur/charts/taxonomy
```

The publication workflow runs only after a successful non-dry-run `Release Workflow`. It resolves the exact release version from that workflow's immutable evidence, packages the tagged source, publishes the chart, pulls the same version back from GHCR, renders it and compares it with the local release render. A retry reuses an existing version only when it renders identically; it does not silently overwrite different content.

In Rancher:

1. open the target cluster and select **Apps → Repositories**;
2. select **Create** and choose **OCI Repository**;
3. enter `oci://ghcr.io/carstenartur/charts/taxonomy`;
4. for a private GHCR package, configure BasicAuth or an authentication Secret with a GitHub user and a token that can read packages;
5. wait until the repository is active, then select it under **Apps → Charts**;
6. choose the immutable Taxonomy version and install it into the intended namespace.

The packaged chart derives `image.tag=v<chart appVersion>` automatically, so selecting chart version `X.Y.Z` selects Taxonomy image `vX.Y.Z`. Source/SNAPSHOT rendering still requires an explicit immutable image tag or digest.

The OCI chart includes `questions.yaml` for the existing credential Secret, storage and upgrade-safety choices. Rancher-specific ingress and NetworkPolicy values remain explicit because controller namespaces and labels differ between clusters. Open Rancher's values editor and layer the content of `values-rancher-rke2.yaml`, replacing the example host and retaining only the ingress-controller selector that matches the cluster.

## Fresh installation versus upgrade

A fresh installation requires all of the following:

- a new namespace or otherwise non-conflicting release name;
- an empty new PostgreSQL database or dedicated empty schema;
- a credential Secret pointing to that new database;
- a distinct ingress host when the existing installation remains online.

Deleting and reinstalling the Kubernetes resources while keeping the same PostgreSQL database is not a fresh installation. The new Pod detects and migrates the existing managed schema, so that operation is an upgrade/redeployment.

A safe parallel fresh installation can therefore coexist with the current system until acceptance testing is complete. After validation, DNS or the ingress host can be switched deliberately; the old database remains an independent rollback source until it is retired.

## Migration-safe upgrades

Taxonomy executes Flyway migrations during startup before Hibernate validates the schema. The chart now defaults to:

```yaml
upgrade:
  strategy: Recreate
  allowConcurrentApplicationVersions: false
```

With the supported one-replica configuration, Kubernetes stops the old Pod before starting the new release. That prevents mixed Taxonomy versions from accessing PostgreSQL while the new release migrates it. The brief maintenance window is intentional.

`RollingUpdate` is rejected unless an operator explicitly confirms release-specific backward compatibility:

```yaml
upgrade:
  strategy: RollingUpdate
  allowConcurrentApplicationVersions: true
```

Do not set that acknowledgement merely to avoid downtime. It is appropriate only when the selected release proves that the old application remains compatible with every database state produced by the new migration stream. `persistence.enabled=true` always requires `Recreate`.

Before a production upgrade, create and verify a restorable PostgreSQL backup. A Helm rollback changes Kubernetes resources and the application image; it does not reverse an already executed database migration.

## 1. Select and prepare the values

For a root-path evaluation deployment without ingress-specific overrides, layer the small profile:

```bash
helm template taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-small.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets
```

For Rancher/RKE2 with ingress-nginx, use `values-rancher-rke2.yaml`. Replace `taxonomy.example.invalid` with the real host. Keep an additional private values file for storage class, TLS and environment-specific settings.

The profile publishes:

```text
https://<host>/taxonomy/
```

Ingress-nginx strips `/taxonomy` before forwarding the request and sends `X-Forwarded-Prefix: /taxonomy`. The Kubernetes profile enables Spring's framework handling of forwarded headers, so server-generated links and redirects retain the public prefix. The browser bootstrap script derives the same prefix from its generated script URL and applies it to legacy root-relative API requests.

## 2. Create the credential Secret

The chart expects a Secret named `taxonomy-secrets` in the examples below. It must contain at least:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
ADMIN_PASSWORD
```

`ADMIN_PASSWORD` is the interactive local administrator credential and is injected into the application as `TAXONOMY_ADMIN_PASSWORD`. When the protected Prometheus ServiceMonitor is enabled, add a separate machine credential:

```text
ADMIN_TOKEN
```

The chart injects `ADMIN_TOKEN` into the application as its historic `ADMIN_PASSWORD` environment variable and configures the ServiceMonitor to read the same Secret key. Never reuse the interactive `ADMIN_PASSWORD` value as `ADMIN_TOKEN`. The chart rejects a configuration that maps both purposes to the same key. LLM API keys remain optional. Create the Secret in the same namespace in which Rancher installs the chart, and do not place credentials in ordinary chart values.

Example:

```bash
kubectl -n taxonomy create secret generic taxonomy-secrets \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.example:5432/taxonomy' \
  --from-literal=SPRING_DATASOURCE_USERNAME='taxonomy' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='replace-me' \
  --from-literal=ADMIN_PASSWORD='replace-with-a-long-random-login-password' \
  --from-literal=ADMIN_TOKEN='replace-with-a-different-long-random-machine-token'
```

For a new installation, point `SPRING_DATASOURCE_URL` at the empty new database. For an upgrade, retain the existing database URL and backup that database before installing the new chart version. Existing installations that leave `serviceMonitor.enabled=false` may omit the optional `ADMIN_TOKEN` key until another admin-token consumer requires it.

## 3. Render before installing

```bash
helm template taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets
```

Use an immutable release tag such as `v1.4.0` or `sha-<commit>`; mutable tags such as `latest` are rejected. For a packaged OCI release, the stable chart `appVersion` supplies the matching release image automatically.

The render must contain:

```text
type: Recreate
nginx.ingress.kubernetes.io/rewrite-target: /$2
nginx.ingress.kubernetes.io/x-forwarded-prefix: /taxonomy
path: /taxonomy(/|$)(.*)
```

## 4. Install or upgrade from a checkout

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --create-namespace \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets \
  --set ingress.hosts[0].host=taxonomy.example.com \
  --wait \
  --timeout 15m
```

For the published OCI chart, the equivalent command is:

```bash
helm upgrade --install taxonomy \
  oci://ghcr.io/carstenartur/charts/taxonomy \
  --version X.Y.Z \
  --namespace taxonomy \
  --create-namespace \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set existingSecret=taxonomy-secrets \
  --set ingress.hosts[0].host=taxonomy.example.com \
  --wait \
  --timeout 15m
```

The Rancher UI performs the same Helm lifecycle operation when the chart is installed from the OCI repository.

## CPU quota diagnosis

The small and Rancher profiles request `100m` CPU and limit the pod to `500m`. The small profile requests `768Mi` memory, while the Rancher profile requests `512Mi`; both limit memory to `1536Mi`. Optional embeddings and model download remain disabled. This reduces quota pressure but cannot create free quota. Inspect all allocations in the namespace:

```bash
kubectl -n <namespace> describe resourcequota
kubectl -n <namespace> get pods \
  -o custom-columns='NAME:.metadata.name,CPU_LIMIT:.spec.containers[*].resources.limits.cpu,CPU_REQUEST:.spec.containers[*].resources.requests.cpu'
```

If no `limits.cpu` remains, remove unused workloads, increase the quota, or lower the limit only after measuring the workload.

## Controller and NetworkPolicy variants

The profile permits common ingress-nginx layouts in `kube-system` and `ingress-nginx`. Determine the actual controller namespace and labels:

```bash
kubectl get pods -A --show-labels | grep -E 'ingress|nginx'
```

Remove the unused selector and adapt the remaining pod selector to the cluster. A Traefik cluster needs a Traefik `StripPrefix` middleware instead of nginx annotations.

## Verification

```bash
kubectl get ingress,service,pods -n taxonomy
kubectl describe ingress -n taxonomy
kubectl rollout status deployment/taxonomy-taxonomy -n taxonomy --timeout=15m
curl -fsS https://taxonomy.example.com/taxonomy/actuator/health/readiness
```

The readiness response must report `UP`. The application remains behind a `ClusterIP` service; external traffic enters through the Ingress.

For a fresh installation, additionally inspect startup logs to confirm that both schema streams installed cleanly. For an upgrade, confirm that Flyway completed and Hibernate validation succeeded before declaring the release healthy.

## Profile scope

The small envelope is intended for one replica, demonstrations and functional validation with the in-memory search backend. It is not a claim that ONNX, embedding-model download, bulk imports or high-concurrency analysis fit this envelope. Measured standard/large profiles and live ResourceQuota validation are tracked in issue #638.

The browser base-path contract is verified independently: the bootstrap script derives `/taxonomy` from its own URL, rewrites root-relative application requests, and leaves already-prefixed or external URLs unchanged. Helm verification locks the matching ingress-nginx regex, rewrite target, `X-Forwarded-Prefix` annotation, packaged release image selection, safe upgrade strategy and guarded rolling override.
