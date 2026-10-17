# OpenTelemetry performance evidence

Generated: `2026-10-17T16:51:02.565467467Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 16988 | 1456.2 | 1471.8 | `cgroup-v2-anon` | 91868.8 | 62 | 67 | 0.00 |
| agent-always-on | 21193 | 1514.6 | 1537.5 | `cgroup-v2-anon` | 103325.9 | 63 | 70 | 47.08 |
| agent-sampled-10-percent | 19895 | 1494.5 | 1524.3 | `cgroup-v2-anon` | 97642.0 | 63 | 70 | 5.29 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1456.2 MiB, 1456.2 MiB, 1456.2 MiB, 1456.2 MiB, 1456.2 MiB, 1456.2 MiB, 1456.2 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1514.6 MiB, 1514.6 MiB, 1514.6 MiB, 1514.6 MiB, 1514.6 MiB, 1514.6 MiB, 1514.6 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1494.5 MiB, 1494.5 MiB, 1494.5 MiB, 1494.5 MiB, 1494.5 MiB, 1494.5 MiB, 1494.5 MiB]

## Budget evaluation

- Always-on p95 overhead: **4.5%**.
- Sampled p95 overhead: **4.5%**.
- Always-on startup overhead: **24.8%**.
- Always-on steady-state median memory delta (hard gate): **58 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **66 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
