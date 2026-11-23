# Packed portfolio snapshot storage QA

## Selected implementation and result

The final candidate uses GZIP `BEST_SPEED` for opt-in portfolio snapshot writes and bulk UTF-16 pair conversion in the existing v1 reader. The ordinary archive packer and streamed journal writer keep their prior compression policy. The frame, exact decoded text, bounds, CRC/SHA checks and complete stored-stream fingerprint remain unchanged. Packed writes default to **false**.

Three fresh JVMs on the final product implementation reduced elapsed time for the measured write-plus-continuation-read cases. Each case begins with an actual row write, one cold continuation read, then zero, one or two verified memo hits. First full/cold reads remain slower than raw; one- and two-read mixes also consume more calling-thread CPU. Activation therefore depends on the deployment's actual access pattern and resource constraints, after the compatible-reader rollout. This is controlled file-HSQL evidence for one final snapshot, not an application-wide or external-driver speedup.

| Actual operation | Raw elapsed p50 (ms) | Fast v1 elapsed p50 (ms) | Per-fork elapsed p50 change (ms) | Per-fork thread CPU p50 change (ms) |
| --- | ---: | ---: | ---: | ---: |
| Full DTO | 112.0–119.5 | 174.4–176.0 | +55.9 to +64.0 | +62.4 to +67.9 |
| Continuation cold | 137.8–144.4 | 192.0–199.2 | +49.1 to +59.5 | +53.7 to +60.4 |
| Continuation hit | 76.4–77.8 | 32.5–34.8 | −44.5 to −41.6 | −46.8 to −44.5 |
| Same-key changed | 222.5–225.3 | 222.4–228.5 | −3.0 to +6.0 | +3.1 to +14.9 |
| Write, including serialization and transaction | 771.6–812.4 | 566.4–588.0 | −226.0 to −194.9 | +41.8 to +46.9 |
| Write + 1 read | 906.0–949.1 | 761.6–779.9 | −173.1 to −144.4 | +98.1 to +99.9 |
| Write + 2 reads | 985.0–1037.2 | 792.2–820.2 | −232.5 to −190.4 | +48.6 to +58.6 |
| Write + 3 reads | 1075.4–1121.8 | 822.5–854.0 | −288.1 to −248.5 | −3.4 to +13.0 |

Ranges span the independently computed percentile from each of three processes. Changes subtract raw and candidate percentiles within each process; they are not percentiles of individual paired differences. The controlled setup and operation order below are identical to the initial baseline. The source probe is unchanged, and its classpath was checked to load the final production codec and scoped readers, with no experimental shadows. Source implementation commit: `32c24ccbee8bd0572d26ca0edb6a5014428c2658`; the exact input and JVM settings are given below.

The final original frame is **6,943,239 UTF-16 units**, 63.3% fewer than the 18,900,061-unit raw input and 68.1% more than the initial default-effort frame. The independently encoded changed frame has 6,943,247 units. New writes after DTO serialization held 6,943,239, 6,943,243 and 6,943,235 units across the three JVMs. Each fork verifies exact within-process text and physical counts; normalized serialized bytes were not compared across processes. Every full/cold/hit reads all stored units through one locator, using 27 JDBC range streams instead of 73 for raw. Changed content performs two complete passes of the changed value. Binds, scoping, close/free and commit counts remain as in the initial protocol.

| Actual operation | Raw elapsed p95 (ms) | Fast v1 elapsed p95 (ms) | Per-fork allocation p50 change (decimal MB) |
| --- | ---: | ---: | ---: |
| Full DTO | 146.8–150.9 | 190.7–215.5 | −15.05 |
| Continuation cold | 178.3–182.6 | 221.4–241.2 | −15.05 |
| Continuation hit | 90.2–102.4 | 42.7–47.2 | −71.83 |
| Same-key changed | 247.1–265.2 | 260.3–292.4 | −86.88 |
| Write | 874.9–963.2 | 648.0–669.1 | +43.16 |
| Write + 1 read | 1047.2–1147.7 | 834.7–867.1 | +28.11 |
| Write + 2 reads | 1127.6–1255.2 | 867.3–916.1 | −43.72 |
| Write + 3 reads | 1213.0–1345.9 | 899.5–963.8 | −115.55 |

