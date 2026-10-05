# Whole-application worker startup footprint

`WorkerRuntimeFootprintTest` complements the frozen snapshot/cache experiment with
six fresh production Spring JVMs. Each connects over loopback TCP to an actual
Artemis broker running outside the measured process and uses a fresh HSQLDB
database. No production bean is mocked or replaced.

| Configuration | Runtime role | Root consumers | Global catalogue and search |
| --- | --- | --- | --- |
| `CP` | `worker` | CP only | Must remain empty/disabled |
| `ALL_WORKER` | `worker` | All eight roots | Must remain empty/disabled |
| `FULL_CATALOGUE` | `all` | All eight roots | Must load and index all eight roots |

Each configuration runs once with embeddings disabled (`COLD`) and once with the
pinned native model enabled (`NATIVE`). The native worker cases execute actual
384-dimensional inference. The native full-catalogue case also waits for global
node and relation index readiness. These are separate processes, not a claimed
within-process cold-to-warm memory delta.

The harness records post-GC heap, committed/max heap, non-heap, process RSS and
peak RSS, startup time, actual database node/root counts, indexed-node count,
filesystem index bytes, native model load state, mapped ONNX Runtime library and
candidate-cache population. It retains HotSpot `vmNativeMemory summary` output and
the Linux process status alongside each measurement, with NMT total reserved and
committed bytes also in the JSON. The diagnostic command runs through the local
platform MBeanServer, without requiring OS attach permissions. It checks actual
broker consumer counts while the measured application is alive. A CP worker must have no consumer
for any unrelated root and no global index files.

## Reproduce through Maven

Use HotSpot JDK 21 with native-memory tracking and the repository's pinned model
downloader. The existing `onnx` profile owns model provisioning and test selection; there is no
additional CI lane or changed exclusion. Run from the repository root:

```bash
./.github/scripts/download-embedding-model.sh
./mvnw -B -ntp -pl taxonomy-app -am test -Ponnx \
  -Dtest=WorkerRuntimeFootprintTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.footprint.output-directory="$(mktemp -d /tmp/taxonomy-worker-runtime.XXXXXX)"
```

Each application child uses exactly `-Xms128m -Xmx1536m -XX:+UseSerialGC
-XX:ActiveProcessorCount=2 -XX:NativeMemoryTracking=summary`; the harness checks the
reported JVM arguments and removes `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` and
`_JAVA_OPTIONS` from that child's environment. The production application uses
explicit eager Spring initialization, the `hsqldb` profile, filesystem Hibernate
Search indexes, one consumer per configured root and local ONNX with downloads
disabled. The loopback fixture alone disables broker TLS/authentication. These
settings are recorded with the measurements and are not deployment defaults.

Each invocation needs a fresh output directory. A failed case retains its
application log and cannot write the final `evidence.json`. The final file is
written only after all six applications pass readiness, role/index assertions,
subscription checks and clean shutdown. A completed measurement contains the
pinned model and tokenizer SHA-256 digests and source identity.

## Measure a packaged application

The same Maven-owned test can launch a checksum-verified executable application
JAR instead of the current reactor classpath. Build and stage the application
from a known production source commit, then select that artifact explicitly:

```bash
./mvnw -B -ntp -pl taxonomy-app -am package -DskipTests
GITHUB_SHA="$(git rev-parse HEAD)" ./.github/scripts/stage-ui-application.sh
./mvnw -B -ntp -pl taxonomy-app -am test -Ponnx \
  -Dtest=WorkerRuntimeFootprintTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.footprint.application-jar="$PWD/target/ui-application/taxonomy-app-1.4.1-SNAPSHOT.jar" \
  -Dtaxonomy.footprint.output-directory="$(mktemp -d /tmp/taxonomy-worker-runtime.XXXXXX)"
```

The adjacent `manifest.json` must match the JAR filename and actual SHA-256.
Artifact-mode evidence retains that manifest's source commit/tree and hash. The
only added launcher class is the measurement entry point, isolated from other
test configurations. No application classes are overlaid on the packaged JAR.

For comparison across commits that differ only in publication metadata, reproduce
the production-source digest from any checked-out commit with this command. It
includes every tracked `src/main` file and `pom.xml`, using sorted relative paths
and their Git content identities; it deliberately excludes test/documentation
changes. Check `git diff --exit-code HEAD -- '**/src/main/**' '**/pom.xml' pom.xml`
before building, so the measured production sources match the committed inputs.

