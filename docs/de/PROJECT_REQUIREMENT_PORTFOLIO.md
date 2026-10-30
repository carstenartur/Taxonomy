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

<a id="architecture-lifecycle"></a>
## Eine Architektur über mehrere Projekte weiterführen

Eine Architektur besitzt eine stabile Repository-Identität. Ein Projektabschluss
beendet, löscht oder ersetzt diese Architektur nicht. Ein späteres Projekt kann
neue Anforderungen beitragen, eine vorhandene Anforderung ändern oder sie in
derselben Architektur wiederverwenden. Die ursprüngliche projektqualifizierte
Anforderungsidentität bleibt erhalten. Das Folgeprojekt wird als Beitrag erfasst;
es wird nicht stillschweigend zum ursprünglichen Projekt der Anforderung. Ähnliche
Formulierungen bedeuten nicht automatisch dieselbe Anforderung.

Die gemeinsame Navigation verbindet Analyse, Projekte, Anforderungsdetail,
gespeicherte Architekturansicht, Architektureditor und Versionierung. Der Kontext
verbindet Architektur, Projekt, Anforderung und gewählten Modellstand. Projektübersicht
und Analyse zeigen ihn zunächst auf allen Bildschirmgrößen eingeklappt.
Architekturtitel, aktuelle Auswahl, gewählter Stand sowie Schreibschutz- oder Problemhinweise
bleiben sichtbar. Diese Zeile für Kontextdetails und Lifecycle-Aktionen öffnen. Lesbare Namen und
Fachschlüssel stehen im Vordergrund. **Technische Details** zeigt innerhalb des
Kontexts bei Bedarf Repository-, Workspace-, vollständige Commit-, Snapshot- und Versionskennungen,
soweit vorhanden. Die bestehenden Hilfelinks führen zum passenden Abschnitt dieses
Handbuchs oder zur [Editorhilfe](ARCHITECTURE_EDITOR.md).

**Anforderungen, Realisierung und Historie** aufklappen und **Textversion im gewählten Stand**
mit **Geprüfte Textversion** vergleichen. Drei Datensätze haben verschiedene
Bedeutungen:

| Datensatz | Bedeutung |
|---|---|
| Aktueller Anforderungstext | Neueste unveränderliche Textversion im Portfolio; eine Textänderung erzeugt eine neue Version. |
| Gewählter Architekturstand | Aktueller Workspace, frühere semantische Revision oder Git-Checkpoint; der dortige Anforderungstext gehört zu diesem Stand. |
| Geprüfte Umsetzung | Menschliche Bewertung einer exakten Textversion samt Hash gegen gespeicherte Architekturabhängigkeiten. |

Der aktuelle Anforderungskopf kann Version 2 zeigen, während ein früher gewähltes
Modell Version 1 enthält. Die Tabelle beschreibt dieses ältere Modell und benennt
nicht die aktuelle Portfolioversion um. Auch ein Analysesnapshot behält seine eigene
Quellversion. Eine Textänderung, eine Analyse oder der Projektabschluss beweist nicht,
dass die Architektur den neuen Text umsetzt.

| Zustand | Bedeutung |
|---|---|
| `OPEN` | Keine anwendbare teilweise oder vollständige Umsetzungsprüfung gespeichert. |
| `PARTIAL` | Ein Mensch hat einen Teil der exakten Anforderung anhand ausgewählter Nachweise geprüft. |
| `COMPLETE` | Vollständige Umsetzung für die geprüfte Version und ihre Abhängigkeiten dokumentiert. |
| `REVIEW_REQUIRED` | Anforderung oder gespeicherte Abhängigkeit seit der Prüfung geändert; die frühere geprüfte Version bleibt sichtbar. |
| `INVALID_EVIDENCE` | Die gespeicherten Nachweise erlauben keine gültige Bewertung und müssen korrigiert werden. |

