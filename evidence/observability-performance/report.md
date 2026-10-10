# OpenTelemetry performance evidence

Generated: `2026-10-10T08:39:30.635159278Z`  
Java: `21.0.12.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 25265 | 1476.5 | 1502.1 | `cgroup-v2-anon` | 144771.2 | 88 | 98 | 0.00 |
| agent-always-on | 31238 | 1510.0 | 1529.5 | `cgroup-v2-anon` | 159080.5 | 90 | 99 | 47.08 |
| agent-sampled-10-percent | 31248 | 1510.2 | 1524.1 | `cgroup-v2-anon` | 145889.2 | 88 | 96 | 4.70 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1476.6 MiB, 1476.5 MiB, 1476.6 MiB, 1476.5 MiB, 1476.6 MiB, 1476.5 MiB, 1476.5 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1510.0 MiB, 1510.0 MiB, 1510.0 MiB, 1510.0 MiB, 1510.0 MiB, 1510.0 MiB, 1510.0 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1510.2 MiB, 1510.2 MiB, 1510.2 MiB, 1510.2 MiB, 1510.2 MiB, 1510.2 MiB, 1510.2 MiB]

## Budget evaluation

- Always-on p95 overhead: **1.0%**.
- Sampled p95 overhead: **-2.0%**.
- Always-on startup overhead: **23.6%**.
- Always-on steady-state median memory delta (hard gate): **33 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **27 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
