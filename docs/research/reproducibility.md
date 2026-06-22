# Reproducibility

This guide describes how to reproduce a Taxonomy Architecture Analyzer release from source code and how to preserve analysis results for research use.

## 1. Use a fixed release or commit

For research, avoid using a moving branch as the only reference. Prefer:

- a Zenodo DOI for an archived release;
- a GitHub release tag such as `v1.2.2`;
- a full Git commit SHA;
- the corresponding `CITATION.cff`, `codemeta.json`, and `.zenodo.json` files.

Record the exact source reference in publications, reports, and exported artefacts.

## 2. Environment

Recommended environment:

- Java 21 or newer;
- Maven 3.9 or newer;
- Docker for integration tests and production-like deployment;
- optional LLM provider key, or `LLM_PROVIDER=LOCAL_ONNX` for offline/local analysis.

The root `pom.xml` declares project version, module structure, Java version, and relevant dependency versions.

## 3. Build from source

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy
git checkout v1.2.2   # or a concrete commit SHA
mvn test
```

For a fuller verification run:

```bash
mvn verify
```

Integration tests may require Docker.

## 4. Run locally

```bash
cd taxonomy-app
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run
```

Then open `http://localhost:8080`.

Do not expose this local HTTP setup to the internet. Use the documented Docker + HTTPS deployment for non-local use.

## 5. Reproduce an analysis

To reproduce an architecture analysis, record:

- release tag or commit SHA;
- taxonomy/catalogue version;
- input requirement text;
- LLM provider and model configuration;
- provider mode, for example cloud provider or `LOCAL_ONNX`;
- relevant environment variables;
- accepted and rejected relation proposals;
- exported artefacts.

For cloud LLM providers, exact byte-for-byte reproducibility may not be possible because provider models and stochastic settings can change. For repeatable demonstrations, prefer deterministic configuration where available or use the local/offline provider mode.

## 6. Preserve outputs

Recommended artefacts to preserve with a study or report:

- exported JSON result;
- Mermaid diagram;
- ArchiMate or Visio export if used;
- Markdown/HTML/DOCX report;
- screenshot of scored tree and architecture impact view;
- repository commit or workspace version identifier.

## 7. Software metadata

The repository includes:

- `CITATION.cff` for GitHub citation rendering and citation managers;
- `CITATION.md` for human-readable citation guidance;
- `codemeta.json` for machine-readable software metadata;
- `.zenodo.json` for Zenodo deposition metadata.

These files should be kept in sync with release versions.

## 8. Known reproducibility limits

- Cloud LLM outputs may change over time.
- Different taxonomy versions can lead to different scoring paths.
- GUI screenshots can vary with browser, viewport, and theme.
- Integration-test behavior can depend on Docker availability and local resources.

The project therefore treats reproducibility as a combination of archived code, recorded inputs, explicit configuration, and preserved outputs.