Fast serialization plus packing takes 299.2–303.0 ms p50 and 291.4–297.4 ms calling-thread CPU, versus the initial default-effort 931.3–944.4 ms and 922.5–931.6 ms. The larger fast frames increase the packed transaction and read cost relative to that default-effort baseline. An independent bulk-reader-only screen retained its 4,131,031-unit frame and reduced full/cold p50 to 146.5/155.6 ms, but still lost the write/read mix because encoding dominated. These two separate exploratory screens preceded the selected combined three-process verification; their timings are not combined statistically with it.

Correctness verification executed 384 cases across 20 named codec, portfolio, checkpoint and archive suites, with zero failures, errors or skips. The strengthened streaming-tail assertions subsequently passed all 12 cases. All three final probe processes passed exact text/DTO/policy equality, same-length corruption rejection, physical sizes, scoped query/bind counts, locator lifecycle and database cleanup. Actual PostgreSQL/OID, SQL Server and Oracle execution remains a distinct release gate described below.

## Lossless reference experiment: retain v1

A separate scratch experiment removed lexically identical root and `architectureView` report/coverage values, retained the earlier exact UTF-16 slice and reconstructed the complete original text before binding. The reserved experimental frame validated its bounded header, inner v1 frame, full original digest and canonical JSON paths. It used the selected `packFast` effort for the remainder and the actual production scoped JDBC reader. The control remained the final fast-v1 implementation at local revision `70cf28dda90411faafd7b535d6ed7268b4d332a6`, tree `1229f78ef7a44c0eb4b425c6634c9dc54b199edb`.

Each policy received one fresh 8-warmup/25-pair file-HSQL screen with the same JVM limits and late fixture. Each pair used identical saved, changed and DTO-normalized input within that JVM. The conservative writers charged both the candidate encoding and whole-v1 encoding needed for their smaller-than-v1 selection. The separately authorized direct combined writer charged one reference encoding and compared its complete frame with raw length; on another eligible input it can exceed the counterfactual v1 frame. Positive changes below mean slower. These are actual percentiles of per-pair differences, unlike the marginal-percentile differences in the preceding three-process table.

| Experimental policy | Stored UTF-16 units | Reduction from fast v1 | Paired p50 change: write + 1 / 2 / 3 reads |
| --- | ---: | ---: | --- |
| Conservative report reference | 4,967,389 | 28.46% | +225 / +222 / +205 ms |
| Conservative coverage reference | 6,923,235 | 0.29% | +441 / +445 / +444 ms |
| Conservative combined references | 4,946,782 | 28.75% | +249 / +240 / +231 ms |
| Direct combined references | 4,946,782 | 28.75% | −7.7 / −9.7 / −19.8 ms |

The direct writer improved paired median write-only wall time by 64.5 ms. Its mixed-operation median gains were smaller than the selected-v1 historical control spread of 18.4/28.1/31.4 ms, and the paired p95 changes were regressions of 62.7/51.8/42.4 ms. Those same mixes added 36.6/27.9/20.1 ms of median caller CPU and 213.5/201.5/189.5 MB of median allocation. Full-read p95 rose from 196.7 to 259.7 ms, cold-read p95 from 198.7 to 285.4 ms, and changed-row p95 from 258.3 to 303.0 ms. The smaller stored representation did not establish an incremental net gain, so no policy qualified for three-process promotion. The selected product format remains v1.

All 69 bounded codec vector groups/subvectors and all four actual JDBC screens passed. A same-ID, same-length outer-hash mutation consumed two complete corrupt locators, closed/freed every range and locator, invoked no DTO binder and published no replacement memo. Restoring the original and repeating the changed row reused each content's own verified memo with zero expansion on hits. An injected real reader failure propagated with full cleanup and a subsequent cold retry. Production build identity matched all 39 checked classes, including the portable streaming reader; only the scratch probe and reference-dispatch adapter shadowed classes. The original fixture and six earlier timing logs remained byte-identical.

