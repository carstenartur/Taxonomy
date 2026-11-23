# Automatic continuation performance and fresh provider QA

## Intent and authorization

Carsten requests reliable use of the previously demonstrated assistant-served OpenAI-compatible endpoint and high application performance, especially automatic workflows. Existing authorization covers investigation, fixes, verification, PR publication and merge. This is an incremental performance change on main `ef55275`; it does not alter the model, inference budget or supported workflow.

## Evidence and scope

The recovered live path is a local HTTP bridge that forwards real Taxonomy prompts to fresh assistant responses. The opt-in production-gateway dialogue test has now passed two fresh requests on `ef55275`; this is integration evidence, not a standalone vLLM-engine benchmark. Provider wait must be accounted for separately from application work.

The reference scenario completed 254 explicit continuation steps. Eligibility GET, locked prepare and worker begin each fully bind the same immutable snapshot. The final analysis JSON is 18,929,372 UTF-8 bytes. Three warmed JVM measurements show full binding medians of 53.33 / 53.35 / 55.24 ms and about 41 MB temporary allocation. A temporary field-filter prototype saves most allocations but only 18–21 ms per read; it also skips type validation of ignored historical fields and is not selected for production. No whole-workflow speedup is claimed from these measurements.

## Approaches

1. A compact streaming parser would bind only policy inputs. It saves allocations, but malformed ignored fields previously rejected by the full DTO could become accepted. Reject for this change.
2. A bounded memo of compact immutable policy inputs after full validation reuses validated work only when the exact current CLOB content and all snapshot identities match. Select this incremental approach. Continue reading and closing every caller's database locator, and repeat all authority and mutable-state checks.
3. A persisted compact summary could avoid CLOB transport, but requires schema migration, atomic write/restore coupling and legacy fallback. Defer unless I/O measurement justifies it.

## Design

Add a purpose-specific immutable continuation input that retains every field read by the existing budget and version policy. It must not masquerade as a full AnalysisResult. Move/reuse the existing policy through one implementation and preserve the existing AnalysisResult test entry point by adaptation. Constructing the input must not eagerly evaluate policy: scope limitations and begin's version check keep their existing precedence. Preserve null-bearing warnings and source-node absence until the original policy boundary, without inventing defaults.

The existing full snapshot APIs retain full DTO deserialization. Add a continuation-only reader over the same SELECT_ANALYSIS predicate (snapshot/project/scope/job/requirement/requirementVersion) and transaction-owned CLOB. A bean-owned memo stores only compact immutable input and a verified fingerprint. Include SessionFactory identity in keys, plus every pinned row identity; do not create a global strong cache that retains factories across application contexts. Maximum 16 entries and a conservative aggregate 2 MiB retention budget; account for strings, list elements and pinned identities. Inputs exceeding the budget follow ordinary full parsing. Do not retain full JSON, full AnalysisResult, graph/trace collections or caller authorization. A missing row must remain distinguishable from an existing row whose analysis is null, empty or blank.

On a cold read, fully deserialize once while hashing every stored UTF-16 code unit and length, then drain and close the stream and free the locator. Publish only after successful cleanup. On a warm read, independently consume the entire current CLOB and verify that fingerprint before returning the immutable input. Same-ID changed bytes require fresh full binding; malformed fields remain errors. Publish the fingerprint from that fully bound read pass, never from an earlier comparison pass, so concurrent content changes cannot associate inputs with the wrong content. UTF-8 replacement of unpaired surrogates is not a valid content-identity shortcut. Read, close and free failures remain observable even on hits. Do not cache failed reads or mutable journal state. Concurrent callers must never share locators or wait under the memo lock.

GET, prepare and begin use this reader for the saved immutable snapshot. All current project/workspace checks, locking, exact item/version matching, live journal scoped/expire/flush/recovery, question state, provider binding, cancellation and worker-owner checks remain. Journal read/request decoding is unchanged in this bounded change.

## Verification and delivery

Keep the existing 19 policy cases and real Hibernate/HSQL snapshot/CLOB tests. First reproduce that repeated identical policy reads currently bind the complete DTO each time; pin desired warm-read behavior together with unchanged decisions. Test same-length corrupt replacement, cold malformed unused fields, scope/identity isolation, new journal state, model binding on old versions, null/legacy precedence, late reader/close/free failures, concurrency and retention bounds. Use the actual CLOB fixture for alternating A/B comparisons against the retained full-reader reference, with identical inputs, one long-lived reader and a fresh persistence context for each independent transaction. Demonstrate that the real payload fits the compact-input retention limit and that warm reads actually avoid repeated binding. Report cold and warm wall time, p95, allocation, SQL count, CLOB characters and the additional read pass on a content mismatch separately; do not generalize to model latency.

Run focused red/green verification and independent review, then the repository's complete hosted gates. Preserve all timeouts, heaps, provider budgets, assertions, test ownership, architecture rules, coverage and security baselines. Keep generated payloads/JFR/logs in target, concise reproducible findings in QA docs. Publish a focused PR and merge only after its current head passes.
