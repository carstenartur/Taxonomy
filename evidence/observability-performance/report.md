# OpenTelemetry performance evidence

Generated: `2026-09-05T09:46:57.213815106Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 20241 | 1434.8 | 1452.6 | `cgroup-v2-anon` | 130124.9 | 96 | 104 | 0.00 |
| agent-always-on | 26244 | 1491.5 | 1513.3 | `cgroup-v2-anon` | 151367.9 | 97 | 106 | 39.00 |
| agent-sampled-10-percent | 26226 | 1488.3 | 1504.0 | `cgroup-v2-anon` | 137225.4 | 97 | 104 | 4.39 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1434.8 MiB, 1434.8 MiB, 1434.8 MiB, 1434.8 MiB, 1434.8 MiB, 1434.8 MiB, 1434.8 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1491.5 MiB, 1491.5 MiB, 1491.5 MiB, 1491.5 MiB, 1491.5 MiB, 1491.5 MiB, 1491.5 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1488.4 MiB, 1488.3 MiB, 1488.4 MiB, 1488.3 MiB, 1488.3 MiB, 1488.3 MiB, 1488.3 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.9%**.
- Sampled p95 overhead: **0.0%**.
- Always-on startup overhead: **29.7%**.
- Always-on steady-state median memory delta (hard gate): **57 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **61 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
