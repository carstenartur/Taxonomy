# Rancher / RKE2 deployment

`values-rancher-rke2.yaml` is the supported starting point for a Rancher-managed RKE2 cluster with ingress-nginx. It addresses two common deployment failures:

- namespace CPU quotas that reject the default `limits.cpu: 2`, and
- HTTP 404 responses when the application is published below `/taxonomy/` without stripping that external prefix.

## 1. Prepare the values

Copy the profile and replace `taxonomy.example.invalid` with the real host. Keep the regular chart values or an additional private values file for storage-class, TLS and environment-specific settings.

The profile publishes this external route:

```text
https://<host>/taxonomy/
```

Ingress-nginx strips `/taxonomy` before forwarding the request to the Spring Boot service and sends `X-Forwarded-Prefix: /taxonomy`. The application keeps `TAXONOMY_FORWARD_HEADERS_STRATEGY=framework`, so redirects and generated absolute links retain the public prefix.

## 2. Create the credential Secret

The default chart expects a Secret named `taxonomy-secrets` when installed with the commands below. It must contain at least:

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

Use an immutable release tag such as `v1.3.1` or `sha-<commit>`; mutable tags such as `latest` are rejected by the chart.

## 4. Install or upgrade

```bash
helm upgrade --install taxonomy deploy/helm/taxonomy \
  --namespace taxonomy \
  --create-namespace \
  --values deploy/helm/taxonomy/values-rancher-rke2.yaml \
  --set image.tag=sha-<full-commit-sha> \
  --set existingSecret=taxonomy-secrets
```

## Controller variants

The supplied profile is intentionally specific to the RKE2 ingress-nginx controller in `kube-system`. A cluster using Traefik must use a Traefik `StripPrefix` middleware instead of the nginx annotations and must adapt the NetworkPolicy controller selectors. Do not combine nginx annotations with a Traefik ingress class.

## CPU quota diagnosis

Rancher displays the Kubernetes admission error, but the quota allocation is determined from all pods in the namespace. Inspect it directly with:

```bash
kubectl -n <namespace> describe resourcequota
kubectl -n <namespace> get pods -o custom-columns='NAME:.metadata.name,CPU_LIMIT:.spec.containers[*].resources.limits.cpu'
```

The RKE2 profile lowers the application CPU limit from `2` to `1`. Change that value only after checking the namespace quota and measured workload requirements.