Beitragendes Projekt, Status, Architekturabhängigkeiten und Begründung wählen,
vorschauen und die Prüfung ausdrücklich annehmen. Teilweise und vollständige
Bewertungen benötigen Nachweise. Relevanzwerte sind keine Umsetzungsprozente.
Siehe [Anforderungsumsetzung prüfen](ARCHITECTURE_EDITOR.md#requirement-realization).

Eine frühere Workspace-Revision oder erreichbare Git-Version zeigt das Modell
schreibgeschützt. Historische Stände verwenden ihre gespeicherte Quelle ohne
Überblendung durch das heutige Portfolio. Eine ausdrücklich ausgewählte aktuelle
Revision bleibt der aktuelle Arbeitsstand. Editor, Lifecycle-Bereich und Kontextkopf
teilen dieselbe Auswahl, auch bei Zurück/Vorwärts im Browser. **Aktuellen Arbeitsstand
anzeigen** führt zum laufenden Modell; **Aktualisieren** lädt den gewählten
Stand neu. Ein fehlender oder unzugänglicher Stand ergibt einen Fehler statt
stillschweigend aktuelle Daten anzuzeigen.

Ein angenommener Beitrag und ein abgeschlossener Git-Checkpoint sind getrennte
Ergebnisse. `CHECKPOINT_PENDING` bedeutet, dass die Änderung erhalten ist, ihr
Git-Schreibvorgang aber noch abgeschlossen werden muss. `RECONCILIATION_REQUIRED`
erfordert den Vergleich des lokalen und eingehenden Stands. Für eine exakte
Wiederholung dieselbe Befehlsidentität behalten; geänderte Eingaben benötigen eine
neue Prüfung. Siehe [Stände abgleichen](ARCHITECTURE_EDITOR.md#editor-version-reconciliation).

<a id="requirement-history"></a>
## Anforderungs- und Umsetzungshistorie nachvollziehen

Die Historie in **Anforderungen, Realisierung und Historie** öffnen. Sie durchsucht tatsächlich
erreichbare Git-Commits der gewählten Architektur und ihres Zweigs mit nativem JGit
und dem datenbankgestützten Git-Repository. Sie ist vom Operationsjournal des Editors
und der Liste unveränderlicher Textversionen getrennt. Lokale Git-Kommandos sind
dafür nicht erforderlich.

Text unter **Git-Protokoll durchsuchen** eingeben und **Suchen** wählen oder mit der
Historienaktion einer Anforderungszeile auf deren ursprüngliche Projekt- und
Anforderungsidentität eingrenzen. Ein Folgeprojektbeitrag ändert diesen ursprünglichen
Filter nicht. Die
Einträge unterscheiden Text-, Metadaten-, Abhängigkeits- und Umsetzungsänderungen
sowie Ergänzungen, Entfernungen und relevante Merges. Der Commit-Betreff hält die
Liste lesbar. **Commitdetails** zeigt die vollständige Nachricht, Commit-ID,
tatsächliche Eltern, Autor, Zeitpunkt und verfügbare Signaturbelege. Die Vorgänger
beschreiben echte Ancestry, keine erfundene Folge von Anforderungsversionen.

Das Ergebnis nennt Git-Quelle und erfassten HEAD. Jede Seite durchsucht einen
begrenzten Bereich. Teilweise Abdeckung oder eine leere begrenzte Seite beweist
nicht, dass ältere passende Änderungen fehlen. Solange angeboten, weitere Seiten
laden. Nach einer Änderung des Branch-HEAD die Suche neu beginnen. Speicher- oder
Berechtigungsfehler sind keine leere Historie; unzugängliche Zweige und private
Workspaces anderer Benutzer werden nicht durchsucht. Ein historischer Treffer
öffnet seinen schreibgeschützten Modellstand.

<a id="requirement-intake"></a>
## Eingehenden Text vor dem Speichern prüfen

Die Eingangsprüfung beim Speichern einer neuen Anforderung oder beim Übertragen
einer Analyse in ein Projekt verwenden. Sie kann Duplikate, Änderungen, Ergänzungen
oder Konflikte unter den zugelassenen Kandidaten vorschlagen. Quellauszüge und
Herkunft prüfen und eine ausdrückliche Entscheidung treffen:

| Auswahl | Ergebnis |
|---|---|
| `NEW` | Anforderung mit eigener Identität im gewählten oder neuen Projekt anlegen. |
| `CHANGE` | Neue unveränderliche Textversion der ausgewählten bestehenden Anforderung anlegen; ihre ursprüngliche Identität bleibt erhalten. |
| `LINK` | Ausgewählte Anforderung wiederverwenden und Beitrag speichern, ohne ihren Text zu ersetzen. |

Ein Folgeprojekt kann über `CHANGE` oder `LINK` beitragen. Ursprüngliche Anforderung
und beitragendes Projekt getrennt prüfen. Ein Treffer in einer anderen Architektur
ist ein prüfbarer Hinweis, keine Berechtigung zur Änderung dieser Architektur.
Die angebotenen Aktionen berücksichtigen den Geltungsbereich des Kandidaten.

Suche und Providerbewertung sind begrenzt. Abdeckung und Providerzustand beachten:
Ein ungeprüfter Kandidat, nicht verfügbarer Provider, Timeout oder fehlgeschlagene
Antwort ist kein Nachweis fehlender Duplikate oder Konflikte. Exakte Textgleichheit
kann ohne Aussage über semantische Gleichwertigkeit erkannt werden. Manuelle Prüfung
und ausdrückliche Auswahl bleiben bei nicht verfügbarer KI möglich. Lokale Embeddings
und simulierte Provider belegen keine Qualität einer realen generativen Bewertung.

Geänderter Text oder Kontext macht die vorherige Prüfung ungültig. `CHANGE` und
`LINK` binden an ausgewählte aktuelle Version und Hash. Bei geänderter Version neu
laden, vergleichen und erneut entscheiden. Das bloße Öffnen der Prüfung legt nichts
an, verknüpft nichts und überschreibt nichts. Siehe
[Eingangsprüfung und Veröffentlichung](PROJECT_PORTFOLIO_API.md#architecture-lifecycle-api).

<a id="requirement-clarification-workflow"></a>
## Eine Anforderung anhand ihres Architekturkontexts klären

Für neuen Eingangstext mit der [Intake-Entscheidung](#requirement-intake) beginnen.
Bei einem Folgeprojekt die [fortbestehende Architektur](#architecture-lifecycle) beibehalten.

Verwenden Sie die Anforderungsdetailseite, wenn die Ausgangsformulierung Entscheidungen
offenlässt. Ein gespeichertes Neuformulierungsangebot ist von Quellanforderung und
Architektur getrennt. Das Erzeugen eines Angebots, Beantworten einer Frage oder
Bearbeiten des Entwurfs gibt die aktive Anforderung weder frei noch ersetzt es sie.

| Schritt | Ihre Aktion | Was nachvollziehbar bleibt |
|---|---|---|
| Quelle auswählen | Anforderung öffnen, gespeicherte Version und zugehörigen Analysesnapshot wählen | Exakter Originaltext, Quellversion und analysierte Architektur |
| Angebot erzeugen | Separaten Formulierungsvorschlag anfordern und Synthesezustand prüfen | Ein gespeicherter Anfangsentwurf ist noch kein abgeschlossenes oder freigegebenes Ergebnis |
| Fragen klären | Architekturbezüge prüfen; antworten, zurückstellen oder als nicht zutreffend kennzeichnen | Typisierte Entscheidungen, Nachweise und ungelöste Konflikte bleiben unterscheidbar |
| Vorschlag prüfen | Text oder Aussagen bearbeiten, Ergänzungen prüfen und gewünschte Revision speichern | Manuelle Änderungen sind gegen verspätete Modellantworten geschützt |
| Ausdrücklich übernehmen | Gespeicherte Übernahmevorschau, Warnungen und Endtext prüfen; mit Begründung bestätigen | Neuer Anforderungsentwurf und Übernahmebeleg; frühere Versionen bleiben erhalten |
| Gesondert analysieren | Bei Bedarf eine weitere Analyse anfordern | Übernommene Entscheidungen behalten ihre Herkunft; der alte Snapshot wird nicht umgeschrieben |

Die Formulierung entsteht zunächst von unten nach oben innerhalb der ausgewählten
Taxonomienachweise. Ein begrenzter Abgleich zwischen Teiltaxonomien behandelt danach
Grenzen und gemeinsame Randbedingungen. Das ist nicht nur eine allgemeine sprachliche
Überarbeitung: Fragen können auf Elemente und gerichtete Beziehungen der eingefrorenen
Analyse verweisen. Weder ein abgeschlossener Lauf noch eine leere Fragenliste beweist
Freigabe oder fachliche Vollständigkeit.

**Illustratives Beispiel, kein protokolliertes Modellergebnis:** „Beschäftigte sollen
Arbeitszeiten mobil erfassen.“ Bei der Prüfung können Offline-Erfassung,
Korrekturberechtigungen und das empfangende System zu klären sein. Eine menschliche
Entscheidung wie „Offline-Erfassung ist erforderlich“ soll im gespeicherten Vorschlag
und der anschließend übernommenen Version nachvollziehbar bleiben. Diese konkreten
Fragen sind keine garantierte Modellausgabe.

Exportieren Sie eine exakt gespeicherte Angebotsrevision oder einen Übernahmebeleg als
JSON, Markdown, HTML oder DOCX. Ungespeicherter Browsertext bleibt ausgeschlossen.
Historische Exporte verwenden ihre damalige Quelle und Architekturevidenz, nicht den
heutigen Katalog. Eine Übernahme markiert Analysebedarf, startet aber weder automatisch
eine Analyse noch verändert sie die Architektur.

Siehe den [detaillierten Neuformulierungsvertrag](../features/requirement-reformulation.md)
und die [Prüfgrenzen](../testing/requirement-reformulation.md). Technische Playback-,
Wiederanlauf- und Exporttests belegen keine allgemeine Sprachqualität realer Modelle.

<a id="analysis-scope-and-progress"></a>
## Analyseumfang wählen und offene Arbeit verstehen

Bei automatischer Analyse und Copilot können Sie Taxonomiewurzeln auswählen und
zwischen vollständiger Analyse und **nur Taxonomien** unterscheiden. Eine begrenzte
Wurzelauswahl beschränkt Bewertungen und Relationsquellen. Im vollständigen Modus
bleiben kompatible Relationsziele anderer Teiltaxonomien erreichbar. Der Modus
„nur Taxonomien“ führt weder Relationssuche noch davon abhängige Architekturableitung
aus.

Das Ergebnis erhält seinen tatsächlich verwendeten Umfang. Einstellungen für den
nächsten Lauf interpretieren ältere Ergebnisse nicht neu. Nicht ausgewählte oder
nicht besuchte Knoten werden nicht zu negativen Befunden. Ein begrenzter Lauf ist
keine globale Lückenbewertung.

Der Fortschritt unterscheidet direkt bewertete Knoten, durch eine gültige Nullbewertung
ausgeschlossene Nachfahren und offene Knoten. Der Relationsfortschritt zeigt geplante
Quell-/Zieltaxonomie-Arbeit, erledigte und ungeklärte Aufgaben sowie neue Provideraufrufe
getrennt. Bei einem Budgetstopp bleibt unerledigte Arbeit sichtbar. Eine kompatible
Fortsetzung verwendet validierte Antworten erneut, statt erfolgreiche Abfragen erneut
zu bezahlen. Geänderte Eingaben oder Wiederaufnahmeregeln können einen neuen Lauf
erfordern.

Ein abgeschlossener Arbeitsplan beweist nicht, dass jede denkbare Beziehung im Ergebnis
enthalten ist. Relevanzgewichte sind keine kalibrierten Wahrscheinlichkeiten oder
Prozentwerte der Anforderungserfüllung. Lokales ONNX liefert Embeddings für Suche und
Bewertung, nicht die generativen Relations- oder Formulierungsfähigkeiten eines
konfigurierten Sprachmodells.

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

<a id="portfolio-projects"></a>
## 1. Projekt anlegen oder auswählen

Für ein Folgeprojekt die bestehende Architektur beibehalten und im
[Lifecycle-Ablauf](#architecture-lifecycle) Anforderungen neu anlegen, ändern oder verknüpfen.

Öffnen Sie `/projects`.

Wählen Sie **Neues Projekt** und erfassen Sie einen eindeutigen Projektschlüssel, einen Titel und optional eine Beschreibung. Die Projektliste bleibt neben dem Arbeitsbereich sichtbar, sodass zwischen Projekten gewechselt werden kann, ohne die aktuelle Browsersitzung zu verlieren.

Der Hauptarbeitsbereich enthält Anforderungen, Taxonomieabdeckung, Lösungen, Produkte, Konflikte, Snapshots und konsolidierte Kennzahlen.

<a id="portfolio-import"></a>
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

<a id="portfolio-matrices"></a>
## 8. Interaktive Matrizen verwenden

Öffnen Sie `/projects/{projectId}/matrices`.

Der Arbeitsbereich enthält:

- Anforderung–Taxonomie-Abdeckung;
- Anforderung–Lösung-Abdeckung;
- Lösung–Produkt-Abdeckung.

Suchen, filtern und sortieren Sie die sichtbaren Beziehungen. Eine nicht leere Zelle öffnet einen Drill-down mit Herkunft, Abdeckung, Snapshot, Reviewstatus, Evidenz und Verweisen auf die zugehörige Anforderung, den Taxonomieknoten, die Lösung oder das Produkt.

Leere Zellen bedeuten, dass keine Beziehung gespeichert ist. Sie bedeuten nicht, dass eine Beziehung mit null bewertet wurde.

<a id="portfolio-versioning"></a>
## 9. Geprüfte Portfoliozustände committen und mergen

Für Anforderungsänderungen und tatsächliche Git-Ancestry die
[Anforderungshistorie](#requirement-history) verwenden. Bei geändertem HEAD und
angenommener lokaler Arbeit dem [ausdrücklichen Abgleich](ARCHITECTURE_EDITOR.md#editor-version-reconciliation) folgen.

Öffnen Sie `/projects/{projectId}/versioning`.

Die Seite zeigt aktuellen Branch, HEAD-Commit, Portfoliozahlen und eine technische TaxDSL-Vorschau.

Sie können:

1. die geprüfte Datenbankprojektion in einen ausgewählten Branch committen;
2. vorab prüfen, wie ein Branch-HEAD in das Portfolio materialisiert würde;
3. hinzugefügte und entfernte Zeilen vor der Anwendung prüfen;
4. ausschließlich den exakt geprüften HEAD anwenden;
5. zwei unterschiedliche Branches über den semantischen Git-Merge-Dienst zusammenführen.

Ändert sich der Zielbranch nach der Vorschau, wird die Materialisierung ohne Änderung der Portfoliodaten abgewiesen. Dadurch kann eine veraltete Prüfung keine neueren Arbeiten überschreiben.

<a id="portfolio-reports"></a>
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

## Produktdemo ohne KI (QA-Referenz)

Die Eingabe `produktdemo` (Groß-/Kleinschreibung und umgebende Leerzeichen sind egal) im Analysefeld startet mit der normalen Schaltfläche **Analysieren** keine KI-Analyse, sondern legt ein isoliertes, gekennzeichnetes Demo-Projekt an (Schlüssel `DEMO-…`, Titel `DEMO · <Szenariotitel>` in der Sprache des Szenarios). Der eingegebene Entwurf, andere Projekte und Anforderungen werden nicht verändert. Szenario: „Wir benötigen unternehmensweit ein System zur Arbeitszeiterfassung der Mitarbeiter.“

- **Nur die KI-Antwort ist ersetzt.** Analyse, Reformulierung, Antworten, Übernahme, Referenzvergleich und Exporte laufen über die normalen Produktpfade. Die externe KI-Antwort stammt aus einer versionierten Szenario-Wiedergabe (Provider `DEMO.TIME-TRACKING-V1.R0`/`R1`). Fehlt eine passende Antwort, schlägt der Schritt mit `MISSING_REPLY` fehl; es gibt keinen Rückgriff auf einen Live-Provider.
- **Katalogprüfung.** Beim Start werden alle im Szenario verwendeten Codes gegen den geladenen Katalog geprüft; Abweichungen verhindern den Start.
- **Banner und Stationen.** Die Anforderungsseite zeigt dauerhaft das Demo-Banner, den aktiven Szenario-Provider, die Stationen R0 → A0 → Rückfragen F-01…F-05 → Antworten/Ablehnung der GPS-Ergänzung → Übernahme → R1 → A1 → Referenzbewertung sowie die sichtbaren Demo-Antworten.
- **Ablauf.** Analysieren Sie R0, starten Sie die Reformulierung, beantworten Sie die Fragen mit den angezeigten Demo-Antworten und lehnen Sie die GPS-Ergänzung ab. Ergibt die Neugenerierung einen geschützten Kandidaten, laden Sie ihn mit **Kandidat in den Editor laden**, speichern den Entwurf und übernehmen ihn mit Vorschau und Bestätigung. Danach R1 analysieren und **Referenzbewertung anzeigen** wählen.
- **Zulassung.** Demo-Provider sind nur für die Demo-Anforderung des Demo-Laufs zugelassen; normale Projekte weisen sie ab (`PROVIDER_REQUIRES_PROJECT_BINDING`), und ein Demo-Projekt weist jeden anderen Provider ab (`DEMO_PROVIDER_REJECTED`). Geänderte Szenarioversionen (`DEMO_SCENARIO_CHANGED`), nicht vorgesehene Iterationen (`DEMO_ITERATION_UNAVAILABLE`), andere Anforderungen (`DEMO_REQUIREMENT_MISMATCH`) und beendete Läufe (`DEMO_RUN_CLOSED`) werden abgewiesen; ein Wiederholungslauf, dessen zugelassener Provider sich geändert hat, scheitert mit `PROVIDER_BINDING_CHANGED`. Die Codes stehen in der Problem-Detail-Eigenschaft `code`. Zulassung und Beenden sperren dieselbe Demo-Lauf-Zeile; nach einem bestätigten Beenden wird keine Szenario-Analyse mehr zugelassen.
- **Architektur.** Der Analysekontext bietet laufgebundene Szenario-Wiedergaben über genau eine Provider-Strategie (`ScenarioReplayProviders`) an; der Portfoliokontext kennt nur den generischen Port `ProjectProviderBinding`. Die Demo selbst (Laufdatensatz, Binding-Adapter, Service und REST-API) wird im Anwendungsmodul zusammengesetzt.
- **Referenzbewertung.** `GET /api/product-demo/runs/{id}/comparison` bewertet A0/A1 unabhängig gegen die Szenario-Referenz; nicht ausgeführte Schritte erscheinen als `NOT_EXECUTED`, nicht als Fehlbefund.
- **API.** `GET /api/product-demo/scenarios`, `POST /api/product-demo/runs`, `GET /api/product-demo/runs[?projectId=…]`, `GET /api/product-demo/runs/{id}`, `POST /api/product-demo/runs/{id}/close`. Lesen erfordert die Rolle `USER`, `ARCHITECT` oder `ADMIN`; Starten und Beenden erfordern `ARCHITECT` oder `ADMIN`.

## Rollen und Barrierefreiheit

Benutzer mit Leserechten können Portfolioinformationen einsehen, aber keine Architekturänderungen ausführen. Deaktivierte Steuerelemente erläutern die fehlende Berechtigung. Architekten und Administratoren können die durch die Sicherheitskonfiguration zugelassenen geprüften Schreiboperationen durchführen.

Die Hauptabläufe verwenden native Bedienelemente, tastaturbedienbare Dialoge, fokussierbare Fehlermeldungen, responsive Alternativen für Matrizen sowie Layouts für schmale Ansichten und vergrößerte Schrift.

## Weiterführende Dokumentation

- [PROJECT_PORTFOLIO_FEATURE_MATRIX.md](PROJECT_PORTFOLIO_FEATURE_MATRIX.md) — nachgewiesene GUI-, API- und Betriebsabdeckung
- [PROJECT_PORTFOLIO_API.md](PROJECT_PORTFOLIO_API.md) — REST-Verträge und Automatisierungsbeispiele
- [PROJECT_PORTFOLIO_GIT_COLLABORATION.md](PROJECT_PORTFOLIO_GIT_COLLABORATION.md) — Zusammenarbeit und Branch-Semantik
- [PROJECT_PORTFOLIO_OPERATIONS.md](PROJECT_PORTFOLIO_OPERATIONS.md) — Betrieb, Wiederanlauf und Diagnose
