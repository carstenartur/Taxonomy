# Taxonomy

NATO NC3T Taxonomy Browser — a Spring Boot web application that loads the
C3 Taxonomy Catalogue (Baseline 7, 8 sheets, ~2,500 nodes) from the bundled
Excel file into an in-process HSQLDB, visualises the hierarchy as a collapsible
tree, and lets you match a plain-text business requirement against the taxonomy
using an AI language model.

## Features

* Full taxonomy tree browser (Bootstrap 5, collapsible nodes)
* Free-text business-requirement analyser powered by **multiple LLM providers**
  (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral)
* Recursive match: root → matching children → their children … until leaf nodes
* Results overlaid on the tree with **green shading** (intensity = match %)
* All taxonomy data kept in an **in-process HSQLDB** (no external DB needed)
* AI availability indicator: the Analyze button is disabled with a clear warning
  when no API key is configured

## Quick Start (local)

```bash
# Build & run
mvn spring-boot:run

# or with Docker
docker build -t nato-taxonomy .
docker run -p 8080:8080 -e GEMINI_API_KEY=<your-key> nato-taxonomy
```

Then open <http://localhost:8080>.

## LLM Provider Configuration

The app supports multiple AI providers.  Set **one** of the following
environment variables to enable AI analysis:

| Provider  | Environment Variable  | Notes                              |
|-----------|-----------------------|------------------------------------|
| Gemini    | `GEMINI_API_KEY`      | Free key at aistudio.google.com    |
| OpenAI    | `OPENAI_API_KEY`      |                                    |
| DeepSeek  | `DEEPSEEK_API_KEY`    |                                    |
| Qwen      | `DASHSCOPE_API_KEY`   | Alibaba Cloud DashScope            |
| Llama     | `LLAMA_API_KEY`       |                                    |
| Mistral   | `MISTRAL_API_KEY`     |                                    |

**Provider selection priority:**

1. `LLM_PROVIDER` environment variable (values: `GEMINI`, `OPENAI`, `DEEPSEEK`,
   `QWEN`, `LLAMA`, `MISTRAL`)
2. Auto-detected from whichever API key is set (order as in the table above)
3. Default: Gemini (app still works as a browser if no key is set — all scores = 0)

Without any API key the app still works as a pure taxonomy browser (all scores = 0)
and the Analyze button is disabled with an explanatory warning.

## CI / CD

Every push triggers the **CI / CD** GitHub Actions workflow:

| Step | What happens |
|---|---|
| **Build & Test** | `mvn verify` — compiles, runs integration tests |
| **Publish Docker Image** | Pushes to GitHub Container Registry (`ghcr.io`) |
| **Deploy to Render** | Triggers a Render deploy hook (if secret is set) |

### One-click deployment on Render.com

1. Create a free account at <https://render.com> and connect your GitHub repo.
2. Render detects `render.yaml` and creates a **Web Service** automatically.
3. In the Render dashboard → *Environment* → add one of the API key variables above.
4. Optionally set `LLM_PROVIDER` to pin a specific provider.
5. For automatic re-deploys on every CI success, copy the **Deploy Hook URL**
   from *Settings → Deploy Hook* and add it as a GitHub secret named
   `RENDER_DEPLOY_HOOK_URL`.

### Pull the published Docker image

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run -p 8080:8080 -e GEMINI_API_KEY=<your-key> ghcr.io/carstenartur/taxonomy:latest
```

