# Civilian acceptance validation

The reproducible contract and source requirement are described in the
[acceptance guide](../testing/civilian-acceptance.md). This record distinguishes
executed checks from pending visual/product acceptance.

## Executed locally

- The semantic playback tests failed before implementation and pass with the
  implementation: rule order and repeated calls, unknown requirement/keys/budget/
  task, duplicate rules.
- Real authenticated HTTP creation, two complete Copilot verification passes,
  78 remote response playbacks, snapshot persistence/reopening, and 17 generated
  export artifacts pass. The graph contains 38 elements and 44 relationships.
- Focused regressions cover qualified ancestor suppression without suppressing
  sibling branches, human-readable snapshot titles and PDF label size/bounds.
  Each defect was reproduced before its fix.
- The PDF was rendered with Poppler and visually inspected. It now uses a wider
  poster page to preserve natural-size labels, and places long type names on
  their own line. The overall graph still needs zoom or a large print format;
  taxonomy hypotheses are not an approved application design.
- The actual decision DOCX was rendered through LibreOffice to 87 PDF/PNG pages.
  Inspection of pages 1–4 and 50 found an empty body on page 2, repeated long
  rationales in narrow table columns and substantial pagination. This is a
  concrete open presentation finding, not a successful full-document visual
  approval. The parser/provenance assertions remain valid; compact, readable
  decision-report pagination needs separate corrective work.

## Environment limits and pending evidence

Local Chromium cannot create its process singleton socket (`Operation not
permitted`). Automatic approval review rejected sandbox escalation. Docker is
also unavailable. Browser clicks, responsive layout and the five documentation
screenshots must therefore be established by the Maven-owned CI scenario; no
screenshots are fabricated or marked as successful here.

The independent review found two important browser-test gaps, now corrected:
EXHAUSTIVE selects the required two passes, and actual downloaded bytes / exact
focus neighbors are checked instead of accepting clicks alone. No critical issues
were reported. The browser checks still require an executed CI run.

The full repository verification is running with the pinned ONNX model. The exact
browser profile command was executed locally and reached Testcontainers, which
failed because `/var/run/docker.sock` is absent.
Desktop import into Sparx EA / Microsoft Visio, independent Structurizr grammar
validation, and a domain review of the flood information design are not claimed.
