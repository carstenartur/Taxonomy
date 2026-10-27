# Independent local embedding index

## Intent and approved scope

Keep `LLM_PROVIDER=CUSTOM_OPENAI` for generative analysis while local ONNX
embeddings supply semantic search. The user approved this correction on
27 October 2026 after the provider-dependent startup guard was identified.

## Approach

Remove the index initializer's dependency on `llm.provider`. Enabled embeddings
start the existing model warm-up and verified node/relation index lifecycle.
Provider overrides per request would leave other generative workflows dependent
on an unsuitable global default; a second enable flag would duplicate the
existing `embedding.enabled` decision. Neither is needed.

## Preserved contracts

- Disabled embeddings and catalogue-only workers do not start global indexing.
- Failed models or indexes cannot report semantic readiness.
- `LOCAL_ONNX` remains an explicit local-scoring provider; generative providers,
  credentials and model-download opt-in are unchanged.
- Reuse existing tests and Maven verification; make no external LLM calls.

## Verification

Use a Spring-wired regression for enabled embeddings with `CUSTOM_OPENAI`,
another generative provider, `LOCAL_ONNX` and an unset provider. It must fail
against the old guard and pass after decoupling. Retain the existing lifecycle,
worker-isolation and real Lucene index coverage checks; correct paired EN/DE
operator guidance.
