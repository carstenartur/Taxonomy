# Taxonomy Architecture Analyzer — Benutzerhandbuch

## Schnellster Weg — Ihre erste Analyse in 5 Schritten

| Schritt | Aktion | Wo |
|:---:|---|---|
| **1** | Melden Sie sich mit `admin` / `admin` an | Anmeldeseite unter `http://localhost:8080` |
| **2** | Geben Sie Ihre Anforderung ein | Rechtes Panel → Textbereich „Business Requirement Analysis" |
| **3** | Klicken Sie auf **Analyze with AI** | Schaltfläche unterhalb des Textbereichs |
| **4** | Erkunden Sie den bewerteten Baum und die Architekturansicht | Linkes Panel (Baum) + rechtes Panel (Architekturansicht-Karte) |
| **5** | Exportieren Sie Ihr Diagramm | Linkes Panel → ArchiMate / Visio / Mermaid / JSON Schaltflächen |

> **Beispielanforderung:** _„Bereitstellung einer integrierten Kommunikationsplattform für Krankenhauspersonal, die einen Echtzeit-Sprach- und Datenaustausch zwischen Abteilungen ermöglicht."_

Das System bewertet jeden Taxonomieknoten (0–100), hebt die relevantesten Elemente mit farbcodierten Bewertungen hervor, erzeugt eine Architekturansicht, die deren Zusammenhänge zeigt, und ermöglicht den Export der Ergebnisse.

![Bewerteter Taxonomiebaum](../images/15-scored-taxonomy-tree.png)

