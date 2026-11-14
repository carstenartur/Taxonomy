# OpenTelemetry performance evidence

Generated: `2026-11-14T12:27:49.680805998Z`  
Java: `25.0.5`  
Common benchmark JVM options: `-Xms1024m -Xmx1024m -XX:+AlwaysPreTouch`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| baseline | 20789 | 1530.3 | 1540.3 | `cgroup-v2-anon` | 107241.4 | 72 | 85 | 0.00 |
| agent-always-on | 27235 | 1671.7 | 1682.6 | `cgroup-v2-anon` | 112013.1 | 72 | 80 | 55.08 |
| agent-sampled-10-percent | 25889 | 1583.2 | 1593.5 | `cgroup-v2-anon` | 106460.1 | 71 | 80 | 6.20 |

### Raw steady-state memory samples

- `baseline` (`cgroup-v2-anon`): [1530.3 MiB, 1530.3 MiB, 1530.3 MiB, 1530.3 MiB, 1530.3 MiB, 1530.3 MiB, 1530.3 MiB]
- `agent-always-on` (`cgroup-v2-anon`): [1671.6 MiB, 1671.7 MiB, 1671.7 MiB, 1671.7 MiB, 1671.7 MiB, 1671.7 MiB, 1671.7 MiB]
- `agent-sampled-10-percent` (`cgroup-v2-anon`): [1583.2 MiB, 1583.2 MiB, 1583.2 MiB, 1583.2 MiB, 1583.2 MiB, 1583.2 MiB, 1583.2 MiB]

## Budget evaluation

- Always-on p95 overhead: **-5.9%**.
- Sampled p95 overhead: **-5.9%**.
- Always-on startup overhead: **31.0%**.
- Always-on steady-state median memory delta (hard gate): **141 MiB**.
- Always-on lifetime peak memory delta (diagnostic): **142 MiB**.
- Lifetime peak requires investigation: **false**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
