# OpenTelemetry performance evidence

Generated: `2026-08-08T08:22:30.622700765Z`  
Java: `21.0.11`  
Application image built and runtime-prewarmed before timing: `true`  
Workload: 80 requests to `/api/relations` after 12 warm-up requests.

| Mode | Startup ms | Memory peak MiB | CPU µs/request | p50 ms | p95 ms | Spans/request |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 15979 | 963.1 | 101536.3 | 73 | 79 | 0.00 |
| agent-always-on | 19881 | 1034.6 | 109075.7 | 74 | 80 | 22.00 |
| agent-sampled-10-percent | 19044 | 971.4 | 101322.1 | 74 | 80 | 1.93 |

## Budget evaluation

- Always-on p95 overhead: **1.3%**.
- Sampled p95 overhead: **1.3%**.
- Always-on startup overhead: **24.4%**.
- Always-on memory delta: **72 MiB**.
- More than 10.0% p95 overhead requires investigation: **false**.
- Hard regression budget exceeded: **false**.
