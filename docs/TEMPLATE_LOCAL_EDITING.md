# Local Word-template editing without WebDAV / Lokale Vorlagenbearbeitung ohne WebDAV

## Deutsch

Unter `/admin/document-templates` bei der vorhandenen Vorlage **Lokal bearbeiten
(ohne WebDAV)** wählen. Die eigene Bearbeitungsseite hält Vorlagen-ID, Namen und
vollständige Ausgangsrevision fest. Keine technische ID muss eingegeben werden.

1. **Diese Ausgangsversion herunterladen** wählen. In Word über **Datei → Öffnen**
   die DOTX-Vorlage selbst öffnen, bearbeiten und wieder als DOTX speichern.
   Ein aus der Vorlage erzeugtes DOCX-Dokument ist kein Vorlagen-Upload.
2. Auf **derselben Bearbeitungsseite** die lokale Datei auswählen und **Prüfen und
   als neue Version speichern** wählen. Word selbst baut keine Serververbindung
   auf; das Speichern in Word allein aktualisiert Taxonomy nicht.
3. Die gespeicherten Änderungen vergleichen. Für die Entscheidungsvorlage steht
   außerdem der bereits vorhandene Testbericht der **aktuellen Serverversion**
   zur Verfügung. Das ist ausdrücklich keine Vorabfreigabe vor Aktivierung.

Die Bearbeitungsadresse enthält `?revision=<vollständige Git-ID>`. Seite offen
lassen, über den Verlauf wieder öffnen oder als Lesezeichen speichern: Ein Reload
liest keine neue Ausgangsversion ein. Ein neuer Bearbeitungslink aus der Übersicht
ist ein neuer Ausgangspunkt und darf nicht zum Zurückladen einer Datei aus einem
älteren Download benutzt werden. Nach Verlust der Adresse die ursprüngliche
Version über die Historie klären, nicht einfach eine aktuelle Version unterstellen.

Der Browser sendet beim Upload das feste `If-Match` der Ausgangsversion. Bei
HTTP 412/409 bleiben Datei und Ausgangsversion erhalten; die parallelen Änderungen
müssen verglichen und manuell zusammengeführt werden. Es gibt weder einen
Überschreiben-Knopf noch automatisches Wiederholen oder Nachladen eines neueren
ETags. Bei verlorener Antwort zuerst die Historie prüfen: Der Commit kann bereits
existieren. Nach abgelaufener Anmeldung in einem anderen Tab erneut anmelden und
zur selben Bearbeitungsadresse zurückkehren.

Nur Administratoren dürfen speichern. Browser-Anmeldung, CSRF-Schutz,
Größenbeschränkung, OOXML-Sicherheitsprüfung, Pflichtplatzhalterprüfung und atomare
Git-Versionsprüfung bleiben im bestehenden API-/Service-Pfad. Erfolgreiche Uploads
sind unmittelbar aktive Versionen; ein gesonderter Entwurfs-/Freigabeprozess bleibt
Teil von #836. Das allgemeine Uploadformular legt nur zusätzliche neue Vorlagen an.
WebDAV bleibt optional; Windows-Richtlinien werden nicht geändert. Es wurde kein
zusätzlicher Übertragungsclient integriert oder als kompatibel zertifiziert.

## English

At `/admin/document-templates`, choose **Edit locally (without WebDAV)** on an
existing template. Its dedicated editing page fixes the template ID, display name
and full starting revision; users do not have to enter a technical ID.

Download that starting version, use **File → Open** in Word to edit the DOTX itself,
and save it as DOTX, not DOCX. Return to the **same editing page**, select the file
and choose **Validate and save as a new version**. Word does not connect to Taxonomy;
saving locally alone does not update the server. Review the saved change comparison.
For the decision template, the existing sample-report action uses the **current
server version**, not a guaranteed snapshot of this save or a pre-publication gate.

The page address contains `?revision=<full Git ID>`. Keep or bookmark it; reloading
preserves the original version. A new editing link from the list is a new checkout
and must not be used for a file based on an older download. When the original address
is lost, establish the file's starting version from history rather than assuming HEAD.

The upload sends that fixed revision as `If-Match`. Conflicts retain the selected
file and revision and require manual reconciliation. There is no force-overwrite,
automatic retry or automatic rebase. After a lost response, check history before
retrying because the commit may already exist. After sign-in expires, sign in in
another tab and return to the original editing address.

Existing administrator authorization, browser session, CSRF, size bounds, OOXML
validation, required-placeholder checks and atomic Git preconditions remain in the
shared API/service. Valid uploads become active immediately; staging and a separate
publication gate remain part of #836. The generic upload form is create-only.
WebDAV remains optional; this does not change Windows security policies or certify
any external transfer client.

## Focused verification

`npm --prefix .github run test:document-template-local-edit` exercises the real
browser controller and API boundary with deterministic HTTP fixtures. It is included
in both existing UI contract entry points. `DocumentTemplateLocalEditControllerTest`
checks immutable server-side reload behavior and rejects mutable/abbreviated refs.
These tests do not replace the full Maven/database/security/browser/deployment gates
or a test with the installed Microsoft Word version on the affected workstation.

## Packaged-application browser acceptance

The existing `DocumentTemplateReportDownloadIT` runs
`.github/scripts/document-template-local-edit-acceptance.mjs` before the unchanged
hospital/report journey, against the **same disposable packaged application**.
No extra workflow or application start is added. The ordinary `document-template-report-e2e`
profile now also fails when local editing or its screenshot evidence fails.

The German desktop (1280 × 900) and English narrow-screen (390 × 844) cases each
create a uniquely named QA template from the server's bundled DOTX. Two tabs open
the same starting revision and download it through the visible controls. The fixture
helper uses the existing JDK `jar` utility to insert a marked paragraph without
changing the source archive or moving final Word section properties. This simulates
returning a locally edited DOTX; **it does not automate or certify desktop Word**.

Each case checks a real invalid upload (400), file retention, a visible busy state,
duplicate-submit prevention, an accepted save, the actual saved comparison and
OOXML inspection. The second tab reloads its original address after the first saves,
redownloads exactly the original bytes and submits its stale edit. The server must
return 412 while preserving the winning bytes and the exact two-entry history.
There are no fabricated server responses or changed preconditions. The progress
check temporarily holds the outgoing request, then continues it to the real server.

Missing, mutable and abbreviated revision parameters return 400; a syntactically
valid but absent starting revision returns 404, never an automatic checkout of HEAD.
Page-script errors and unexpected console errors fail the scenario. The only allowed
HTTP console errors are the deliberately rejected 400/412 uploads on that case's
unique template URL. Enabled download, save and history controls are checked for
occlusion, and horizontal page overflow is rejected.

Evidence is written below
`target/ui-verification/document-template-report/local-edit/`: one JSON report and
six screenshots per language (`checkout`, `invalid-file-retained`, `upload-progress`,
`saved`, `conflict-after-reload`, `saved-diff`). These describe the executed test only
when the corresponding exact-head CI succeeds; their implementation alone is not
an acceptance result. The supplementary fixture tests run with the existing Node
contract suite and do not need a browser.
