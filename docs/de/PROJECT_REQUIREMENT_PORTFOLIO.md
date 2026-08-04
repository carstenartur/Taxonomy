# Projekt-, Anforderungs-, Lösungs- und Produktportfolio

## Zweck des Arbeitsbereichs

Das Projektportfolio überführt Dokumente und fachliche Anforderungen in ein nachvollziehbares Architektur-, Lösungs- und Produktportfolio. Anforderungen bleiben während Analyse, Review und Berichtserstellung getrennt. Unabhängige Anforderungstexte werden niemals stillschweigend zusammengefügt.

Der Arbeitsbereich unterstützt insbesondere:

- den Import und die Prüfung mehrerer Anforderungen eines Projekts;
- unveränderliche Anforderungsversionen mit Quellenprovenienz;
- getrennte Analysen mit persistenten Hintergrundjobs;
- die Prüfung von Taxonomiezuordnungen und Architekturfolgen;
- die Zuordnung von Anforderungen zu wiederverwendbaren Lösungen und belegten Produkten;
- die Erkennung und Bearbeitung von Anforderungskonflikten;
- konsolidierte, interaktive Matrizen;
- Commit, Vergleich, Materialisierung und Merge über Git;
- menschen- und maschinenlesbare Berichte.

Alle nachfolgend beschriebenen Arbeitsschritte sind über die Weboberfläche erreichbar. Technische Integrationsbeispiele stehen getrennt in [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md).

## Routen der Portfoliooberfläche

| Arbeitsbereich | Route |
|---|---|
| Projektportfolio | `/projects` |
| Geführter Dokumentimport | `/projects/{projectId}/import` |
| Anforderungsdetail | `/projects/{projectId}/requirements/{requirementId}` |
| Interaktive Matrizen | `/projects/{projectId}/matrices` |
| Versionierung und Zusammenarbeit | `/projects/{projectId}/versioning` |
| Berichte und Exporte | `/projects/{projectId}/reports` |

Die Platzhalter werden bei der Navigation aus einem ausgewählten Projekt beziehungsweise einer Anforderung automatisch ersetzt.

## 1. Projekt anlegen oder auswählen

Öffnen Sie `/projects`.

Wählen Sie **Neues Projekt** und erfassen Sie einen eindeutigen Projektschlüssel, einen Titel und optional eine Beschreibung. Die Projektliste bleibt neben dem Arbeitsbereich sichtbar, sodass zwischen Projekten gewechselt werden kann, ohne die aktuelle Browsersitzung zu verlieren.

Der Hauptarbeitsbereich enthält Anforderungen, Taxonomieabdeckung, Lösungen, Produkte, Konflikte, Snapshots und konsolidierte Kennzahlen.

## 2. Anforderungen aus Dokumenten importieren

Öffnen Sie die Importaktion des ausgewählten Projekts oder `/projects/{projectId}/import`.

1. Laden Sie ein unterstütztes PDF- oder DOCX-Dokument hoch.
2. Prüfen Sie jeden erkannten Kandidaten einzeln.
3. Bearbeiten Sie bei Bedarf Schlüssel, Titel, Typ, Priorität, Kritikalität und Text.
4. Treffen Sie für jeden Kandidaten eine ausdrückliche Entscheidung:
   - neue Anforderung anlegen;
   - neue Version einer vorhandenen Anforderung anlegen;
   - bewusst mit einem anderen geprüften Kandidaten zusammenführen;
   - verwerfen.
5. Prüfen Sie die Zusammenfassung vor der Bestätigung.
6. Starten Sie optional unmittelbar eine getrennte Analyse der übernommenen Anforderungen.

Soweit vorhanden, bleiben Quelldokument, Dokumentversion, Fragment, Abschnitt, Seite und Originaltext an der resultierenden Anforderungsversion erhalten. Doppelte Schlüssel und unvollständige Entscheidungen werden vor der Speicherung abgewiesen.

Der gemischte Import ist atomar: Entweder werden alle geprüften Entscheidungen übernommen oder keine davon verändert das Projekt.