The prototype covered one late snapshot and file-HSQL. Its broader public-reader, archive and external-driver compatibility was not implemented because the performance screen did not support advancing the format. Earlier/middle snapshots, the normal automatic access mix and external-provider latency remain outside these measurements. The four raw screens, per-pair values, source diffs, codec vectors and source/class hashes are retained in the experiment evidence, separately from the product test results.

## Reproduction

Run from the repository root with Java 25. Use the final analysis fixture from the continuation evidence bundle in place. It is 18,929,372 UTF-8 bytes with SHA-256 `1364b8d8a6116c6cce17e1a5d05d6310f480e7d26806ab943d8ff789074ff233`.

```sh
./mvnw -B -pl taxonomy-portfolio -am test \
  -Dtest=PortfolioSnapshotEvidenceReaderTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false
fixture=/workspace/scratch/2f52288a8644/artifacts/taxonomy-continuation-evidence-2026-11-23/input-analysis.json
sha256sum "$fixture"
probe_cp=$(python3 -c 'import xml.etree.ElementTree as E; r=E.parse("taxonomy-portfolio/target/surefire-reports/TEST-com.taxonomy.portfolio.service.PortfolioSnapshotEvidenceReaderTest.xml"); print(next(p.get("value") for p in r.findall(".//property") if p.get("name")=="surefire.test.class.path"))')
for replica in 1 2 3
do
  "$JAVA_HOME/bin/java" -Xms512m -Xmx768m -XX:ActiveProcessorCount=2 -cp "$probe_cp" \
    com.taxonomy.portfolio.service.PortfolioPackedStorageProbe "$fixture" 8 25 \
    > "target/portfolio-packed-fast-ab-$replica.log" || exit 1
done
```

The command on current source reproduces the selected fast-v1 candidate. The initial default-effort measurement below used the source tree `588a3419a3560ecf92aaae7da74a7ac68673e1a5` at `b9fc0ce` before the codec change, with original outputs retained as `portfolio-packed-ab-{1,2,3}.log`. Do not overwrite those logs or compare a current fast writer to the old table as though the representation were unchanged. The rejected lossless-reference experiment used a separate scratch classpath; this command loads the product v1 reader/writer.

The three replicas each use a fresh file-backed HSQL 2.7.3 database and alternate raw-first and v1-first pairs. Each process uses 8 warm-up pairs and 25 measured pairs. The measurement host was Linux 6.18.57 x86_64 on AMD EPYC 9V74, running Temurin 25.0.5+8-LTS. The JVM saw two processors with a 512 MiB initial and 768 MiB maximum heap. Allocation and CPU figures count the calling thread. Each read or write opens a fresh entity manager and transaction.

The read measurements use one production `PortfolioSnapshotEvidenceReader` per representation and the full scoped query for snapshot, project, tenant, job, requirement and version. Stored strings derive independently from the same original JSON. The changed fixture changes `PARTIAL` to `SUCCESS` while keeping the same scoped row key. Every event checks the JDBC character count against the stored representation and the policy outcome against fresh DTO input. Before timing, the probe verifies exact text expansion, full DTO binding and rejection of length-preserving corruption after a continuation memo is primed. It also checks successful re-reading of valid replacement content.

The write measurement uses the production serializer and storage switch, then Hibernate merge and commit of the assigned-ID snapshot with the same ancillary columns in both scenarios. Fixture creation, direct SQL replacement and physical CLOB length queries run outside the measured segments. The elapsed transaction includes flush, insertion and commit. The probe records JDBC `commit()` separately. The service's broader claim, mapping and authorization work is exercised by correctness tests and falls outside this snapshot-row timing boundary.

## Initial default-effort baseline: measured read cost

The tables show ranges across three independent per-process percentiles. Each percentile uses 25 measured operations per representation. Allocation is in decimal MB.

