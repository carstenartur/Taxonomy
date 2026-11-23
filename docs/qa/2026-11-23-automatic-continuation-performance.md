# Automatic continuation performance and fresh provider QA, 23 November 2026

This investigation responds to two requirements: exercise the assistant-served
OpenAI-compatible endpoint again, and improve application work between automatic
analysis steps. Provider waiting time, snapshot processing and complete workflow
duration are separate measurements.

## Fresh provider integration

The baseline is main commit `ef55275aa6f59e4485b92bf2f6a75e5e854c1dbb`
([PR #1592](https://github.com/carstenartur/Taxonomy/pull/1592)).
`ArchitectureDialogueVllmLiveTest` passed with one test, no failures, errors or
skips. The production `CUSTOM_OPENAI` gateway sent two original requests to a
temporary loopback HTTP relay using model name `chat-session-relay`. The assistant
read those requests and wrote fresh answers; both HTTP responses were 200. No
recorded responses or remote-provider fallback were used.

The test uses its existing synthetic editor checkpoint, not a complete portfolio
run. The first response identified `KOI-17` and cited both the visibility rule and
account assignment. The follow-up retained the unresolved approval role as
`offen`, with supporting evidence. This proves that the current transport and
dialogue handling can consume fresh session answers. It is not a standalone vLLM
inference-engine test or a weak-model quality benchmark.

| Measured interval | Time | Boundary |
| --- | ---: | --- |
| JUnit test case | 24.900 s | Setup within the case, HTTP client, gateway, application handling and assertions |
| Relay awaiting the two fresh answers | 22.028 s | Time until each assistant answer appeared in the file spool |
| Test time after subtracting that wait | 2.872 s | Includes setup and assertions; not pure application latency |
| Relay handler work excluding answer waiting | 4.643 ms total | Local request/response and evidence handling |

The first setup attempt failed with connection refused before a completion
reached the relay: separate execution invocations had isolated loopback network
contexts. Starting the relay and Maven in the same invocation resolved the setup
failure without changing application or test timeouts. The relay was stopped
after the successful test.

The request, answer and delivered-response SHA-256 hashes were independently
checked against the retained bytes. Raw requests, answers, timing metadata and
the Maven report remain under `target/live-relay-20261123/`; they are not source
fixtures. The two request hashes are:

- `6c764ad3af97fc8e9bc10079b0c5e7e18ea6ae83f4116f3357b7184e053d6ba5`
- `93cbf67298578267aea8f00c2280051897f1d1b8fa5a22dfe11aacbd7fee6043`

To repeat the existing opt-in test, configure a reachable compatible endpoint and
its model, then run from the repository root with Java 25:

```bash
export CUSTOM_LLM_URL=http://127.0.0.1:PORT/v1/chat/completions
export CUSTOM_LLM_MODEL=chat-session-relay
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest=ArchitectureDialogueVllmLiveTest \
  -Dgroups=real-llm -DexcludedGroups=onnx \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.dialogue.evaluateLive=true -Dmaven.build.cache.enabled=false
```

`PORT` is the current relay's actual port. A session-backed relay requires the
assistant to read and answer each new request while the test is running. The
endpoint must exist in the Maven process's network context; these environment
variables alone do not provision a server. The successful local run used the
environment's Maven proxy/trust configuration and Mockito Java agent in addition
to the unchanged test selection above. See [custom endpoint setup](../dev/custom-llm.md#fresh-session-backed-integration-checks).

## Repeated snapshot processing

The reference architecture scenario completed 254 continuation steps in its
successful [source run](https://github.com/carstenartur/Taxonomy/actions/runs/45153613162).
On the baseline implementation, eligibility GET, locked prepare and worker begin
each fully deserialize the same saved snapshot. That makes at least 762 full
bindings across those steps, before any additional polling. Current journal reads
also deserialize the saved result and original request; this change does not
alter their expiry, flush or recovery behavior.

The final reference archive contains a 22,242,756-byte `snapshot.json`. Its compact
analysis object is 18,929,372 UTF-8 bytes, with SHA-256
`1364b8d8a6116c6cce17e1a5d05d6310f480e7d26806ab943d8ff789074ff233`.
This is the final payload size, not the average size of the earlier 254 snapshots
or a measured database transfer volume.

The archived application log records 6,246 successful gateway responses, each
after the HTTP response arrived. The 6,245 intervals between those responses
span 1,862.268 s. Of those intervals, 255 exceed two seconds and account for
1,741.642 s (93.5%); 252 of these contain the snapshot persistence log markers.
This localizes long gaps to continuation boundaries, but it cannot divide them
into provider waiting, database work, orchestration and QA-client work. The
archive lacks matching start times for most calls. The log does not establish
that snapshot deserialization alone caused those delays.

### Baseline parser measurements

The unchanged policy, evidence-reader and real Hibernate/HSQL tests passed:
66 tests, zero failures, errors or skips. A temporary harness then compared full
`PortfolioJsonCodec.read(Reader, AnalysisResult.class)` binding with a selective
field-reading prototype on exactly the same final JSON. Each of three fresh JVMs
ran 12 warm-up pairs and 31 alternating measured pairs. Every pair compared all
policy inputs used by the experiment.

| Fresh JVM | Full binding median / p95 | Selective prototype median / p95 |
| --- | ---: | ---: |
| 1 | 53.330 / 102.483 ms | 35.439 / 118.226 ms |
| 2 | 53.354 / 99.222 ms | 32.662 / 36.268 ms |
| 3 | 55.238 / 122.729 ms | 34.012 / 38.914 ms |

Full binding allocated 40,972,024–41,011,352 bytes per measured access; the
prototype allocated 98,320 bytes. The prototype is not the production change:
skipping unused DTO fields would also skip their established type validation.
For example, corruption in a graph field could otherwise become accepted by a
continuation read.

A JFR profile of 100 full bindings attributed most sampled work to Jackson string
decoding; score-semantic refreshing accounted for approximately 4% of the
relevant execution samples. This does not support a broader score-calculation
rewrite as the first optimization. The input, temporary harness, JVM logs and JFR
summary are under `target/automatic-performance/`.

### Selected change

The [design](../superpowers/specs/2026-11-23-automatic-continuation-performance-design.md)
uses a bounded memo of immutable continuation inputs after normal full DTO
validation. Each caller still reads its own scoped CLOB completely and checks
its exact content fingerprint. Matching content can reuse the validated inputs;
changed content requires a fresh full bind. Reader and locator cleanup must
succeed before a result is reused or published.

The memo does not retain the full result, graph, trace or JSON. It is limited to
16 entries and a conservative aggregate 2 MiB of compact inputs and keys.
Workspace authority, item/version matching, cancellation, current questions,
provider binding and worker ownership remain fresh checks. The full snapshot
reader remains available for exports and evidence access.

### Real Hibernate/HSQL comparison

The candidate was frozen at `f41fd9786bc924fdb19d7f9cb863fb2d8db99167`. Its unchanged
full `readAnalysis` method provides the reference in the same compiled build.
Three fresh Temurin 25.0.5 JVMs each ran eight warm-up cycles and 25 measured
cycles. Every process used its own file-backed HSQL database with the reference
scenario's JDBC settings, the actual final analysis payload and all six scoped
query identities. Each access used the same long-lived reader bean but a fresh
EntityManager and transaction. Baseline/candidate order alternated between
cycles. The direct measurement JVMs used `-Xms512m -Xmx768m -XX:ActiveProcessorCount=2`,
without Mockito or JaCoCo instrumentation.

Times include opening and completing the transaction. Database setup and fixture
updates are outside the timer. Each cell reports median / nearest-rank p95;
allocations are measured on the caller thread with `ThreadMXBean`. MB is decimal.

| Reader | JVM 1 | JVM 2 | JVM 3 | Median allocation |
| --- | ---: | ---: | ---: | ---: |
| Full reference | 116.316 / 160.878 ms | 115.264 / 181.553 ms | 112.085 / 171.148 ms | 154.55–155.12 MB |
| Candidate, first access | 141.912 / 241.169 ms | 140.772 / 188.556 ms | 147.914 / 201.603 ms | 154.58–155.14 MB |
| Candidate, unchanged content | 76.917 / 149.630 ms | 76.957 / 97.594 ms | 79.104 / 111.185 ms | 113.63 MB |
| Candidate, changed content | 230.456 / 312.221 ms | 232.215 / 268.402 ms | 233.445 / 313.060 ms | 268.19–268.75 MB |

| Reader | SQL SELECTs | UTF-16 code units read | Locators released | Full DTO bindings |
| --- | ---: | ---: | ---: | ---: |
| Full reference | 1 | 18,900,061 | 1 | 1 |
| Candidate, first access | 1 | 18,900,061 | 1 | 1 |
| Candidate, unchanged content | 1 | 18,900,061 | 1 | 0 |
| Candidate, changed content | 2 | 37,800,122 | 2 | 1 |

Actual JDBC statement executions, reader returns, locator releases and codec
bindings were counted and asserted on every iteration. All 75 measured matching
reads reused the same immutable policy inputs after consuming the current CLOB.
All 75 same-length replacements under the same snapshot identity were detected
and fully rebound; the changed status produced the expected changed policy
decision. The validated inputs account for 35,074 bytes; including the measured
key gives 36,026 bytes under the conservative retention formula.

Matching accesses saved 32.981–39.399 ms of median time within each JVM
(29.4–33.9%) and approximately 41 MB of allocation. The first access costs an
additional 25.508–35.829 ms because it both validates and fingerprints the
payload. Changed content costs a second query and read. The gain therefore
depends on repeated use of the same content; a one-off read is slower. The
workflow regression below confirms reuse across its continuation phases.

The remaining approximately 113.63 MB of allocation on a matching HSQL access
is material: every call still consumes the complete CLOB. This comparison does
not measure the hit rate of an entire deployment, earlier smaller snapshots,
provider latency, JDBC network traffic or complete workflow duration.

A separate, bounded JFR allocation profile checked this remaining cost across
24 matching reads. It measured 113,634,717 allocated bytes per access, one query
and no full bindings. Of 2,394 relevant allocation samples, 47.54% of the weighted
bytes were HSQL `LobManager.getBytesNormal` byte arrays, 26.85% were
`LobStoreRAFile.getBlockBytes` byte arrays and 25.59% were
`ArrayUtil.byteArrayToChars` character arrays. The fingerprint reader buffer
accounted for 0.02%. This points to the file-backed HSQL driver's LOB reading and
conversion, not another full binding or a large application buffer. No inflater
allocation was observed. It does not establish the same profile for PostgreSQL,
SQL Server or Oracle. Raw evidence is `hsql-warm-only.jfr`, `hsql-warm-only.log`
and `hsql-warm-allocation-summary.log` under the same measurement directory.

The temporary harness is `target/automatic-performance/HsqlContinuationBench.java`.
After compiling the candidate reactor and writing its Surefire runtime classpath
to `target/automatic-performance/classpath.txt`, run from that directory with
Java 25:

```bash
sh run-hsql-ab.sh /absolute/path/to/input-analysis.json 8 25 3
```

`run-hsql-ab.sh` compiles the harness and launches the three JVMs with the options
above. `hsql-ab-report.md` retains the exact input-extraction and classpath
commands. Raw results are `hsql-ab-1.log`, `hsql-ab-2.log` and `hsql-ab-3.log`; the
short smoke run is excluded from the table. The input must match the recorded
SHA-256 above.

## Candidate regression verification

Implementation commit `f41fd9786bc924fdb19d7f9cb863fb2d8db99167` passed 107 focused
tests with no failures, errors or skips: 20 policy, 43 evidence-reader, 21 real
Hibernate/HSQL payload-reader and 23 HSQL continuation-workflow tests.

The new workflow regression first failed on the unchanged implementation after
all functional assertions passed: two eligibility reads, prepare and worker begin
had performed four full snapshot bindings. The same successful workflow now
performs one. Other regressions cover changed and corrupt content, database scope
and factory identity, memory limits, concurrent access, current journal state,
legacy inputs, model binding and reader/locator failures.

```bash
./mvnw -B -ntp -pl taxonomy-portfolio -am test \
  -Dtest=ProjectAnalysisContinuationPolicyTest,PortfolioSnapshotEvidenceReaderTest,PortfolioSnapshotPayloadReadTest,ProjectAnalysisBudgetContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false
```

The local run used Java 25, the environment's Maven proxy/trust configuration and
Mockito's Java agent. These focused tests supplement the complete hosted
verification. They do not replace browser, external-database, security, coverage
or full-scenario gates. No existing timeout, provider budget, test assertion,
verification selector or quality threshold was relaxed.
