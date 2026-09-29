# BGE pooling mismatch found by the real ONNX reference run

## Observed failure

Run `36462654876` evaluated PR head
`1289faae82040311af412125dcb9c58227a54065`. Its artifact
`10990625935` has SHA-256
`7868142e518a40607cfec8bc6a871151c02b144cc6dadeef9fadb7dc422d841e`.
Unlike the earlier provisioning failure, real local inference and all twelve
reference/baseline searches completed. The index reported READY with 2572 nodes.
The only reported test failure was the English reference assertion in
`LocalOnnxPipelineIT`; the other eight pipeline tests and `OnnxSeleniumIT` passed.

English semantic retrieval found payroll at rank 1 but missed word processing
and email in the first ten hits. Full-text retrieval found all three at rank 1.
German semantic retrieval found email at rank 9 but missed the other two references.
These are measurements on six synthetic positive-reference cases, not a general
product or language benchmark. The original evidence remains unchanged.

## Confirmed inference-configuration defect

The pinned BGE model declares CLS pooling, not mean pooling:

- https://huggingface.co/BAAI/bge-small-en-v1.5/blob/5c38ec7c405ec4b44b94cc5a9bb96e735b38267a/1_Pooling/config.json
- https://huggingface.co/BAAI/bge-small-en-v1.5#using-huggingface-transformers

The actual DJL 0.38.0 `TextEmbeddingTranslator` defaults to `pooling=mean` and
`normalize=true`. `LocalEmbeddingService` supplied only `includeTokenTypes`;
it did not pass BGE's required pooling. A 384-dimensional, finite result therefore
did not prove correct BGE inference semantics. This is a production integration
defect, not a reason to relax the reference expectations.

The service now explicitly selects `pooling=cls` and `normalize=true` in the one
criteria builder used for query, node and relation embeddings. No writable model
metadata, extra model files or runtime download is needed. Model weights, query
prefix, reference cases, top-K, deadlines and quality gates are unchanged.
The small package-private criteria method allows testing the actual production
arguments without loading model weights.

Existing persisted mean-pooled vectors must not be compared with new CLS-pooled
queries. With `LLM_PROVIDER=LOCAL_ONNX`, the existing initializer warms the model,
rebuilds the node index before semantic readiness and then rebuilds relations on
startup. Other deployments retaining vector indexes must rebuild them too.
No migration of user data or catalogue labels is involved.

## Regression evidence and remaining verification

The native regression sends a hand-computed token tensor through DJL's real
postprocessor using the service's actual criteria. CLS is `(3,4,0,...)`, another
token is `(-3,4,0,...)` and a large padding token is masked out. The expected
normalized CLS is `(0.6,0.8,0,...)`; mean pooling instead produces `(0,1,0,...)`.

The native check failed before the correction (component 0: expected 0.6, actual
0.0) and passed after it. It uses Java 21, DJL 0.38.0 and ONNX Runtime 1.21.1 from
the available application artifact. It exercises actual tensor postprocessing,
not the pretrained model or catalogue retrieval. CPU execution logs an unavailable
CUDA-provider warning but completes without a GPU. No warning was suppressed.
Three JUnit methods cover explicit model arguments, the preserved engine/factory
boundary and the native projection. The native method is tagged `onnx`.

The complete local Maven/JUnit suite cannot run in the recovered source subset:
GitHub DNS resolution and Maven/model download access are unavailable here. The
focused production class and native regression helper compile, but the new JUnit
wrapper and full pretrained-model run still require CI. Correct pooling is proven
by this regression; whether it also resolves both observed top-ten misses must be
established by rerunning the unchanged real reference gate. No claim of a green
PR or improved recall is based solely on this tensor test.