| Operation | Storage | Elapsed p50 (ms) | Elapsed p95 (ms) | Allocated p50 (MB) | Allocated p95 (MB) |
| --- | --- | ---: | ---: | ---: | ---: |
| Full DTO | Raw JSON | 109.8–113.1 | 134.8–142.6 | 154.55–154.59 | 154.55–154.59 |
| Full DTO | v1 frame | 165.8–169.8 | 180.3–195.4 | 98.05–98.09 | 98.05–98.09 |
| Continuation cold | Raw JSON | 132.4–133.3 | 164.2–178.2 | 154.57–154.61 | 154.58–154.62 |
| Continuation cold | v1 frame | 168.7–174.8 | 195.8–212.8 | 98.07–98.11 | 98.07–98.11 |
| Continuation hit | Raw JSON | 79.1–82.5 | 93.0–103.1 | 113.63 | 113.63–113.64 |
| Continuation hit | v1 frame | 18.5–19.3 | 24.4–34.7 | 24.93 | 24.93 |
| Same-key changed | Raw JSON | 220.0–221.6 | 261.1–306.1 | 268.19–268.23 | 268.19–268.23 |
| Same-key changed | v1 frame | 187.3–193.8 | 238.1–250.5 | 122.98–123.02 | 122.98–123.02 |

| Operation | SELECTs / DML | Raw / v1 UTF-16 units read | Full DTO binds | Locators acquired / freed | Raw / v1 JDBC range streams closed |
| --- | ---: | ---: | ---: | ---: | ---: |
| Full DTO and cold, each | 1 / 0 | 18,900,061 / 4,131,031 | 1 | 1 / 1 | 73 / 16 |
| Hit | 1 / 0 | 18,900,061 / 4,131,031 | 0 | 1 / 1 | 73 / 16 |
| Same-key changed | 2 / 0 | 37,800,122 / 8,262,086 | 1 | 2 / 2 | 146 / 32 |

Packing reduced the original stored character count by 78.1%. The changed raw value is 18,900,061 units and its separately packed value is 4,131,043 units, which both passes read on mismatch. Hits still read every stored unit and free their locators. The full and cold operations allocate roughly 56 MB less per read with v1, while taking longer in each replica. Warm hits and changed reads improve in both elapsed time and allocation. Physical database bytes depend on the engine and its storage configuration.

## Initial default-effort baseline: write cost and access sensitivity

Newly committed rows held 18,900,061 UTF-16 units in raw mode and 4,131,035 in packed mode. These lengths come from serializing the same analysis DTO in the normal writer. Both modes perform six SELECTs and one INSERT in the measured Hibernate transaction, with one commit, no CLOB reads, and no full DTO binds. JDBC commit p50 was 0.674–0.771 ms raw and 0.679–0.718 ms packed. The corresponding p95 ranges were 1.483–5.932 and 1.308–2.873 ms.

| Operation | Storage | Elapsed p50 (ms) | Elapsed p95 (ms) | Thread CPU p50 (ms) | Thread CPU p95 (ms) | Allocated p50 (MB) | Allocated p95 (MB) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Serialize and apply storage option | Raw JSON | 48.8–52.7 | 69.2–71.0 | 46.6–47.6 | 56.2–61.3 | 135.36 | 135.66 |
| Serialize and apply storage option | v1 frame | 931.3–944.4 | 1016.8–1071.2 | 922.5–931.6 | 995.9–1038.1 | 159.42 | 159.42 |
| Write transaction including commit | Raw JSON | 763.4–782.2 | 853.6–1015.0 | 326.6–339.3 | 359.6–430.2 | 0.33 | 1.15–1.16 |
| Write transaction including commit | v1 frame | 173.6–201.4 | 211.3–277.3 | 82.4–84.2 | 92.4–112.6 | 0.33–0.34 | 0.46 |
| Both write segments | Raw JSON | 818.8–851.8 | 911.3–1066.3 | 372.8–391.1 | 415.7–489.6 | 135.69 | 136.62 |
| Both write segments | v1 frame | 1102.5–1128.2 | 1222.0–1327.0 | 997.0–1017.4 | 1080.2–1145.9 | 159.75–159.76 | 159.88 |

