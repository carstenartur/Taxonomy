# Application and CI performance, 22 November 2026

Measurements use the application in PR #1588 before the performance changes (tree `319748c10c3800f3b325f815279ffa2d92917cbb`). The table gives local medians for the same data and operation on each implementation.

| Operation | Baseline | With changes | Workload |
|---|---:|---:|---|
| Read the public catalogue tree | 123.37 ms, 162 SQL statements | 60.57 ms, 2 SQL statements | 2,572 bilingual catalogue nodes and 37 reference relationships, HSQLDB |
| Select a node with the architecture lens active | 6.55 ms | 1.10 ms | Chromium 149, 1,500 nodes and 4,500 relationships, with 200 cards and 400 relationships visible |
| Render the architecture layer summary | 89.35 ms | 29.65 ms | Same graph, including DOM update and layout |
| Admit a new mail request and persist it | 489.25 µs | 129.40 µs | 10,000 retained completed requests, 400 fresh admissions after 100 warm-ups, including fsync |
| Read mail job status | 174.35 µs | 0.50 µs | 10,000 retained completed requests plus one waiting request |

The catalogue benchmark alternates implementations in the same JVM for 11 measured rounds after warm-up. It also checks the entire serialized result and that foreign and workspace relationships stay outside the shared catalogue. Reproduce with the repository wrapper.

```sh
./mvnw -pl taxonomy-app -am -Dtest=CatalogueTreeReadIT -Dsurefire.failIfNoSpecifiedTests=false -Dtaxonomy.benchmark.catalogue-tree=true test
```

The browser runs alternated baseline and candidate. The selection p95 fell from 10.5 to 1.7 ms across 48 samples per version. Across 24 selections the renderer created 9,600 replacement SVG relationships before the change and zero after it. The summary p95 fell from 153.9 to 44.6 ms across 24 samples per version. The mail queue rebuilds its scheduling indexes from the persisted records at startup. The admission p95, including persistence, fell from 751.19 to 249.32 µs on the same filesystem.

## GitHub Actions

The new distribution moves 24 suites among existing Maven test groups. The replay keeps measured runner start offsets and uses the original JUnit suite durations. The comparison measures when coverage aggregation could start. It cannot predict runner availability, Spring context reuse, or total time to merge.

| Successful source run | Projected earlier start of coverage aggregation |
|---|---:|
| [44983110260](https://github.com/carstenartur/Taxonomy/actions/runs/44983110260) | 7 min 30 s |
| [44977735812](https://github.com/carstenartur/Taxonomy/actions/runs/44977735812) | 8 min 04 s |
| [44976725043](https://github.com/carstenartur/Taxonomy/actions/runs/44976725043) | 7 min 55 s |

Before redistribution the core runner took 46–51 minutes in those runs. The domain-integration and runtime runners took 10–15 minutes. The Maven test catalogue and JUnit tags continue to assign each moved suite to one owner. Coverage aggregation still requires every lane.

A new commit to a PR also cancels its superseded screenshot job. For example, the screenshot job for PR #1587 ran for a further 8 min 39 s after its successor was submitted on 22 November. Manual screenshot publication retains its original completion policy. [Earlier run](https://github.com/carstenartur/Taxonomy/actions/runs/44990582116) · [Next revision](https://github.com/carstenartur/Taxonomy/actions/runs/44991185675)