**Bereit für mehr?** Lesen Sie weiter für die vollständige Anleitung, oder springen Sie zu [Architekturansicht](#7-architecture-view), um zu erfahren, wie die Architektur generiert wird.

> 💡 **Neue Benutzer:** Beginnen Sie mit dem Kernarbeitsablauf (Analysieren → Architektur → Export).
> Erweiterte Funktionen wie Graph Explorer, DSL-Editor und Gap-Analyse werden in späteren Abschnitten beschrieben.

---

## Inhaltsverzeichnis

1. [Übersicht](#1-overview)
2. [Erste Schritte](#2-getting-started)
3. [Die Benutzeroberfläche verstehen](#3-understanding-the-interface)
4. [Eine Geschäftsanforderung analysieren](#4-analyzing-a-business-requirement)
5. [Die Taxonomie erkunden](#5-exploring-the-taxonomy)
6. [Arbeiten mit Analyseergebnissen](#6-working-with-analysis-results)
7. [Architekturansicht](#7-architecture-view)
8. [Den Graph Explorer verwenden](#8-using-the-graph-explorer)
9. [Arbeiten mit Beziehungsvorschlägen](#9-working-with-relation-proposals)
10. [Ergebnisse exportieren](#10-exporting-results)
11. [Suche](#11-search)
    - [Qualitäts-Dashboard](#11a-quality-dashboard)
    - [Beziehungs-Browser](#11b-relations-browser)
    - [Anforderungsabdeckung](#11c-requirement-coverage)
    - [Architektur-Lückenanalyse](#11d-architecture-gap-analysis)
    - [Architekturempfehlung](#11e-architecture-recommendation)
    - [Architekturmuster-Erkennung](#11f-architecture-pattern-detection)
    - [Architektur-DSL](#11g-architecture-dsl)
12. [Versionen-Tab](#12-versions-tab)
13. [Git-Status und Kontextleiste](#13-git-status-and-context-bar)
14. [Administration](#14-administration)
15. [Beziehungstypen-Referenz](#15-relation-types-reference)
16. [Tipps und Best Practices](#16-tips-and-best-practices)
17. [Glossar](#17-glossary)
18. [Fehlerbehebung](#18-troubleshooting)

---

## 1. Übersicht

Der **Taxonomy Architecture Analyzer** ist eine Webanwendung, die Architekten, Analysten und Requirements Engineers dabei unterstützt, freitextliche Missions- und Geschäftsanforderungen dem C3-Taxonomie-Katalog zuzuordnen. Sie beschreiben, was Sie benötigen, in einfachem Englisch, und die Anwendung findet die relevantesten Taxonomieknoten, zeigt Ihnen deren Zusammenhänge und ermöglicht den Export strukturierter Diagramme.

**Für wen ist dieses Handbuch?**

- **Requirements Engineers**, die Anforderungen klassifizieren und Architektur-Elementen zuordnen müssen.
- **Architekten und Capability-Planer**, die die Taxonomie nutzen, um C3-Systeme zu entwerfen oder zu bewerten.
- **Analysten**, die die Taxonomiestruktur erkunden und KI-generierte Beziehungsvorschläge überprüfen.

**Was Sie mit der Anwendung tun können:**

| Aufgabe | Wo zu finden |
|---|---|
| Eine Anforderung analysieren und passende Taxonomieknoten anzeigen | Rechtes Panel → Business Requirement Analysis Karte |
| Die Taxonomie in verschiedenen visuellen Layouts durchsuchen | Linkes Panel → Ansichtsumschalter-Schaltflächen |
| Herausfinden, warum ein Knoten hoch bewertet wurde | Klicken Sie auf 📋 bei einem beliebigen bewerteten Knoten |
| Nach Taxonomieknoten suchen (Volltext, semantisch, hybrid, Graph) | Rechtes Panel → 🔍 Search Taxonomy Panel |
| Semantisch ähnliche Knoten finden | Klicken Sie auf 🔍 Similar bei einem beliebigen Taxonomieknoten |
| Vor- und nachgelagerte Abhängigkeiten verfolgen | Rechtes Panel → Graph Explorer |
| Anforderungs-Auswirkungsanalyse durchführen | Graph Explorer → 🎯 Req. Impact Schaltfläche |
| Architekturlücken identifizieren | API: `POST /api/gap/analyze` (§11d) |
| Architekturempfehlungen erhalten | API: `POST /api/recommend` (§11e) |
| Architekturmuster erkennen | API: `GET /api/patterns/detect` (§11f) |
| Erweiterte Ausfallauswirkung mit Anforderungskorrelation | API: `GET /api/graph/node/{code}/enriched-failure-impact` (§8) |
| KI-generierte Beziehungsvorschläge überprüfen, akzeptieren oder ablehnen | Rechtes Panel → Relation Proposals Panel |
| Qualitätsmetriken für Beziehungsvorschläge anzeigen | Rechtes Panel → 📊 Quality Dashboard Panel |
| Anforderungsabdeckung erfassen und analysieren | Rechtes Panel → 📋 Requirement Coverage Panel |
| Taxonomie-Beziehungen durchsuchen, erstellen oder löschen | Rechtes Panel → 🔗 Relations Browser Panel |
| Ein Diagramm oder Bewertungsbogen exportieren | Linkes Panel → Export-Schaltflächen (erscheinen nach der Analyse) |
| Analyse als JSON speichern | Linkes Panel → Export-Schaltflächen → 📥 JSON |
| Eine gespeicherte Analyse laden | Linkes Panel → 📤 Load Scores Schaltfläche |
| LLM-Einstellungen und Prompt-Vorlagen verwalten | Admin-Modus über 🔒 in der Navigationsleiste freischalten |

> Für die REST-API-Referenz, die von Entwicklern und Integratoren verwendet wird, siehe [API-Referenz](API_REFERENCE.md).

---

## 2. Erste Schritte

### Die Anwendung öffnen

Öffnen Sie Ihren Webbrowser und navigieren Sie zur Anwendungs-URL (zum Beispiel `http://localhost:8080` bei lokaler Ausführung oder die von Ihrem Administrator bereitgestellte Deploy-URL).

Die Anwendung wird als einzelne Seite geladen. Beim ersten Zugriff wird eine Anmeldeseite angezeigt — melden Sie sich mit den Standard-Anmeldedaten (`admin` / `admin`) oder mit dem über die Umgebungsvariable `TAXONOMY_ADMIN_PASSWORD` konfigurierten Passwort an. Nach der Anmeldung sind alle Standardfunktionen verfügbar; Administratorfunktionen erfordern zusätzlich das Freischalten des Admin-Modus (siehe [Abschnitt 14](#14-administration)).

**Erstbenutzer** sehen ein **Willkommens-Overlay** mit einer 3-Schritte-Anleitung, die erklärt, wie Sie beginnen:
1. Beschreiben Sie Ihre Anforderung im Textbereich
2. Klicken Sie auf **Analyze with AI**
3. Erkunden Sie die Ergebnisse

Klicken Sie auf **Got it — let's start!**, um das Overlay zu schließen. Das Overlay wird bei nachfolgenden Besuchen nicht mehr angezeigt (wird im localStorage Ihres Browsers gespeichert). Um das Onboarding zurückzusetzen, öffnen Sie die Browser-Konsole und führen Sie `TaxonomyOnboarding.reset()` aus.

![Vollständiges Seitenlayout](../images/01-full-page-layout.png)

### KI-Verfügbarkeit prüfen

Schauen Sie auf die Navigationsleiste am oberen Rand der Seite. Dort befindet sich ein **AI Status**-Indikator:

- 🟢 **Grünes Badge** — ein LLM-Anbieter ist verbunden und die Analyse ist verfügbar.
- 🔴 **Rotes Badge** — kein LLM-Anbieter ist konfiguriert; die Analyse ist nicht verfügbar. Kontaktieren Sie Ihren Administrator.

---

## 3. Die Benutzeroberfläche verstehen

Die Anwendung ist in zwei nebeneinander liegende Hauptpanels unterteilt.

### Linkes Panel — Taxonomiebaum

Das linke Panel (breitere Spalte) zeigt den **Taxonomiebaum** an. Dies ist der vollständige Katalog der C3-Fähigkeiten, Dienste, Rollen und Informationsprodukte.

Am oberen Rand des linken Panels finden Sie:

- **Ansichtsumschalter-Schaltflächen:** 📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision | 📋 Summary — wechseln Sie zwischen verschiedenen Visualisierungen der Taxonomie.
- **Export-Schaltflächen** (erscheinen nur nach einer erfolgreichen Analyse): 📥 SVG | 📥 PNG | 📥 PDF | 📥 CSV | 📥 JSON | 📥 Visio | 📥 ArchiMate | 📥 Mermaid
- **Load Scores-Schaltfläche** (immer sichtbar): 📤 Load Scores — importiert eine zuvor gespeicherte JSON-Analysedatei
- **Expand All / Collapse All** — alle Knoten in der aktuellen Ansicht auf- oder zuklappen.
- **Taxonomie-Stamm-Auswahl** (nur Baumansicht) — wählen Sie, welcher Taxonomie-Stamm angezeigt werden soll.
- **Descriptions-Umschalter** — Beschreibungstext für jeden Knoten ein- oder ausblenden.

Jede Taxonomieknoten-Zeile zeigt:
- Den **Knotennamen** und seinen hierarchischen Code.
- Einen **Bewertungsbalken** und Prozentsatz (sichtbar nach der Analyse).
- **Aktionsschaltflächen pro Knoten:** 🔗 (Beziehungen vorschlagen) | 📋 (Begründung anfordern) | 🔎 (Graph Explorer)

![Linkes Panel — Taxonomiebaum in Listenansicht](../images/02-left-panel-list-view.png)

### Rechtes Panel — Analyse und Werkzeuge

Das rechte Panel (schmalere Spalte) enthält alle interaktiven Werkzeuge:

- **Business Requirement Analysis Karte** — der Haupttextbereich, in dem Sie eine Anforderung eingeben und die Analyse ausführen.
- **Match Legend** — Farbskala von 0 % bis 100 %, die zeigt, was jeder Grünton bedeutet.

![Match Legend](../images/10-match-legend.png)

- **Statusbereich** — Meldungen und Warnungen zur aktuellen Operation.
- **Analyseprotokoll** (einklappbar) — Schritt-für-Schritt-Protokoll des Bewertungsprozesses.
- **Architekturansicht-Panel** — erscheint nach der Analyse, wenn das Architecture View-Kontrollkästchen aktiviert ist.
- **Graph Explorer Panel** — geben Sie einen Knotencode ein und führen Sie Upstream-, Downstream- oder Ausfallauswirkungs-Abfragen durch.
- **Relation Proposals Panel** — überprüfen, akzeptieren oder ablehnen Sie KI-generierte Beziehungsvorschläge.
- **LLM-Kommunikationsprotokoll** (nur Admin, einklappbar) — Roh-Prompt- und Antwortprotokoll.
- **LLM-Diagnose-Panel** (nur Admin, einklappbar) — Verbindungstest und Statistiken.
- **Prompt-Vorlagen-Editor** (nur Admin, einklappbar) — LLM-Prompt-Vorlagen anzeigen und bearbeiten.

![Rechtes Panel — Standardzustand](../images/03-right-panel-default.png)

### Navigationsleiste

Die Navigationsleiste am oberen Rand der Seite enthält:

- **Anwendungstitel / Logo**
- **AI Status Badge** (🟢 grün oder 🔴 rot)
- **🔒 Admin-Modus-Schaltfläche** — klicken Sie, um das Admin-Modus-Passwort-Modal zu öffnen

### Dunkelmodus

Klicken Sie auf die **🌙** (Mond)-Schaltfläche in der Navigationsleiste, um in den Dunkelmodus zu wechseln. Klicken Sie auf **☀️** (Sonne), um zum Hellmodus zurückzukehren. Ihre Einstellung wird in Ihrem Browser gespeichert und bleibt über Sitzungen hinweg erhalten.

---

## 4. Eine Geschäftsanforderung analysieren

### Eine gute Anforderung formulieren

In der **Business Requirement Analysis** Karte im rechten Panel sehen Sie einen großen Textbereich mit der Beschriftung *„Geben Sie Ihre Geschäftsanforderung ein…"*.

Geben Sie Ihre Anforderung als klaren, imperativen Satz ein. Zum Beispiel:

> *„Bereitstellung einer integrierten Kommunikationsplattform für Krankenhauspersonal, die einen Echtzeit-Sprach- und Datenaustausch zwischen Abteilungen ermöglicht."*

Tipps für gute Anforderungen:
- Verwenden Sie Fachvokabular: Fähigkeit, Dienst, Informationsprodukt, Kommunikation, Kommando, Kontrolle.
- Seien Sie spezifisch hinsichtlich der benötigten Funktion oder des gewünschten Ergebnisses.
- Halten Sie den Text unter 500 Wörtern; längerer Text verbessert die Genauigkeit nicht.

![Business Requirement Analysis Karte](../images/04-analysis-panel-empty.png)

### Standardanalyse

1. Geben Sie Ihre Anforderung im Textbereich ein.
2. Stellen Sie sicher, dass das **Interactive Mode**-Kontrollkästchen **deaktiviert** ist, für eine Standard-Analyse (gesamter Baum).
3. Klicken Sie auf die Schaltfläche **Analyze with AI**.
4. Im Statusbereich erscheint eine Fortschrittsanzeige. Der Taxonomiebaum im linken Panel beginnt, farbcodierte Bewertungsbalken anzuzeigen, sobald Ergebnisse eintreffen.
5. Wenn die Analyse abgeschlossen ist, zeigt der Statusbereich eine Zusammenfassung an und die Export-Schaltflächen werden verfügbar.

![Bewerteter Taxonomiebaum](../images/15-scored-taxonomy-tree.png)

Der vollständig aufgeklappte Baum zeigt Bewertungen auf jeder Ebene und macht es leicht zu erkennen, welche Zweige am relevantesten sind:

![Bewerteter Taxonomiebaum — vollständig aufgeklappt](../images/35-scored-bp-tree-expanded.png)

### Interaktiver Modus

Aktivieren Sie das **Interactive Mode**-Kontrollkästchen, bevor Sie auf **Analyze with AI** klicken, um eine ebenenweise Erkundung anstelle der Bewertung des gesamten Baums auf einmal zu verwenden.

Im interaktiven Modus:
- Zuerst werden nur die Knoten der obersten Ebene bewertet.
- Neben jedem Knoten der obersten Ebene erscheint eine **▶ Analyze Node**-Schaltfläche.
- Klicken Sie auf **▶ Analyze Node** bei einem Knoten, um dessen Unterknoten zu bewerten.
- Setzen Sie die Erkundung des Baums Ebene für Ebene fort.

Dieser Modus ist nützlich für sehr große Taxonomien oder wenn Sie sich auf einen Zweig konzentrieren möchten.

![Interaktiver Modus](../images/16-interactive-mode.png)

### Architekturansicht-Kontrollkästchen

Aktivieren Sie das **Architecture View**-Kontrollkästchen, bevor Sie die Analyse starten, um nach der Berechnung der Bewertungen zusätzlich eine Architekturansicht zu erstellen. Die Architekturansicht verfolgt, wie die am höchsten bewerteten Knoten über bestätigte Architekturbeziehungen miteinander verbunden sind. Siehe [Abschnitt 7](#7-architecture-view) für Details.

### Bewertungen und die Farblegende verstehen

Die **Match Legend** (unterhalb der Analysekarte) zeigt die Farbskala:

| Farbe | Bewertungsbereich | Bedeutung | Textfarbe |
|---|---|---|---|
| Transparent | 0 % | Keine Übereinstimmung | Dunkel (Standard) |
| Sehr helles Grün | 1 % – 24 % | Sehr geringe Übereinstimmung | Dunkel (Standard) |
| Helles Grün | 25 % – 49 % | Geringe Übereinstimmung | Dunkel (Standard) |
| Mittleres Grün | 50 % – 59 % | Mäßige Übereinstimmung | Dunkel (Standard) |
| Dunkles Grün | 60 % – 99 % | Gute Übereinstimmung | **Weiß** (für Lesbarkeit) |
| Sattes Grün | 100 % | Perfekte Übereinstimmung | **Weiß** |

Die Farbe wird berechnet als `rgba(0, 128, 0, score/100)` — ein reines Grün, dessen **Deckkraft** (Alphakanal) linear mit dem Bewertungsprozentsatz ansteigt. Ab 60 % und höher wechselt die Textfarbe zu Weiß, um die Lesbarkeit vor dem dunkleren Hintergrund zu gewährleisten.

Knoten mit einer Bewertung von 0 % haben keine Hervorhebung. Fahren Sie mit der Maus über ein beliebiges Legendenfeld, um einen Tooltip mit der Beschreibung der Übereinstimmungsstufe zu sehen.

![Match Legend mit Bewertungen](../images/17-match-legend-with-scores.png)

### Das Analyseprotokoll

Unterhalb des Statusbereichs zeichnet ein einklappbarer **Analyseprotokoll**-Abschnitt jeden Schritt des Bewertungsprozesses auf: welche LLM-Phasen ausgeführt wurden, wie viele Knoten bewertet wurden und eventuelle Warnungen. Klicken Sie auf die Protokollüberschrift, um es auf- oder zuzuklappen.

### Streaming-Fortschrittsanzeige

Während der Analyse (insbesondere im interaktiven Modus) zeigt der Statusbereich Echtzeit-Fortschrittsmeldungen an. Jede Meldung entspricht einer Phase in der LLM-Verarbeitungspipeline:

| Phasenmeldung | Bedeutung |
|---|---|
| *„Stammtaxonomien werden analysiert…"* | Das LLM bewertet die Taxonomiekategorien der obersten Ebene |
| *„[Name] wird erweitert…"* | Das LLM untersucht die Unterknoten eines übereinstimmenden Knotens |
| *„Ebene N wird bewertet…"* | Das LLM verarbeitet Taxonomieknoten auf Tiefe N |
| *„Architekturansicht wird erstellt…"* | Die beziehungsbasierte Architekturansicht wird zusammengestellt |
| *„Analyse abgeschlossen"* | Alle Ebenen wurden erfolgreich verarbeitet |

Es kann auch ein Fortschrittsbalken in Prozent erscheinen, der ungefähr anzeigt, wie weit die Analyse durch die Taxonomieebenen fortgeschritten ist.

### Fehlerbehandlung während der Analyse

Wenn das LLM während der Analyse auf einen Fehler stößt, behandelt die Anwendung dies elegant:

| Fehler | Was Sie sehen | Was zu tun ist |
|---|---|---|
| **Verbindungs-Timeout** | Status zeigt „LLM-Verbindung abgelaufen" mit Teilergebnissen | Erneut versuchen — der LLM-Server kann vorübergehend überlastet sein |
| **Ratenlimit (HTTP 429)** | Status zeigt „Ratenlimit überschritten" | 60 Sekunden warten und erneut versuchen |
| **Ungültiger API-Schlüssel** | Status zeigt „Authentifizierung fehlgeschlagen" | Überprüfen Sie Ihren API-Schlüssel in den Umgebungsvariablen |
| **Teilweiser Fehler** | Einige Stämme wurden bewertet, andere zeigen Warnungen | Überprüfen Sie die Warnungen im Analyseprotokoll; Bewertungen für abgeschlossene Stämme sind weiterhin gültig |

Teilergebnisse werden nach Möglichkeit beibehalten — wenn 7 von 10 Stämmen vor einem Timeout bewertet wurden, werden diese Bewertungen angezeigt und nur die fehlgeschlagenen Stämme werden mit Warnungen gekennzeichnet.

### Sichtbarkeit der Export-Schaltflächen

Die Export-Schaltflächen (SVG, PNG, PDF, CSV, JSON, Visio, ArchiMate, Mermaid) erscheinen nur, wenn Analysebewertungen größer als 0 vorhanden sind. Wenn keine Analyse durchgeführt wurde oder alle Bewertungen 0 sind, werden die Export-Schaltflächen ausgeblendet und stattdessen der Hinweistext **„📋 Analyze first to enable exports"** angezeigt. Die **📤 Load Scores**-Schaltfläche ist immer sichtbar und kann verwendet werden, um eine vorherige Analyse wiederherzustellen.

---

## 5. Die Taxonomie erkunden

Das linke Panel zeigt die Taxonomie in sechs verschiedenen Ansichten. Wechseln Sie zwischen ihnen mit den Schaltflächen oben: **📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision | 📋 Summary**.

### Listenansicht (Standard)

Die Standardansicht zeigt alle Taxonomieknoten als flache, eingerückte Liste. Jede Zeile enthält den Knotennamen, seinen Code und — nach der Analyse — einen Bewertungsbalken und Prozentsatz.

- Klicken Sie auf einen beliebigen Knotennamen, um dessen Unterknoten auf- oder zuzuklappen.
- Verwenden Sie **Expand All**, um den gesamten Baum zu öffnen, oder **Collapse All**, um ihn zu schließen.
- Schalten Sie den **Descriptions**-Umschalter um, um den Beschreibungstext unter jedem Knotennamen ein- oder auszublenden.

![Listenansicht mit sichtbaren Beschreibungen](../images/09-list-view-descriptions.png)

### Registerkartenansicht

Die Registerkartenansicht gruppiert Taxonomieknoten unter Registerkarten-Überschriften für jede Kategorie der obersten Ebene. Klicken Sie auf eine Registerkarte, um nur die Knoten in diesem Zweig anzuzeigen.

![Registerkartenansicht](../images/05-tabs-view.png)

### Sunburst-Ansicht

Die Sunburst-Ansicht stellt die Taxonomie als radiales Sunburst-Diagramm dar, bei dem das Zentrum der Stamm ist und jeder Ring eine tiefere Ebene darstellt. Nach der Analyse werden die Segmente entsprechend ihrer Bewertung eingefärbt.

- Fahren Sie mit der Maus über ein Segment, um den Knotennamen und die Bewertung zu sehen.
- Klicken Sie auf ein Segment, um in diesen Teilbaum hineinzuzoomen.

![Sunburst-Ansicht](../images/06-sunburst-view.png)

Nach Durchführung einer Analyse zeigt das Sunburst-Diagramm Heatmap-Farbverläufe an, die die Bewertungen widerspiegeln:

![Bewertete Sunburst-Ansicht](../images/39-scored-sunburst.png)

### Baumansicht

Die Baumansicht stellt die Taxonomie als interaktives Knoten-Verbindungs-Diagramm dar. Verwenden Sie das **Taxonomy root selector**-Dropdown, um auszuwählen, welcher Stamm angezeigt werden soll, wenn es mehrere Taxonomiestämme gibt.

![Baumansicht](../images/07-tree-view.png)

### Entscheidungskarten-Ansicht

Die Entscheidungskarten-Ansicht zeigt die Taxonomie als Entscheidungsbaum-Layout, das für die Auswahl relevanter Knoten basierend auf den Analysebewertungen optimiert ist.

![Entscheidungskarten-Ansicht](../images/08-decision-map-view.png)

### Zusammenfassungsansicht (📋 Summary)

Die Zusammenfassungsansicht erscheint automatisch nach Durchführung einer Analyse mit aktiviertem **Architecture View**-Kontrollkästchen. Sie präsentiert einen geschichteten Architekturüberblick Ihrer Analyseergebnisse, gruppiert nach Taxonomiekategorie:

- **🔵 Fähigkeiten** — Fähigkeitsknoten der obersten Ebene
- **🟢 Geschäftsprozesse / Geschäftsrollen** — Operative Prozesse und organisatorische Rollen
- **🟠 Dienste** — Kern-, COI- und allgemeine Dienstknoten
- **🟣 Anwendungen** — Benutzerorientierte Anwendungselemente
- **🔷 Informationsprodukte** — Daten- und Informationsartefakte
- **🔴 Kommunikationsdienste** — Netzwerk- und Kommunikationsinfrastruktur

Jedes Element zeigt seinen Knotencode, Namen, Relevanzprozentsatz und eine Ankermarkierung (★), wenn es eine direkte Übereinstimmung war. Pfeile zwischen den Schichten zeigen die vorherrschenden Beziehungstypen an (z. B. SUPPORTS, REALIZES).

**Durch Klicken auf ein Element** in der Zusammenfassungsansicht wird zur Listenansicht gewechselt und zu diesem Knoten gescrollt, wobei er kurz hervorgehoben wird.

Die Summary-Schaltfläche erscheint im Ansichtsumschalter erst nach einer erfolgreichen Analyse mit aktivierter Architekturansicht.

### Zwischen Ansichten wechseln

Klicken Sie jederzeit auf eine der Ansichtsumschalter-Schaltflächen (📋 List | 📑 Tabs | 🔆 Sunburst | 🌳 Tree | 🏆 Decision | 📋 Summary). Ihre Analysebewertungen bleiben beim Ansichtswechsel erhalten.

### Alle auf-/zuklappen verwenden

Die **Expand All**- und **Collapse All**-Schaltflächen sind nur in Ansichten aktiv, die hierarchisches Auf- und Zuklappen unterstützen (Liste und Registerkarten). Sie öffnen oder schließen alle Knoten gleichzeitig.

### Beschreibungen ein-/ausblenden

Der **Descriptions**-Umschalter (oberhalb des Baums, unterhalb der Ansichtsschaltflächen) steuert, ob der Beschreibungstext unter jedem Knotennamen angezeigt wird. Schalten Sie ihn ein, um zu lesen, was jedes Taxonomieelement abdeckt; schalten Sie ihn aus für eine kompaktere Ansicht.

---

## 6. Arbeiten mit Analyseergebnissen

### Die Bewertungsfarben lesen

Nach Abschluss der Analyse zeigt jeder Taxonomieknoten einen farbigen Bewertungsbalken. Beziehen Sie sich auf die **Match Legend** im rechten Panel:

- **Keine Farbe** — Bewertung ist 0 %, nicht relevant.
- **Helles Grün → dunkles Grün** — zunehmende Relevanz.
- **Kräftiges/volles Grün** — maximale Relevanz.

Konzentrieren Sie sich auf Knoten mit dunkelgrüner Hervorhebung; diese sind die besten Übereinstimmungen für Ihre Anforderung.

### Eine Blattbegründung anfordern (📋-Schaltfläche)

Für jeden Blattknoten (einen Knoten ohne Unterknoten) mit einer Bewertung größer Null können Sie die KI bitten, in verständlichem Englisch zu erklären, **warum** dieser Knoten mit Ihrer Anforderung übereinstimmt.

1. Finden Sie den Knoten im Taxonomiebaum.
2. Klicken Sie auf die **📋**-Schaltfläche in der Zeile dieses Knotens.
3. Ein **Leaf Justification Modal** öffnet sich und zeigt die LLM-generierte Erklärung an.
4. Lesen Sie die Erklärung und schließen Sie das Modal, wenn Sie fertig sind.

![Blattbegründungs-Modal](../images/18-leaf-justification-modal.png)

### Warnung bei veralteten Ergebnissen

Wenn Sie Ihren Anforderungstext nach einer abgeschlossenen Analyse bearbeiten, erkennt die Anwendung, dass die angezeigten Bewertungen nicht mehr zum aktuellen Text passen, und zeigt eine **Warnung bei veralteten Ergebnissen** an:

1. Der **Textbereich** erhält einen **gelben Rahmen** mit einem sanften gelben Leuchten.
2. Eine **Warnmeldung** erscheint im Statusbereich: *„⚠️ Der Geschäftstext wurde geändert — vorherige Ergebnisse sind nicht mehr gültig."*
3. Eine **Reset Results**-Schaltfläche erscheint, mit der Sie die veralteten Bewertungen löschen können.

Die Warnung wird nach einer 300-ms-Verzögerung (Debounce) ausgelöst, wenn Sie im Geschäftstext-Bereich tippen — sie wird nicht sofort ausgelöst, um Flackern zu vermeiden.

**Um die Warnung zu beheben:**
- Klicken Sie auf **Reset Results**, um die alten Bewertungen zu löschen, und führen Sie dann die Analyse erneut durch, oder
- Klicken Sie erneut auf **Analyze with AI**, um die veralteten Bewertungen durch frische Ergebnisse zu ersetzen.

![Warnung bei veralteten Ergebnissen](../images/19-stale-results-warning.png)

---

## 7. Architekturansicht

Die Architekturansicht zeigt, wie die am höchsten bewerteten Taxonomieknoten über bestätigte Architekturbeziehungen (gespeichert in der Wissensbasis) miteinander verbunden sind. Sie bietet Ihnen eine strukturierte Ansicht der Architekturelemente, die für Ihre Anforderung relevant sind.

### Das Architekturansicht-Kontrollkästchen aktivieren

Aktivieren Sie vor der Analyse das **Architecture View**-Kontrollkästchen in der Business Requirement Analysis Karte. Nach Abschluss der Analyse erscheint das **Architekturansicht-Panel** im rechten Panel.

### Was im Architekturansicht-Panel erscheint

Das Panel zeigt drei Abschnitte:

| Abschnitt | Inhalt |
|---|---|
| **Anker** | Die am höchsten bewerteten Blattknoten — die primären Übereinstimmungen für Ihre Anforderung |
| **Elemente** | Alle Taxonomieknoten, die von den Ankern über bestätigte Beziehungen erreichbar sind |
| **Beziehungen** | Die gerichteten Kanten, die Elemente verbinden |

### Anker, Elemente und Beziehungen verstehen

- **Anker** sind Ihre direkten Treffer — die Knoten, die die KI als beste Antwort auf Ihre Anforderung betrachtet.
- **Elemente** erweitern das Bild: Wenn ein Ankerknoten eine Fähigkeit *realisiert*, erscheint diese Fähigkeit ebenfalls als Element.
- **Beziehungen** zeigen die Richtung und den Typ der Verbindung (z. B. REALIZES, SUPPORTS, DEPENDS_ON).

![Architekturansicht](../images/20-architecture-view.png)

---

## 8. Den Graph Explorer verwenden

Der Graph Explorer ermöglicht es Ihnen, das Netzwerk bestätigter Architekturbeziehungen um jeden Taxonomieknoten herum zu verfolgen, unabhängig davon, ob Sie eine Analyse durchgeführt haben.

### Einen Knoten auswählen

Im **Graph Explorer Panel** (rechtes Panel, unterhalb des Architekturansicht-Panels):

1. Geben Sie einen Knotencode in das Feld **Node Code** ein, oder klicken Sie auf die **🔎 Graph**-Schaltfläche bei einem beliebigen Taxonomieknoten im linken Panel, um das Feld vorauszufüllen.
2. Stellen Sie den **Max Hops**-Wert ein, um zu steuern, wie viele Beziehungsschritte durchlaufen werden sollen (Standard: 2).

![Graph Explorer Panel](../images/11-graph-explorer-panel.png)

### Upstream-Abfrage — „Was speist diesen Knoten?"

Klicken Sie auf **⬆️ Upstream**, um alle Knoten zu finden, die in den ausgewählten Knoten einfließen: die Knoten, von denen er abhängt oder die ihn realisieren. Die Ergebnisse erscheinen als Tabelle, die jeden verknüpften Knoten, seinen Beziehungstyp und einen Relevanzindikator auflistet.

### Downstream-Abfrage — „Was hängt davon ab?"

Klicken Sie auf **⬇️ Downstream**, um alle Knoten zu finden, die vom ausgewählten Knoten abhängen.

### Ausfallauswirkungs-Abfrage — „Was fällt aus, wenn dies ausfällt?"

Klicken Sie auf **⚠️ Failure Impact**, um alle Knoten zu finden, die gestört würden, wenn der ausgewählte Knoten ausfällt oder entfernt wird. Dies ist nützlich für Änderungsauswirkungs-Analysen und Risikobewertungen.

### Erweiterte Ausfallauswirkung — „Welche Anforderungen sind betroffen?"

Die **erweiterte Ausfallauswirkung** ergänzt die Standard-Ausfallauswirkungsanalyse, indem sie jeden betroffenen Knoten mit seinen Anforderungsabdeckungsdaten korreliert. Für jedes betroffene Element können Sie sehen:

- **Welche Anforderungen** dieses Element abdecken (nach Anforderungs-ID)
- **Wie viele Anforderungen** betroffen sind
- Einen aggregierten **Risiko-Score**, der die Anzahl der betroffenen Anforderungen mit der Relevanz jedes Elements kombiniert

Dies ist über die REST-API unter `GET /api/graph/node/{code}/enriched-failure-impact?maxHops=3` verfügbar. Siehe die [API-Referenz](API_REFERENCE.md#85-enriched-failure-impact) für vollständige Details.

> **Tipp:** Verwenden Sie die erweiterte Ausfallauswirkung zusammen mit dem Anforderungsabdeckungs-Panel (§11c), um zu priorisieren, welche Ausfälle das höchste Geschäftsrisiko tragen.

### Die Ergebnistabelle verstehen

Die Ergebnisse werden mit einem **Graph/Tabellen-Umschalter** dargestellt:

- **🔗 Graph-Ansicht** (Standard) — ein interaktives kräftebasiertes Knoten-Verbindungs-Diagramm, erstellt mit D3.js. Knoten sind nach Taxonomiekategorie eingefärbt, und der Ausgangsknoten ist mit einem dicken Rahmen hervorgehoben. Sie können Knoten ziehen, um das Layout neu anzuordnen, mit der Maus darüberfahren für Details und auf einen Knoten klicken, um ihn für weitere Abfragen auszuwählen.
- **📊 Tabellenansicht** — die traditionelle tabellarische Darstellung mit sortierbaren Spalten.

Die Tabelle/der Graph zeigt:

| Spalte | Bedeutung |
|---|---|
| Knotencode | Der eindeutige Bezeichner des verknüpften Knotens |
| Knotenname | Menschenlesbare Bezeichnung |
| Beziehungstyp | Der Typ der Beziehung (z. B. REALIZES, DEPENDS_ON) |
| Hops | Entfernung vom Ausgangsknoten |
| Relevanz | Auswirkungs-Score oder Ähnlichkeitsindikator |

![Graph Explorer Upstream-Ergebnisse](../images/21-graph-explorer-upstream.png)

![Graph Explorer Ausfallauswirkung](../images/22-graph-explorer-failure.png)

Akzeptierte Vorschläge erscheinen ebenfalls als Graph-Kanten. Nach dem Akzeptieren eines Vorschlags (siehe [Abschnitt 9](#9-working-with-relation-proposals)) ist die neue Beziehung sofort im Graph Explorer sichtbar:

![Graph Explorer mit akzeptierter Beziehung](../images/37-graph-with-accepted-relation.png)

---

## 9. Arbeiten mit Beziehungsvorschlägen

Das System kann automatisch neue Beziehungen zwischen Taxonomieknoten mithilfe von KI vorschlagen. Diese Vorschläge werden in einer Überprüfungswarteschlange gespeichert, in der Sie sie akzeptieren oder ablehnen können.

### Vorschläge auslösen (🔗-Schaltfläche an einem Knoten)

1. Finden Sie einen Taxonomieknoten im linken Panel, von dem Sie glauben, dass er mit anderen Knoten verknüpft sein sollte.
2. Klicken Sie auf die **🔗** (Beziehungen vorschlagen)-Schaltfläche in der Zeile dieses Knotens.
3. Das **Propose Relations Modal** öffnet sich.

![Modal „Relationen vorschlagen"](../images/13-propose-relations-modal.png)

### Auswahl eines Relationstyps

Im Modal „Relationen vorschlagen":

1. Bestätigen oder notieren Sie den oben angezeigten **Node Code**.
2. Verwenden Sie das Dropdown-Menü **Relation Type**, um den Typ der Relation auszuwählen, den die KI vorschlagen soll (z. B. REALIZES, SUPPORTS, DEPENDS_ON).
3. Klicken Sie auf **Generate Proposals**.
4. Das System sucht nach Kandidaten-Knoten und erstellt PENDING-Vorschläge. Schließen Sie das Modal, wenn der Vorgang abgeschlossen ist.

### Überprüfung von Vorschlägen (Filter: Pending / All / Accepted / Rejected)

Öffnen Sie das **Relation Proposals Panel** im rechten Bereich. Verwenden Sie die Filterschaltflächen, um Vorschläge nach Status anzuzeigen:

- **Pending** — Vorschläge, die auf Ihre Entscheidung warten.
- **All** — alle Vorschläge unabhängig vom Status.
- **Accepted** — Vorschläge, die Sie bereits genehmigt haben.
- **Rejected** — Vorschläge, die Sie abgelehnt haben.

Die Vorschlagstabelle zeigt:
- Quell-Knoten und Ziel-Knoten (mit Namen und Codes)
- Vorgeschlagener Relationstyp
- Konfidenz-Wert (0–100 %) — wie sicher die KI bei dem Vorschlag ist
- KI-generierte Begründung, die erklärt, warum diese Relation vorgeschlagen wurde

![Panel „Relationsvorschläge"](../images/12-relation-proposals-panel.png)

Die Vorschlags-Überprüfungswarteschlange zeigt alle Vorschläge mit Filterung nach Status. Verwenden Sie die Filterschaltflächen **Pending**, **All**, **Accepted** und **Rejected**, um sich auf bestimmte Vorschlagsstatus zu konzentrieren:

![Vorschlags-Überprüfungswarteschlange — alle Vorschläge](../images/28-proposal-review-queue.png)

### Annehmen oder Ablehnen eines Vorschlags

Für jede Zeile in der Vorschlagstabelle:

- Klicken Sie auf **Accept**, um die Relation zu genehmigen. Eine bestätigte `TaxonomyRelation` wird in der Wissensbasis erstellt und der Vorschlagsstatus ändert sich auf ACCEPTED.
- Klicken Sie auf **Reject**, um die Relation abzulehnen. Der Vorschlagsstatus ändert sich auf REJECTED.

Nach der Annahme zeigt der Vorschlag ein grünes **Accepted**-Badge an. Die akzeptierte Relation ist sofort im Graph-Explorer sichtbar:

![Akzeptierter Vorschlag](../images/36-proposal-accepted.png)

Nach jeder Annahme- oder Ablehnungsaktion erscheint eine **Rückgängig-Toast-Benachrichtigung** in der unteren rechten Ecke für 8 Sekunden. Klicken Sie auf **↩️ Undo**, um den Vorschlag auf den Status PENDING zurückzusetzen (und, falls er akzeptiert wurde, die erstellte Relation zu löschen).

### Massenaktionen

Beim Anzeigen ausstehender Vorschläge erscheint links eine **Checkbox-Spalte** und oberhalb der Tabelle eine **Massenaktionsleiste**:

1. Verwenden Sie die **Select All**-Checkbox in der Kopfzeile, um alle Vorschläge auszuwählen oder abzuwählen, oder wählen Sie einzelne Zeilen aus.
2. Klicken Sie auf **✅ Accept Selected**, um alle ausgewählten Vorschläge auf einmal anzunehmen.
3. Klicken Sie auf **❌ Reject Selected**, um alle ausgewählten Vorschläge auf einmal abzulehnen.
4. Die Rückgängig-Toast-Benachrichtigung nach einer Massenaktion ermöglicht es Ihnen, alle betroffenen Vorschläge mit einem Klick rückgängig zu machen.

### Konfidenz-Werte und Begründung verstehen

Die Spalte **Confidence** zeigt, wie stark die KI davon überzeugt ist, dass die vorgeschlagene Relation korrekt ist. Ein höherer Wert bedeutet einen sichereren Vorschlag. Die Spalte **Rationale** zeigt die Begründung der KI im Klartext. Verwenden Sie beides zusammen, um zu entscheiden, ob Sie annehmen oder ablehnen möchten.

---

## 10. Ergebnisse exportieren

Nach einer erfolgreichen Analyse erscheinen Export-Schaltflächen am oberen Rand des linken Bereichs. Diese Schaltflächen sind nur sichtbar, wenn Analyse-Bewertungen vorhanden sind.

![Export-Schaltflächen](../images/23-export-buttons.png)

Der Export-Tab bietet ein dediziertes Panel mit allen verfügbaren Exportformaten, gegliedert nach Kategorie (Diagramme, Daten, Berichte) sowie Importoptionen:

![Export-Tab — Gesamtansicht](../images/33-export-tab.png)

### SVG-Export

Klicken Sie auf **📥 SVG**, um die aktuelle Taxonomie-Ansicht als skalierbare Vektorgrafik (SVG-Datei) herunterzuladen. Geeignet zum Einbetten in Dokumente oder zur weiteren Bearbeitung in Vektorgrafiksoftware.

### PNG-Export

Klicken Sie auf **📥 PNG**, um einen gerasterten Screenshot der aktuellen Taxonomie-Ansicht als PNG-Bild herunterzuladen.

### PDF (Drucken)

Klicken Sie auf **📥 PDF**, um den Druckdialog des Browsers auszulösen, der vorkonfiguriert ist, um die aktuelle Ansicht als PDF zu drucken.

### CSV (Bewertungen)

Klicken Sie auf **📥 CSV**, um eine kommagetrennte Datei herunterzuladen, die alle Knoten-Codes, Namen und ihre Analyse-Bewertungen enthält. Öffnen Sie die Datei in einer Tabellenkalkulationsanwendung zur weiteren Analyse oder Berichterstellung.

### Visio (.vsdx) Architekturdiagramm

Klicken Sie auf **📥 Visio**, um eine strukturierte Microsoft Visio-Datei (`.vsdx`) herunterzuladen, die die Architekturansicht darstellt. Das Diagramm enthält die Anker-Knoten, verwandte Elemente und beschriftete Beziehungen.

> **Voraussetzung:** Die Checkbox „Architecture View" muss vor der Durchführung der Analyse aktiviert worden sein.

### ArchiMate XML-Architekturdiagramm

Klicken Sie auf **📥 ArchiMate**, um eine ArchiMate 3.x XML-Datei herunterzuladen, die zum Import in Tools wie Archi oder Sparx EA geeignet ist.

> **Voraussetzung:** Die Checkbox „Architecture View" muss vor der Durchführung der Analyse aktiviert worden sein.

### Mermaid-Flowchart-Export

Klicken Sie auf **📥 Mermaid**, um die Architekturansicht als Mermaid-Flowchart-Textdatei (`.mmd`) herunterzuladen. Das generierte Diagramm verwendet:

- **Subgraphs** für jede Taxonomie-Ebene (Capabilities, Processes, Services usw.)
- **Farbcodierte Klassendefinitionen**, die dem Farbschema der Ebene entsprechen
- **Beschriftete Kanten**, die Beziehungstypen anzeigen (REALIZES, SUPPORTS usw.)
- **Anker-Markierungen** (★) und Relevanz-Prozentsätze

Die Mermaid-Datei kann direkt in GitHub-READMEs, Confluence-Seiten und jedem Markdown-Renderer eingebettet werden, der die Mermaid-Syntax unterstützt. Fügen Sie den Inhalt einfach in einen ` ```mermaid `-Codeblock ein.

> **Voraussetzung:** Die Checkbox „Architecture View" muss vor der Durchführung der Analyse aktiviert worden sein.

### JSON-Bewertungsexport

Klicken Sie auf **📥 JSON** (in der Export-Gruppe, sichtbar nach einer erfolgreichen Analyse), um das aktuelle Analyseergebnis als `SavedAnalysis`-JSON-Datei herunterzuladen. Die Datei enthält:

- Den Text der Geschäftsanforderung
- Alle bewerteten Knoten-Codes und ihre Bewertungen (0–100)
- Die Begründungen/Erklärungen für jeden bewerteten Knoten
- Den Namen des LLM-Anbieters und einen Zeitstempel

Diese Datei kann mit Kollegen geteilt oder zu einem späteren Zeitpunkt über die Schaltfläche **📤 Load Scores** wieder geladen werden, ohne die KI-Analyse erneut durchführen zu müssen.

> **Semantische Unterscheidung:** Ein Wert von `0` im JSON bedeutet, dass der Knoten _bewertet und als nicht relevant eingestuft_ wurde. Ein Knoten-Code, der im JSON _fehlt_, wurde nie bewertet.

### Laden einer gespeicherten Analyse (Import)

Klicken Sie auf **📤 Load Scores** (immer sichtbar in der Werkzeugleiste, neben den Export-Schaltflächen), um eine zuvor exportierte `SavedAnalysis`-JSON-Datei zu laden. Nach Auswahl der Datei:

1. Der Text der Geschäftsanforderung wird im Textfeld wiederhergestellt.
2. Der Taxonomie-Baum wird mit den importierten Bewertungen dargestellt.
3. Die Export-Schaltflächen werden sichtbar.
4. Eine Statusmeldung bestätigt, wie viele Knoten bewertet wurden, und meldet eventuelle Warnungen (z. B. Knoten-Codes, die in der aktuellen Taxonomie-Version nicht vorhanden sind).

Dies ermöglicht **Offline-Überprüfung** und **Reproduzierbarkeit** — Sie können ein bewertetes Ergebnis mit einem Kollegen teilen, der es ohne einen API-Schlüssel öffnen kann.

> 📖 Für den Import von Architekturmodellen aus externen Frameworks (UAF, APQC, C4/Structurizr) siehe **[Framework-Import](FRAMEWORK_IMPORT.md)**.

### Wann Export-Schaltflächen erscheinen

Die Export-Schaltflächen erscheinen nur, nachdem eine Analyse durchgeführt wurde und mindestens ein Taxonomie-Knoten eine Bewertung größer als 0 hat. Wenn Sie die Seite verlassen oder aktualisieren, gehen die Bewertungen verloren und die Schaltflächen verschwinden. Ein Hinweis **"📋 Analyze first to enable exports"** wird angezeigt, wenn Exporte nicht verfügbar sind. Führen Sie die Analyse erneut durch oder verwenden Sie **📤 Load Scores**, um die Export-Schaltflächen wiederherzustellen.

---

## 11. Suche

Das Panel **Search Taxonomy** (rechte Spalte, einklappbar) bietet vier Suchmodi zum Auffinden von Taxonomie-Knoten. Öffnen Sie es durch Klicken auf die Zusammenfassung **🔍 Search Taxonomy**.

### Suchmodi

| Modus | Beschreibung | Embeddings erforderlich? |
|---|---|---|
| **Full-text** | Lucene-basierte Schlüsselwortsuche über Knotennamen und -beschreibungen | Nein |
| **Semantic** | KNN-Vektorähnlichkeit unter Verwendung von Satz-Embeddings | Ja |
| **Hybrid** | Reciprocal Rank Fusion, die Volltext- und semantische Ergebnisse kombiniert | Ja |
| **Graph** | Graph-semantische Suche einschließlich relationsbewusster Ergebnisse | Ja |

### Verwendung des Suchpanels

1. Geben Sie Ihre Abfrage in das **Sucheingabefeld** ein.
2. Wählen Sie einen **Suchmodus** aus dem Dropdown-Menü.
3. Wählen Sie die **maximale Ergebnisanzahl** (10, 20 oder 50).
4. Klicken Sie auf **🔍 Search** oder drücken Sie die Eingabetaste.
5. Die Ergebnisse erscheinen als anklickbare Liste. Klicken Sie auf ein Ergebnis, um den Knoten im Taxonomie-Baum hervorzuheben.

Screenshots der einzelnen Suchmodi in Aktion:

![Volltextsuche-Ergebnisse](../images/29-search-fulltext.png)

![Semantische Suche-Ergebnisse](../images/30-search-semantic.png)

![Hybridsuche-Ergebnisse](../images/31-search-hybrid.png)

![Graph-Suche-Ergebnisse](../images/32-search-graph.png)

### Embedding-Status

Das Badge **🧠 Embeddings** in der Navigationsleiste zeigt an, ob semantische Embeddings verfügbar sind:

- **🧠 Embeddings: N nodes** (blau) — Embeddings sind geladen; die Modi Semantic, Hybrid und Graph sind aktiviert.
- **🧠 Embeddings: unavailable** (grau) — Embeddings nicht geladen; nur die Volltextsuche ist verfügbar.

Wenn Embeddings nicht verfügbar sind, sind die Optionen Semantic, Hybrid und Graph im Modusauswahl-Menü ausgegraut.

### Ähnliche Knoten finden

Jede Taxonomie-Knotenzeile enthält eine Schaltfläche **🔍 Similar**. Durch Klicken darauf wird das Suchpanel geöffnet und die 10 semantisch ähnlichsten Knoten aufgelistet (erfordert Embeddings).

---

## 11a. Qualitäts-Dashboard

Das Panel **📊 Quality Dashboard** (rechte Spalte, einklappbar) zeigt Metriken zur Qualität der Relationsvorschläge an. Öffnen Sie es durch Klicken auf die Zusammenfassung; die Metriken werden automatisch geladen.

### Zusammenfassende Metriken

| Metrik | Beschreibung |
|---|---|
| **Total** | Gesamtzahl der generierten Vorschläge |
| **Accepted** | Akzeptierte und in Relationen umgewandelte Vorschläge |
| **Rejected** | Von einem Prüfer abgelehnte Vorschläge |
| **Pending** | Vorschläge, die auf Überprüfung warten |
| **Rate** | Annahmequote (akzeptiert ÷ gesamt, als Prozentsatz) |

### Nach Relationstyp

Eine Aufschlüsselungstabelle zeigt, wie viele Vorschläge jedes Relationstyps vorgeschlagen, akzeptiert, abgelehnt wurden und wie hoch die Annahmequote für diesen Typ ist.

### Am häufigsten abgelehnt

Die Tabelle listet die Vorschläge mit der höchsten Konfidenz auf, die abgelehnt wurden (falsch-positive Ergebnisse mit höchster Konfidenz). Bewegen Sie den Mauszeiger über eine Zeile, um die Ablehnungsbegründung zu sehen.

Klicken Sie auf **🔄 Refresh**, um das Dashboard jederzeit neu zu laden.

---

## 11b. Relations-Browser

Das Panel **🔗 Relations Browser** (rechte Spalte, einklappbar) ermöglicht es Ihnen, bestätigte Taxonomie-Relationen zu durchsuchen, zu erstellen und zu löschen.

### Relationen durchsuchen

1. Öffnen Sie das Panel **🔗 Relations Browser**.
2. Filtern Sie optional nach Relationstyp über das Dropdown-Menü.
3. Eine Tabelle listet alle passenden Relationen mit Quelle, Ziel, Typ und Herkunft auf.

### Eine Relation erstellen

1. Klicken Sie auf **➕ New Relation**, um das Modal „Relation erstellen" zu öffnen.
2. Geben Sie den **Source Node Code** und den **Target Node Code** ein.
3. Wählen Sie den **Relation Type**.
4. Fügen Sie optional eine **Description** hinzu.
5. Klicken Sie auf **Create**.

### Eine Relation löschen

Klicken Sie auf die Schaltfläche **✖** in einer Relationszeile und bestätigen Sie die Löschung.

### Anforderungs-Auswirkungsanalyse

Die Schaltfläche **🎯 Req. Impact** im Graph-Explorer-Panel führt eine transitive Auswirkungsanalyse basierend auf den aktuellen Analyse-Bewertungen durch. Sie zeigt, welche Taxonomie-Elemente indirekt über den Relationsgraphen betroffen sind.

1. Führen Sie zunächst eine Analyse durch (siehe [Abschnitt 4](#4-analyzing-a-business-requirement)).
2. Klicken Sie auf **🎯 Req. Impact** im Graph-Explorer.
3. Die Ergebnisse zeigen betroffene Elemente und die durchlaufenen Beziehungen.

---

## 11c. Anforderungsabdeckung

Das Panel **📋 Requirement Coverage** (rechte Spalte, einklappbar) verfolgt, welche Taxonomie-Knoten durch Ihre erfassten Anforderungen abgedeckt sind, und hebt Knoten hervor, die noch nicht durch eine Anforderung abgedeckt wurden (Lücken-Kandidaten).

### Öffnen des Panels

Klicken Sie auf die Zusammenfassung **📋 Requirement Coverage** in der rechten Spalte. Die Abdeckungsstatistiken werden automatisch aus der Datenbank geladen.

<img src="../images/26-coverage-dashboard-empty.png" alt="Abdeckungs-Dashboard — leerer Zustand" width="600">

### Zusammenfassende Metriken

| Metrik | Beschreibung |
|---|---|
| **Total nodes** | Gesamtzahl der Taxonomie-Knoten |
| **Covered** | Knoten, die durch mindestens eine Anforderung abgedeckt sind |
| **Uncovered** | Knoten ohne Anforderungsabdeckung (Lücken-Kandidaten) |
| **Coverage %** | Prozentsatz der Knoten, die eine Abdeckung haben |
| **Requirements** | Eindeutige Anforderungs-IDs, die erfasst wurden |
| **Avg req/node** | Durchschnittliche Anzahl von Anforderungen pro abgedecktem Knoten |

### Am häufigsten abgedeckte Knoten

Eine Tabelle, die die 10 Knoten zeigt, die durch die meisten Anforderungen abgedeckt werden. Klicken Sie auf einen Knoten-Code, um die Liste der Anforderungen anzuzeigen, die ihn abdecken, zusammen mit Bewertungen und Analyse-Zeitstempeln.

### Lücken-Kandidaten

Eine Tabelle, die bis zu 10 Knoten ohne Anforderungsabdeckung zeigt. Diese sind erstrangige Kandidaten für Architekturlücken — keine bestehende Anforderung adressiert diese Elemente.

<img src="../images/27-coverage-dashboard-data.png" alt="Abdeckungs-Dashboard — nach Aufzeichnung einer Analyse" width="600">

### Aufzeichnung einer Analyse

1. Führen Sie eine Anforderungsanalyse im Hauptpanel durch (geben Sie Ihren Geschäftstext ein und klicken Sie auf **Analyse**).
2. Öffnen Sie das Panel **📋 Requirement Coverage**.
3. Klicken Sie auf **📥 Record Current Analysis**.
4. Geben Sie bei Aufforderung eine kurze Anforderungskennung ein (z. B. `REQ-101`).
5. Die Analyse-Bewertungen werden an den Server gesendet; Knoten mit einer Bewertung ≥ 50 werden als durch diese Anforderung abgedeckt erfasst.

Klicken Sie auf **🔄 Refresh**, um die Abdeckungsstatistiken nach der Aufzeichnung neuer Analysen neu zu laden.

> **Tipp:** Verwenden Sie aussagekräftige Anforderungs-IDs (z. B. `REQ-001-COMMS`, `SPRINT-3-SEC`), um im Laufe der Zeit ein Anforderungsregister aufzubauen und die Architekturabdeckung pro Sprint oder Release zu verfolgen.

---

## 11d. Architektur-Lückenanalyse

Die **Architektur-Lückenanalyse** identifiziert fehlende architektonische Relationen, indem verglichen wird, was *existieren sollte* (gemäß der Kompatibilitätsmatrix) mit dem, was *tatsächlich* in der Wissensbasis vorhanden ist.

### Was sie bewirkt

Für jeden hoch bewerteten Knoten aus einer Anforderungsanalyse prüft die Lückenanalyse:

1. **Erwartete ausgehende Relationen** — welche Relationstypen sollte die Taxonomie-Wurzel dieses Knotens haben? (z. B. sollte eine Capability eine Core Service `REALIZES`)
2. **Tatsächliche Relationen** — welche dieser erwarteten Relationen existieren tatsächlich in der Wissensbasis?
3. **Fehlende Relationen** — die Differenz: erwartet minus tatsächlich.

### Verwendung der API

Senden Sie eine `POST`-Anfrage an `/api/gap/analyze` mit den Bewertungen aus einer Anforderungsanalyse:

```json
{
  "scores":       { "CP": 85, "BP": 72 },
  "businessText": "Secure voice communications",
  "minScore":     50
}
```

Die Antwort enthält:

| Feld | Beschreibung |
|---|---|
| **Missing relations** | Erwartete, aber fehlende Relationen (z. B. „CP hat keine REALIZES-Relation zu einem CR-Knoten") |
| **Incomplete patterns** | Relationsketten mit mindestens einem fehlenden Schritt |
| **Coverage gaps** | Knoten mit hohen Bewertungen, denen erwartete architektonische Nachbarn fehlen |

### Interpretation der Ergebnisse

- **Missing relations** sagen Ihnen, *welche Verbindungen in der Wissensbasis erstellt werden müssen*, um die Architektur zu vervollständigen.
- **Incomplete patterns** zeigen, *welche Relationskette unterbrochen ist* und wo.
- **Coverage gaps** heben Knoten hervor, die für die Anforderung wichtig, aber architektonisch isoliert sind.

> **Tipp:** Verwenden Sie nach einer Lückenanalyse die Funktion „Relationsvorschläge" (§9), um neue Relationen vorzuschlagen, die die identifizierten Lücken füllen.

Siehe die [API-Referenz](API_REFERENCE.md#13-architecture-gap-analysis) für die vollständige Anfrage-/Antwort-Dokumentation.

---

## 11e. Architektur-Empfehlung

Die Funktion **Architektur-Empfehlung** kombiniert Anforderungsbewertung, Lückenanalyse und semantische Suche in einer automatisierten Pipeline, die Architekturempfehlungen für eine Geschäftsanforderung erstellt.

### Was sie bewirkt

Die Empfehlungs-Pipeline führt vier Schritte aus:

1. **Elemente bestätigen** — Knoten mit hohen Bewertungen (≥ 70) werden als relevante Architekturelemente bestätigt.
2. **Lückenanalyse** — identifiziert fehlende architektonische Verbindungen (siehe §11d).
3. **Kandidatenvorschlag** — für jede Lücke werden Kandidaten-Knoten aus der fehlenden Taxonomie-Wurzel vorgeschlagen, geordnet nach semantischer Ähnlichkeit zur Geschäftsanforderung, wenn das Embedding-Modell verfügbar ist.
4. **Relationsvorschlag** — schlägt Relationen vor, die die identifizierten Lücken füllen würden.

### Verwendung der API

Senden Sie eine `POST`-Anfrage an `/api/recommend`:

```json
{
  "scores":       { "CP": 85, "BP": 72 },
  "businessText": "Secure voice communications",
  "minScore":     50
}
```

Die Antwort enthält:

| Feld | Beschreibung |
|---|---|
| **Confirmed elements** | Hoch-konfidente übereinstimmende Knoten (Bewertung ≥ 70) |
| **Proposed elements** | KI-vorgeschlagene Knoten zum Füllen von Lücken |
| **Suggested relations** | Relationen, die die Architektur vervollständigen würden |
| **Confidence** | Gesamtvertrauens-Prozentsatz |
| **Reasoning** | Schritt-für-Schritt-Protokoll der Empfehlungs-Pipeline |

### Interpretation der Ergebnisse

- **Confidence** spiegelt wider, wie vollständig die bestehende Architektur für die gegebene Anforderung ist: `bestätigt / (bestätigt + Lücken) × 100%`.
- **Proposed elements** sind Vorschläge — sie sollten von einem Architekten überprüft werden, bevor sie akzeptiert werden.
- **Suggested relations** können manuell über den Relations-Browser (§11b) oder durch Annahme von Vorschlägen (§9) erstellt werden.

> **Tipp:** Verwenden Sie die Empfehlungs-Pipeline nach einer ersten Analyse, um einen umfassenden Überblick darüber zu erhalten, was vorhanden ist, was fehlt und was dagegen unternommen werden sollte.

Siehe die [API-Referenz](API_REFERENCE.md#14-architecture-recommendation) für die vollständige Anfrage-/Antwort-Dokumentation.

---

## 11f. Erkennung von Architekturmustern

Die Funktion **Erkennung von Architekturmustern** prüft, ob Standard-Architekturmuster im Relationsgraphen vorhanden sind (vollständig oder teilweise vollständig).

### Vordefinierte Muster

Das System prüft die folgenden Architekturmuster:

| Muster | Kette | Beschreibung |
|---|---|---|
| **Full Stack** | CP → REALIZES → CR → SUPPORTS → BP → CONSUMES → IP | Eine Fähigkeit, die vollständig durch Services, Prozesse und Informationsprodukte realisiert wird |
| **App Chain** | UA → USES → CR → SUPPORTS → BP | Eine Benutzeranwendung, die Services nutzt, die Geschäftsprozesse unterstützen |
| **Role Chain** | BR → ASSIGNED_TO → BP → CONSUMES → IP | Eine Geschäftsrolle, die einem Prozess zugewiesen ist, der Informationsprodukte nutzt |

### Verwendung der API

**Für einen bestimmten Knoten:**

```
GET /api/patterns/detect?nodeCode=CP
```

**Für bewertete Knoten aus einer Analyse:**

```json
POST /api/patterns/detect
{
  "scores": { "CP": 85, "BP": 72 },
  "minScore": 50
}
```

### Interpretation der Ergebnisse

Die Antwort zeigt:

| Feld | Beschreibung |
|---|---|
| **Matched patterns** | Muster, die zu 100 % vollständig sind |
| **Incomplete patterns** | Muster, bei denen mindestens ein Schritt vorhanden ist, aber einige fehlen |
| **Pattern coverage** | Prozentsatz der erkannten Muster, die vollständig übereinstimmen |

Für jedes Muster können Sie sehen:
- **Expected steps** — alle Schritte, die das Muster erfordert
- **Present steps** — Schritte, die im Graphen vorhanden sind
- **Missing steps** — Schritte, die fehlen
- **Completeness** — Prozentsatz der vorhandenen Schritte (0–100 %)

> **Tipp:** Unvollständige Muster offenbaren spezifische architektonische Lücken. Verwenden Sie die fehlenden Schritte
> als Orientierung, welche Relationen als Nächstes erstellt werden sollten — entweder manuell oder über die Relationsvorschlags-Pipeline (§9).

Siehe die [API-Referenz](API_REFERENCE.md#15-architecture-pattern-detection) für die vollständige Anfrage-/Antwort-Dokumentation.

---

## 11g. Architektur-DSL

Die **Architektur-DSL** ist eine textbasierte domänenspezifische Sprache zur Beschreibung von Architekturmodellen als versionierbare, diff-freundliche Quelldateien. Sie dient als **Single Source of Truth** für Architekturdefinitionen — Änderungen werden in ein Git-gestütztes Repository committet, können in Pull Requests überprüft werden und werden in die Anwendungsdatenbank materialisiert.

### Warum DSL?

| Traditioneller Ansatz | DSL-Ansatz |
|---|---|
| Architektur nur in der Datenbank gespeichert | Architektur als lesbare Textdateien gespeichert |
| Änderungen sind unsichtbar, bis sie verglichen werden | Änderungen sind als Git-Diffs sichtbar |
| Kein Überprüfungsprozess für Architekturänderungen | Änderungen können vor dem Zusammenführen überprüft werden |
| Vergangene Zustände schwer reproduzierbar | Vollständige Versionshistorie über Git |
| Datenbankabhängig | Portables Textformat |

### DSL-Formatübersicht

DSL-Dokumente verwenden das `.taxdsl`-Format. Ein Dokument besteht aus einem optionalen `meta`-Block, gefolgt von geschweiften Klammer-Blöcken für Elemente, Relationen, Anforderungen, Zuordnungen, Ansichten und Nachweise. Blöcke verwenden `{` `}`-Begrenzungszeichen und Eigenschaften verwenden die `key: value;`-Syntax.

**Beispieldokument:**

```
meta {
  language: "taxdsl";
  version: "2.0";
  namespace: "mission.hospital-comms";
}

element CP-1023 type Capability {
  title: "Communication and Information System Capabilities";
  description: "Ability to provide communication and information systems";
  taxonomy: "Capabilities";

  x-owner: "CIS";
  x-criticality: "high";
}

element BP-1327 type Process {
  title: "Enable";
  description: "Enablement of operations";
  taxonomy: "Business Processes";
}

relation CP-1023 REALIZES BP-1327 {
  status: accepted;
  confidence: 0.83;
  provenance: "manual";
}

requirement REQ-001 {
  title: "Integrated communication platform for clinical staff";
  text: "Provide integrated communication and information services for hospital staff across all departments";
}

mapping REQ-001 -> CP-1023 {
  score: 0.92;
  source: "llm";
}

view hospital-comms-overview {
  title: "Hospital Communications Architecture Overview";
  include: CP-1023;
  include: BP-1327;
  layout: layered;
}
```

### Blocktypen

| Block | Header-Syntax | Beschreibung |
|---|---|---|
| `meta` | `meta {` | Dokument-Metadaten: Sprache, Version, Namensraum |
| `element` | `element <ID> type <TypeName> {` | Architekturelement; die ID muss ein gültiger Taxonomie-Code aus der Arbeitsmappe sein |
| `relation` | `relation <SourceID> <RelType> <TargetID> {` | Gerichtete Beziehung zwischen zwei Elementen |
| `requirement` | `requirement <ID> {` | Text einer Geschäftsanforderung |
| `mapping` | `mapping <ReqID> -> <ElementID> {` | Zuordnung einer Anforderung zu einem Element mit Bewertung |
| `view` | `view <ID> {` | Benannte Teilmenge von Elementen zur Diagrammerstellung |
| `evidence` | `evidence <ID> {` | Unterstützender Nachweis für eine Beziehung; Ziel wird über die Eigenschaft `for-relation` angegeben |

### Elementtypen

| Typname | Taxonomie-Wurzel | Beschreibung |
|---|---|---|
| `Capability` | CP | Eine abgegrenzte, ergebnisorientierte Fähigkeit |
| `Process` | BP | Geschäftsprozess |
| `CoreService` | CR | Kerndienst (SOA) |
| `COIService` | CI | Community-of-Interest-Dienst |
| `CommunicationsService` | CO | Kommunikationsinfrastruktur |
| `UserApplication` | UA | Benutzeranwendung |
| `InformationProduct` | IP | Strukturiertes Informationsprodukt |
| `BusinessRole` | BR | Organisatorische Rolle |

### Beziehungstypen

Siehe [§15 Referenz der Beziehungstypen](#15-relation-types-reference) für die vollständige Liste der 10 Beziehungstypen und deren Kompatibilitätsregeln.

### Erweiterungsattribute

Jede Eigenschaft, die mit `x-` beginnt, wird als **Erweiterungsattribut** behandelt. Erweiterungen bleiben bei Round-Trips erhalten und werden nicht validiert — sie bieten einen benutzerdefinierten Erweiterungsmechanismus:

```
element CP-1023 type Capability {
  title: "Communication and Information System Capabilities";

  x-owner: "CIS";
  x-criticality: "high";
  x-lifecycle: "target";
}
```

### Serialisierungsgarantien

Der DSL-Serializer erzeugt **deterministische, Git-Diff-freundliche** Ausgaben:

| Eigenschaft | Garantie |
|---|---|
| **Block-Reihenfolge** | Sortiert nach Art (Elemente → Beziehungen → Anforderungen → Zuordnungen → Ansichten → Nachweise), dann nach primärer ID innerhalb jeder Art |
| **Eigenschafts-Reihenfolge** | Kanonische Reihenfolge pro Blockart (z. B. title → description → taxonomy für Elemente) |
| **Erweiterungs-Reihenfolge** | Alphabetisch sortiert nach bekannten Eigenschaften, getrennt durch eine Leerzeile |
| **Escape-Sequenzen** | `\"` und `\\` in Zeichenketten für Sonderzeichen |
| **Round-Trip-Stabilität** | `parse → serialize → parse → serialize` erzeugt immer identische Ausgaben |

Diese Garantien bedeuten, dass **dieselbe Architektur immer zum selben Text serialisiert wird**, unabhängig von der Reihenfolge, in der Elemente hinzugefügt wurden. Git-Diffs zeigen nur tatsächliche semantische Änderungen.

### DSL-Editor-Panel

Der DSL-Editor-Tab in der Anwendung bietet:

1. **Load Current** — Exportiert den aktuellen Architekturzustand als DSL-Text
2. **Edit** — Bearbeiten Sie den DSL-Text direkt im Editor
3. **Validate** — Prüft die DSL auf Fehler und Warnungen
4. **Commit** — Speichert Änderungen im Git-gestützten Repository mit einer Commit-Nachricht
5. **Branch management** — Erstellen von Branches, Cherry-Pick und Merge

![DSL-Editor-Panel](../images/34-dsl-editor-panel.png)

Nach dem Akzeptieren von Vorschlägen enthält die exportierte DSL `relation`-Blöcke neben `element`-Blöcken und zeigt das vollständige Architekturdatenmodell:

![DSL-Editor mit Beziehungen](../images/40-dsl-editor-with-relations.png)

### Versionskontrolle

Die DSL wird in einem **Git-Repository** gespeichert, das vollständig innerhalb der Anwendung verwaltet wird — es ist kein externer Git-Server oder Dateisystem erforderlich. Das bedeutet, dass jede Änderung automatisch nachverfolgt wird.

Sie interagieren mit der Versionskontrolle über die grafische Benutzeroberfläche — es ist nicht notwendig, Git-Befehle zu verwenden. Die wichtigsten Konzepte:

- **Branches** — Verwenden Sie Branches, um mit Architekturänderungen zu experimentieren, ohne die Hauptversion zu beeinflussen. Sie können Branches wechseln, neue erstellen und zusammenführen.
- **Commits** — Jedes Mal, wenn Sie eine Version speichern oder im DSL-Editor committen, wird ein Snapshot aufgezeichnet. Sie können Commits jederzeit durchsuchen, vergleichen, wiederherstellen oder rückgängig machen.
- **Rückgängig / Wiederherstellen** — Haben Sie einen Fehler gemacht? Verwenden Sie die Schaltfläche **Undo**, um die letzte Änderung zu entfernen, oder **Restore**, um zu einer früheren Version zurückzukehren.

Für Details zur schrittweisen Verwendung dieser Funktionen siehe [§12 Versionen-Tab](#12-versions-tab) unten.

> 📖 Für eine umfassende Anleitung zu Branching, Merge-Vorschauen, Konflikterkennung, Aktualitätsverfolgung und der vollständigen Git-REST-API siehe **[Git Integration](GIT_INTEGRATION.md)**.

### Materialisierung

Wenn Sie die DSL bearbeiten und Änderungen committen, muss das Architekturmodell in Ihrem DSL-Text **materialisiert** (angewendet) werden, damit andere Teile der Anwendung — der Graph Explorer, der Relations Browser und die Architecture View — diese Änderungen widerspiegeln.

- **Vollständige Materialisierung** ersetzt alle Beziehungen in der Datenbank durch den Inhalt der DSL.
- **Inkrementelle Materialisierung** wendet nur die Unterschiede (Delta) zwischen dem aktuellen Datenbankzustand und der DSL an, was bei großen Modellen schneller ist.

Beide Optionen sind im DSL-Editor-Panel verfügbar. Nach der Materialisierung wird die Git-Statusleiste oben auf der Seite aktualisiert und zeigt an, dass die Projektion **aktuell** (synchron) ist.

### Hypothesen

Wenn die KI eine Geschäftsanforderung analysiert, generiert sie **Hypothesen** — vorläufige Beziehungen, die Ihre Überprüfung benötigen, bevor sie dauerhaft werden:

1. Die KI schlägt eine Beziehung vor (z. B. „CP-1023 REALIZES BP-1327").
2. Sie überprüfen diese im **Relations**-Tab mit den Schaltflächen Accept / Reject.
3. **Akzeptierte** Hypothesen werden zu echten Architekturbeziehungen und erscheinen im Graph Explorer und der DSL.
4. **Abgelehnte** Hypothesen werden als abgelehnt markiert und von zukünftigen Exporten ausgeschlossen.

### Commit-Verlauf durchsuchen

Sie können den vollständigen Commit-Verlauf direkt im Versionen-Tab durchsuchen. Die Suche umfasst:

- **Commit-Nachrichten** — Finden Sie Commits anhand der Beschreibung (z. B. „Review-Runde 2").
- **Geänderte Elemente** — Finden Sie alle Commits, die ein bestimmtes Element oder eine Beziehung betroffen haben.

Siehe [§12 Versionen-Tab](#12-versions-tab) für die Verwendung der Suche in der GUI.

---

## 12. Versionen-Tab

Der **🕓 Versions**-Tab bietet eine visuelle Oberfläche zum Durchsuchen, Verwalten und Zurücksetzen von Architekturversionen. Klicken Sie auf **🕓 Versions** in der oberen Navigationsleiste, um ihn zu öffnen.

![Versionen-Tab — Verlaufszeitachse](../images/41-versions-tab-history.png)

### Branch-Auswahl

Oben rechts im Versionen-Tab sehen Sie ein **Branch**-Dropdown. Dieses zeigt alle verfügbaren Branches im Architektur-Repository an.

- **Wählen Sie einen Branch**, um dessen Commit-Verlauf anzuzeigen.
- Der Standard-Branch ist `draft` — hier werden neue Architekturänderungen gespeichert.
- Wenn Sie Varianten-Branches erstellt haben (z. B. zum Experimentieren), können Sie hier zwischen ihnen wechseln.

### Verlaufszeitachse

Der **🕓 History**-Unter-Tab zeigt eine Zeitachse aller Commits auf dem ausgewählten Branch. Jeder Eintrag zeigt:

- **Commit-Nachricht** — Was geändert wurde (z. B. „Baseline nach Review-Runde 2").
- **Zeitstempel und Autor** — Wann und von wem die Änderung vorgenommen wurde.
- **Kurze Commit-ID** — Eine 7-stellige Kennung für den Commit (z. B. `a3f8c2d`).

Jeder Zeitachseneintrag hat vier Aktionsschaltflächen:

| Schaltfläche | Aktion |
|---|---|
| **👁 View** | Öffnet ein Modal, das den vollständigen DSL-Text dieser Version anzeigt |
| **🔍 Compare** | Zeigt einen Diff zwischen dieser Version und dem aktuellen HEAD |
| **↩ Restore** | Ersetzt den aktuellen Zustand durch diese Version (erstellt einen neuen Commit) |
| **❌ Revert** | Erstellt einen neuen Commit, der die durch diesen bestimmten Commit eingeführten Änderungen rückgängig macht |

### Letzte Änderung rückgängig machen

Oben im Versionen-Tab entfernt die Schaltfläche **↩ Undo last change** den letzten Commit aus dem Branch-Verlauf. Dies ist nützlich, wenn Sie einen Fehler gemacht haben und schnell einen Schritt zurückgehen möchten.

- Der Text neben der Schaltfläche zeigt die Nachricht des letzten Commits, damit Sie wissen, was Sie rückgängig machen.
- Vor der Ausführung erscheint ein Bestätigungsdialog.

### Eine benannte Version speichern

Klicken Sie auf den Unter-Tab **💾 Save Version**, um einen benannten Snapshot des aktuellen Architekturzustands zu erstellen.

![Versionen-Tab — Version speichern](../images/42-versions-tab-save.png)

1. Geben Sie einen **Titel** ein (erforderlich) — zum Beispiel „Baseline nach Review-Runde 2".
2. Fügen Sie optional eine **Beschreibung** mit weiteren Details zu den Änderungen hinzu.
3. Klicken Sie auf **💾 Save Version**.
4. Eine Erfolgsmeldung mit der Commit-ID bestätigt, dass die Version gespeichert wurde.

Dies entspricht einem Git-Commit mit einer beschreibenden Nachricht. Sie können diese Version später in der Verlaufszeitachse finden und bei Bedarf wiederherstellen.

### Zeitachse aktualisieren

Klicken Sie auf die Schaltfläche **🔄 Refresh** in der Kopfzeile der Verlaufskarte, um die Zeitachse neu zu laden. Dies ist nützlich, wenn ein anderer Benutzer oder Prozess Änderungen vorgenommen hat.

### Varianten-Browser

Klicken Sie auf den Unter-Tab **🔀 Variants**, um alle Architektur-Varianten-Branches anzuzeigen. Jede Variante zeigt:

![Varianten-Browser-Tab](../images/47-variants-browser-tab.png)

- **Branch-Name** — Der Name der Variante (z. B. `feature-voice-services`)
- **Letzter Commit** — Die neueste Änderung auf dieser Variante
- **Commit-Anzahl** — Wie viele Commits auf der Variante existieren

Verfügbare Aktionen für jede Variante:

| Schaltfläche | Aktion |
|---|---|
| **Switch** | Wechselt zu dieser Variante zur Bearbeitung (öffnet einen neuen Kontext) |
| **Compare** | Vergleicht die Variante mit einem anderen Branch (semantischer Diff) |
| **Merge** | Führt Änderungen dieser Variante in den aktuellen Branch zusammen |
| **🗑 Delete** | Löscht den Varianten-Branch (geschützte Branches `draft`, `accepted`, `main` können nicht gelöscht werden) |

Um eine neue Variante zu erstellen, klicken Sie auf **+ New Variant** in der Kopfzeile der Karte. Dies öffnet ein Modal, in dem Sie den Variantennamen eingeben. Die neue Variante wird vom aktuellen Branch abgezweigt.

![Modal zur Variantenerstellung](../images/46-variant-creation-modal.png)

### Varianten löschen

Nicht-geschützte Varianten-Branches können mit der Schaltfläche **🗑 Delete** gelöscht werden. Vor dem Löschen erscheint ein Bestätigungsdialog. Geschützte Branches (`draft`, `accepted`, `main`) können nicht gelöscht werden — die Löschen-Schaltfläche erscheint bei diesen nicht.

### Zurückkopieren (Nur-Lese-Kontexte)

Wenn Sie eine Variante im **READ-ONLY**-Modus anzeigen, erscheint eine Schaltfläche **📤 Copy Back** in der Kontextleiste. Dies ermöglicht es Ihnen, Elemente und Beziehungen selektiv aus der schreibgeschützten Variante in Ihren bearbeitbaren Arbeitsbereich zu übertragen — nützlich zum gezielten Übernehmen von Ideen aus experimentellen Branches.

![Schaltfläche Zurückkopieren](../images/49-copy-back-button.png)

### Merge-Vorschau

Bevor eine Merge-Operation ausgeführt wird, wird ein **Merge-Vorschau-Modal** angezeigt. Dieses Modal zeigt:
- Die **Quell**- und **Ziel**-Branches
- Ob der Merge ein **Fast-Forward** wäre (keine Konflikte möglich)
- Ob **Konflikte** erwartet werden
- Eine **Proceed**-Schaltfläche (wenn der Merge sicher ist) oder eine Nachricht, die den Konflikt erklärt

Dies ersetzt die bisherigen Browser-`confirm()`-Dialoge durch ein informativeres Bootstrap-Modal.

### Cherry-Pick-Vorschau

Ebenso wird vor dem Cherry-Picking eines Commits ein **Cherry-Pick-Vorschau-Modal** angezeigt:
- Der **Commit**, der cherry-gepickt wird, und der **Ziel-Branch**
- Ob die Operation sauber durchgeführt werden kann
- Eine **Proceed**-Schaltfläche oder Konfliktwarnung

### Merge-Konflikte lösen

Wenn ein Merge oder Cherry-Pick nicht automatisch abgeschlossen werden kann (beide Seiten haben denselben DSL-Inhalt geändert), öffnet sich das **Modal zur Merge-Konfliktlösung**. Es bietet:

- **Nebeneinander-Ansicht**: „Ours" (Inhalt des Ziel-Branches) und „Theirs" (Inhalt des Quell-Branches/Commits)
- **Schnellaktionen**: Schaltflächen **Use Ours** und **Use Theirs**, um eine Seite vollständig zu übernehmen
- **Manuelle Bearbeitung**: Ein Textfeld, in dem Sie den endgültigen aufgelösten Inhalt erstellen
- **Resolve & Commit**: Committet den aufgelösten Inhalt in den Ziel-Branch

Nach der Auflösung bestätigt eine Erfolgs-Toast-Benachrichtigung die Operation.

### Synchronisation mit dem gemeinsamen Repository

Klicken Sie auf den Unter-Tab **🔄 Sync**, um die Synchronisation zwischen Ihrem Arbeitsbereich und dem gemeinsamen Team-Repository zu verwalten:

- **Sync from Shared** — Zieht die neuesten Änderungen vom gemeinsamen `draft`-Branch in Ihren Arbeitsbereich-Branch. Dies entspricht einem `git merge` vom gemeinsamen Branch.
- **Publish to Shared** — Überträgt Ihre Arbeitsbereichsänderungen auf den gemeinsamen Branch. Dies entspricht einem `git merge` in den gemeinsamen Branch.

Das Synchronisationsstatus-Panel zeigt:
- **Sync-Status** — `UP_TO_DATE` (synchron), `BEHIND` (gemeinsames Repository hat neuere Änderungen), `AHEAD` (Sie haben unveröffentlichte Änderungen) oder `DIVERGED` (beide haben Änderungen)
- **Anzahl unveröffentlichter Commits** — Anzahl Ihrer Commits, die noch nicht im gemeinsamen Repository veröffentlicht wurden
- **Letzte Sync-/Publish-Zeitstempel** — Wann Sie zuletzt synchronisiert oder veröffentlicht haben

### Divergierten Zustand auflösen

Wenn der Synchronisationsstatus **DIVERGED** anzeigt, erscheint eine Schaltfläche **Resolve…** neben dem Status-Badge. Ein Klick darauf öffnet das **Modal zur Auflösung divergierter Synchronisation** mit drei Strategien:

| Strategie | Beschreibung |
|---|---|
| **🔀 Merge** | Versucht, gemeinsame Änderungen in Ihren Branch zusammenzuführen. Kann fehlschlagen, wenn Konflikte bestehen. |
| **📤 Keep Mine** | Veröffentlicht Ihre Version im gemeinsamen Repository und überschreibt die dortigen Änderungen. |
| **📥 Take Shared** | Ersetzt Ihren Branch durch die gemeinsame Version und verwirft Ihre Änderungen. |

### Benachrichtigungen über Operationsergebnisse

Alle Git-Operationen (Merge, Cherry-Pick, Publish, Sync, Branch löschen) erzeugen eine **Toast-Benachrichtigung** in der unteren rechten Ecke des Bildschirms:
- **✅ Grün** — Operation erfolgreich, mit einer Zusammenfassung
- **❌ Rot** — Operation fehlgeschlagen, mit Fehlerdetails
- **⚠️ Gelb** — Warnung (z. B. Konflikt erkannt)

Die Toast-Benachrichtigung verschwindet automatisch nach 5 Sekunden.

### Arbeitsbereich-Benutzer-Badge

In der Navigationsleiste (oben rechts) zeigt das **Arbeitsbereich-Badge** Ihren Benutzernamen und den aktuellen Branch an. Die Farbe des Badges ändert sich je nach Zustand:

![Arbeitsbereich-Benutzer-Badge](../images/45-workspace-user-badge.png)

- **Blau** — Normal, Arbeitsbereich ist sauber
- **Gelb** — Arbeitsbereich hat ungespeicherte/unveröffentlichte Änderungen (unsauberer Zustand)

---

## 13. Git-Status und Kontextleiste

Zwei horizontale Leisten oben auf der Seite bieten einen schnellen Überblick über den aktuellen Architekturzustand.

### Git-Statusleiste

Die **Git-Statusleiste** erscheint direkt unter der Navigationsleiste. Sie zeigt:

![Git-Statusleiste](../images/43-git-status-bar.png)

| Indikator | Bedeutung |
|---|---|
| **🔀 Branch-Name** | Der aktuell aktive Branch (z. B. `draft`) |
| **Commit-SHA** | Die Kurz-ID des letzten Commits (z. B. `a3f8c2d`) |
| **Projection: fresh / STALE** | Ob die Datenbankbeziehungen mit dem letzten Git-Commit übereinstimmen. Ein grüner Punkt bedeutet **aktuell** (synchron); ein roter Punkt bedeutet **VERALTET** (Sie müssen materialisieren). |
| **Index: fresh / STALE** | Ob der Suchindex mit dem letzten Git-Commit übereinstimmt |
| **N variants** | Wie viele Branches existieren |
| **N versions** | Gesamtanzahl der Commits auf dem aktuellen Branch |
| **Sync: Status** | Synchronisationszustand mit dem gemeinsamen Repository (synchron / hinterher / voraus / divergiert) |

Wenn die Projektion **STALE** anzeigt, bedeutet dies, dass die DSL geändert, aber noch nicht in die Datenbank materialisiert wurde. Gehen Sie zum DSL-Editor und klicken Sie auf **Materialize**, um die Datenbank zu aktualisieren.

### Kontext-Navigationsleiste

Die **Kontextleiste** erscheint unterhalb der Git-Statusleiste, wenn Sie zwischen verschiedenen Architekturkontexten navigieren (z. B. beim Anzeigen einer historischen Version, Erkunden eines Varianten-Branches oder Vergleichen von Branches).

![Kontext-Navigationsleiste](../images/44-context-bar.png)

Die Kontextleiste zeigt:

- **Modus-Badge** — `EDITABLE` (grün), `READ-ONLY` (gelb) oder `TEMPORARY` (grau)
- **Branch-Name** und **Commit-ID** — Welche Version Sie gerade anzeigen
- **Herkunftsindikator** — Wenn Sie von einem anderen Kontext navigiert sind, wird angezeigt, woher Sie kamen

Navigationsschaltflächen in der Kontextleiste:

| Schaltfläche | Aktion |
|---|---|
| **← Back** | Zurück zum vorherigen Kontext (wie Browser-Zurück, aber für Architekturversionen) |
| **↺ Origin** | Direkt zurück zum Ausgangspunkt der Navigation springen |
| **📤 Copy Back** | Elemente aus einem schreibgeschützten Kontext zurück in Ihren bearbeitbaren Arbeitsbereich kopieren (nur im READ-ONLY-Modus sichtbar) |
| **+ Variant** | Einen neuen Branch vom aktuellen Kontext erstellen |
| **↔ Compare** | Einen Vergleichsdialog öffnen, um zwei Branches oder Commits zu vergleichen |

---

## 14. Administration

Administrationsfunktionen sind hinter einem passwortgeschützten Admin-Modus verborgen. Ein Standardbenutzer muss nicht auf diese Funktionen zugreifen.

> 📖 Für eine umfassende Anleitung zu allen KI-Anbietern, anbieterspezifischer Überschreibung pro Anfrage, Mock-Modus, Diagnose-API und Ratenbegrenzung siehe **[AI Providers](AI_PROVIDERS.md)**.
> Für die Verwaltung von Laufzeiteinstellungen (LLM-Einstellungen, DSL-Konfiguration, Größenbeschränkungen) siehe **[Preferences](PREFERENCES.md)**.

### KI-Statusanzeige (🟢 / 🔴 in der Navigationsleiste)

Das Badge in der Navigationsleiste zeigt an, ob ein LLM-Anbieter verbunden ist:

| Badge | Zustand | Bedeutung |
|---|---|---|
| 🟢 **AI: [Anbietername]** | Verfügbar (grün) | KI-Analyse- und Begründungsfunktionen sind aktiv. Das Badge zeigt den aktiven Anbieter an (z. B. „Google Gemini"). |
| 🔴 **AI: Unavailable** | Nicht verfügbar (rot) | Kein LLM-API-Schlüssel ist konfiguriert. Die Schaltfläche **Analyze with AI** ist deaktiviert. Eine Inline-Warnung unterhalb der Schaltfläche erklärt, welche Umgebungsvariablen gesetzt werden müssen. |
| ⚠️ **AI: Unknown** | Fehler (gelb) | Die Statusprüfung ist fehlgeschlagen (Netzwerkfehler oder Server startet gerade). Das Badge aktualisiert sich automatisch alle 30 Sekunden. |

Wenn Sie ein rotes Badge sehen, können Sie entweder:
- Einen der LLM-API-Schlüssel (`GEMINI_API_KEY`, `OPENAI_API_KEY`, etc.) setzen und die Anwendung neu starten, oder
- `LLM_PROVIDER=LOCAL_ONNX` für Offline-Analyse ohne API-Schlüssel setzen.

Wenn die KI nicht verfügbar ist, erscheint eine **Inline-Warnmeldung** unterhalb der Analyse-Schaltfläche, die die erforderlichen Umgebungsvariablen auflistet.

### Admin-Modus freischalten (🔒-Schaltfläche → Passwort-Modal)

1. Klicken Sie auf die **🔒**-Schaltfläche in der Navigationsleiste.
2. Das **Admin-Modus-Modal** öffnet sich mit einem Passwort-Eingabefeld.
3. Geben Sie das Administratorpasswort ein.
4. Klicken Sie auf **Unlock**.
5. Das Schlosssymbol ändert sich, um anzuzeigen, dass der Admin-Modus aktiv ist, und die Admin-Panels werden im rechten Panel sichtbar.

Um den Admin-Modus wieder zu sperren, klicken Sie auf die Schloss-Schaltfläche und wählen Sie **Lock**.

### LLM-Kommunikationsprotokoll

Sobald der Admin-Modus freigeschaltet ist, ist das Panel **LLM-Kommunikationsprotokoll** im rechten Panel sichtbar. Es zeichnet den vollständigen Prompt, der an das LLM gesendet wurde, und die empfangene Rohantwort für jede Analyseoperation auf. Klappen Sie das Panel auf, um die Protokolleinträge anzuzeigen. Dies ist nützlich zur Fehlersuche bei unerwarteten Bewertungsergebnissen.

### LLM-Diagnose-Panel

Das **LLM-Diagnose-Panel** (nur Admin, einklappbar) zeigt Statistiken zur LLM-Nutzung:

- Anbietername und Modellversion
- Gesamtanzahl der API-Aufrufe
- Fehleranzahl und Fehlerrate
- Durchschnittliche Antwortlatenz

Klicken Sie auf **Refresh**, um die Statistiken zu aktualisieren. Klicken Sie auf **Test Connection**, um eine Testanfrage an den LLM-Anbieter zu senden und zu bestätigen, dass er korrekt antwortet.

![LLM-Diagnose-Panel](../images/24-llm-diagnostics.png)

### Prompt-Vorlagen-Editor

Der **Prompt-Vorlagen-Editor** (nur Admin, einklappbar) ermöglicht es Ihnen, die an das LLM gesendeten Anweisungen anzupassen, ohne die Anwendung neu bereitstellen zu müssen.

1. Verwenden Sie das **Taxonomie-Auswahl**-Dropdown, um die Prompt-Vorlage auszuwählen, die Sie bearbeiten möchten.
2. Der aktuelle Vorlagentext erscheint im **Vorlagen-Textfeld**.
3. Bearbeiten Sie den Text nach Bedarf.
4. Klicken Sie auf **Save**, um Ihre Änderungen zu speichern, oder **Reset**, um die integrierte Standardeinstellung wiederherzustellen.

![Prompt-Vorlagen-Editor](../images/25-prompt-template-editor.png)

---

## 15. Referenz der Beziehungstypen

Das System verwendet 10 Beziehungstypen, die jeweils einer spezifischen Beziehung im NATO Architecture Framework (NAF) oder The Open Group Architecture Framework (TOGAF) entsprechen.

| Beziehungstyp | Bedeutung in einfacher Sprache | Standard |
|---|---|---|
| **REALIZES** | Eine Fähigkeit wird durch einen Dienst realisiert | NAF NCV-2, TOGAF SBB |
| **SUPPORTS** | Ein Dienst unterstützt einen Geschäftsprozess | TOGAF Business Architecture |
| **CONSUMES** | Ein Geschäftsprozess konsumiert ein Informationsprodukt | TOGAF Data Architecture |
| **USES** | Eine Benutzeranwendung nutzt einen Kerndienst | NAF NSV-1 |
| **FULFILLS** | Ein COI-Dienst erfüllt eine Fähigkeit | NAF NCV-5 |
| **ASSIGNED_TO** | Eine Geschäftsrolle ist einem Geschäftsprozess zugeordnet | TOGAF Org mapping |
| **DEPENDS_ON** | Ein Dienst hängt von einem anderen Dienst ab, um zu funktionieren | Technische Abhängigkeit |
| **PRODUCES** | Ein Geschäftsprozess erzeugt ein Informationsprodukt | Datenfluss |
| **COMMUNICATES_WITH** | Ein Kommunikationsdienst kommuniziert mit einem Kerndienst | NAF NSOV |
| **RELATED_TO** | Eine allgemeine Beziehung, wenn kein spezifischer Typ zutrifft | Generischer Fallback |

---

## 16. Tipps und Best Practices

### Effektive Anforderungen formulieren

- **Seien Sie spezifisch:** Statt *„Kommunikation"* schreiben Sie *„integrierte Kommunikationsdienste für Krankenhauspersonal, die den Echtzeit-Datenaustausch zwischen Abteilungen ermöglichen"*.
- **Verwenden Sie Fachvokabular:** Begriffe wie *Capability*, *Service*, *Information Product*, *Command*, *Control* helfen der KI, bessere Treffer zu finden.
- **Eine Anforderung auf einmal:** Analysieren Sie pro Sitzung eine Anforderung für sauberere, fokussiertere Ergebnisse.
- **Halten Sie es kurz:** Streben Sie 1–3 Sätze an. Sehr lange Absätze verbessern die Genauigkeit nicht.

### Ergebnisse interpretieren

- Konzentrieren Sie sich auf Knoten mit Bewertungen über 50 % als Ihre primären Treffer.
- Knoten mit Bewertungen von 25–50 % sind sekundäre Treffer — sie können relevant sein, aber weniger direkt.
- Knoten unter 25 % können in der Regel ignoriert werden, es sei denn, Ihr Fachwissen deutet auf etwas anderes hin.
- Verwenden Sie **Leaf Justification** (📋), um zu verstehen, *warum* ein bestimmter Knoten hoch bewertet wurde, bevor Sie ihn in Ihre Architektur aufnehmen.

### Mit Vorschlägen arbeiten

- Führen Sie Vorschläge bald nach der Analyse durch, solange der Kontext noch frisch ist.
- Überprüfen Sie die KI-Begründung vor dem Akzeptieren; hohe Konfidenz bedeutet nicht immer korrekt.
- Lehnen Sie Vorschläge ab, bei denen die Begründung architektonisch keinen Sinn ergibt, selbst wenn der Konfidenzwert hoch ist.
- Akzeptierte Vorschläge werden zu bestätigten Beziehungen in der Wissensbasis und beeinflussen zukünftige Graph-Explorer-Ergebnisse.

### Exportieren

- Verwenden Sie **CSV**, um Bewertungen mit Kollegen zu teilen, die keinen Zugang zur Anwendung haben.
- Verwenden Sie **JSON**, um einen vollständigen Snapshot des Analyseergebnisses (Bewertungen + Begründungen + Anforderungstext) zu speichern, der später mit **📤 Load Scores** wieder geladen werden kann.
- Verwenden Sie **Visio**- oder **ArchiMate**-Export, um Ergebnisse in Ihre Enterprise-Architecture-Werkzeuge zu integrieren.
- Aktivieren Sie immer das Kontrollkästchen **Architecture View** vor der Analyse, wenn Sie Visio- oder ArchiMate-Dateien exportieren möchten.

---

## 17. Glossar

| Begriff | Definition |
|---|---|
| **Anchor node** | Ein hoch bewerteter Blattknoten, der eine Geschäftsanforderung direkt erfüllt; der Ausgangspunkt für die Architecture View |
| **Architecture DSL** | Eine textbasierte domänenspezifische Sprache (`.taxdsl`-Format) zur Beschreibung von Architekturmodellen als versionierbare, Diff-freundliche Quelldateien |
| **Architecture gap** | Eine erwartete Beziehung (gemäß der Kompatibilitätsmatrix), die in der Wissensbasis fehlt |
| **Architecture pattern** | Eine vordefinierte Kette von Beziehungstypen durch die Taxonomie (z. B. Full Stack, App Chain, Role Chain) |
| **Architecture recommendation** | Ein automatisierter Vorschlag, der bestätigte Elemente, Lückenanalyse und Kandidatenvorschläge für eine Geschäftsanforderung kombiniert |
| **Architecture View** | Ein gefilterter Teilgraph der Taxonomie, der nur die für eine bestimmte Anforderung relevanten Elemente und Beziehungen zeigt |
| **ArchiMate** | Eine offene Standard-Modellierungssprache für Enterprise Architecture, gepflegt von The Open Group |
| **C3** | Command, Control and Communications — der von dieser Taxonomie abgedeckte NATO-Funktionsbereich |
| **Capability** | Eine abgegrenzte, ergebnisorientierte Fähigkeit einer Organisation oder eines Systems (NAF, TOGAF) |
| **COI** | Community of Interest — eine Gruppe, die Informationen unter einem gemeinsamen Governance-Rahmenwerk teilt |
| **Compatibility matrix** | Ein Regelwerk, das definiert, welche Beziehungstypen zwischen Taxonomie-Wurzelpaaren gültig sind |
| **Confidence score** | Ein Wert von 0–100 %, der angibt, wie stark die KI davon überzeugt ist, dass eine vorgeschlagene Beziehung korrekt ist |
| **Coverage gap** | Ein Knoten, der Anforderungsabdeckung hat, aber erwartete architektonische Nachbarn vermissen lässt |
| **Enriched failure impact** | Ausfallwirkungsanalyse, die Anforderungsabdeckungsdaten und Risikobewertung einschließt |
| **Graph Explorer** | Das Werkzeug im rechten Panel zur Ausführung von Upstream-, Downstream- und Ausfallwirkungsabfragen auf dem Beziehungsgraphen |
| **Hybrid search** | Eine Abrufstrategie, die Volltextsuche und semantische Suche kombiniert (über API verfügbar) |
| **Hypothesis** | Eine vorläufige Beziehung, die während der LLM-Analyse generiert wird und auf menschliche Überprüfung wartet, bevor sie dauerhaft wird |
| **Information Product** | Ein spezifisches, strukturiertes Ergebnis eines Geschäftsprozesses (TOGAF Data Architecture) |
| **Interactive Mode** | Ein Analysemodus, der jeweils eine Baumebene bewertet, anstatt den gesamten Baum auf einmal |
| **Leaf node** | Ein Taxonomieknoten ohne Kinder; die spezifischste Ebene der Taxonomie |
| **LLM** | Large Language Model — die KI-Komponente, die für Bewertung, Begründung und Vorschlagserstellung verwendet wird |
| **Materialization** | Der Prozess der Umwandlung von DSL-Text in Datenbankentitäten (TaxonomyRelation usw.) |
| **Match Legend** | Die Farbskala im rechten Panel, die zeigt, welchem Bewertungswert jede Grünschattierung entspricht |
| **NAF** | NATO Architecture Framework — der Standard zur Beschreibung von NATO-Architekturen |
| **Pattern detection** | Prüfung, ob vordefinierte Architekturmuster im Beziehungsgraphen vollständig oder teilweise vorhanden sind |
| **Projection** | Ein benutzerspezifischer materialisierter Snapshot des DSL-Modells; wird „veraltet", wenn HEAD über den Snapshot-Commit hinausgeht |
| **Proposal** | Eine KI-generierte Kandidatenbeziehung, die im Panel „Relation Proposals" auf menschliche Überprüfung wartet |
| **Publish** | Ihren Arbeitsbereich-Branch in den gemeinsamen Integrations-Branch zusammenführen, damit Ihre Änderungen dem Team zur Verfügung stehen |
| **Relation** | Eine bestätigte, gerichtete Verbindung zwischen zwei Taxonomieknoten, die in der Wissensbasis gespeichert ist |
| **Risk score** | Eine aggregierte Metrik, die Anforderungsanzahl und Relevanz für die Ausfallwirkungsanalyse kombiniert |
| **Shared branch** | Der kanonische teamweite Branch (standardmäßig `draft` genannt), mit dem sich alle Benutzer synchronisieren |
| **Stale results** | Analysebewertungen, die nicht mehr dem aktuellen Anforderungstext entsprechen (mit gelber Warnung angezeigt) |
| **Sync** | Die neuesten Änderungen vom gemeinsamen Branch in Ihren Arbeitsbereich-Branch ziehen; das Gegenteil von „Publish" |
| **Taxonomy node** | Ein einzelnes Element im C3 Taxonomy Catalogue (Fähigkeit, Dienst, Rolle, Informationsprodukt usw.) |
| **TOGAF** | The Open Group Architecture Framework — eine weit verbreitete Enterprise-Architecture-Methodik |
| **Variant** | Ein benannter Branch im versionskontrollierten DSL-Repository, der zur Erkundung alternativer Architekturdesigns verwendet wird, ohne den gemeinsamen Branch zu beeinflussen |
| **Workspace** | Eine isolierte Bearbeitungsumgebung für jeden Benutzer, die unabhängige Kontextnavigation, Projektionsverfolgung und Branch-Isolation bietet |

---

## 18. Fehlerbehebung

### Die Schaltfläche „Analyze with AI" ist deaktiviert oder ausgegraut

**Ursache:** Kein LLM-Anbieter ist konfiguriert oder verfügbar. Das KI-Status-Badge in der Navigationsleiste wird 🔴 rot sein.

**Maßnahme:** Kontaktieren Sie Ihren Administrator, um die LLM-Anbieterkonfiguration zu überprüfen (`GEMINI_API_KEY`, `OPENAI_API_KEY` oder `LLM_PROVIDER=LOCAL_ONNX`).

### Die Analyse wird ausgeführt, aber alle Bewertungen sind 0 %

**Ursache:** Der Anforderungstext stimmt möglicherweise mit keinen Taxonomieknoten überein, oder es gibt ein Problem mit der LLM-Antwort.

**Maßnahme:**
1. Überprüfen Sie das **Analysis Log** (rechtes Panel, einklappbar) auf Fehlermeldungen.
2. Versuchen Sie, die Anforderung mit spezifischerer C3-Fachterminologie umzuformulieren.
3. Wenn der Admin-Modus verfügbar ist, prüfen Sie das **LLM-Kommunikationsprotokoll**, um zu sehen, was das LLM zurückgegeben hat.

### Export-Schaltflächen sind nicht sichtbar

**Ursache:** Export-Schaltflächen erscheinen nur nach einer abgeschlossenen Analyse mit Bewertungen größer null.

**Maßnahme:** Führen Sie zuerst eine Analyse durch, oder verwenden Sie **📤 Load Scores**, um eine zuvor gespeicherte JSON-Analysedatei zu importieren. Wenn Bewertungen vorhanden sind, aber die Schaltflächen weiterhin fehlen, versuchen Sie, die Seite zu aktualisieren und die Analyse erneut auszuführen.

### Die Visio- oder ArchiMate-Exportdatei ist leer oder enthält keine Elemente

**Ursache:** Das Kontrollkästchen **Architecture View** war vor der Analyse nicht aktiviert.

**Maßnahme:** Führen Sie die Analyse erneut mit aktiviertem Kontrollkästchen **Architecture View** durch.

### Der Taxonomiebaum wird nicht geladen

**Ursache:** Der Anwendungsserver ist möglicherweise nicht verfügbar, oder es ist ein Netzwerkfehler aufgetreten.

**Maßnahme:**
1. Aktualisieren Sie die Browserseite.
2. Überprüfen Sie die Browserkonsole (F12) auf Fehlermeldungen.
3. Stellen Sie sicher, dass die Anwendungs-URL korrekt ist.
4. Kontaktieren Sie Ihren Administrator, wenn das Problem weiterhin besteht.

### Bewertungen einer früheren Analyse werden noch angezeigt, nachdem die Anforderung geändert wurde

**Ursache:** Bewertungen werden nicht automatisch gelöscht, wenn Sie den Anforderungstext bearbeiten.

**Maßnahme:** Klicken Sie erneut auf **Analyze with AI**, nachdem Sie Ihre Anforderung bearbeitet haben, um aktuelle Bewertungen zu erhalten. Die Warnung für veraltete Ergebnisse (gelber Rahmen) erinnert Sie daran, wenn angezeigte Bewertungen möglicherweise nicht mehr aktuell sind.

### Die Admin-Panels (LLM-Diagnose, Prompt-Editor usw.) sind nicht sichtbar

**Ursache:** Der Admin-Modus ist nicht freigeschaltet.

**Maßnahme:** Klicken Sie auf die **🔒**-Schaltfläche in der Navigationsleiste und geben Sie das Administratorpasswort ein. Wenn Sie das Passwort nicht kennen, kontaktieren Sie Ihren Administrator.
