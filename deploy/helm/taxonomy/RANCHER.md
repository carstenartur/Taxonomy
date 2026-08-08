# Rancher / RKE2 deployment

`values-rancher-rke2.yaml` is the supported starting point for a Rancher-managed RKE2 cluster with ingress-nginx. `values-small.yaml` is the generic quota-compatible evaluation profile for clusters that do not need Rancher-specific ingress annotations. Both use the same 500-mCPU ceiling; the Rancher profile additionally publishes the application below `/taxonomy/`.

The profiles address two common deployment failures:

- namespace CPU quotas that reject the default `limits.cpu: 2`; and
- HTTP 404 responses when the application is published below `/taxonomy/` without stripping that external prefix.

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

Ingress-nginx strips `/taxonomy` before forwarding the request and sends `X-Forwarded-Prefix: /taxonomy`. The Kubernetes profile enables Spring's framework handling of forwarded headers, so server-generated links and redirects retain the public prefix. The first browser bootstrap script derives the same prefix from its generated script URL and applies it to legacy root-relative API requests.

## 2. Create the credential Secret

The chart expects a Secret named `taxonomy-secrets` in the examples below. It must contain at least:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
ADMIN_PASSWORD
```

LLM API keys remain optional.

## 3. Render before installing

```bash
helm template taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets
```

Use an immutable release tag such as `v1.3.1` or `sha-<commit>`; mutable tags such as `latest` are rejected.

## 4. Install or upgrade

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --create-namespace \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets \
  --set ingress.hosts[0].host=taxonomy.example.com
```

## CPU quota diagnosis

The small and Rancher profiles request `100m` CPU and limit the pod to `500m`; memory is requested at `768Mi` and limited to `1536Mi`. Optional embeddings and model download remain disabled. This reduces quota pressure but cannot create free quota. Inspect all allocations in the namespace:

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
curl -fsS https://taxonomy.example.com/taxonomy/actuator/health/readiness
```

The readiness response must report `UP`. The application remains behind a `ClusterIP` service; external traffic enters through the Ingress.


## Profile scope

The small envelope is intended for one replica, demonstrations and functional validation with the in-memory search backend. It is not a claim that ONNX, embedding-model download, bulk imports or high-concurrency analysis fit this envelope. Measured standard/large profiles and live ResourceQuota validation are tracked in issue #638.

The browser base-path contract is verified independently: the bootstrap script derives `/taxonomy` from its own URL, rewrites root-relative application requests, and leaves already-prefixed or external URLs unchanged. Helm verification locks the matching ingress-nginx regex, rewrite target and `X-Forwarded-Prefix` annotation.
