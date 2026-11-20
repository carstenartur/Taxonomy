# OpenTelemetry performance evidence

Generated: `2026-11-20T08:17:45.086442037Z`  
Java: `25.0.5`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 24086 | 1527.9 | 1537.7 | `cgroup-v2-anon` | 113855.0 | 79 | 85 | 0.00 |
| agent-always-on | 30254 | 1662.8 | 1673.4 | `cgroup-v2-anon` | 126901.7 | 79 | 92 | 57.16 |
| agent-sampled-10-percent | 28116 | 1683.0 | 1693.5 | `cgroup-v2-anon` | 122536.3 | 79 | 87 | 6.41 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1527.9 MiB, 1527.9 MiB, 1527.9 MiB, 1527.9 MiB, 1527.9 MiB, 1527.9 MiB, 1527.9 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1662.8 MiB, 1662.8 MiB, 1662.8 MiB, 1662.8 MiB, 1662.8 MiB, 1662.8 MiB, 1662.8 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1683.0 MiB, 1683.0 MiB, 1683.0 MiB, 1683.0 MiB, 1683.0 MiB, 1683.1 MiB, 1683.1 MiB]

## Budget evaluation

- Always-on p95 overhead: **8.2%**.
- Sampled p95 overhead: **2.4%**.
- Always-on startup overhead: **25.6%**.
- Always-on steady-state median memory delta (hard gate): **135 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **136 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
