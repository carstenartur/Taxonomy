# OpenTelemetry performance evidence

Generated: `2026-11-28T05:10:37.756122603Z`  
Java: `25.0.5.1`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 22094 | 1447.7 | 1457.6 | `cgroup-v2-anon` | 118900.4 | 77 | 90 | 0.00 |
| agent-always-on | 28921 | 1605.0 | 1615.5 | `cgroup-v2-anon` | 125955.8 | 78 | 88 | 57.13 |
| agent-sampled-10-percent | 28219 | 1617.1 | 1627.4 | `cgroup-v2-anon` | 112466.9 | 77 | 87 | 7.15 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1447.7 MiB, 1447.7 MiB, 1447.7 MiB, 1447.7 MiB, 1447.7 MiB, 1447.7 MiB, 1447.7 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1604.9 MiB, 1605.0 MiB, 1605.0 MiB, 1605.0 MiB, 1604.9 MiB, 1605.0 MiB, 1604.9 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1617.1 MiB, 1617.1 MiB, 1617.1 MiB, 1617.1 MiB, 1617.1 MiB, 1617.1 MiB, 1617.1 MiB]

## Budget evaluation

- Always-on p95 overhead: **-2.2%**.
- Sampled p95 overhead: **-3.3%**.
- Always-on startup overhead: **30.9%**.
- Always-on steady-state median memory delta (hard gate): **157 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **158 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
