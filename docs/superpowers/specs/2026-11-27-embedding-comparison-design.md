# Fair local embedding comparison

Carsten authorized comparing European candidates before changing Taxonomy's local
embedding default. Preserve the current production default. Compare the existing
quantized multilingual MiniLM L12 with Jina embeddings v2 base DE and Narratic
MiniLM L6 European Union, using actual local CPU ONNX inference.

Use the unchanged existing multilingual reference fixture, the actual bilingual
catalogue with its virtual roots, and `NodeEmbeddingText.globalReferenceText` for
the primary production-parity arm. The fixture's positive targets cover only
three office application concepts; explicitly retain that limitation. Candidate
codes and relevance judgments must never enter inference, scoring or ranking.
Use exact cosine, deterministic code tie breaks, common 128-token truncation,
masked mean pooling and L2 normalization, one model at a time and the same CPU
thread budget. A separately labeled optional bilingual-text diagnostic may use
the same four text fields for every model. Do not silently mix these arms.

Model artifacts, tokenizer and configuration files are local, explicitly pinned
and hash-verified. The repository-owned
`taxonomy-app/src/test/resources/local-embedding-comparison/model-assets.tsv`
defines the required local paths, upstream revisions and SHA-256 digests. It
includes `narratic-original/1_Pooling/config.json` and
`narratic-original/sentence_bert_config.json` alongside the community ONNX bundle.
The reproduction guide's download loop provisions every listed file under the
root given by `taxonomy.embedding.comparison.models`. No inference/downloading
through a paid or remote provider.
The comparison remains test-side because production indexes currently require
384 dimensions and Jina requires 768. Record configuration, pins, full input
hashes, platform/native versions, actual model work and timings, every query rank
and misses, distractor observations, EN/DE metrics and anchor regressions. Startup,
document preparation and warm query latency are separate measurements. Use a
Java/Maven opt-in command; ordinary builds neither load nor download extra models.

Decision rule fixed before measurement: retain MiniLM unless the candidate
preserves all baseline anchor hits, improves held-out/paraphrase retrieval without
a language regression, has acceptable measured resource cost and is supported by
a broader independently judged corpus. This narrow existing fixture can nominate
a candidate but cannot by itself authorize a production default migration.

Alternatives considered: compare published leaderboards (different corpora and
hardware), add all candidates to production (premature dimension migration), or
run a test-side controlled actual-model comparison. The third is selected.
