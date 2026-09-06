# OpenTelemetry performance evidence

Generated: `2026-09-06T17:05:03.240685904Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 20147 | 1466.2 | 1480.1 | `cgroup-v2-anon` | 138859.2 | 96 | 103 | 0.00 |
| agent-always-on | 25238 | 1517.0 | 1532.7 | `cgroup-v2-anon` | 155649.7 | 98 | 104 | 39.00 |
| agent-sampled-10-percent | 25229 | 1496.3 | 1513.1 | `cgroup-v2-anon` | 143555.2 | 97 | 105 | 2.93 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1466.2 MiB, 1466.2 MiB, 1466.2 MiB, 1466.2 MiB, 1466.2 MiB, 1466.2 MiB, 1466.2 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1517.0 MiB, 1517.0 MiB, 1517.0 MiB, 1517.0 MiB, 1517.0 MiB, 1517.0 MiB, 1517.0 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1496.3 MiB, 1496.3 MiB, 1496.3 MiB, 1496.3 MiB, 1496.3 MiB, 1496.3 MiB, 1496.3 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.0%**.
- Sampled p95 overhead: **1.9%**.
- Always-on startup overhead: **25.3%**.
- Always-on steady-state median memory delta (hard gate): **51 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **53 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
