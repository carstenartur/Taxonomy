# OpenTelemetry performance evidence

Generated: `2026-11-07T10:59:42.447311816Z`  
Java: `25.0.5`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 27257 | 1528.1 | 1538.2 | `cgroup-v2-anon` | 133644.8 | 93 | 102 | 0.00 |
| agent-always-on | 34295 | 1656.3 | 1666.7 | `cgroup-v2-anon` | 151043.6 | 95 | 103 | 48.08 |
| agent-sampled-10-percent | 33222 | 1582.9 | 1593.0 | `cgroup-v2-anon` | 150044.3 | 93 | 101 | 4.20 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1528.1 MiB, 1528.1 MiB, 1528.1 MiB, 1528.1 MiB, 1528.1 MiB, 1528.1 MiB, 1528.1 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1656.3 MiB, 1656.3 MiB, 1656.3 MiB, 1656.3 MiB, 1656.3 MiB, 1656.3 MiB, 1656.3 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1582.9 MiB, 1582.9 MiB, 1582.9 MiB, 1582.9 MiB, 1582.9 MiB, 1582.9 MiB, 1582.9 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.0%**.
- Sampled p95 overhead: **-1.0%**.
- Always-on startup overhead: **25.8%**.
- Always-on steady-state median memory delta (hard gate): **128 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **128 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
