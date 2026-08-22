# OpenTelemetry performance evidence

Generated: `2026-08-22T16:47:18.429940409Z`  
Java: `21.0.12`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 21180 | 1416.9 | 1436.5 | `cgroup-v2-anon` | 128819.7 | 87 | 94 | 0.00 |
| agent-always-on | 28272 | 1484.9 | 1500.2 | `cgroup-v2-anon` | 147910.5 | 88 | 97 | 39.00 |
| agent-sampled-10-percent | 28219 | 1488.1 | 1501.5 | `cgroup-v2-anon` | 127710.3 | 88 | 95 | 4.88 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1416.9 MiB, 1416.9 MiB, 1416.9 MiB, 1416.9 MiB, 1416.9 MiB, 1416.9 MiB, 1416.9 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1484.9 MiB, 1484.9 MiB, 1484.9 MiB, 1484.9 MiB, 1484.9 MiB, 1484.9 MiB, 1484.9 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1488.1 MiB, 1488.1 MiB, 1488.1 MiB, 1488.1 MiB, 1488.1 MiB, 1488.1 MiB, 1488.1 MiB]

## Budget evaluation

- Always-on p95 overhead: **3.2%**.
- Sampled p95 overhead: **1.1%**.
- Always-on startup overhead: **33.5%**.
- Always-on steady-state median memory delta (hard gate): **68 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **64 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
