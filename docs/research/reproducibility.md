# Reproducibility

This guide describes how to reproduce a Taxonomy Architecture Analyzer release
and preserve the inputs, configuration and outputs used in an evaluation.
Reproducing software behavior and assessing model-generated architecture quality
are separate tasks.

## 1. Use a fixed release or commit

Record a Zenodo DOI, release tag or full Git commit SHA together with the
corresponding `CITATION.cff`, `codemeta.json` and `.zenodo.json` files.
Use the instructions belonging to that same revision; current build commands and
configuration should not be assumed to apply to an older release.

## 2. Environment

For the source revision containing this guide, use:

- Java 25;
- the checked-in Maven Wrapper;
- Docker and the browser/runtime prerequisites required by the selected verification profile;
- an optional configured generative model endpoint for live inference.

A self-hosted generative model uses `LLM_PROVIDER=CUSTOM_OPENAI` and a separate
compatible model server. `LOCAL_ONNX` provides embedding-based similarity
scoring and semantic search; it cannot generate relationship assessments or
requirement reformulations. Embeddings require explicit enablement and provisioned
model files. See [AI providers](../en/AI_PROVIDERS.md) and the
[pinned embedding-model guide](../testing/multilingual-model-provisioning.md).

## 3. Build from source

Replace `YOUR_RECORDED_TAG_OR_COMMIT` with the revision you recorded:

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
git checkout YOUR_RECORDED_TAG_OR_COMMIT
./mvnw verify
```

The default lifecycle is a bounded developer check. The canonical Maven CI command is:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Record which command and profiles ran, their results and missing prerequisites.
The [verification authority](../dev/MAVEN_VERIFICATION.md) documents the remaining
limits of Java/Maven-only reproduction and the additional database/runtime gates;
a focused run is not a complete CI result.

## 4. Run locally

Build and start the executable application from the repository root:

```bash
./mvnw -pl taxonomy-app -am package -DskipTests
export TAXONOMY_EMBEDDING_ENABLED=false
java -Dloader.path=taxonomy-app/target/features -jar taxonomy-app/target/taxonomy-app-*.jar --taxonomy.plugins.directory=taxonomy-app/target/plugins
```

Open `http://localhost:8080` and follow the
[bootstrap/sign-in procedure](../../README.md#quick-start). Catalogue browsing
and many editing, history and export functions do not require a model provider.
The package command above is a launch step, not a test result.

For live generative development, first start a compatible model server and set
these variables before starting Taxonomy. This example assumes that the model
server and the Java process run directly on the same host:

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://127.0.0.1:8000/v1/chat/completions
export CUSTOM_LLM_MODEL=your-served-model-name
```

Use a model actually served by that endpoint. For separate containers or Pods,
follow the [custom model-server guide](../dev/custom-llm.md) and the deployment
guide's network/TLS requirements; loopback refers to the current container or
process host.

A smaller real model is sufficient for developing the workflows it can exercise
with representative inputs. Record its context, output and latency limits.
Assess architectural quality separately instead of requiring premium-model
results before functional development can proceed.

Use prepared responses or the existing scenario fixtures for repeatable software
checks. `LLM_MOCK=true` supplies prepared scoring responses; it does not turn
every workflow into a live-model test. See the
[scenario guide](../testing/scenario-acceptance.md).

For local embeddings, explicitly enable them and provide the pinned model files.
Keep a generative provider selected when combining generative analysis with
local semantic search. Do not expose the local HTTP application directly to the
internet; use the [deployment guide](../en/DEPLOYMENT_GUIDE.md).

## 5. Reproduce an analysis

Preserve:

- source release/tag or full commit SHA;
- taxonomy/catalogue version, requirement text and source-document versions;
- selected roots, analysis mode, budgets and relevant configuration;
- provider and model identifier/revision, quantization and serving runtime where available;
- prompt/settings versions, context/output limits and live/recorded/authored response provenance;
- original scores, relation evidence, unresolved work and human corrections;
- accepted and rejected proposals and the exact exported result.

Exclude credentials and secret values from the recorded configuration.
A local or self-hosted model does not by itself guarantee byte-identical output.
Record settings and preserve the actual responses and snapshots; distinguish
replaying an existing result from running inference again.

## 6. Preserve outputs

Useful artifacts include the exported JSON result, diagrams, decision reports,
selected screenshots and the architecture's repository/revision identifiers.
Keep the software/model provenance and human decisions with those outputs.
An authored response fixture can make software checks repeatable but is not
evidence that a live model produced the same architecture.

## 7. Software metadata

The repository includes `CITATION.cff`, `CITATION.md`, `codemeta.json` and
`.zenodo.json`. Keep them in sync with the archived software release.
See [citation guidance](../../CITATION.md).

## 8. Known reproducibility limits

- Model revisions, inference settings and execution runtimes can affect output.
- Catalogue and input versions can change traversal paths and proposed relations.
- GUI screenshots depend on browser, viewport and theme.
- Database, container, cluster and capacity results belong to the tested environment.
- Technical success does not establish architectural completeness, field suitability or cost savings.

Use the [evaluation plan](evaluation.md) to report functional behavior,
live integration and architectural quality separately.
