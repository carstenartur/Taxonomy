# Help links and selected capture repair — 26 November 2026

## Runtime help

The help controller now resolves rendered link/image attributes relative to the actual packaged Markdown resource. Registered documents remain inside the help reader. Explicit language links retain their language. Supplementary repository documents retain the correct directory, query and fragment. Fenced examples are not rewritten. The reader applies the application context to both links and images, including capture-provenance downloads.

Stable aliases now connect German transparency/concept sections to their existing cross-document links. The German and English security guides contain actual authentication-audit and SBOM sections. Intake, version selection and report documentation describe the corrected controls and evidence boundaries.

Behavioral regressions first reproduced four failing cases, including 143 failed link/fragment checks across the registered documents. The broader corrected run passes 30 Java tests, including the eight help-reader callbacks and the repository documentation link policy. Independent review then exposed a query-only reference losing its current document; a new regression reproduced that failure and now checks the requested language, section and whole-document variants. The final affected selection passes 17 tests. These overlapping counts are not added together. They do not certify every external website or every browser.

## Actual replacement captures

Only `diagram-print-controls-de.png` and `diagram-print-controls-en.png` are replaced. Both were inspected at native dimensions and show readable controls and keyboard guidance without clipping. They come from the successful [documentation screenshot workflow](https://github.com/carstenartur/Taxonomy/actions/runs/45818579625), artifact `generated-documentation-screenshots` ID `15118456176`. The actual capture source is the GitHub test merge **a76704c4497750397bb610094d028f4cd5d86573**, tree **0edfe7584aeac90e4ef24736d374775045f5b788**, not a later correction commit.

The [unaltered generated manifest](../images/documentation-screenshots-2026-11-26.json) records sourceDirty=false, `./mvnw -B verify -Pscreenshots`, start 2026-11-26T12:21:17Z and capture 2026-11-26T12:35:21.039294656Z. Its application data is isolated deterministic mock/demo input; it is not a live-provider evaluation.

| Capture | Dimensions | SHA-256 |
| --- | --- | --- |
| German toolbar | 729 × 167 | a90f6255a69e3a7a1a3900ed8803244992f37b65d7d5b99c88904d4a7fa3e772 |
| English toolbar | 729 × 130 | fd79e91e79e2af0def154632d462cd15d17d828406cb121f99cc7099efeff38e |

The [active capture inventory](../images/documentation-screenshots.json) preserves its original global November 7 source metadata and the other 75 records. Each of the two replacements has explicit overriding source metadata, a link to the unaltered new manifest, its previous claimed record and the digest of the actual replaced bytes. Both prior records disagreed with the bytes present before this repair; their provenance is not retrospectively asserted. All 77 active PNG hashes now match the retained bytes.

The [original retained November 7 manifest](../images/documentation-screenshots-2026-11-07.json) is archived byte-for-byte from the pre-repair source. It remains distinct from the derived active inventory. Older October capture records remain unchanged as historical evidence. The new full generated manifest is provenance for its own CI run; it does not relabel the 75 unchanged repository PNGs as new captures.
