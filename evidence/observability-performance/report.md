# OpenTelemetry performance evidence

Generated: `2026-09-26T09:48:42.578775008Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 16938 | 1439.1 | 1472.6 | `cgroup-v2-anon` | 94570.4 | 67 | 74 | 0.00 |
| agent-always-on | 21617 | 1500.7 | 1525.9 | `cgroup-v2-anon` | 114613.7 | 71 | 85 | 40.08 |
| agent-sampled-10-percent | 21186 | 1503.1 | 1537.1 | `cgroup-v2-anon` | 111646.8 | 68 | 84 | 5.50 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1439.1 MiB, 1439.1 MiB, 1439.1 MiB, 1439.1 MiB, 1439.1 MiB, 1439.1 MiB, 1439.1 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1500.7 MiB, 1500.7 MiB, 1500.7 MiB, 1500.7 MiB, 1500.7 MiB, 1500.7 MiB, 1500.7 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1503.1 MiB, 1503.1 MiB, 1503.1 MiB, 1503.1 MiB, 1503.1 MiB, 1503.1 MiB, 1503.1 MiB]

## Budget evaluation

- Always-on p95 overhead: **14.9%**.
- Sampled p95 overhead: **13.5%**.
- Always-on startup overhead: **27.6%**.
- Always-on steady-state median memory delta (hard gate): **62 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **53 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **true**.
- Hard regression budget exceeded: **false**.
