# Konzepte und Glossar

Dieses Dokument erläutert die zentralen Begriffe, die im gesamten Taxonomy Architecture Analyzer verwendet werden.

---

## Inhaltsverzeichnis

- [Taxonomie-Knoten](#taxonomie-knoten)
- [Taxonomie-Blatt](#taxonomie-blatt)
- [Beziehung](#beziehung)
- [Anforderungsanalyse](#anforderungsanalyse)
- [Bewertung](#bewertung)
- [Ankerknoten](#ankerknoten)
- [Relevanzpropagierung](#relevanzpropagierung)
- [Architekturansicht](#architekturansicht)
- [Architektur-Wissensgraph](#architektur-wissensgraph)
- [Anforderungsauswirkung](#anforderungsauswirkung)
- [Ausfallauswirkung](#ausfallauswirkung)
- [Lückenanalyse](#lückenanalyse)
- [Mustererkennung](#mustererkennung)
- [Beziehungsvorschlag](#beziehungsvorschlag)
- [Blattknoten-Begründung](#blattknoten-begründung)
- [Ansichtsmodi](#ansichtsmodi)
- [Beziehungstyp](#beziehungstyp)
- [Architektur-DSL](#architektur-dsl)
- [Beziehungshypothese](#beziehungshypothese)
- [Beziehungsnachweis](#beziehungsnachweis)
- [Erklärungsverfolgung](#erklärungsverfolgung)
- [Kanonisches Architekturmodell](#kanonisches-architekturmodell)
- [Framework-Zuordnung](#framework-zuordnung)
- [Qualitätsanalyse](#qualitätsanalyse)
- [Lückenanalyse](#lückenanalyse)
- [Arbeitsbereich](#arbeitsbereich)
- [Variante](#variante)
- [Arbeitsbereich-Projektion](#arbeitsbereich-projektion)
- [Synchronisation (Sync)](#synchronisation-sync)
- [Konfliktlösung](#konfliktlösung)
- [Divergierter Zustand](#divergierter-zustand)
- [Rückmeldung zu Operationsergebnissen](#rückmeldung-zu-operationsergebnissen)

---

## Taxonomie-Knoten

Ein einzelnes Element in der C3-Taxonomie-Hierarchie. Jeder Knoten besitzt:

- **Code** — eine hierarchische Kennung aus dem zentral veröffentlichten C3 Taxonomy Catalogue-Arbeitsbuch, z. B. `CP-1022` (Command and Control Capabilities) oder `CR-1047` (Infrastructure Services). Die acht Stammkategorien verwenden zweistellige Codes (`BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA`); alle untergeordneten Codes sind im Arbeitsbuch definiert und können nicht lokal erfunden werden.
- **Titel** — ein kurzer englischer Name, beispielsweise _„Secure Voice Service"_.
- **Beschreibung** — eine ausführlichere englische Beschreibung, die den Zweck des Knotens erläutert.
- **Ebene** — seine Tiefe in der Hierarchie (0 = Wurzel, 1 = Kind erster Ebene usw.).

Der Katalog enthält ungefähr **2.500 Knoten**.

---

## Taxonomie-Blatt

Der Katalog ist in acht übergeordnete Blätter unterteilt, die jeweils eine eigene Kategorie darstellen:

| Code | Blattname |
|---|---|
| **BP** | Business Processes |
| **BR** | Business Roles |
| **CP** | Capabilities |
| **CI** | COI Services |
| **CO** | Communications Services |
| **CR** | Core Services |
| **IP** | Information Products |
| **UA** | User Applications |

Jeder Knoten gehört genau einem Blatt an.

---

## Beziehung

Eine gerichtete Verbindung zwischen zwei Taxonomie-Knoten. Beziehungen drücken aus, wie ein Element von einem anderen abhängt, es unterstützt oder mit ihm verknüpft ist. Beispielsweise kann ein _Core Service_ eine _Capability_ _unterstützen_.

Beziehungen bilden die Kanten des [Architektur-Wissensgraphen](#architektur-wissensgraph).

---

## Anforderungsanalyse

Der Prozess, bei dem jeder Taxonomie-Knoten anhand einer Freitextanforderung bewertet wird. Ein KI-Sprachmodell beurteilt die Relevanz jedes Knotens und gibt für jeden einen Übereinstimmungsprozentsatz (0–100) zurück.

---

## Bewertung

Ein numerischer Wert (0–100), der die Relevanz eines Taxonomie-Knotens für eine bestimmte Anforderung darstellt. Bewertungen werden in der Benutzeroberfläche farblich codiert:

| Bereich | Farbe | Bedeutung |
|---|---|---|
| **70–100** | Dunkelgrün | Starke Übereinstimmung |
| **50–69** | Mittelgrün | Moderate Übereinstimmung |
| **1–49** | Hellgrün | Schwache Übereinstimmung |
| **0** | Keine Hervorhebung | Keine Übereinstimmung |

---

## Ankerknoten

Ein Knoten, der als Ausgangspunkt für den Aufbau einer Architekturansicht ausgewählt wird. Standardmäßig werden Knoten mit einer Bewertung ≥ 70 als Anker gewählt. Werden weniger als drei Anker gefunden, fällt der Schwellenwert auf ≥ 50 zurück (Top 3).

---

## Relevanzpropagierung

Nach der Auswahl der Ankerknoten folgt das System den Taxonomie-Beziehungen von jedem Anker nach außen und weist verbundenen Knoten abgeleitete Relevanzbewertungen zu. Dadurch wird die Architekturansicht um Elemente erweitert, die indirekt, aber sinnvoll mit der ursprünglichen Anforderung zusammenhängen.

---

## Architekturansicht

Ein strukturiertes Modell, das aus den bewerteten Analyseergebnissen generiert wird. Es enthält:

- **Elemente** — die als relevant ausgewählten Taxonomie-Knoten (Anker plus propagierte Knoten).
- **Beziehungen** — die Relationen, die diese Elemente verbinden.

Architekturansichten können nach ArchiMate XML, Visio `.vsdx`, Mermaid-Flussdiagramme oder JSON exportiert werden.

---

## Architektur-Wissensgraph

Der vollständige Graph, der aus allen Taxonomie-Knoten (Vertices) und ihren Beziehungen (Kanten) gebildet wird. Der Graph-Explorer ermöglicht es Ihnen, diesen Graphen zu durchsuchen, indem Sie vorgelagerte Abhängigkeiten, nachgelagerte Abhängige oder Ausfallauswirkungs-Nachbarschaften abfragen.

---

## Anforderungsauswirkung

Eine Analyse, die bestimmt, welche Teile des Architektur-Wissensgraphen von einer bestimmten Anforderung betroffen sind. Ausgehend von bewerteten Knoten verfolgt das System Beziehungen, um den breiteren Auswirkungsbereich zu identifizieren.

---

## Ausfallauswirkung

Eine Nachbarschaftsabfrage, die beantwortet: _„Wenn dieser Knoten ausfällt, was ist noch betroffen?"_ Die Abfrage folgt ausgehenden Beziehungen vom ausgewählten Knoten, um jedes Element zu ermitteln, das direkt oder transitiv von ihm abhängt.

---

## Lückenanalyse

Eine Analyse, die die bewerteten Taxonomie-Ergebnisse mit dem vollständigen Beziehungsgraphen vergleicht, um Folgendes zu identifizieren:

- **Fehlende Beziehungen** — Paare von Elementen, die beide relevant sind, aber keine verbindende Beziehung aufweisen.
- **Unvollständige Muster** — Standard-Architekturmuster, die nur teilweise vorhanden sind.

---

## Mustererkennung

Überprüfung von Standard-Architekturmustern innerhalb der bewerteten Ergebnisse. Unterstützte Muster umfassen:

| Muster | Beschreibung |
|---|---|
| **Full Stack** | Ein vollständiger vertikaler Schnitt von der User Application über Core/COI Service bis hinunter zum Communications Service |
| **App Chain** | Eine Kette von User Applications, die über gemeinsame Information Products verknüpft sind |
| **Role Chain** | Eine Abfolge von Business Roles, die über Business Processes verbunden sind |

---

## Beziehungsvorschlag

Ein KI-generierter Vorschlag für eine neue Beziehung zwischen zwei Taxonomie-Knoten. Vorschläge gelangen in eine Überprüfungswarteschlange, in der ein menschlicher Prüfer jeden einzelnen **akzeptieren** oder **ablehnen** kann, bevor er Teil des Wissensgraphen wird.

---

## Blattknoten-Begründung

Eine natürlichsprachliche Erklärung, die von der KI generiert wird und beschreibt, _warum_ ein bestimmter Blattknoten für eine gegebene Anforderung eine hohe Bewertung erhalten hat. Nützlich für die Prüfung und Validierung von Analyseergebnissen.

---

## Ansichtsmodi

Der Taxonomie-Baum kann in fünf verschiedenen visuellen Layouts dargestellt werden:

| Modus | Beschreibung |
|---|---|
| **Liste** | Traditioneller zusammenklappbarer Baum mit eingerückten Knoten |
| **Tabs** | Jedes Taxonomie-Blatt in einem separaten Tab |
| **Sunburst** | Radiales Hierarchiediagramm, das den gesamten Baum auf einen Blick zeigt |
| **Baum** | Horizontales Dendrogramm-Layout |
| **Entscheidungskarte** | Treemap-Layout zum Vergleich von Knotengrößen und Bewertungen |

---

## Beziehungstyp

Jede Beziehung hat einen Typ, der die Art der Verbindung zwischen zwei Taxonomie-Knoten definiert. Das System definiert 10 Beziehungstypen, die jeweils einem Konzept eines Standard-Architektur-Frameworks entsprechen:

| Typ | Quelle → Ziel | Standard |
|---|---|---|
| **REALIZES** | Capability → Core Service | NAF NCV-2 |
| **SUPPORTS** | Core Service → Business Process | TOGAF Business Architecture |
| **CONSUMES** | Business Process → Information Product | TOGAF Data Architecture |
| **USES** | User Application → Core Service | NAF NSV-1 |
| **FULFILLS** | COI Service → Capability | NAF NCV-5 |
| **ASSIGNED_TO** | Business Role → Business Process | TOGAF Org mapping |
| **DEPENDS_ON** | Core Service → Core Service | Technical dependency |
| **PRODUCES** | Business Process → Information Product | Data flow |
| **COMMUNICATES_WITH** | Communications Service → Core Service | NAF NSOV |
| **RELATED_TO** | Any → Any | Generischer Fallback |

Die `RelationCompatibilityMatrix` erzwingt, welche Quell- und Ziel-Stammkategorien für jeden Typ gültig sind.

---

## Architektur-DSL

Eine textbasierte domänenspezifische Sprache zur Beschreibung von Architekturmodellen. DSL-Dokumente verwenden das `.taxdsl`-Format mit geschweiften Klammern (`element ... {`, `relation ... {`, `meta {`) und `key: value;`-Eigenschaftssyntax für Elemente, Beziehungen und Domänenmetadaten.

DSL-Dokumente werden mit JGit versioniert, wobei alle Git-Objekte in der Datenbank gespeichert werden (nicht im Dateisystem). Das System unterstützt Branching, Merging, Cherry-Picking und semantische Diffs zwischen Versionen.

---

## Beziehungshypothese

Eine vorläufige Beziehung, die während der LLM-Analyse generiert wird. Im Gegensatz zu [Beziehungsvorschlägen](#beziehungsvorschlag) (die auf Abruf generiert werden) werden Hypothesen automatisch als Teil der Analyse-Pipeline erstellt.

Hypothesen durchlaufen einen Lebenszyklus: **PENDING** → **ACCEPTED** (erstellt eine bestätigte `TaxonomyRelation`), **REJECTED** (verworfen) oder **APPLIED** (nur für die aktuelle Sitzung angewendet, ohne Persistierung).

---

## Beziehungsnachweis

Unterstützende Daten für eine Beziehungshypothese oder einen Vorschlag. Nachweis-Datensätze erfassen, warum eine Beziehung vorgeschlagen wurde, einschließlich des LLM-Antworttexts, des Analysekontexts und etwaiger Konfidenzwerte.

---

## Erklärungsverfolgung

Eine strukturierte Argumentationskette, die erklärt, warum ein Taxonomie-Knoten für eine bestimmte Anforderung eine bestimmte Bewertung erhalten hat. Verfolgungen umfassen die Argumentation des LLM, beitragende Faktoren und das Konfidenzniveau.

---

## Kanonisches Architekturmodell

Die interne Darstellung einer in DSL beschriebenen Architektur. Es enthält:

- **Elemente** — Architektur-Bausteine (Capabilities, Services, Anwendungen usw.)
- **Beziehungen** — gerichtete Verbindungen zwischen Elementen
- **Anforderungen** — die geschäftlichen Anforderungen, die die Architektur motiviert haben
- **Zuordnungen** — Verknüpfungen zwischen Anforderungen und Architektur-Elementen
- **Ansichten** — benannte Teilmengen von Elementen und Beziehungen
- **Nachweise** — Begründungsdaten für Beziehungen

---

## Framework-Zuordnung

Eine Framework-Zuordnung konvertiert Elemente und Beziehungen aus einem externen Architektur-Framework (wie UAF, APQC oder C4/Structurizr) in die interne Darstellung der Taxonomie. Jedes unterstützte Framework verfügt über ein **Zuordnungsprofil**, das definiert, wie externe Typen den Taxonomie-Stammcodes und Beziehungstypen entsprechen.

Beispielsweise ordnet das UAF-Profil `Capability` → CP, `OperationalActivity` → BP, `System` → UA zu. Das APQC-Profil ordnet Hierarchieebenen (1–5) verschiedenen Stammcodes zu. Das C4-Profil ordnet `SoftwareSystem` → SY, `Container` → UA, `Component` → CM zu.

Importierte Elemente tragen ein `x-source-framework`-Erweiterungsattribut, das festhält, aus welchem Framework sie stammen.

Siehe [Framework-Import](FRAMEWORK_IMPORT.md) für vollständige Zuordnungstabellen und den Import-Workflow.

---

## Qualitätsanalyse

Die Qualitätsanalyse bewertet die Vollständigkeit und Konsistenz des Architekturmodells. Sie untersucht:

- **Beziehungsvollständigkeit** — ob Elemente die erwarteten ausgehenden und eingehenden Beziehungen basierend auf ihrem Typ aufweisen
- **Waisenerkennung** — Elemente ohne jegliche Beziehungen
- **Typkonsistenz** — ob die Quell-/Zieltypen der Beziehungen mit der Kompatibilitätsmatrix übereinstimmen

Das Qualitäts-Dashboard (📊) im rechten Panel zeigt diese Metriken für die aktuellen Beziehungsvorschläge an.

---

## Lückenanalyse

Die Lückenanalyse identifiziert fehlende Architekturabdeckung, indem das aktuelle Modell mit erwarteten Mustern verglichen wird:

- **Fehlende Beziehungen** — erwartete Beziehungen, die zwischen verwandten Elementen nicht existieren
- **Unvollständige Muster** — Teiltreffer bekannter Architekturmuster (Full Stack, App Chain, Role Chain)
- **APQC-Abdeckung** — wie gut die Taxonomie auf die APQC Process Classification Framework-Kategorien abgebildet wird

API-Endpunkt: `GET /api/gap/apqc-coverage` gibt `ApqcCoverageResult` mit Abdeckungsstatistiken zurück.

Siehe [Benutzerhandbuch](USER_GUIDE.md) § 11e für das Lückenanalyse-Panel.

---

## Arbeitsbereich

Eine isolierte Bearbeitungsumgebung für einen einzelnen Benutzer. Jeder Arbeitsbereich bietet:

- **Unabhängige Kontextnavigation** — browserähnliches Vor-/Zurücknavigieren durch Architekturversionen
- **Projektionsverfolgung** — benutzerspezifische Materialisierungszustände, sodass die Aktualisierung eines Benutzers andere nicht beeinflusst
- **Operationsisolierung** — Merge-, Cherry-Pick- und Revert-Operationen werden pro Arbeitsbereich verfolgt
- **Branch-Level-Isolierung** — das zugrunde liegende Git-Repository wird geteilt, aber jeder Benutzer arbeitet auf seinem eigenen Branch

Arbeitsbereiche werden beim ersten Zugriff verzögert erstellt und bleiben über Sitzungen hinweg bestehen. Der In-Memory-Zustand (aktueller Kontext, Navigationshistorie) wird von `WorkspaceManager` gehalten; persistente Metadaten (Branch, Zeitstempel) werden in der `user_workspace`-Tabelle gespeichert.

Siehe [Architektur](ARCHITECTURE.md) § Multi-User Workspace für die Designübersicht.

---

## Variante

Ein benannter Branch im Git-gestützten DSL-Repository. Varianten ermöglichen es Benutzern, alternative Architekturdesigns zu erkunden, ohne den Haupt-Branch (gemeinsam genutzt) zu beeinflussen. Wichtige Operationen:

| Operation | Beschreibung |
|---|---|
| **Erstellen** | Einen neuen Branch vom aktuellen Kontext abzweigen |
| **Wechseln** | Eine andere Variante zur Bearbeitung öffnen |
| **Vergleichen** | Semantischer Diff zwischen zwei Varianten |
| **Zusammenführen** | Änderungen von einer Variante in eine andere integrieren |
| **Zurückkopieren** | Selektiv Elemente von einer schreibgeschützten Variante in den bearbeitbaren Arbeitsbereich übertragen |

Varianten werden im Unter-Tab **Varianten** des Versionen-Tabs angezeigt. Jede Variante zeigt ihren Branch-Namen, den letzten Commit und die Commit-Anzahl.

---

## Arbeitsbereich-Projektion

Eine benutzerspezifische materialisierte Ansicht des DSL-Modells zu einem bestimmten Commit. Die Projektion konvertiert die textbasierte DSL in Datenbankentitäten (Taxonomie-Knoten, Beziehungen), die die Benutzeroberfläche und den Suchindex antreiben.

Die Projektion jedes Benutzers wird unabhängig verfolgt:

- **Projektions-Commit** — der SHA des Commits, der zuletzt materialisiert wurde
- **Index-Commit** — der SHA des Commits, aus dem der Suchindex zuletzt erstellt wurde
- **Veralterung** — die Projektion ist veraltet, wenn HEAD über den Projektions-Commit hinausgegangen ist

Dies ermöglicht es mehreren Benutzern, auf verschiedenen Branches zu arbeiten, ohne den materialisierten Zustand des anderen zu beeinträchtigen.

---

## Synchronisation (Sync)

Der Prozess des Austauschs von Änderungen zwischen dem Arbeitsbereich eines Benutzers und dem gemeinsamen Integrations-Repository. Zwei Hauptoperationen:

| Operation | Richtung | Beschreibung |
|---|---|---|
| **Vom Gemeinsamen synchronisieren** | Gemeinsam → Benutzer | Führt die neuesten Änderungen des gemeinsamen Branches in den Benutzer-Branch zusammen |
| **Veröffentlichen** | Benutzer → Gemeinsam | Führt die Änderungen des Benutzer-Branches in den gemeinsamen Branch zusammen |

Der Synchronisationszustand verfolgt:
- **Status**: `UP_TO_DATE`, `BEHIND` (gemeinsam hat neuere Commits), `AHEAD` (Benutzer hat unveröffentlichte Commits), `DIVERGED` (beide haben sich geändert)
- **Unveröffentlichte Commit-Anzahl**: Anzahl der Benutzer-Commits, die noch nicht in den gemeinsamen Branch zusammengeführt wurden

Siehe [Benutzerhandbuch](USER_GUIDE.md) § 11 für den Sync-Unter-Tab im Versionen-Tab.

## Konfliktlösung

Wenn eine Merge- oder Cherry-Pick-Operation nicht automatisch abgeschlossen werden kann (weil beide Seiten denselben DSL-Inhalt geändert haben), tritt das System in einen **Konfliktzustand** ein. Die Benutzeroberfläche zur Konfliktlösung zeigt:

- **Ours (Ziel)** — den DSL-Inhalt auf dem Ziel-Branch
- **Theirs (Quelle)** — den DSL-Inhalt vom Quell-Branch oder dem Cherry-Pick-Commit
- **Aufgelöst** — einen bearbeitbaren Bereich, in dem der Benutzer den endgültigen Inhalt manuell zusammenstellt

Schnelloptionen **Unsere verwenden** / **Deren verwenden** ermöglichen es dem Benutzer, eine Seite vollständig auszuwählen; die
Schaltfläche **Auflösen & Committen** speichert den aufgelösten Inhalt auf dem Ziel-Branch.

API-Unterstützung: `GET /api/dsl/merge/conflicts`, `POST /api/dsl/merge/resolve`,
`GET /api/dsl/cherry-pick/conflicts`, `POST /api/dsl/cherry-pick/resolve`.

## Divergierter Zustand

Wenn sowohl der Branch des Benutzers als auch der gemeinsame Integrations-Branch Commits haben, die der andere nicht hat, ist der Synchronisationszustand **DIVERGED**. Drei Lösungsstrategien stehen zur Verfügung:

| Strategie | Beschreibung |
|---|---|
| **MERGE** | Versuch, die gemeinsamen Änderungen in den Branch des Benutzers zusammenzuführen. Kann fehlschlagen, wenn Inhaltskonflikte bestehen. |
| **KEEP_MINE** | Die Version des Benutzers im gemeinsamen Repository veröffentlichen und gemeinsame Änderungen überschreiben. |
| **TAKE_SHARED** | Den Branch des Benutzers durch die gemeinsame Version ersetzen und lokale Änderungen verwerfen. |

API-Unterstützung: `POST /api/workspace/resolve-diverged?strategy=MERGE|KEEP_MINE|TAKE_SHARED`.

## Rückmeldung zu Operationsergebnissen

Jede Git-Operation (Merge, Cherry-Pick, Veröffentlichen, Sync, Branch-Löschung) erzeugt visuelles Feedback über eine **Bootstrap-Toast-Benachrichtigung** in der unteren rechten Ecke. Der Toast zeigt:
- ✅ Erfolg mit Operationszusammenfassung
- ❌ Fehler mit Fehlerdetails
- ⚠️ Warnungen (z. B. Konflikt erkannt)