## 3. Anforderungen manuell anlegen und versionieren

Verwenden Sie **Neue Anforderung** in `/projects` für die manuelle Erfassung.

Eine Anforderung besitzt eine stabile Identität und eine unveränderliche aktuelle Textversion. Öffnen Sie `/projects/{projectId}/requirements/{requirementId}`, um:

- aktuellen Fachtext und Quelle zu lesen;
- frühere Versionen einzusehen;
- mit Begründung eine neue Version anzulegen;
- Analyse-Snapshots zu vergleichen;
- Taxonomiezuordnungen und Architekturelemente zu prüfen;
- Entscheidungen, Lösungsbezüge und Produktkandidaten nachzuvollziehen.

Eine Textänderung erzeugt eine neue Version und überschreibt keine Historie.

## 4. Anforderungen analysieren, ohne die Seite zu blockieren

Verwenden Sie **Analysieren** für eine Anforderung oder **Alle analysieren** für das ausgewählte Projekt.

Die Anfrage wird als persistenter Hintergrundjob angenommen. Während der Verarbeitung bleibt die Oberfläche bedienbar. Das Job-Center zeigt:

- wartenden, laufenden, erfolgreichen, teilweisen, fehlgeschlagenen oder abgebrochenen Zustand;
- Fortschritt je Anforderung;
- Versuche und Ergebnisangaben;
- fehlgeschlagene Einträge, die ohne Wiederholung erfolgreicher Einträge erneut gestartet werden können.

Aktive und abgeschlossene Jobs werden nach dem Neuladen des Browsers wiederhergestellt. Jeder erfolgreiche oder teilweise erfolgreiche Eintrag erzeugt genau einen unveränderlichen Snapshot für genau eine Anforderungsversion.

## 5. Taxonomiezuordnungen und Architekturfolgen prüfen

Öffnen Sie den Detailarbeitsbereich einer Anforderung und wählen Sie einen Analyse-Snapshot.

Der Snapshot zeigt erzeugte Taxonomiezuordnungen, Architekturansicht, Evidenz, Provider-/Modellangaben und Reproduzierbarkeits-Fingerprints. Generierte Ergebnisse und menschliche Prüfentscheidungen bleiben sichtbar getrennt.

Für relevante Zuordnungen kann ein geprüfter Handlungsstatus dokumentiert werden, zum Beispiel:

- bereits erfüllt;
- wiederverwenden;
- ändern;
- neu erstellen;
- beschaffen;
- organisatorische Maßnahme;
- außer Betrieb nehmen oder ersetzen;
- noch unentschieden.

Eine bestätigte Entscheidung benötigt tatsächlich geprüfte Evidenz oder eine Begründung. Die Anwendung erfindet keine menschliche Evidenz.

## 6. Lösungen und Produkte prüfen

Im Reiter **Lösungen** können wiederverwendbare Lösungsdefinitionen angelegt und dem Projekt zugeordnet werden. Der Taxonomie-Picker sucht nach Code und Titel und zeigt den Hierarchiekontext; technische Knotenkennungen müssen daher nicht auswendig eingegeben werden.

Nach Prüfung der Taxonomieabdeckung werden Lösungen mit den von ihnen abgedeckten Anforderungen verbunden. Vorgeschlagene Beziehungen bleiben Vorschläge, bis sie ausdrücklich bestätigt werden.

Im Reiter **Produkte** werden belegte Hersteller-, Produkt- und Versionsangaben erfasst. Produktbehauptungen behalten Quelle und Prüfzeitpunkt. Produkte können als Kandidaten einer Lösung hinzugefügt, verglichen, in die engere Wahl genommen oder ausdrücklich ausgewählt werden. Harte Ausschlussgründe verhindern eine Auswahl.

## 7. Konflikte erkennen und auflösen

Wählen Sie **Konflikte erkennen** im Projektarbeitsbereich.

