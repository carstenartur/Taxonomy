# Pinned multilingual ONNX model provisioning

The default local embedding profile is `MULTILINGUAL_MINILM_L12`. Provisioning and
inference must select the same profile: changing a directory name does not select
a model, tokenizer, pooling strategy or query prefix.

| Profile | Upstream repository | Revision | Remote export → local file | Default directory | License |
|---|---|---|---|---|---|
| `MULTILINGUAL_MINILM_L12` | `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` | `e8f8c211226b894fcb81acc59f3b34ba3efd5f42` | `onnx/model_quint8_avx2.onnx` → `model.onnx` | `models/multilingual-minilm` | Apache-2.0 |
| `BGE_SMALL_EN` | `BAAI/bge-small-en-v1.5` | `5c38ec7c405ec4b44b94cc5a9bb96e735b38267a` | `onnx/model.onnx` → `model.onnx` | `models/bge-small-en-v1.5` | MIT |

The multilingual profile uses mean pooling, unit normalization, a 128-token limit
and no document or query prefix. It produces 384-dimensional vectors. The legacy
profile retains its English retrieval contract. Equal dimensions do not make
vectors from different profiles comparable. When switching profiles, rebuild the
semantic index and discard vectors cached under the previous model identity.

From the repository root, provision the default bundle before running inference:

```bash
bash .github/scripts/download-embedding-model.sh
export TAXONOMY_EMBEDDING_MODEL_DIR="$PWD/models/multilingual-minilm"
export TAXONOMY_EMBEDDING_MODEL_PROFILE=MULTILINGUAL_MINILM_L12
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

`MODEL_DIRECTORY=/another/path` changes only the provisioning destination. Set the
application's `TAXONOMY_EMBEDDING_MODEL_DIR` to that destination as well. Inference
does not download files with `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false`; the default
application configuration and the ONNX Maven test profiles keep this disabled.

Explicit legacy provisioning remains available:

```bash
MODEL_PROFILE=BGE_SMALL_EN bash .github/scripts/download-embedding-model.sh
export TAXONOMY_EMBEDDING_MODEL_DIR="$PWD/models/bge-small-en-v1.5"
export TAXONOMY_EMBEDDING_MODEL_PROFILE=BGE_SMALL_EN
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

These commands configure legacy application runs. The checked-in Maven `onnx` and
`local-onnx-model` profiles provision test JVMs with the default multilingual
directory; they are not legacy acceptance lanes.

## Integrity and cache behavior

The script pins all five required files independently. Nonempty files alone do
not establish cache validity. Each invocation checks all five SHA-256 digests,
including tokenizer and configuration files, before accepting a cache hit.

| Local file | `MULTILINGUAL_MINILM_L12` SHA-256 |
|---|---|
| `model.onnx` | `98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f` |
| `tokenizer.json` | `2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8` |
| `tokenizer_config.json` | `5036ea374ffedd706e3bef33e2e0d6953cb868ef8a490e76e32ba0faa37a6b9b` |
| `special_tokens_map.json` | `378eb3bf733eb16e65792d7e3fda5b8a4631387ca04d2015199c4d4f22ae554d` |
| `config.json` | `6300193cb75e01cf80c96decef7187dfb33094d97cc1490b7ead6ff134476e4e` |

| Local file | `BGE_SMALL_EN` SHA-256 |
|---|---|
| `model.onnx` | `828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35` |
| `tokenizer.json` | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |
| `tokenizer_config.json` | `9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3` |
| `special_tokens_map.json` | `b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3` |
| `config.json` | `094f8e891b932f2000c92cfc663bac4c62069f5d8af5b5278c4306aef3084750` |

The script downloads an invalid or missing bundle into a temporary directory next
to the destination. Only after all five digests pass does it move files into the
destination and write `MODEL_PROVENANCE.txt` with the selected profile, source,
revision, export, digests and license. A failed download or checksum leaves
existing destination files intact and removes temporary files. Installation uses
per-file moves after verification; it is not a transaction across concurrent
provisioners, so run one provisioning process per destination.

HTTP retries remain bounded by `--retry 12 --retry-max-time 600`, with a 20-second
connection timeout and 300-second request timeout. `HF_TOKEN`, when supplied, is
used only for the request authorization header. Unknown profiles fail before
network access. Legacy `MODEL_REPOSITORY`, `MODEL_REVISION` and
`MODEL_ONNX_SHA256` overrides are accepted only when they match the selected
profile's pins; they cannot replace its trust roots.

When explicitly enabled, the application's runtime downloader enforces the same
five hashes for the selected profile's upstream URL. Its cache path includes the
profile and immutable revision. A corrupt existing file fails before any HTTP
request, names the invalid file and asks the operator to re-provision the cache.
Missing files are staged and the complete bundle verified before installation;
failed downloads leave existing files intact. Custom URLs and caller-provided
local bundles retain their explicit inference profile and content identity.

Mounted weights matching the other known profile are rejected before native
loading. For example, retaining BGE weights while leaving the new multilingual
default selected produces an actionable profile error instead of silently
applying MiniLM pooling to BGE. Select `BGE_SMALL_EN` for that legacy bundle.

## Verification ownership

The root Maven initialization step invokes the provisioning script for the
existing enabled profiles. Both Surefire and Failsafe under `-Ponnx` receive
`models/multilingual-minilm` and downloads disabled. CI, documentation screenshots
and release workflows use that same directory; CI caches it under a key that
includes the provisioning script hash.

`EmbeddingModelProvisioningTest` executes the real Bash script against offline
HTTP fixtures and real `sha256sum`. The test substitutes the trusted digest
values in a temporary script copy with digests of tiny independent fixture
payloads, avoiding model downloads in build-policy tests. It verifies both
profiles' pinned URLs and default paths, custom directory independence, valid
cache reuse, corruption repair for every required file, fail-closed downloads,
temporary cleanup and rejection of unpinned overrides. `OnnxProfileWiringTest`
checks the existing Maven and executed-evidence contract. These tests validate
provisioning and wiring, not model inference quality.

`PinnedEmbeddingDownloadIntegrityTest` independently exercises the Java runtime
downloader: valid revision-scoped caches, corrupted weights/tokenizer/config,
all five pinned HTTP paths, staged-download failure cleanup, custom-URL
compatibility and known-profile mismatches. Its eight cases use tiny offline
trust-root fixtures and loopback HTTP; they do not download real model weights.

The complete CI-equivalent command remains:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

For the existing local model reference lane, use:

```bash
./mvnw -B verify -Ponnx
```

The [verification guide](../../.github/copilot-instructions.md#authoritative-verification)
defines the broader prerequisite and evidence boundaries. The immutable sources
are the [multilingual revision](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/e8f8c211226b894fcb81acc59f3b34ba3efd5f42)
and [legacy revision](https://huggingface.co/BAAI/bge-small-en-v1.5/tree/5c38ec7c405ec4b44b94cc5a9bb96e735b38267a).