The captured scenario contains one final snapshot. Its checkpoint and snapshot exports contain the same analysis bytes. The continuation history records snapshot identifiers and progress, with no earlier or middle payloads or representative per-snapshot read frequencies. The following sensitivity cases use one actual new write and sequential reads on that row, beginning with a cold continuation read and then memo hits. Each sample sums the measured segments for that row and excludes intervening fixture checks.

| Scenario | Raw p50 / p95 (ms) | v1 p50 / p95 (ms) | v1 p50 increase across replicas (ms) | Raw / v1 p50 allocation (MB) |
| --- | ---: | ---: | ---: | ---: |
| Write + 1 read | 955.3–973.7 / 1077.9–1193.6 | 1274.6–1317.0 / 1400.5–1502.1 | 316.5–343.3 | 290.26–290.30 / 257.82–257.87 |
| Write + 2 reads | 1032.5–1059.5 / 1154.8–1270.3 | 1295.4–1339.2 / 1419.5–1519.5 | 263.0–279.7 | 403.90–403.94 / 282.75–282.80 |
| Write + 3 reads | 1109.3–1143.2 / 1237.6–1353.4 | 1312.7–1362.3 / 1438.4–1537.4 | 203.4–219.1 | 517.53–517.57 / 307.68–307.73 |

This initial default-effort result did not support activation: encoding CPU outweighed the measured HSQL write and repeat-read savings at each tested frequency. It motivated the isolated codec investigation and the selected fast-v1 measurements above. The current writer still defaults to false. These measurements cover a single late snapshot, a single-thread embedded database and the stated mix. They provide no latency estimate for earlier snapshots, other databases, concurrent production workloads or full provider-to-portfolio latency.

## CPU and allocation diagnosis

A separate Java 25 Flight Recorder run used the same machine, JVM limits and fixture, with four warm-up pairs and eight measured pairs. Local phase markers isolated duration windows on the probe's calling thread. The profile uses separate Java and native sampling streams, with sampled allocation weights. The timings above come from the three uninstrumented runs.

- On v1 encoding, all 463 native samples ended in `Deflater.deflateBytesBytes`. The Java samples included 64 in UTF-16 emission and 35 in Jackson serialization out of 101. This identifies deflate as the main native encoding cost and the byte emission loop as the main Java encoding cost.
- On full and cold v1 reads, Jackson and DTO processing accounted for 52 of 105 and 47 of 109 Java samples. The packed reader's decoded code-unit path accounted for 33 and 35 samples, all with `PackedReader.nextByte` at the leaf. HSQL accounted for 7 and 9, and stored-frame materialization for 2 and 5. Inflater accounted for 24 of 25 and 20 of 23 native samples. Both binding and portable decoding contribute to the slower first read.
- Jackson and DTO processing led sampled v1 allocation weight for full and cold reads at approximately 76% and 53%. Header and Base64 validation accounted for approximately 11% and 15%. Stored-frame materialization accounted for approximately 2% and 12%. The allocation table above contains the direct per-thread measurements.

## Actual-driver acceptance

The hosted PostgreSQL, SQL Server and Oracle profiles run the complete existing database gates. In each profile, confirm that `packedPortfolioSnapshotUsesActualDriverAcrossFullColdHitAndDirectReplacement` appears as a passing testcase in the `DiagnosticsPostgresContainerIT`, `DiagnosticsMssqlContainerIT` or `DiagnosticsOracleContainerIT` Failsafe XML. The PostgreSQL version overwrites an equal-length frame through the same OID after the continuation memo is primed, then replaces the row's OID and checks the current analysis again. Require the passing PostgreSQL case `portfolioArchiveReadsPlainAndPackedAnalysisFromActualOidLocators` as well. The local file-HSQL timings are independent of these driver gates.

```sh
./mvnw -B verify -Pdatabase-postgres,ci-database-compatibility
./mvnw -B verify -Pdatabase-mssql,ci-database-compatibility
./mvnw -B verify -Pdatabase-oracle,ci-database-compatibility
```

The archive compatibility check verifies one standard v1 unpack gives the exact original JSON and verifies readability after seeding a new isolated snapshot. The public live restore support registry remains closed.
