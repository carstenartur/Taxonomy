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

## Limitations

- The parser extracts **requirement candidates** but does not interpret legal
  meaning.  Users must review and select relevant candidates.
- Not all paragraphs in a regulation are requirements — the parser intentionally
  casts a wide net and relies on user review.
- Section heading detection works best with standard heading formats (§, Art.,
  numbered sections).
- Page-level attribution is available for PDF documents but may not be precise
  for all layouts.
- This is the first stage of administrative integration.  Future versions will
  support FIM catalogue import, XÖV schema mapping, and 115 knowledge base
  connections.

## Best Practices

1. **Always review candidates** before analysis — not every paragraph is a
   requirement
2. **Use descriptive titles** when uploading to make source tracking easier
3. **Select the correct source type** to maintain accurate provenance records
4. **Combine related candidates** into a single analysis run for coherent
   architecture views
