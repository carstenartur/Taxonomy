# Taxonomy

NATO NC3T Taxonomy Browser — a Spring Boot web application that loads the
C3 Taxonomy Catalogue (Baseline 7, 8 sheets, ~2,500 nodes) from the bundled
Excel file into an in-process HSQLDB, visualises the hierarchy as a collapsible
tree, and lets you match a plain-text business requirement against the taxonomy
using Google Gemini.

## Features

* Full taxonomy tree browser (Bootstrap 5, collapsible nodes)
* Free-text business-requirement analyser powered by **Google Gemini 1.5 Flash**
* Recursive match: root → matching children → their children … until leaf nodes
* Results overlaid on the tree with **green shading** (intensity = match %)
* All taxonomy data kept in an **in-process HSQLDB** (no external DB needed)

## Quick Start (local)

```bash
# Build & run
mvn spring-boot:run

# or with Docker
docker build -t nato-taxonomy .
docker run -p 8080:8080 -e GEMINI_API_KEY=<your-key> nato-taxonomy
```

Then open <http://localhost:8080>.

## Gemini API key

Set the `GEMINI_API_KEY` environment variable to enable AI analysis.
Without it the app still works as a pure taxonomy browser (all scores = 0).

Obtain a free key at <https://aistudio.google.com/app/apikey>.

## CI / CD

Every push triggers the **CI / CD** GitHub Actions workflow:

| Step | What happens |
|---|---|
| **Build & Test** | `mvn verify` — compiles, runs 10 integration tests |
| **Publish Docker Image** | Pushes to GitHub Container Registry (`ghcr.io`) |
| **Deploy to Render** | Triggers a Render deploy hook (if secret is set) |

### One-click deployment on Render.com

1. Create a free account at <https://render.com> and connect your GitHub repo.
2. Render detects `render.yaml` and creates a **Web Service** automatically.
3. In the Render dashboard → *Environment* → add `GEMINI_API_KEY`.
4. For automatic re-deploys on every CI success, copy the **Deploy Hook URL**
   from *Settings → Deploy Hook* and add it as a GitHub secret named
   `RENDER_DEPLOY_HOOK_URL`.

### Pull the published Docker image

```bash
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run -p 8080:8080 -e GEMINI_API_KEY=<your-key> ghcr.io/carstenartur/taxonomy:latest
```
