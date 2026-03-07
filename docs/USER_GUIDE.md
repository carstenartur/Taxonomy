# NATO NC3T Taxonomy Browser — User Guide

## Table of Contents

1. [Overview](#1-overview)
2. [Getting Started](#2-getting-started)
3. [Understanding the Interface](#3-understanding-the-interface)
4. [Analyzing a Business Requirement](#4-analyzing-a-business-requirement)
5. [Exploring the Taxonomy](#5-exploring-the-taxonomy)
6. [Working with Analysis Results](#6-working-with-analysis-results)
7. [Architecture View](#7-architecture-view)
8. [Using the Graph Explorer](#8-using-the-graph-explorer)
9. [Working with Relation Proposals](#9-working-with-relation-proposals)
10. [Exporting Results](#10-exporting-results)
11. [Search](#11-search)
12. [Administration](#12-administration)
13. [Relation Types Reference](#13-relation-types-reference)
14. [Tips and Best Practices](#14-tips-and-best-practices)
15. [Glossary](#15-glossary)
16. [Troubleshooting](#16-troubleshooting)

---

## 1. Overview

The **NATO NC3T Taxonomy Browser** is a web application that helps Architects, Analysts, and Requirements Engineers map free-text mission and business requirements to the NATO C3 Taxonomy catalogue. You describe what you need in plain English, and the application finds the most relevant taxonomy nodes, shows you how they relate to each other, and lets you export structured diagrams.

**Who is this guide for?**

- **Requirements Engineers** who need to classify and map requirements to architecture elements.
- **Architects and Capability Planners** who use the taxonomy to design or assess C3 systems.
- **Analysts** exploring the taxonomy structure and reviewing AI-generated relation proposals.

**What you can do with the application:**

| Task | Where to find it |
|---|---|
| Analyze a requirement and see matching taxonomy nodes | Right panel → Business Requirement Analysis card |
| Browse the taxonomy in different visual layouts | Left panel → view switcher buttons |
| Drill into why a node scored highly | Click 📋 on any scored node |
| Trace upstream/downstream dependencies | Right panel → Graph Explorer |
| Review and accept or reject AI-generated relation proposals | Right panel → Relation Proposals panel |
| Export a diagram or scoresheet | Left panel → export buttons (appear after analysis) |
| Manage LLM settings and prompt templates | Unlock admin mode via 🔒 in the navigation bar |

> For the REST API reference used by developers and integrators, see [API Reference](API_REFERENCE.md).

---

## 2. Getting Started

### Opening the Application

Open your web browser and navigate to the application URL (for example `http://localhost:8080` when running locally, or the deployed URL provided by your administrator).

The application loads as a single page. No login is required for standard use; administrator features require unlocking admin mode (see [Section 12](#12-administration)).

> 📸 **Screenshot:** [Full page on first load — taxonomy tree on the left, analysis panel on the right]
>
> *TODO: Add screenshot of the application on first load*

### Checking AI Availability

Look at the navigation bar at the top of the page. There is an **AI Status** indicator:

- 🟢 **Green badge** — an LLM provider is connected and analysis is available.
- 🔴 **Red badge** — no LLM provider is configured; analysis is unavailable. Contact your administrator.

---

## 3. Understanding the Interface

The application is divided into two main panels side by side.

### Left Panel — Taxonomy Tree

The left panel (wider column) displays the **Taxonomy Tree**. This is the full catalogue of NATO C3 capabilities, services, roles, and information products.

At the top of the left panel you will find:

- **View switcher buttons:** 📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision — switch between different visualisations of the taxonomy.
- **Export buttons** (appear only after a successful analysis): 📥 SVG | 📥 PNG | 📥 PDF | 📥 CSV | 📥 Visio | 📥 ArchiMate
- **Expand All / Collapse All** — expand or collapse all nodes in the current view.
- **Taxonomy root selector** (Tree view only) — choose which taxonomy root to display.
- **Descriptions toggle switch** — show or hide the description text for each node.

Each taxonomy node row shows:
- The **node name** and its hierarchical code.
- A **score bar** and percentage (visible after analysis).
- **Per-node action buttons:** 🔗 (Propose Relations) | 📋 (Request Justification) | 🔎 (Graph Explorer)

> 📸 **Screenshot:** [Left panel showing taxonomy tree with nodes, score bars, and action buttons]
>
> *TODO: Add screenshot of the left panel in List view*

### Right Panel — Analysis and Tools

The right panel (narrower column) contains all interactive tools:

- **Business Requirement Analysis card** — the main textarea where you type a requirement and run the analysis.
- **Match Legend** — colour scale from 0 % to 100 % showing what each green shade means.
- **Status Area** — messages and warnings about the current operation.
- **Analysis Log** (collapsible) — step-by-step log of the scoring process.
- **Architecture View Panel** — appears after analysis when the Architecture View checkbox is enabled.
- **Graph Explorer Panel** — enter a node code and run upstream, downstream, or failure-impact queries.
- **Relation Proposals Panel** — review, accept, or reject AI-generated relation proposals.
- **LLM Communication Log** (admin only, collapsible) — raw prompt and response log.
- **LLM Diagnostics Panel** (admin only, collapsible) — connection test and statistics.
- **Prompt Templates Editor** (admin only, collapsible) — view and edit the LLM prompt templates.

> 📸 **Screenshot:** [Right panel with Business Requirement Analysis card, Match Legend, and Status Area visible]
>
> *TODO: Add screenshot of the right panel in its default (empty) state*

### Navigation Bar

The navigation bar at the top of the page contains:

- **Application title / logo**
- **AI Status badge** (🟢 green or 🔴 red)
- **🔒 Admin mode button** — click to open the Admin Mode password modal

---

## 4. Analyzing a Business Requirement

### Writing a Good Requirement

In the **Business Requirement Analysis** card on the right panel, you will see a large textarea labelled something like *"Enter your business requirement…"*.

Type your requirement as a clear, imperative sentence. For example:

> *"Provide secure voice communications between HQ and deployed forces."*

Tips for good requirements:
- Use domain vocabulary: capability, service, information product, communications, command, control.
- Be specific about the function or outcome you need.
- Keep the text under 500 words; longer text does not improve accuracy.

> 📸 **Screenshot:** [Business Requirement Analysis card with a requirement typed in the textarea]
>
> *TODO: Add screenshot of the analysis panel with example requirement text*

### Standard Analysis

1. Type your requirement in the textarea.
2. Make sure the **Interactive Mode** checkbox is **unchecked** for a standard (full-tree) analysis.
3. Click the **Analyze with AI** button.
4. A progress indicator appears in the Status Area. The taxonomy tree in the left panel will start showing colour-coded score bars as results arrive.
5. When analysis is complete, the Status Area shows a summary message and the export buttons become available.

> 📸 **Screenshot:** [Taxonomy tree showing green score bars during or after analysis]
>
> *TODO: Add screenshot of the scored taxonomy tree in List view*

### Interactive Mode

Tick the **Interactive Mode** checkbox before clicking **Analyze with AI** to use level-by-level exploration instead of scoring the whole tree at once.

In Interactive Mode:
- Only the top-level nodes are scored first.
- A **▶ Analyze Node** button appears next to each top-level node.
- Click **▶ Analyze Node** on a node to score its children.
- Continue expanding the tree level by level.

This mode is useful for very large taxonomies or when you want to focus on one branch.

> 📸 **Screenshot:** [Interactive Mode in progress — some nodes scored, Analyze Node buttons visible]
>
> *TODO: Add screenshot of interactive mode with partially expanded tree*

### Architecture View Checkbox

Tick the **Architecture View** checkbox before running analysis to also build an architecture view after the scores are computed. The Architecture View traces how the highest-scoring nodes relate to each other through confirmed architecture relationships. See [Section 7](#7-architecture-view) for details.

### Understanding Scores and the Colour Legend

The **Match Legend** (below the analysis card) shows the colour scale:

| Colour | Score range | Meaning |
|---|---|---|
| Light green (faint) | 0 % – 24 % | Very low match |
| Green | 25 % – 49 % | Low match |
| Medium green | 50 % – 74 % | Moderate match |
| Dark green | 75 % – 99 % | Good match |
| Bright green / highlighted | 100 % | Perfect match |

Nodes with a score of 0 % are not highlighted. The higher the score, the darker and more prominent the green highlight on the node row.

> 📸 **Screenshot:** [Match Legend component showing the green gradient from 0% to 100%]
>
> *TODO: Add screenshot of the Match Legend close-up*

### The Analysis Log

Below the Status Area, a collapsible **Analysis Log** section records each step of the scoring process: which LLM phases ran, how many nodes were scored, and any warnings. Click the log header to expand or collapse it.

---

## 5. Exploring the Taxonomy

The left panel displays the taxonomy in five different views. Switch between them using the buttons at the top: **📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision**.

### List View (Default)

The default view shows all taxonomy nodes as a flat, indented list. Each row contains the node name, its code, and — after analysis — a score bar and percentage.

- Click any node name to expand or collapse its children.
- Use **Expand All** to open the entire tree, or **Collapse All** to close it.
- Toggle the **Descriptions** switch to show or hide the description text beneath each node name.

> 📸 **Screenshot:** [List view with several nodes expanded and descriptions visible]
>
> *TODO: Add screenshot of the List view with descriptions toggled on*

### Tabs View

The Tabs view groups taxonomy nodes under tab headers for each top-level category. Click a tab to display only the nodes in that branch.

> 📸 **Screenshot:** [Tabs view with one tab selected and its nodes displayed below]
>
> *TODO: Add screenshot of the Tabs view*

### Sunburst View

The Sunburst view renders the taxonomy as a radial sunburst chart where the centre is the root and each ring is a deeper level. After analysis, segments are coloured by their score.

- Hover over a segment to see the node name and score.
- Click a segment to zoom into that subtree.

> 📸 **Screenshot:** [Sunburst visualisation with colour-coded segments]
>
> *TODO: Add screenshot of the Sunburst view after analysis*

### Tree View

The Tree view renders the taxonomy as an interactive node-link diagram. Use the **Taxonomy root selector** dropdown to choose which root to display when there are multiple taxonomy roots.

> 📸 **Screenshot:** [Tree visualisation showing hierarchy as a node-link diagram]
>
> *TODO: Add screenshot of the Tree view*

### Decision Map View

The Decision Map view shows the taxonomy as a decision-tree style layout optimised for selecting relevant nodes based on the analysis scores.

> 📸 **Screenshot:** [Decision Map visualisation]
>
> *TODO: Add screenshot of the Decision Map view*

### Switching Between Views

Click any of the view switcher buttons (📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision) at any time. Your analysis scores are preserved across view switches.

### Using Expand All / Collapse All

The **Expand All** and **Collapse All** buttons are only active in views that support hierarchical expansion (List and Tabs). They open or close all nodes simultaneously.

### Showing/Hiding Descriptions

The **Descriptions** toggle switch (above the tree, below the view buttons) controls whether the description text is shown beneath each node name. Turn it on to read what each taxonomy element covers; turn it off for a more compact view.

---

## 6. Working with Analysis Results

### Reading the Score Colours

After analysis completes, every taxonomy node shows a coloured score bar. Refer to the **Match Legend** on the right panel:

- **No colour** — score is 0 %, not relevant.
- **Light green → dark green** — increasing relevance.
- **Bright/full green** — maximum relevance.

Focus your attention on nodes with dark green highlights; these are the best matches for your requirement.

### Requesting a Leaf Justification (📋 button)

For any leaf node (a node with no children) that has a non-zero score, you can ask the AI to explain in plain English **why** that node matched your requirement.

1. Find the node in the taxonomy tree.
2. Click the **📋** button on that node's row.
3. A **Leaf Justification Modal** opens, displaying the LLM-generated explanation.
4. Read the explanation and close the modal when done.

> 📸 **Screenshot:** [Leaf Justification modal showing LLM explanation text]
>
> *TODO: Add screenshot of the Leaf Justification modal*

### Stale Results Warning

If you edit your requirement text after a completed analysis without re-running the analysis, the taxonomy tree may display a **yellow border** or warning message indicating that the displayed scores no longer match the current requirement text. Re-run the analysis to refresh the scores.

> 📸 **Screenshot:** [Stale results warning — yellow border or warning banner visible]
>
> *TODO: Add screenshot of the stale results warning state*

---

## 7. Architecture View

The Architecture View shows how the highest-scoring taxonomy nodes relate to each other through confirmed architecture relationships (stored in the knowledge base). It gives you a structured view of the architecture elements that are relevant to your requirement.

### Enabling the Architecture View Checkbox

Before running analysis, tick the **Architecture View** checkbox in the Business Requirement Analysis card. After analysis completes, the **Architecture View Panel** will appear in the right panel.

### What Appears in the Architecture View Panel

The panel shows three sections:

| Section | Contents |
|---|---|
| **Anchors** | The highest-scoring leaf nodes — the primary matches for your requirement |
| **Elements** | All taxonomy nodes reachable from the anchors via confirmed relations |
| **Relationships** | The directed edges connecting elements |

### Understanding Anchors, Elements, and Relationships

- **Anchors** are your direct hits — the nodes the AI considers the best answer to your requirement.
- **Elements** extend the picture: if an anchor node *realizes* a capability, that capability also appears as an element.
- **Relationships** show the direction and type of the link (e.g., REALIZES, SUPPORTS, DEPENDS_ON).

> 📸 **Screenshot:** [Architecture View panel populated with anchors, elements, and relationships]
>
> *TODO: Add screenshot of the Architecture View panel after analysis*

---

## 8. Using the Graph Explorer

The Graph Explorer lets you trace the network of confirmed architecture relationships around any taxonomy node, regardless of whether you have run an analysis.

### Selecting a Node

In the **Graph Explorer Panel** (right panel, below the Architecture View Panel):

1. Type a node code in the **Node Code** field, or click the **🔎 Graph** button on any taxonomy node in the left panel to pre-fill the field.
2. Set the **Max Hops** value to control how many relationship steps to traverse (default: 2).

> 📸 **Screenshot:** [Graph Explorer panel with a node code entered and Max Hops set]
>
> *TODO: Add screenshot of the Graph Explorer panel before running a query*

### Upstream Query — "What feeds into this?"

Click **⬆️ Upstream** to find all nodes that feed into the selected node: the nodes it depends on or that realise it. The results appear as a table listing each related node, its relation type, and a relevance indicator.

### Downstream Query — "What depends on this?"

Click **⬇️ Downstream** to find all nodes that depend on the selected node.

### Failure Impact Query — "What breaks if this fails?"

Click **⚠️ Failure Impact** to find all nodes that would be disrupted if the selected node failed or was removed. This is useful for change-impact analysis and risk assessment.

### Understanding the Results Table

The results table shows:

| Column | Meaning |
|---|---|
| Node code | The unique identifier of the related node |
| Node name | Human-readable label |
| Relation type | The type of relationship (e.g., REALIZES, DEPENDS_ON) |
| Hops | Distance from the starting node |
| Relevance | Impact score or similarity indicator |

> 📸 **Screenshot:** [Graph Explorer with upstream results table populated]
>
> *TODO: Add screenshot of the Graph Explorer showing upstream query results*

> 📸 **Screenshot:** [Graph Explorer with failure impact results table populated]
>
> *TODO: Add screenshot of the Graph Explorer showing failure impact results*

---

## 9. Working with Relation Proposals

The system can automatically propose new relations between taxonomy nodes using AI. These proposals are stored in a review queue where you can accept or reject them.

### Triggering Proposals (�� button on a Node)

1. Find a taxonomy node in the left panel that you believe should be related to other nodes.
2. Click the **🔗** (Propose Relations) button on that node's row.
3. The **Propose Relations Modal** opens.

> 📸 **Screenshot:** [Propose Relations modal with node code and relation type dropdown visible]
>
> *TODO: Add screenshot of the Propose Relations modal*

### Choosing a Relation Type

In the Propose Relations Modal:

1. Confirm or note the **Node Code** displayed at the top.
2. Use the **Relation Type** dropdown to select the type of relation you want the AI to propose (e.g., REALIZES, SUPPORTS, DEPENDS_ON).
3. Click **Generate Proposals**.
4. The system will search for candidate nodes and create PENDING proposals. Close the modal when the operation completes.

### Reviewing Proposals (Pending / All / Accepted / Rejected Filters)

Open the **Relation Proposals Panel** in the right panel. Use the filter buttons to view proposals by status:

- **Pending** — proposals awaiting your decision.
- **All** — all proposals regardless of status.
- **Accepted** — proposals you have already approved.
- **Rejected** — proposals you have declined.

The proposals table shows:
- Source node and target node (with names and codes)
- Proposed relation type
- Confidence score (0–100 %) — how confident the AI is in the proposal
- AI-generated rationale explaining why this relation was suggested

> 📸 **Screenshot:** [Relation Proposals panel with the Pending filter active and a list of proposals]
>
> *TODO: Add screenshot of the Relation Proposals panel showing pending proposals*

### Accepting or Rejecting a Proposal

For each row in the proposals table:

- Click **Accept** to approve the relation. A confirmed `TaxonomyRelation` is created in the knowledge base and the proposal status changes to ACCEPTED.
- Click **Reject** to decline the relation. The proposal status changes to REJECTED.

> **Note:** There is currently no undo for accept or reject decisions in the UI.

### Understanding Confidence Scores and Rationale

The **Confidence** column shows how strongly the AI believes the proposed relation is correct. A higher score means a more confident proposal. The **Rationale** column shows the AI's reasoning in plain text. Use both together to decide whether to accept or reject.

---

## 10. Exporting Results

After a successful analysis, export buttons appear at the top of the left panel. These buttons are only visible when analysis scores are present.

> 📸 **Screenshot:** [Export button group visible at the top of the left panel after analysis]
>
> *TODO: Add screenshot of the export button group in its visible (post-analysis) state*

### SVG Export

Click **📥 SVG** to download the current taxonomy view as a scalable vector graphics (SVG) file. Suitable for embedding in documents or further editing in vector graphics software.

### PNG Export

Click **📥 PNG** to download a rasterised screenshot of the current taxonomy view as a PNG image.

### PDF (Print)

Click **📥 PDF** to trigger the browser's print dialogue, pre-configured to print the current view as a PDF.

### CSV (Scores)

Click **📥 CSV** to download a comma-separated file containing all node codes, names, and their analysis scores. Open in a spreadsheet application for further analysis or reporting.

### Visio (.vsdx) Architecture Diagram

Click **📥 Visio** to download a structured Microsoft Visio file (`.vsdx`) representing the Architecture View. The diagram includes the anchor nodes, related elements, and labelled relationships.

> **Requires:** The Architecture View checkbox must have been enabled before running the analysis.

### ArchiMate XML Architecture Diagram

Click **📥 ArchiMate** to download an ArchiMate 3.x XML file suitable for import into tools such as Archi or Sparx EA.

> **Requires:** The Architecture View checkbox must have been enabled before running the analysis.

### When Export Buttons Appear

The export buttons only appear after analysis has been run and scores are present. If you navigate away or refresh the page, scores may be lost and the buttons will disappear. Re-run the analysis to restore them.

---

## 11. Search

> **Note:** Advanced search (semantic, hybrid, graph) is currently available via the REST API. See [API Reference](API_REFERENCE.md) for endpoint details. A search UI panel may be added in a future release.

Basic taxonomy browsing and filtering is available directly in the taxonomy tree view using the view switcher and the expand/collapse controls described in [Section 5](#5-exploring-the-taxonomy).

---

## 12. Administration

Administration features are hidden behind a password-protected admin mode. A standard user does not need to access these features.

### AI Status Indicator (🟢 / 🔴 in Navbar)

The badge in the navigation bar shows whether an LLM provider is connected:

- 🟢 **Green** — AI is available; analysis and justification features are active.
- 🔴 **Red** — AI is unavailable; the **Analyze with AI** button will be disabled.

If you see a red badge, contact your administrator to check the LLM provider configuration.

### Unlocking Admin Mode (🔒 button → Password Modal)

1. Click the **🔒** button in the navigation bar.
2. The **Admin Mode Modal** opens with a password input field.
3. Enter the administrator password.
4. Click **Unlock**.
5. The padlock icon changes to indicate admin mode is active, and the admin-only panels become visible in the right panel.

To lock admin mode again, click the lock button and choose **Lock**.

> 📸 **Screenshot:** [Admin Mode modal showing the password input and Unlock button]
>
> *TODO: Add screenshot of the Admin Mode modal*

### LLM Communication Log

Once admin mode is unlocked, the **LLM Communication Log** panel is visible in the right panel. It records the full prompt sent to the LLM and the raw response received for each analysis operation. Expand the panel to view the log entries. This is useful for debugging unexpected scoring results.

### LLM Diagnostics Panel

The **LLM Diagnostics Panel** (admin only, collapsible) shows statistics about LLM usage:

- Provider name and model version
- Total number of API calls
- Error count and error rate
- Average response latency

Click **Refresh** to update the statistics. Click **Test Connection** to send a test request to the LLM provider and confirm it is responding correctly.

> 📸 **Screenshot:** [LLM Diagnostics panel showing provider info and stats]
>
> *TODO: Add screenshot of the LLM Diagnostics panel*

### Prompt Template Editor

The **Prompt Templates Editor** (admin only, collapsible) allows you to customise the instructions sent to the LLM without redeploying the application.

1. Use the **taxonomy selector** dropdown to choose the prompt template you want to edit.
2. The current template text appears in the **template textarea**.
3. Edit the text as needed.
4. Click **Save** to save your changes, or **Reset** to restore the built-in default.

> 📸 **Screenshot:** [Prompt Template Editor panel with a template loaded and the Save/Reset buttons visible]
>
> *TODO: Add screenshot of the Prompt Template Editor panel*

---

## 13. Relation Types Reference

The system uses 10 relation types, each corresponding to a specific relationship in the NATO Architecture Framework (NAF) or The Open Group Architecture Framework (TOGAF).

| Relation Type | Plain-Language Meaning | Standard |
|---|---|---|
| **REALIZES** | A capability is made real by a service | NAF NCV-2, TOGAF SBB |
| **SUPPORTS** | A service supports a business process | TOGAF Business Architecture |
| **CONSUMES** | A business process consumes an information product | TOGAF Data Architecture |
| **USES** | A user application uses a core service | NAF NSV-1 |
| **FULFILLS** | A COI service fulfills a capability | NAF NCV-5 |
| **ASSIGNED_TO** | A business role is assigned to a business process | TOGAF Org mapping |
| **DEPENDS_ON** | One service depends on another service to function | Technical dependency |
| **PRODUCES** | A business process produces an information product | Data flow |
| **COMMUNICATES_WITH** | A communications service communicates with a core service | NAF NSOV |
| **RELATED_TO** | A general relationship when no specific type applies | Generic fallback |

---

## 14. Tips and Best Practices

### Writing Effective Requirements

- **Be specific:** Instead of *"communications"*, write *"secure voice communications between HQ and deployed forces"*.
- **Use domain vocabulary:** Terms like *capability*, *service*, *information product*, *command*, *control* help the AI find better matches.
- **One requirement at a time:** Analyze one requirement per session for cleaner, more focused results.
- **Keep it concise:** Aim for 1–3 sentences. Very long paragraphs do not improve accuracy.

### Interpreting Results

- Focus on nodes with scores above 50 % as your primary matches.
- Nodes scoring 25–50 % are secondary matches — they may be relevant but less directly.
- Nodes below 25 % can usually be ignored unless you have domain knowledge suggesting otherwise.
- Use **Leaf Justification** (📋) to understand *why* a specific node scored highly before including it in your architecture.

### Working with Proposals

- Run proposals soon after analysis while the context is fresh.
- Review the AI rationale before accepting; high confidence does not always mean correct.
- Reject proposals where the rationale does not make architectural sense, even if the confidence score is high.
- Accepted proposals become confirmed relations in the knowledge base and affect future Graph Explorer results.

### Exporting

- Use **CSV** to share scores with colleagues who do not have access to the application.
- Use **Visio** or **ArchiMate** export to integrate results into your enterprise architecture tooling.
- Always enable the **Architecture View** checkbox before analysis if you intend to export Visio or ArchiMate files.

---

## 15. Glossary

| Term | Definition |
|---|---|
| **Anchor node** | A high-scoring leaf node that directly satisfies a business requirement; the starting point for the Architecture View |
| **Architecture View** | A filtered subgraph of the taxonomy showing only the elements and relationships relevant to a given requirement |
| **ArchiMate** | An open standard modelling language for enterprise architecture, maintained by The Open Group |
| **C3** | Command, Control and Communications — the NATO functional area covered by this taxonomy |
| **Capability** | A bounded, outcome-oriented ability of an organisation or system (NAF, TOGAF) |
| **COI** | Community of Interest — a group that shares information under a common governance framework |
| **Confidence score** | A 0–100 % value indicating how strongly the AI believes a proposed relation is correct |
| **Graph Explorer** | The right-panel tool for running upstream, downstream, and failure-impact queries on the relation graph |
| **Hybrid search** | A retrieval strategy combining full-text and semantic search rankings (available via API) |
| **Information Product** | A specific, structured output of a business process (TOGAF Data Architecture) |
| **Interactive Mode** | An analysis mode that scores one tree level at a time instead of the whole tree at once |
| **Leaf node** | A taxonomy node with no children; the most specific level of the taxonomy |
| **LLM** | Large Language Model — the AI component used for scoring, justification, and proposal generation |
| **Match Legend** | The colour scale on the right panel showing what each shade of green corresponds to in terms of score |
| **NAF** | NATO Architecture Framework — the standard for describing NATO architectures |
| **Proposal** | An AI-generated candidate relation awaiting human review in the Relation Proposals panel |
| **Relation** | A confirmed, directed link between two taxonomy nodes stored in the knowledge base |
| **Stale results** | Analysis scores that no longer correspond to the current requirement text (shown with a yellow warning) |
| **Taxonomy node** | A single element in the C3 Taxonomy Catalogue (capability, service, role, information product, etc.) |
| **TOGAF** | The Open Group Architecture Framework — a widely used enterprise-architecture methodology |

---

## 16. Troubleshooting

### The "Analyze with AI" button is disabled or greyed out

**Cause:** No LLM provider is configured or available. The AI Status badge in the navigation bar will be 🔴 red.

**Action:** Contact your administrator to verify the LLM provider configuration (`GEMINI_API_KEY`, `OPENAI_API_KEY`, or `LLM_PROVIDER=LOCAL_ONNX`).

### Analysis runs but all scores are 0 %

**Cause:** The requirement text may not match any taxonomy nodes, or there may be a problem with the LLM response.

**Action:**
1. Check the **Analysis Log** (right panel, collapsible) for error messages.
2. Try rephrasing the requirement using more specific NATO/C3 domain terminology.
3. If admin mode is available, check the **LLM Communication Log** to see what the LLM returned.

### Export buttons are not visible

**Cause:** Export buttons only appear after a completed analysis with non-zero scores.

**Action:** Run an analysis first. If scores are present but buttons are still missing, try refreshing the page and re-running the analysis.

### The Visio or ArchiMate export file is empty or has no elements

**Cause:** The **Architecture View** checkbox was not enabled before running the analysis.

**Action:** Re-run the analysis with the **Architecture View** checkbox ticked.

### The taxonomy tree is not loading

**Cause:** The application server may be unavailable, or a network error occurred.

**Action:**
1. Refresh the browser page.
2. Check the browser console (F12) for error messages.
3. Verify the application URL is correct.
4. Contact your administrator if the problem persists.

### Scores from a previous analysis are still showing after I changed my requirement

**Cause:** Scores are not automatically cleared when you edit the requirement text.

**Action:** Click **Analyze with AI** again after editing your requirement to get fresh scores. The stale-results warning (yellow border) reminds you when displayed scores may be out of date.

### I cannot see the admin panels (LLM Diagnostics, Prompt Editor, etc.)

**Cause:** Admin mode is not unlocked.

**Action:** Click the **🔒** button in the navigation bar and enter the administrator password. If you do not know the password, contact your administrator.
