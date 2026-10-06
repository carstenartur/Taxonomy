# German retrieval with an English catalogue

The default local model now uses the explicitly named `MULTILINGUAL_MINILM_L12`
profile. The English-only `BGE_SMALL_EN` contract remains selectable. Both produce
384-dimensional vectors; catalogue codes, titles, descriptions, hierarchy and
provenance are unchanged. There are no reference-ID boosts, hand-written query
translations, external-provider calls or lexical fallback inside semantic search.

The real workbook contains English titles and descriptions only. Its importer
leaves German fields empty. Adding German fields to the formatter cannot create
translations that are absent from the catalogue. The previous English BGE model
also used an English tokenizer. Its CLS pooling and asymmetric query instruction
were correct for that model, and increasing ANN exploration had already fixed
English recall. The remaining problem was cross-language retrieval.

Before changing production, direct DJL/ONNX inference over all 2572 real workbook
nodes and CSV relations, formatted by production `NodeEmbeddingText`, reproduced
the six recorded application ranks exactly: English payroll 1, documents 2 and
email 1; German payroll/documents absent from ten hits and email 6. This was an
exact cosine diagnostic over real vectors, not REST or a replacement search
implementation used for acceptance.

The first multilingual candidate, official quantized multilingual E5-small,
retained the German payroll and document misses. Its tokenizer produced sensible
German subwords; direct ONNX attention-mask mean pooling agreed with DJL
(cosine 1.00000013, maximum component error 4.2e-8). Unquantized E5 produced
nearly the same bilingual sentence-pair similarities. An altered symmetric-prefix
diagnostic did not repair German ranking and damaged English ranking. That
candidate was rejected and has no production profile.

The selected Sentence Transformers model is trained for multilingual paraphrase
similarity. Its upstream contract is attention-mask mean pooling, no instruction
prefix and 128 tokens. Normalization makes its vectors compatible with the existing
Lucene cosine field. The fixed quantized export is 118,453,870 bytes. Its pin is:

| Item | Value |
| --- | --- |
| Repository | `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` |
| Revision | `e8f8c211226b894fcb81acc59f3b34ba3efd5f42` |
| Export | `onnx/model_quint8_avx2.onnx` |
| Weight SHA-256 | `98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f` |
| Tokenizer SHA-256 | `2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8` |
| License | Apache-2.0 |

Primary sources: the [official model card](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2),
[trained sequence limit](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/blob/e8f8c211226b894fcb81acc59f3b34ba3efd5f42/sentence_bert_config.json)
and [pinned exports](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/e8f8c211226b894fcb81acc59f3b34ba3efd5f42/onnx).

Every indexed node and relation vector now carries a canonical `embeddingModel`
keyword derived from the actual model/tokenizer/configuration bytes, effective
query prefix, document-prefix contract, pooling, token limit and inference/runtime
version. Every node/relation KNN query filters by the active key. Untagged old
vectors and old BGE vectors cannot be retrieved with MiniLM query vectors, even
through a direct service caller during rebuilding. The existing initializer still
rebuilds nodes before exposing semantic readiness, then rebuilds relations.
Frozen workers use the same document-inference method and reject incompatible
model identities. Native inference is limited to two concurrent predictors.

Six original anchor queries and bindings remain byte-for-byte unchanged. The
new `local-onnx-multilingual-v1.json` records those six plus six independent
paraphrases, two artillery distractors and two broad office ambiguities. All 16
were fixed before either multilingual candidate was run. Only the six original
anchors define the mandatory regression gate; unsuccessful observations are
retained rather than rewritten to become successful fixtures.

The direct full-catalogue MiniLM diagnostic found all six original anchors at
rank 1. Independent English payroll/documents/email ranks were 1/4/1; independent
German documents/email ranks were 1/1. The German payroll paraphrase remained at
rank 25, outside the ten-hit response. Neither artillery query retrieved the
three office/payroll targets in ten hits. The English ambiguity retrieved email
at rank 1 but missed word processing; the German ambiguity missed both references
(ranks 113 and 38). These ambiguity bindings are observations of a broad request,
not an exhaustive acceptable-result set or calibrated precision.

The diagnostic built 2572 MiniLM vectors in about 67 seconds in a JVM capped at
512 MiB heap. Observed process RSS was about 792 MiB while the diagnostic briefly
loaded a second graph-inspection session. Native model/tokenizer memory is not
Java heap. These are local measurements, not a hardware-independent latency or
process-memory guarantee. The quantized graph ran with the actual CPU provider
in ONNX Runtime 1.21.1 and DJL 0.38.0.

The supplemental REST evaluator reuses the existing warm application and index,
performs exactly 16 semantic calls with top-K=10 and 30-second HTTP deadlines,
checks readiness around each call, and preserves original-anchor/reference,
application-JAR, model-file and canonical catalogue hashes. Unknown/duplicate,
malformed or empty semantic replies are technical errors with null quality
measurements. Catalogue/model changes and partial execution fail the evaluation.
It writes `target/failsafe-reports/local-onnx-multilingual/report.json`; additional
paraphrase/ambiguity misses keep its status `MEASURED_WITH_REFERENCE_MISSES`.
A six-anchor pass therefore does not claim universal German retrieval quality.

Subsequent native verification passed both real pooling checks, frozen-worker
document-inference parity and all five application ONNX REST endpoint tests.
The original real REST evaluator now makes German anchors mandatory alongside
English anchors. A fresh packaged application at clean source `f40a75a` then
passed that evaluator: all six original EN/DE semantic references ranked 1 over
all 2572 nodes. The additional 16-call REST evaluator reproduced the diagnostic's
successful paraphrases and targeted distractor results while retaining the German
payroll paraphrase and ambiguity misses in `MEASURED_WITH_REFERENCE_MISSES`.

The packaged run reached node semantic readiness in 67.845 seconds and completed
its observed startup/evaluation period in 81.895 seconds. The whole application
process peaked at about 1504 MiB RSS with `-Xmx768m` and two active processors;
this includes native memory and is not comparable to a model-only measurement or
a deployment sizing guarantee. Runtime downloads and external provider calls
were disabled. See the [final local QA evidence](../qa/2026-10-06-remaining-qa.md)
for source/JAR/model/catalogue identities, exact result boundaries and remaining
canonical CI gates.