Jede Konfliktkarte nennt beide Anforderungen und zeigt Konflikttyp, Evidenz und Konfidenz. Im geführten Entscheidungsdialog kann die Hypothese bestätigt, verworfen, zurückgestellt oder aufgelöst werden. Eine Auflösung verlangt eine dokumentierte Begründung und bleibt revisionsfähig.

Die Konflikterkennung unterstützt professionelles Requirements Engineering, ersetzt aber keine menschliche Freigabe.

## 8. Interaktive Matrizen verwenden

Öffnen Sie `/projects/{projectId}/matrices`.

Der Arbeitsbereich enthält:

- Anforderung–Taxonomie-Abdeckung;
- Anforderung–Lösung-Abdeckung;
- Lösung–Produkt-Abdeckung.

Suchen, filtern und sortieren Sie die sichtbaren Beziehungen. Eine nicht leere Zelle öffnet einen Drill-down mit Herkunft, Abdeckung, Snapshot, Reviewstatus, Evidenz und Verweisen auf die zugehörige Anforderung, den Taxonomieknoten, die Lösung oder das Produkt.

Leere Zellen bedeuten, dass keine Beziehung gespeichert ist. Sie bedeuten nicht, dass eine Beziehung mit null bewertet wurde.

## 9. Geprüfte Portfoliozustände committen und mergen

Öffnen Sie `/projects/{projectId}/versioning`.

Die Seite zeigt aktuellen Branch, HEAD-Commit, Portfoliozahlen und eine technische TaxDSL-Vorschau.

Sie können:

1. die geprüfte Datenbankprojektion in einen ausgewählten Branch committen;
2. vorab prüfen, wie ein Branch-HEAD in das Portfolio materialisiert würde;
3. hinzugefügte und entfernte Zeilen vor der Anwendung prüfen;
4. ausschließlich den exakt geprüften HEAD anwenden;
5. zwei unterschiedliche Branches über den semantischen Git-Merge-Dienst zusammenführen.

Ändert sich der Zielbranch nach der Vorschau, wird die Materialisierung ohne Änderung der Portfoliodaten abgewiesen. Dadurch kann eine veraltete Prüfung keine neueren Arbeiten überschreiben.

## 10. Berichte und Exporte erzeugen

Öffnen Sie `/projects/{projectId}/reports`.

Wählen Sie einen projektweiten Bericht oder beschränken Sie ihn auf eine Anforderung. Vorschau und Exporte verwenden dieselbe aktuelle Portfolio-Baseline.

Verfügbare Formate:

- HTML-Vorschau und Download;
- Markdown;
- DOCX;
- JSON;
- CSV für die ausgewählte Matrix.

Die Berichte enthalten Anforderungstexte, Quellenangaben, Taxonomieabdeckung, Lösungs- und Produktentscheidungen, Konflikte und die Reproduzierbarkeits-Baseline der relevanten Snapshots.

## Rollen und Barrierefreiheit

Benutzer mit Leserechten können Portfolioinformationen einsehen, aber keine Architekturänderungen ausführen. Deaktivierte Steuerelemente erläutern die fehlende Berechtigung. Architekten und Administratoren können die durch die Sicherheitskonfiguration zugelassenen geprüften Schreiboperationen durchführen.

Die Hauptabläufe verwenden native Bedienelemente, tastaturbedienbare Dialoge, fokussierbare Fehlermeldungen, responsive Alternativen für Matrizen sowie Layouts für schmale Ansichten und vergrößerte Schrift.

## Weiterführende Dokumentation

- [PROJECT_PORTFOLIO_FEATURE_MATRIX.md](PROJECT_PORTFOLIO_FEATURE_MATRIX.md) — nachgewiesene GUI-, API- und Betriebsabdeckung
- [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md) — REST-Verträge und Automatisierungsbeispiele
- [PROJECT_PORTFOLIO_GIT_COLLABORATION.md](PROJECT_PORTFOLIO_GIT_COLLABORATION.md) — Zusammenarbeit und Branch-Semantik
- [PROJECT_PORTFOLIO_OPERATIONS.md](PROJECT_PORTFOLIO_OPERATIONS.md) — Betrieb, Wiederanlauf und Diagnose
