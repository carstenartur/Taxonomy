# OpenTelemetry performance evidence

Generated: `2026-09-12T18:53:10.596025452Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 16258 | 1433.2 | 1460.3 | `cgroup-v2-anon` | 94895.0 | 64 | 71 | 0.00 |
| agent-always-on | 20487 | 1492.7 | 1511.9 | `cgroup-v2-anon` | 104940.5 | 66 | 77 | 39.00 |
| agent-sampled-10-percent | 18979 | 1526.8 | 1544.2 | `cgroup-v2-anon` | 103772.9 | 66 | 73 | 2.93 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1444.9 MiB, 1444.9 MiB, 1444.9 MiB, 1433.2 MiB, 1433.2 MiB, 1433.2 MiB, 1433.2 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1492.7 MiB, 1492.7 MiB, 1492.7 MiB, 1492.7 MiB, 1492.7 MiB, 1492.7 MiB, 1492.7 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1526.9 MiB, 1526.8 MiB, 1526.8 MiB, 1526.8 MiB, 1526.8 MiB, 1526.8 MiB, 1526.8 MiB]

## Budget evaluation

- Always-on p95 overhead: **8.5%**.
- Sampled p95 overhead: **2.8%**.
- Always-on startup overhead: **26.0%**.
- Always-on steady-state median memory delta (hard gate): **59 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **52 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
