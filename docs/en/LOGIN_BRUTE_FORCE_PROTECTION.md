# Login brute-force protection

Taxonomy applies a bounded peer lockout only in the local-user profile. Keycloak deployments delegate credential validation and brute-force protection to the identity provider.

## Authentication boundary

The login limiter is installed exactly once inside the Spring Security filter chain. It runs after `SecurityContextHolderFilter` has restored a trusted browser session and before `UsernamePasswordAuthenticationFilter` and `BasicAuthenticationFilter` evaluate new credentials.

Consequently:

- an already authenticated, non-anonymous session bypasses peer lockout;
- a new form-login or HTTP-Basic attempt from a locked peer is rejected before credentials are evaluated;
- the limiter does not duplicate Spring Security password validation;
- only the authoritative downstream authentication result changes failure state.

A failed `POST /login` counts when Spring Security returns the login-error response. An `/api/**` response counts only when the request supplied an explicit `Authorization: Basic ...` header and Spring Security returned HTTP `401`. Missing credentials, Bearer credentials and unrelated authorization failures do not allocate or increment lockout state.

## Peer identity and trusted ingress

The limiter uses only the framework-resolved `HttpServletRequest.getRemoteAddr()` value. It never parses `Forwarded`, `X-Forwarded-For` or similar headers itself. In Kubernetes, configure framework forwarding-header processing only behind a trusted ingress and prevent clients from reaching the application port directly.

The in-memory lockout table stores a fixed-size SHA-256 digest of the resolved peer, not the raw address. HTTP responses and limiter log messages never echo the raw address or supplied credentials.

## Capacity and expiry

Failure windows use monotonic time so wall-clock changes cannot extend or shorten a lockout. Inactive entries expire globally. The normal peer table is hard-capped at 10,000 entries per running application instance. New peers above that cap share one fail-closed overflow budget; they cannot clear or replace existing lockouts.

State is local to one application instance. Multiple replicas therefore maintain separate peer lockout tables. A deployment that requires a cluster-wide authentication budget must add an appropriate trusted outer control.

## Configuration

| Environment variable | Default | Contract |
|---|---:|---|
| `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Enables the local-user login limiter. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Positive number of authoritative failures before lockout. Zero and negative values stop startup. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Positive failure window and lockout duration in seconds. Zero and negative values stop startup. |

The same route matching applies at the root context and below a servlet context path such as `/taxonomy`.

## Locked response

A blocked new authentication attempt receives UTF-8 JSON with HTTP `423 Locked`. The response includes:

- `Retry-After` with the remaining whole seconds;
- `Cache-Control: no-store`;
- `status: 423` and `retryAfterSeconds` in the JSON body;
- no peer address, username or credential material.
