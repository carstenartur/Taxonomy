# Taxonomy Helm chart

This chart deploys the same Taxonomy image on Rancher/RKE2, K3s, OpenShift and generic Kubernetes. It deliberately contains no Rancher-specific application code.

## Prerequisites

- Kubernetes 1.27 or newer
- Helm 3 for chart rendering, either on an administrator workstation or in CI
- An externally managed PostgreSQL database
- An existing Kubernetes Secret for credentials
- An ingress controller when ingress is enabled
- The Prometheus Operator CRDs only when `serviceMonitor.enabled=true`

## Secure installation

Create the namespace and credentials without committing secret values:

```bash
kubectl create namespace taxonomy
kubectl -n taxonomy create secret generic taxonomy-secrets \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.example:5432/taxonomy' \
  --from-literal=SPRING_DATASOURCE_USERNAME='taxonomy' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='replace-me' \
  --from-literal=ADMIN_PASSWORD='replace-with-a-long-random-value'
```

Optional LLM keys may be added to the same Secret. Their mappings are marked optional; database and admin credentials are not.

Install or upgrade with an immutable image reference:

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8
```

Replace the example release tag with the intended published version. Supported image references are:

- `image.tag=vX.Y.Z` or a prerelease such as `v1.3.0-rc.1`;
- `image.tag=sha-<7-40 lowercase hexadecimal commit characters>`;
- `image.digest=sha256:<64 lowercase hexadecimal characters>`.

The chart rejects an empty image reference, `latest`, arbitrary mutable tags, Docker-invalid SemVer build metadata and simultaneous tag/digest configuration.

## Rancher without a visible Helm command

Rancher does not need application-specific code. There are two equivalent deployment paths:

1. Render or install the chart from an administrator workstation that has cluster access.
2. Render the manifests in CI or locally and import the resulting YAML through Rancher's **Import YAML** action.

Example rendering:

```bash
helm template taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --set existingSecret=taxonomy-secrets \
  --set image.tag=v1.2.8 \
  > taxonomy-rendered.yaml
```

Create the Secret separately before importing the rendered YAML. Never insert real credentials into the rendered file or ordinary Rancher chart values.

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
- `maxUnavailable: 0` and `maxSurge: 1` for the default stateless rollout;
- `ExitOnOutOfMemoryError`, allowing Kubernetes to restart a failed JVM.

The startup probe permits up to five minutes for initialization. Increase it for unusually large catalogues or model loading.

## Read-only filesystem and persistence

The portable Kubernetes default runs as numeric user/group `10001:10001`, drops all capabilities and expects a read-only root filesystem. The OpenShift profile delegates the numeric identity to the cluster while preserving the same effective restrictions. Writable locations are explicit:

- `/tmp`: ephemeral `emptyDir`;
- `/app/data`: `emptyDir` by default, or a PVC when `persistence.enabled=true`;
- additional explicitly configured mounts.

PostgreSQL is the authoritative persistent store. The default in-memory Lucene directory is rebuilt per pod. When `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`, the chart requires a PVC and restricts the deployment to one replica.

A ReadWriteOnce PVC cannot safely participate in the default surge rollout. For that reason the chart automatically selects the `Recreate` deployment strategy when persistence is enabled. This avoids multi-attach failures but introduces a controlled restart window. Use database-authoritative state and the default local-heap search configuration when zero-downtime rolling updates are required.

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

The application protects `/actuator/prometheus` with the admin token. Enable the optional ServiceMonitor as follows:

```yaml
serviceMonitor:
  enabled: true
  additionalLabels:
    release: kube-prometheus-stack
  authorization:
    enabled: true
    secretKey: ADMIN_PASSWORD
```

The ServiceMonitor reads the Bearer credential from `existingSecret` by default. `serviceMonitor.authorization.secretName` can point to a different Secret. Prometheus must be permitted to read that Secret in the release namespace, and its ServiceMonitor selector must match `additionalLabels`.

## Network policy

The default policy permits ingress only from pods in the same namespace and permits egress because database, LLM and optional model endpoints are environment-specific. Replace `networkPolicy.egress` with explicit DNS, PostgreSQL, identity-provider and approved external endpoint rules in high-assurance clusters.

When enabling ingress, add the ingress-controller selectors as shown above. The same mechanism can allow a Prometheus namespace if the Prometheus instance does not scrape through a same-namespace agent.

## Resource tuning

The defaults reserve 768 MiB and limit the pod to 2 GiB. `JAVA_OPTS` sizes heap as a percentage so native memory remains available for Lucene, ONNX, thread stacks and metaspace. Validate limits with production-sized imports and embedding models.

## Reproducible validation

Run the same chart checks as CI:

```bash
bash deploy/helm/taxonomy/verify.sh
```

The script performs linting, renders Kubernetes and OpenShift variants with monitoring evidence, and proves that unsafe configurations are rejected.

After deployment:

```bash
kubectl -n taxonomy rollout status deployment/taxonomy-taxonomy
kubectl -n taxonomy port-forward service/taxonomy-taxonomy 8080:80
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

For a protected metrics check:

```bash
TOKEN=$(kubectl -n taxonomy get secret taxonomy-secrets \
  -o jsonpath='{.data.ADMIN_PASSWORD}' | base64 --decode)
curl --fail -H "Authorization: Bearer ${TOKEN}" \
  http://127.0.0.1:8080/actuator/prometheus
```
