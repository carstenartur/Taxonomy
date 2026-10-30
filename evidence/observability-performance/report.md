# OpenTelemetry performance evidence

Generated: `2026-10-30T20:02:25.097405160Z`  
Java: `25.0.5`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 29162 | 1572.6 | 1582.2 | `cgroup-v2-anon` | 137992.8 | 90 | 98 | 0.00 |
| agent-always-on | 34277 | 1605.9 | 1637.2 | `cgroup-v2-anon` | 147974.9 | 91 | 99 | 47.08 |
| agent-sampled-10-percent | 36280 | 1590.5 | 1600.7 | `cgroup-v2-anon` | 149236.0 | 90 | 98 | 1.21 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1572.6 MiB, 1572.6 MiB, 1572.6 MiB, 1572.6 MiB, 1572.6 MiB, 1572.6 MiB, 1572.6 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1605.9 MiB, 1605.9 MiB, 1605.9 MiB, 1605.9 MiB, 1605.9 MiB, 1605.9 MiB, 1605.9 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1590.5 MiB, 1590.5 MiB, 1590.5 MiB, 1590.5 MiB, 1590.5 MiB, 1590.5 MiB, 1590.5 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.0%**.
- Sampled p95 overhead: **0.0%**.
- Always-on startup overhead: **17.5%**.
- Always-on steady-state median memory delta (hard gate): **33 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **55 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