```bash
python3 - <<'PY'
import hashlib, subprocess
entries = []
for entry in subprocess.check_output(['git', 'ls-tree', '-r', '-z', 'HEAD']).split(b'\0'):
    if not entry:
        continue
    metadata, path = entry.split(b'\t', 1)
    name = path.decode('utf-8')
    if (name == 'pom.xml' or name.endswith('/pom.xml')
            or name.startswith('src/main/') or '/src/main/' in name):
        entries.append((path, metadata.split()[2]))
canonical = b''.join(path + b'\0' + blob + b'\n' for path, blob in sorted(entries))
print(len(entries), hashlib.sha256(canonical).hexdigest())
PY
```

## Interpretation

Startup workers have not received task snapshots, so both worker configurations
are expected to retain no global catalogue, global search index or frozen
candidate vectors. Any heap difference between CP and eight empty workers mainly
reflects their runtime/subscription overhead. The full-catalogue baseline makes
the retained global catalogue and index cost visible. The separate frozen-data
experiment measures task/cache retention after work arrives.

These are single sequential samples, with no hard-coded memory saving or capacity
threshold. RSS includes native allocations and touched pages; JVM native-memory
tracking cannot attribute every ONNX allocation. This test does not measure
Kubernetes pod overhead, external database/server memory, TLS, concurrent work,
steady-state throughput or provider capacity. It does not change or establish the
5 GiB constrained-cluster resource budget.

## Executed result, 2026-10-04

The [retained JSON](evidence/worker-runtime-footprint-2026-10-04.json) contains all
six successful cases. The Maven-owned test passed with **1 test, 0 failures,
0 errors and 0 skips**, taking 282.2 seconds for the matrix; the reactor completed
in 5 minutes 55 seconds. Each process passed HTTP and broker readiness, actual
root-consumer checks, its catalogue/index assertions and clean shutdown.

| Case | Post-GC heap, MiB | RSS, MiB | NMT committed, MiB | Catalogue/indexed nodes | Index bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| `CP_COLD` | 96.64 | 527.13 | 552.35 | 0 | 0 |
| `ALL_WORKER_COLD` | 97.11 | 546.25 | 554.64 | 0 | 0 |
| `FULL_CATALOGUE_COLD` | 210.31 | 785.41 | 898.30 | 2,572 | 519,545 |
| `CP_NATIVE` | 96.99 | 789.37 | 541.15 | 0 | 0 |
| `ALL_WORKER_NATIVE` | 97.05 | 790.79 | 539.73 | 0 | 0 |
| `FULL_CATALOGUE_NATIVE` | 203.29 | 1,299.66 | 900.90 | 2,572 | 4,614,146 |

MiB means 1,048,576 bytes. NMT committed includes the Java heap and tracked JVM
allocations; it is neither RSS nor a complete total for native ONNX allocations.
The native worker cases loaded the pinned model, produced a real 384-dimensional
vector and mapped ONNX Runtime. The native full-catalogue case reached `READY`
after indexing 2,572 nodes and its relation index. All worker cases retained zero
global catalogue/index entries and zero frozen candidate-cache vectors.

The empty CP and eight-root workers have similar heap usage. The larger
full-catalogue baseline and native-process RSS are visible, but these single
samples do not establish a reliable saving, a cold/warm allocation delta or a
production limit. The cgroup OOM-kill counter did not increase during the run.

The measurement used production commit
`816047f4c4620a217edec6fa9c8e5a16460f4658`, tree
`c95c2dd877c3357f13d3818f22da20160ff7eded`, built into a new executable JAR with
SHA-256 `4d786fde1e673d459f4ac070c314a1294be5fb2da7e9189664a3355b58f31441`.
Its **1,564 production source/POM files** have the reproducible digest
`14ed3fce93e3742877453f879eb428641e576e0236250dd9f9c1d454ecb4f6f8`.
This digest allows comparison with a published commit whose Git metadata differs.
The JSON also records harness-source hashes, model/tokenizer hashes, exact JVM
arguments and hashes of the retained NMT/process-status files.
Trailing whitespace in the report/log copies is normalized; both original and
retained hashes are recorded. Ordinary resource
packaging included an early uncommitted version of this document; that text is
retained verbatim and identified separately. There were no application class or
configuration overrides inside the JAR.

The initial application built from the review baseline failed before readiness:
`CommitIndexSearchLifecycle` tried to rebuild a global index while worker mode had
disabled Hibernate Search. Its [failure log](evidence/worker-runtime-footprint-2026-10-04/before-fix-CP_COLD-application.log)
is retained. Production fix `b16edb47a23bc3e5e4a8fe3a50a6846ca2acbe83` is included
in the measured integrated source. No `search-rebuild-empty=false` workaround was
used. Subsequent harness-only corrections shortened its generated fixture
password to BCrypt's supported length and replaced unavailable OS attach with
the local HotSpot diagnostic command before the final successful matrix.
