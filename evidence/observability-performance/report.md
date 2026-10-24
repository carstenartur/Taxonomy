# OpenTelemetry performance evidence

Generated: `2026-10-24T12:05:22.967666578Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 16735 | 1454.3 | 1481.9 | `cgroup-v2-anon` | 92621.1 | 61 | 70 | 0.00 |
| agent-always-on | 19929 | 1502.9 | 1527.4 | `cgroup-v2-anon` | 105579.9 | 63 | 71 | 47.08 |
| agent-sampled-10-percent | 19021 | 1496.2 | 1526.7 | `cgroup-v2-anon` | 92383.0 | 61 | 68 | 3.53 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1463.9 MiB, 1463.9 MiB, 1454.3 MiB, 1454.3 MiB, 1454.3 MiB, 1454.3 MiB, 1454.3 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1502.9 MiB, 1502.9 MiB, 1502.9 MiB, 1502.9 MiB, 1502.9 MiB, 1502.9 MiB, 1503.0 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1496.2 MiB, 1496.2 MiB, 1496.2 MiB, 1496.2 MiB, 1496.2 MiB, 1496.2 MiB, 1496.2 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.4%**.
- Sampled p95 overhead: **-2.9%**.
- Always-on startup overhead: **19.1%**.
- Always-on steady-state median memory delta (hard gate): **49 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **46 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
