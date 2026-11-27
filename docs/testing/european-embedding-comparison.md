# European embedding model comparison

Keep multilingual MiniLM L12 as the production default. Neither candidate improved paraphrase ranking under the configured production text and token budget. Narratic preserved all six anchors and cut corpus preparation and warm query latency roughly in half, which makes it a candidate for broader evaluation when speed matters.

The comparison uses local CPU ONNX inference over all 2,572 entries in Taxonomy's packaged bilingual workbook, including eight virtual roots. Each encoder receives the same text from the production `NodeEmbeddingText.globalReferenceText` formatter, which uses the English title and description. English queries test same-language retrieval and German queries test cross-language retrieval. One model runs at a time, with one intra-op and one inter-op thread. All three use the same 128-token cap, masked mean pooling, unit normalization and exact cosine ranking, with catalogue code as the tie-breaker. Relevance judgments are applied after ranking.

## Pinned models

| Encoder | Remote ONNX export | Dimensions | Upstream sequence limit | Weight bytes |
| --- | --- | ---: | ---: | ---: |
| [Sentence Transformers multilingual MiniLM L12](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/e8f8c211226b894fcb81acc59f3b34ba3efd5f42), `e8f8c211226b894fcb81acc59f3b34ba3efd5f42` | `onnx/model_quint8_avx2.onnx` | 384 | 128 | 118,453,870 |
| [Jina v2 base DE](https://huggingface.co/jinaai/jina-embeddings-v2-base-de/tree/3f9eede875721714945b6a99a3198299243cf2be), `3f9eede875721714945b6a99a3198299243cf2be` | `onnx/model_quantized.onnx` | 768 | 8,192 | 161,565,240 |
| [Narratic MiniLM L6 European Union](https://huggingface.co/narraticlabs/MiniLM-L6-european-union/tree/4d586294bf64191ad52839cf184ccc2d28b724c2), `4d586294bf64191ad52839cf184ccc2d28b724c2`, [ONNX conversion](https://huggingface.co/onnx-community/MiniLM-L6-european-union-ONNX/tree/831080177e517eac695bbca9b90d72fa49c4f8f1), `831080177e517eac695bbca9b90d72fa49c4f8f1` | `onnx/model_uint8.onnx` | 384 | 256 | 107,137,470 |

All three model cards list Apache-2.0. The baseline and Narratic graphs use unsigned 8-bit initializers alongside floating-point parameters, and Jina uses signed and unsigned 8-bit initializers alongside floating-point parameters. Jina's graph comes from its first-party repository. Narratic's graph comes from a community conversion that identifies the first-party model. The test records both the graph and the original pooling and sequence configuration pins.

## Reproduction

Run on an AVX2-capable x86-64 machine with Java 25. Allow about 400 MB for the three model bundles plus space for the Maven build. Download the pinned files to a local directory outside the application. The [asset manifest](../../taxonomy-app/src/test/resources/local-embedding-comparison/model-assets.tsv) lists each upstream repository, immutable revision, path and SHA-256 digest.

From the repository root, with Bash and `sha256sum` installed:

```bash
set -euo pipefail
models_dir="$PWD/models/european-embedding-comparison"
manifest="taxonomy-app/src/test/resources/local-embedding-comparison/model-assets.tsv"
while IFS=$'\t' read -r repository revision upstream local_path digest; do
    [[ "$repository" == \#* ]] && continue
    destination="$models_dir/$local_path"
    mkdir -p "$(dirname "$destination")"
    if printf '%s  %s\n' "$digest" "$destination" | sha256sum --check --status 2>/dev/null; then
        continue
    fi
    curl --fail --silent --show-error --location --retry 3 --output "$destination.partial" \
        "https://huggingface.co/$repository/resolve/$revision/$upstream"
    printf '%s  %s\n' "$digest" "$destination.partial" | sha256sum --check
    mv "$destination.partial" "$destination"
done < "$manifest"

./mvnw -B -pl taxonomy-app -am test -Plocal-embedding-comparison \
    -Dtaxonomy.embedding.comparison.models="$models_dir"
```

The Maven profile selects the four support tests and the three-model comparison. It verifies local files before opening the first graph and again after the last model finishes. Runtime model downloads and remote inference calls are disabled by design. With Maven dependencies cached, use `-o` for an offline run. The report lives at `taxonomy-app/target/local-embedding-comparison/report.json`. The evidence includes the asset, workbook and query fixture hashes, platform and runtime versions, exact full rank of each judged target, top-ten candidate codes, positive-reference misses and targeted distractor observations. A missing or corrupt pinned asset fails the test.

The comparison distinguishes tokenizer and session setup, first inference, catalogue vector preparation, and warm query latency. Warm latency includes query tokenization, inference, pooling, and exact ranking and has 48 samples per model from three repeats of the same 16 queries. The Linux `cumulativeProcessPeakRssKiB` field is the high-water mark of the shared Surefire JVM, including native memory and all preceding encoders. Comparing isolated memory demand would require a fresh process per encoder. Treat timings and RSS as observations on the measured machine.

## Decision rule

Retain MiniLM unless a candidate preserves every baseline anchor hit, improves paraphrase retrieval without an EN or DE paraphrase regression, has acceptable measured resource cost, and has broader independently judged evidence. In the paired fixture, paraphrase improvement means higher combined MRR@10, and a language regression means lower paraphrase hits or MRR@10. The unchanged 16-case fixture covers three positive office concepts, with six anchors, six paraphrases, two targeted distractors and two ambiguities. Its judgments are synthetic and incomplete. It can nominate a candidate for broader evaluation. The 768-dimensional Jina output also requires a production vector schema and index migration before deployment.

## Measured results

The Maven profile completed all five application tests on Linux x86-64 with an AMD EPYC 9V74, Java 25.0.5, ONNX Runtime 1.21.1 CPU and DJL tokenizers 0.40.0. The native comparison took 242.4 seconds. Each encoder produced 2,572 document vectors and 65 query vectors, including the first inference, the untimed query pass and the 48 timed repetitions.

| Encoder | Anchor hits | Positive hits EN / DE | Positive MRR@10 EN / DE | Positive MRR@10 overall | Paraphrase MRR@10 EN / DE |
| --- | ---: | ---: | ---: | ---: | ---: |
| Multilingual MiniLM L12 | 6/6 | 7/7 / 5/7 | 0.893 / 0.714 | 0.804 | 0.750 / 0.667 |
| Jina v2 base DE | 5/6 | 6/7 / 4/7 | 0.446 / 0.393 | 0.420 | 0.250 / 0.417 |
| Narratic MiniLM L6 EU | 6/6 | 7/7 / 5/7 | 0.663 / 0.571 | 0.617 | 0.464 / 0.333 |

The 14 positive cases comprise seven English and seven German queries. A positive hit means that at least one judged reference appeared in the top ten. Multilingual MiniLM and Narratic both hit five of six paraphrases, with combined MRR@10 of 0.708 and 0.399 respectively. Jina hit four paraphrases, with MRR@10 of 0.333. Jina raised the German payroll paraphrase from rank 25 to rank 1 and dropped the German email anchor from rank 1 to rank 13. Narratic raised the German payroll paraphrase to rank 15 and ranked other paraphrase hits lower overall. Every encoder kept the three targeted office references out of the top ten on both distractor queries.

| Encoder | Session setup (s) | First inference (ms) | Corpus preparation (s) | Warm query p50 / p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| Multilingual MiniLM L12 | 1.482 | 14.31 | 48.41 | 8.66 / 11.42 |
| Jina v2 base DE | 0.662 | 22.29 | 161.39 | 25.84 / 30.12 |
| Narratic MiniLM L6 EU | 1.154 | 5.26 | 23.46 | 4.79 / 5.68 |

The JVM used a 192 MiB heap limit and reached a cumulative peak RSS of 889,024 KiB, including native memory. The fixed session order adds first-time tokenizer initialization to the baseline setup measurement. The raw report retains all 48 model–query observations and exact target ranks. The benchmark uses exact node retrieval on the global document text. Application ANN behavior, other tasks, a larger token budget and a bilingual-text arm require separate evidence.
