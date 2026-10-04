# OpenTelemetry performance evidence

Generated: `2026-10-04T02:51:18.077381097Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 24199 | 1459.4 | 1481.0 | `cgroup-v2-anon` | 140093.7 | 86 | 98 | 0.00 |
| agent-always-on | 29227 | 1515.3 | 1538.3 | `cgroup-v2-anon` | 153942.5 | 90 | 100 | 47.11 |
| agent-sampled-10-percent | 30229 | 1503.0 | 1517.5 | `cgroup-v2-anon` | 140499.8 | 89 | 97 | 5.88 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1459.4 MiB, 1459.4 MiB, 1459.4 MiB, 1459.4 MiB, 1459.4 MiB, 1459.4 MiB, 1459.4 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1515.3 MiB, 1515.3 MiB, 1515.3 MiB, 1515.3 MiB, 1515.3 MiB, 1515.3 MiB, 1515.3 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1503.0 MiB, 1503.0 MiB, 1503.0 MiB, 1503.0 MiB, 1503.0 MiB, 1503.0 MiB, 1503.0 MiB]

## Budget evaluation

- Always-on p95 overhead: **2.0%**.
- Sampled p95 overhead: **-1.0%**.
- Always-on startup overhead: **20.8%**.
- Always-on steady-state median memory delta (hard gate): **56 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **57 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
