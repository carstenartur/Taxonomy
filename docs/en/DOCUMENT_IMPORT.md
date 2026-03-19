# Document Import & Source Provenance

## Overview

The Taxonomy Analyzer supports importing requirements directly from PDF and DOCX
documents.  This is especially useful when working with administrative
regulations (*Verwaltungsvorschriften*), technical specifications, or any other
document that contains requirement-like statements.

Every requirement in the system is **traceable to its source**.  Whether a
requirement was typed manually, imported from a regulation document, or derived
from a meeting note, the provenance information is recorded and visible in the
UI.

## Source Provenance Model

Every requirement is linked to a **Source Artifact** — a logical identity of the
material it originated from.  Source types include:

| Source Type        | Description                                      |
|--------------------|--------------------------------------------------|
| Business Request   | A free-form business requirement                 |
| Regulation         | An administrative or legal regulation            |
| Uploaded Document  | A file uploaded directly (PDF, DOCX)             |
| Meeting Note       | A requirement captured during a meeting          |
| Manual Entry       | A requirement created manually in the system     |

Each source artifact can have one or more **Source Versions** (concrete
snapshots) and **Source Fragments** (traceable paragraphs or sections within a
version).

## Uploading a Document

1. Open the **Analyze** tab
2. Expand the **📄 Document Import** panel
3. Select a PDF or DOCX file
4. Optionally enter a title (e.g. "BayVwVfG §23")
5. Select the source type (Regulation, Business Request, Document, Meeting Note)
6. Click **📄 Upload & Extract**

The system will:
- Parse the document and extract text
- Identify section headings where possible
- Split the content into requirement candidate paragraphs
- Register the document as a source artifact for provenance tracking

## Reviewing Extracted Candidates

After upload, the **Extracted Requirement Candidates** panel appears showing:

- Source document metadata (filename, type, page count)
- A numbered list of candidate paragraphs with section headings
- Checkboxes to select or deselect individual candidates

You can:
- **Select All** / **Deselect All** to quickly adjust the selection
- Review and deselect irrelevant paragraphs (e.g. table of contents, headers)
- Click **🔍 Analyze Selected** to transfer the selected candidates to the
  analysis text area

## Analyzing Imported Requirements

When you click **Analyze Selected**, the selected candidates are combined into a
single requirement text and placed in the standard analysis text area.  You can
then:

- Edit the text further if needed
- Click **Analyze with AI** to run the standard analysis workflow
- Use all existing features (scored tree, architecture view, export, etc.)

The source provenance information is preserved and displayed in the
**🔗 Source Provenance** panel below the analysis area.

## Provenance Visibility

After importing and analyzing a document, the **Source Provenance** panel shows:

- **Source**: The original document filename
- **Type**: The source type (Regulation, Business Request, etc.)
- **Artifact ID**: A unique identifier for traceability
- **Candidates**: Number of candidates selected for analysis
- **Pages**: Total page count of the source document

## Supported Formats

| Format | Extension | Support Level |
|--------|-----------|---------------|
| PDF    | `.pdf`    | Full text extraction, heading detection |
| DOCX   | `.docx`   | Full text extraction, heading detection |

## DSL Representation

When an analysis with source provenance is committed to Git, the provenance
information is represented in the DSL as `source`, `sourceVersion`, and
`requirementSourceLink` blocks.  This enables full traceability in the
version-controlled architecture repository.

See the [User Guide](USER_GUIDE.md#source-provenance-in-the-dsl) for DSL syntax
details.

## Limitations

- The rule-based parser extracts **requirement candidates** but does not interpret legal
  meaning.  Users must review and select relevant candidates.
- Not all paragraphs in a regulation are requirements — the parser intentionally
  casts a wide net and relies on user review.
- Section heading detection works best with standard heading formats (§, Art.,
  numbered sections).
- Page-level attribution is available for PDF documents but may not be precise
  for all layouts.
- AI-assisted extraction and regulation mapping require a configured LLM provider
  (e.g. Gemini API key).  These modes are unavailable when no LLM is configured.
- AI extraction quality depends on document structure and language.  German
  administrative regulations are best supported by the specialized
  `extract-regulation` prompt.
- This is the first stage of administrative integration.  Future versions will
  support FIM catalogue import, XÖV schema mapping, and 115 knowledge base
  connections.

## Import Modes

The Document Import panel offers three modes:

| Mode | Icon | Best For | Uses AI? |
|------|------|----------|----------|
| **Extract Candidates** | 📝 | Quick paragraph extraction | ❌ Rule-based |
| **AI-Assisted Extraction** | 🤖 | Intelligent requirement detection | ✅ LLM |
| **Direct Architecture Mapping** | 🏛️ | Known regulations → architecture | ✅ LLM |

### Extract Candidates (Default)

Rule-based paragraph splitting.  Fast, no API cost.
Best for exploring unfamiliar documents.  Uses heading detection and paragraph
splitting to identify candidate paragraphs.

### AI-Assisted Extraction

Uses a specialized LLM prompt to identify actual requirements within the
document text.  The AI:

- Filters out boilerplate, headers, and non-requirement content
- Classifies each requirement (FUNCTIONAL, ORGANIZATIONAL, TECHNICAL, LEGAL, PROCESS)
- Assigns a confidence score (0.0–1.0) to each extraction
- Preserves section/paragraph references where identifiable

Best when you want cleaner, fewer, more precise candidates.

Two prompt variants are available (configurable in the Admin panel):

| Prompt | Code | Best For |
|--------|------|----------|
| General Extraction | `extract-default` | Any document type |
| Regulation Extraction | `extract-regulation` | German administrative regulations |

The system automatically selects `extract-regulation` when the source type is
set to "Regulation".

### Direct Architecture Mapping

Sends the regulation directly to the LLM along with the full taxonomy node list.
Returns architecture node matches with:

- **Node Code**: The matched taxonomy element
- **Link Type**: MANDATES, REQUIRES, ENABLES, CONSTRAINS, or REFERENCES
- **Confidence**: 0.0–1.0 match confidence
- **Paragraph Reference**: Source location in the regulation
- **Reason**: Brief justification for the match

Best for well-known regulations where you want immediate architecture impact
analysis without going through the standard scoring workflow.

## Prompt Customization

All three prompt families can be customized in the Admin panel under
**Prompt Templates**.  Templates are grouped by category:

- **📊 Scoring** — Standard taxonomy scoring prompts (per root code)
- **📄 Extraction** — AI-assisted document extraction prompts
- **🏛️ Regulation Mapping** — Regulation-to-architecture mapping prompts
- **📝 Justification** — Leaf-node justification prompts

## Best Practices

1. **Always review candidates** before analysis — not every paragraph is a
   requirement
2. **Use descriptive titles** when uploading to make source tracking easier
3. **Select the correct source type** to maintain accurate provenance records
4. **Combine related candidates** into a single analysis run for coherent
   architecture views
