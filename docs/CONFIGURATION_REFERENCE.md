# Taxonomy Architecture Analyzer — Configuration Reference

This document is the **canonical, complete list** of every environment variable and
application property recognised by the Taxonomy Architecture Analyzer.  
Values are set via environment variables (recommended for production) or in
`src/main/resources/application.properties` for local development.

---

## LLM Provider Configuration

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| `LLM_PROVIDER` | `llm.provider` | Enum | *(auto-detect)* | Force a specific LLM provider. Values: `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `LOCAL_ONNX`. When not set, the provider is auto-detected from the first available API key (see priority order below). |
| `GEMINI_API_KEY` | `gemini.api.key` | String | *(empty)* | Google Gemini API key. Obtain from [aistudio.google.com](https://aistudio.google.com). |
| `OPENAI_API_KEY` | `openai.api.key` | String | *(empty)* | OpenAI API key. |
| `DEEPSEEK_API_KEY` | `deepseek.api.key` | String | *(empty)* | DeepSeek API key. |
| `DASHSCOPE_API_KEY` | `qwen.api.key` | String | *(empty)* | Alibaba Cloud DashScope API key (Qwen model). |
| `LLAMA_API_KEY` | `llama.api.key` | String | *(empty)* | Llama API key. |
| `MISTRAL_API_KEY` | `mistral.api.key` | String | *(empty)* | Mistral API key. |

### LLM Provider Auto-Detection Priority Order

When `LLM_PROVIDER` is **not set**, the application checks for available API keys in this order:

1. **Gemini** — if `GEMINI_API_KEY` is set
2. **OpenAI** — if `OPENAI_API_KEY` is set
3. **DeepSeek** — if `DEEPSEEK_API_KEY` is set
4. **Qwen** — if `DASHSCOPE_API_KEY` is set
5. **Llama** — if `LLAMA_API_KEY` is set
6. **Mistral** — if `MISTRAL_API_KEY` is set

The first match wins. If **no key** is set and `LLM_PROVIDER` is not `LOCAL_ONNX`, the
application starts in **browser-only mode** (all analysis scores = 0, Analyze button disabled).

Setting `LLM_PROVIDER=LOCAL_ONNX` explicitly activates offline semantic scoring — no API
key or internet connection is required (after the first model download).

---

## Local Embedding Model

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| `TAXONOMY_EMBEDDING_ENABLED` | `embedding.enabled` | Boolean | `true` | Set to `false` to disable embedding and all semantic search globally. |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | `embedding.model.dir` | Path | *(empty)* | Absolute path to a pre-downloaded model directory. When empty, DJL downloads the model automatically on first use. |
| `TAXONOMY_EMBEDDING_MODEL_NAME` | `embedding.model.name` | String | `djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2` | DJL model URL or HuggingFace model name. Change only if you want a different embedding model. |

### Pre-Downloading the Embedding Model (Air-Gapped Deployments)

For environments without internet access, pre-download the `all-MiniLM-L6-v2` model:

```bash
# 1. On a machine with internet access, run the app once to trigger the download:
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run
# The model is cached under ~/.djl.ai/cache/ (approximately 23 MB)

# 2. Copy the cached model directory to the target machine:
scp -r ~/.djl.ai/cache/repo/model/ai/djl/huggingface/onnxruntime/all-MiniLM-L6-v2/ \
    target-machine:/opt/models/all-MiniLM-L6-v2/

# 3. Set the environment variable on the target machine:
export TAXONOMY_EMBEDDING_MODEL_DIR=/opt/models/all-MiniLM-L6-v2
```

Alternatively, download the model directly from HuggingFace:

```bash
# Download model files
pip install huggingface-hub
huggingface-cli download sentence-transformers/all-MiniLM-L6-v2 --local-dir /opt/models/all-MiniLM-L6-v2
```

---

## Administration

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| `ADMIN_PASSWORD` | `admin.password` | String | *(must be set for non-local deployments)* | Password to protect admin-only panels (LLM Diagnostics, Prompt Templates, Communication Log). **For any deployment exposed beyond a fully trusted local machine, this MUST be set to a strong, non-empty value.** When left empty, all admin panels are accessible to everyone and this behaviour is intended for isolated development environments only. When set, users must click the 🔒 button and enter the password. |

### Configuring the Admin Password

> **Security warning:** Running the application with an empty `ADMIN_PASSWORD` leaves all admin panels unauthenticated. **Do not use an empty admin password on any internet-exposed, shared, or production system.** Always configure a strong, non-empty value for `ADMIN_PASSWORD` in such environments.

**Local development:**
```bash
ADMIN_PASSWORD=my-secret mvn spring-boot:run
```

**Docker:**
```bash
docker run -p 8080:8080 \
  -e ADMIN_PASSWORD=my-secret \
  -e GEMINI_API_KEY=your-key \
  ghcr.io/carstenartur/taxonomy:latest
```

**Render.com:**
Set `ADMIN_PASSWORD` as a secret environment variable in the Render dashboard
(Dashboard → Service → Environment → Add Secret).

---

## Server Configuration

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| — | `server.port` | Integer | `8080` | HTTP port the application listens on. |
| — | `spring.application.name` | String | `taxonomy-analyzer` | Application name (used in logs). |
| — | `spring.thymeleaf.cache` | Boolean | `false` | Set to `true` in production for template caching. |

---

## Database Configuration

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| `TAXONOMY_DATASOURCE_URL` | `spring.datasource.url` | String | `jdbc:hsqldb:mem:taxonomydb;DB_CLOSE_DELAY=-1` | JDBC URL. Defaults to in-memory HSQLDB (local dev / tests). Set to `jdbc:hsqldb:file:/app/data/taxonomydb;hsqldb.default_table_type=cached` for production disk-backed storage. |
| — | `spring.datasource.driver-class-name` | String | `org.hsqldb.jdbc.JDBCDriver` | JDBC driver class. |
| `TAXONOMY_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | String | `create` | Schema generation strategy. `create` rebuilds on each start (safe for in-memory default). Set to `update` for file-based deployments so data is not wiped on restart. |
| — | `spring.jpa.show-sql` | Boolean | `false` | Whether to log SQL statements. |

---

## Hibernate Search / Lucene

| Variable | Property | Type | Default | Description |
|---|---|---|---|---|
| — | `spring.jpa.properties.hibernate.search.enabled` | Boolean | `true` | Enable Hibernate Search integration. |
| — | `spring.jpa.properties.hibernate.search.backend.type` | String | `lucene` | Search backend type. |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | `spring.jpa.properties.hibernate.search.backend.directory.type` | String | `local-heap` | Index storage. `local-heap` = in-memory (local dev / tests). Set to `local-filesystem` for production disk-backed deployments. |
| `TAXONOMY_SEARCH_DIRECTORY_ROOT` | `spring.jpa.properties.hibernate.search.backend.directory.root` | String | `/app/data/lucene-index` | Root directory for the Lucene index files (only used when `TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem`). |

---

## OpenAPI / Swagger UI

The application exposes interactive API documentation via [springdoc-openapi](https://springdoc.org/).

| URL | Description |
|---|---|
| `/swagger-ui.html` | Interactive Swagger UI |
| `/v3/api-docs` | OpenAPI 3.0 JSON specification |

To disable in production (optional):

```properties
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
```
