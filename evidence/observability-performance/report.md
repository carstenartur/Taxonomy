# OpenTelemetry performance evidence

Generated: `2026-09-19T15:40:17.897865358Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 22237 | 1441.6 | 1455.1 | `cgroup-v2-anon` | 128604.4 | 86 | 93 | 0.00 |
| agent-always-on | 27226 | 1486.7 | 1516.0 | `cgroup-v2-anon` | 149938.1 | 89 | 97 | 40.00 |
| agent-sampled-10-percent | 27229 | 1495.8 | 1511.3 | `cgroup-v2-anon` | 143800.8 | 87 | 96 | 2.50 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1441.6 MiB, 1441.6 MiB, 1441.6 MiB, 1441.6 MiB, 1441.6 MiB, 1441.6 MiB, 1441.6 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1486.7 MiB, 1486.7 MiB, 1486.7 MiB, 1486.7 MiB, 1486.7 MiB, 1486.7 MiB, 1486.7 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1495.8 MiB, 1495.8 MiB, 1495.8 MiB, 1495.8 MiB, 1495.8 MiB, 1495.8 MiB, 1495.8 MiB]

## Budget evaluation

- Always-on p95 overhead: **4.3%**.
- Sampled p95 overhead: **3.2%**.
- Always-on startup overhead: **22.4%**.
- Always-on steady-state median memory delta (hard gate): **45 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **61 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
