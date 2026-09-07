# Comparing Word-template package parts / Paketbestandteile vergleichen

## Deutsch

Unter **Vergleichen und wiederherstellen** zwei Versionen auswählen. Die Übersicht
zeigt hinzugefügte, geänderte und entfernte Bestandteile. **Inhalte vergleichen**
öffnet deren Textunterschiede; **Anzeigen** bleibt die unveränderte Einzelansicht.
Zurück zum Vergleich behält beide ausgewählten Versionen bei. Die ursprünglichen
DOTX-Versionen können aus der Vergleichsansicht heruntergeladen werden.

Die nummerierten Zeilen sind Anzeigeabschnitte, keine ursprünglichen XML-Zeilennummern.
Für die Lesbarkeit werden Abschnitte vor einem wörtlichen `<` und nach einem
Zeilenumbruch getrennt. Die Verkettung der Abschnitte ergibt wieder den vollständigen
Text; Leerzeichen, Kommentare, CDATA und Entitäten werden nicht normalisiert. Es wird
kein XML ausgeführt oder semantisch umgeschrieben. Der Vergleich verwendet JGits
vorhandenen Histogram-Diff, keinen neuen Diff-Algorithmus.

Die Vorschau ist je Seite auf 128 KiB und 2.048 Abschnitte begrenzt. Binärdaten,
nicht verlässlich als UTF-8 darstellbare Inhalte und größere Bestandteile erhalten
Metadaten und einen ausdrücklichen Hinweis statt eines irreführend leeren oder
abgeschnittenen Vergleichs. Für diese Inhalte sind die Originaldownloads maßgeblich.
Die Ansicht schreibt nicht ins Repository und verändert keine Wiederherstellungs-
vorbedingung. Word/WebDAV und lokale Bearbeitung bleiben eigenständige Zugänge.

## English

Select two revisions in **Compare and restore**, then choose **Compare contents**
for a package part. The existing **Inspect** action remains available. Returning to
the overview retains both revisions. Both original DOTX versions can be downloaded.

Rows are losslessly split display segments, not source-line numbers or parsed XML
nodes. The view does not normalize whitespace, comments, CDATA or entities. JGit's
existing Histogram algorithm computes the differences. No new XML parser, repository
copy, search service, dependency or frontend framework is introduced.

Preview limits are 128 KiB and 2,048 segments per side. Binary/unsupported text and
oversized inputs keep their metadata and original download links; a partial diff is
never presented as complete. The existing template service and access policy remain
authoritative. This guide lives in the repository, not a new application-help entry.

## Verification

`TemplateTextDiffTest` checks lossless reconstruction, minified XML, Unicode, CDATA,
line endings, hash collisions and both exact bounds. Controller tests cover immutable
revisions, added/deleted/unchanged parts, missing history and metadata-only fallbacks.
The existing document E2E follows the actual comparison links in both directions,
uses the revision selectors and keyboard navigation, checks original downloads and
captures the German desktop and English narrow-screen views before continuing the
unchanged restore/conflict journey in the same application.

The final acceptance command remains `./mvnw verify -DexcludedGroups="real-llm"`.
Implemented tests are not a passing result; current-head CI, review and visual
inspection are required. No Word-desktop or general accessibility certification is
implied by this bounded view.
