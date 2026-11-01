# OpenTelemetry performance evidence

Generated: `2026-11-01T22:10:49.128479820Z`  
Java: `25.0.5`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 26146 | 1448.3 | 1458.0 | `cgroup-v2-anon` | 129103.5 | 87 | 95 | 0.00 |
| agent-always-on | 34293 | 1609.9 | 1619.9 | `cgroup-v2-anon` | 139061.5 | 87 | 97 | 47.11 |
| agent-sampled-10-percent | 34281 | 1685.3 | 1695.7 | `cgroup-v2-anon` | 148768.4 | 88 | 99 | 4.11 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1448.3 MiB, 1448.3 MiB, 1448.3 MiB, 1448.3 MiB, 1448.3 MiB, 1448.3 MiB, 1448.3 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1609.9 MiB, 1609.9 MiB, 1609.9 MiB, 1609.9 MiB, 1609.9 MiB, 1609.9 MiB, 1609.9 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1685.3 MiB, 1685.3 MiB, 1685.3 MiB, 1685.3 MiB, 1685.3 MiB, 1685.3 MiB, 1685.3 MiB]

## Budget evaluation

- Always-on p95 overhead: **2.1%**.
- Sampled p95 overhead: **4.2%**.
- Always-on startup overhead: **31.2%**.
- Always-on steady-state median memory delta (hard gate): **162 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **162 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
