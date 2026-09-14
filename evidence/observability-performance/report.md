# OpenTelemetry performance evidence

Generated: `2026-09-14T12:09:33.061207893Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 22257 | 1455.7 | 1480.6 | `cgroup-v2-anon` | 141315.3 | 97 | 103 | 0.00 |
| agent-always-on | 27234 | 1508.5 | 1525.6 | `cgroup-v2-anon` | 147365.5 | 98 | 104 | 39.00 |
| agent-sampled-10-percent | 27226 | 1509.9 | 1534.5 | `cgroup-v2-anon` | 155870.4 | 98 | 108 | 4.39 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1455.7 MiB, 1455.7 MiB, 1455.7 MiB, 1455.7 MiB, 1455.7 MiB, 1455.7 MiB, 1455.7 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1508.5 MiB, 1508.5 MiB, 1508.5 MiB, 1508.5 MiB, 1508.5 MiB, 1508.5 MiB, 1508.5 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1509.9 MiB, 1509.9 MiB, 1509.9 MiB, 1509.9 MiB, 1509.9 MiB, 1509.9 MiB, 1509.9 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.0%**.
- Sampled p95 overhead: **4.9%**.
- Always-on startup overhead: **22.4%**.
- Always-on steady-state median memory delta (hard gate): **53 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **45 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
