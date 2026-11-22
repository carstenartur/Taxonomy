# Application and CI performance, 22 November 2026

Measurements use the application in PR #1588 before the performance changes (tree `319748c10c3800f3b325f815279ffa2d92917cbb`). The table gives local medians for the same data and operation on each implementation.

| Operation | Baseline | With changes | Workload |
|---|---:|---:|---|
| Read the public catalogue tree | 123.37 ms, 162 SQL statements | 60.57 ms, 2 SQL statements | 2,572 bilingual catalogue nodes and 37 reference relationships, HSQLDB |
| Select a node with the architecture lens active | 6.55 ms | 1.10 ms | Chromium 149, 1,500 nodes and 4,500 relationships, with 200 cards and 400 relationships visible |
| Render the architecture layer summary | 89.35 ms | 29.65 ms | Same graph, including DOM update and layout |
| Admit a new mail request and persist it | 489.25 µs | 129.40 µs | 10,000 retained completed requests, 400 fresh admissions after 100 warm-ups, including fsync |
| Read mail job status | 174.35 µs | 0.50 µs | 10,000 retained completed requests plus one waiting request |
| Read durable Artemis analysis progress | 32.65–45.58 ms | 15.66–18.23 ms | Persisted bilingual catalogue with 2,572 nodes, eight roots and changing scoring results |

The catalogue benchmark alternates implementations in the same JVM for 11 measured rounds after warm-up. It also checks the entire serialized result and that foreign and workspace relationships stay outside the shared catalogue. Reproduce with the repository wrapper.

```sh
./mvnw -pl taxonomy-app -am -Dtest=CatalogueTreeReadIT -Dsurefire.failIfNoSpecifiedTests=false -Dtaxonomy.benchmark.catalogue-tree=true test
```

The browser runs alternated baseline and candidate. The selection p95 fell from 10.5 to 1.7 ms across 48 samples per version. Across 24 selections the renderer created 9,600 replacement SVG relationships before the change and zero after it. The summary p95 fell from 153.9 to 44.6 ms across 24 samples per version. The mail queue rebuilds its scheduling indexes from the persisted records at startup. The admission p95, including persistence, fell from 751.19 to 249.32 µs on the same filesystem.

The durable progress benchmark alternated baseline and candidate, with 25 warm-ups and 45 measured snapshots in each of four runs. All serialized progress snapshots matched across the runs. Median allocation per snapshot went from 54.50 MiB to 34.17 MiB. The cache reuses a catalogue index only when the retained JSON matches exactly and still passes operation and taxonomy-root validation.

## GitHub Actions

The new distribution moves 24 suites among existing Maven test groups. The replay keeps measured runner start offsets and uses the original JUnit suite durations. The comparison measures when coverage aggregation could start. It cannot predict runner availability, Spring context reuse, or total time to merge.

| Successful source run | Projected earlier start of coverage aggregation |
|---|---:|
| [44983110260](https://github.com/carstenartur/Taxonomy/actions/runs/44983110260) | 7 min 30 s |
| [44977735812](https://github.com/carstenartur/Taxonomy/actions/runs/44977735812) | 8 min 04 s |
| [44976725043](https://github.com/carstenartur/Taxonomy/actions/runs/44976725043) | 7 min 55 s |

Before redistribution the core runner took 46–51 minutes in those runs. The domain-integration and runtime runners took 10–15 minutes. The Maven test catalogue and JUnit tags continue to assign each moved suite to one owner. Coverage aggregation still requires every lane.

A new commit to a PR also cancels its superseded screenshot job. For example, the screenshot job for PR #1587 ran for a further 8 min 39 s after its successor was submitted on 22 November. Manual screenshot publication retains its original completion policy. [Earlier run](https://github.com/carstenartur/Taxonomy/actions/runs/44990582116) · [Next revision](https://github.com/carstenartur/Taxonomy/actions/runs/44991185675)

The scope planner sent PR #1589 through full verification although its two changed files only documented cloning and reproducibility. The updated planner selected the documentation lane on the actual GitHub merge tree of that PR. This lane still packages the application and verifies repository documentation and source-bound evidence. A change to the architecture showcase, module inventory or deployment documentation continues to require complete verification. Both scope-planning jobs use shallow checkouts with the PR merge commit and its parents. If the exact event base is unavailable, the planner selects full verification. The previous scope checkouts took 12–22 seconds in four recent jobs, and the new checkout has yet to be timed on GitHub. [#1589 workflow](https://github.com/carstenartur/Taxonomy/actions/runs/45014111153)

The 22 November hosted core jobs also exposed a separate regression. Core verification on #1587 took 92 min 08 s, including 90 min 36 s in the Maven step. The corresponding main job took 32 min 42 s, including 31 min 29 s in Maven. On #1587 the application module forked a new JVM for every core test class to contain retained state. GitHub queue time is additional. In the same PR run, the scope planner waited 46 min 42 s before starting. [PR core job](https://github.com/carstenartur/Taxonomy/actions/runs/45016284393/job/137226114613) · [Main core job](https://github.com/carstenartur/Taxonomy/actions/runs/45035102501/job/137247170699)

Five independent plugin builds in #1587 hit Maven Central's HTTP 429 limit while resolving the same Maven plugin. The packaging test now lets each child reuse resolved third-party dependencies from the parent Maven repository. Each child still receives its own SDK repository built from the reactor outputs. Two representative plugin projects passed fully offline and the other two passed while retrieving missing external dependencies, with 220 plugin tests in total. [Packaging job](https://github.com/carstenartur/Taxonomy/actions/runs/45016284393/job/137226114645)

In the failed architecture scenario, the runner had finished 248 continuation steps before its 40-minute deadline. Replaying its 22,176,981-byte saved snapshot on Java 25, using six alternating warmed measurements per version, reduced the median time spent validating full snapshot contents per continuation from 285.2 to 115.5 ms. The original and new snapshots are still independently read and verified. Selecting the least advanced eligible pass first also avoids 127 redundant eligibility requests over a balanced 254-step run. These measurements cover part of the scenario. [Scenario job](https://github.com/carstenartur/Taxonomy/actions/runs/45016651206/job/137190298159)

The OpenAI-compatible provider check also created a JDK HTTP selector thread whenever Spring constructed the bean. In a Java 25 replay with 40 Spring contexts created and closed, 40 selector threads survived while references to the closed contexts were retained. Creating the client on the first probe and closing it with the context reduced the surviving threads to zero, both with idle contexts and after actual HTTP probes.

The core profile runs two bounded groups of unit tests. On Java 25 with a 245 MiB heap, 319 security and UI tests from 34 classes took 63.28 s in separate processes and 19.01 s in one JVM. The second group includes 426 feature and in-process scenario contract tests from 36 classes. Using Maven Surefire on the same 745 tests, the 70 isolated JVMs took 162.40 s and the two groups took 51.62 s. With JaCoCo instrumentation the two groups took 62.79 s. All three runs produced the same test cases in 70 XML reports, with no skips or failures. The core evidence verifier accepted the combined coverage from both JVMs. The two groups use separate JVMs, removing a total of 68 startups. A Maven/Surefire execution check verified the class selections and process identities in the core profile, ordinary builds and explicit single-test runs. The effect on total hosted core time still needs measurement.
