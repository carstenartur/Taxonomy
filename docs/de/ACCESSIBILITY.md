# Nachweismatrix zur Barrierefreiheit (BITV 2.0 / WCAG 2.1)

**Letzte codebasierte Prüfung:** 30. Juli 2026  
**Ziel:** WCAG 2.1 Level AA / EN 301 549 / BITV 2.0  
**Aktueller Stand:** Teilweise konform – eine formale BIK-BITV-Prüfung wurde noch nicht durchgeführt.

Dieses Dokument hält umgesetzte Maßnahmen, automatisierte Nachweise, bekannte Einschränkungen und die vor einer Freigabe erforderlichen manuellen Prüfungen fest. Es ist keine Erklärung vollständiger rechtlicher Konformität.

## Geltungsbereich

Bewertet wird die authentifizierte Webanwendung mit Analyse, Taxonomiebaum, Architekturansichten, Graph-Erkundung, Versionierung, DSL-Editor, Hilfe, Administration, Einstellungen, Dialogen und Statusmeldungen.

## Umsetzungs- und Nachweismatrix

| Bereich | Aktuelle Umsetzung | Nachweis / Regressionsschutz | Status |
|---|---|---|---|
| Dokumentensprache | Thymeleaf setzt die Sprache anhand der aktiven Locale | Template und authentifizierter axe-Test | Umgesetzt |
| Sprunglink | Bei Fokus sichtbarer Link zum Hauptinhalt | Markup und Fokus-CSS | Umgesetzt |
| Statusmeldungen | Höfliche und dringende ARIA-Live-Regionen | UI-Hilfsschicht und axe-Test | Umgesetzt |
| Hauptnavigation | `tablist`-/`tab`-/`tabpanel`-Semantik, `aria-selected`, Roving Tabindex, Pfeil-/Pos1-/Ende-Tasten | `taxonomy-utils.js` und axe-Test | Umgesetzt |
| Taxonomiebaum | `tree`, `treeitem`, `group`, `aria-expanded`, Pfeil-/Pos1-/Ende-/Enter-/Leertasten-Bedienung | Browsercode und fokussierte Tests | Umgesetzt |
| Scores und Begründungen | Zugänglicher Name enthält Code, Titel, Score und Begründung; MutationObserver synchronisiert dynamische Änderungen | `taxonomy-utils.js` | Umgesetzt |
| Fokusdarstellung | Sichtbare `focus-visible`-Umrandungen für Baum, Navigation, Dialoge und Knotenaktionen | CSS-Vertrag | Umgesetzt |
| Dialoge | Bootstrap-Fokusführung; gemeinsamer Score-/Hinweisdialog basiert auf beschriftetem `<dialog>` | UI-Code und axe-Test | Umgesetzt |
| Administration | Berechtigung ausschließlich über `ROLE_ADMIN`; Symbolschaltfläche besitzt zugänglichen Namen | Security- und UI-Regressionstests | Umgesetzt |
| Veraltete Ergebnisse | Änderung der Anforderung nach einer Analyse erzeugt Warnung und Rücksetzaktion | Screenshot- und Verhaltenstest | Umgesetzt |
| Touch-Bedienung | Knotenaktionen werden bei groben Zeigegeräten eingeblendet; wichtige Bedienelemente erhalten 44-Pixel-Ziele | Responsives Ergonomie-Stylesheet | Umgesetzt |
| Responsive Aufgabenreihenfolge | Bei schmalen beziehungsweise gezoomten Ansichten wird die primäre Analyseaufgabe im tatsächlichen DOM vor den Referenzbaum verschoben; beim Desktop-Layout wird die ursprüngliche Reihenfolge wiederhergestellt | Rollen-/Zustandstests für Geometrie, DOM-, Lese- und Fokusreihenfolge | Umgesetzt |
| Explizite Aufgabenhierarchie | Die Analyse zeigt die Stufen Beschreiben, Analysieren, Prüfen und Weiterarbeiten sowie genau eine kontextabhängige Folgeaktion | Reale Rollen-/Browseraufgaben und Zustandsassertionen | Umgesetzt |
| Progressive Offenlegung | Gesunder Repository-, Arbeitsbereichs-, Modell-/Anbieter- und Expertenstatus ist standardmäßig eingeklappt; handlungsrelevante Fehler öffnen den Betriebszustand | Browserassertionen und automatische Fehlerzustandsnachweise | Umgesetzt |
| Experten-Tastaturzugriff | `Alt+Umschalt+A` fokussiert die Analyseaufgabe; `Alt+Umschalt+O` schaltet den Betriebszustand um und fokussiert ihn | Rollen-/Zustandsassertionen | Umgesetzt |
| Lokalisierte dynamische UI | Aufgabenstufen und Offenlegungen verwenden dieselbe englisch/deutsche Nachrichteninventarliste wie die serverseitig gerenderte UI | i18n-Controller-Integrationstest und Browser-Startvertrag | Umgesetzt |
| Zoom und Reflow | Navigation bleibt eine einzeilige horizontal erreichbare Leiste; Panels und Aktionen brechen ohne wesentlichen horizontalen Inhaltsverlust um | Responsives Stylesheet und Maven-gesteuerte Rollen-/Zustandsmatrix; manuelle Geräteprüfung bleibt erforderlich | Teilweise |
| Reduzierte Bewegung | Animationen und Übergänge werden bei `prefers-reduced-motion` minimiert | CSS | Umgesetzt |
| Graphen und Diagramme | Mehrere Ansichten besitzen Tabellen oder Detaildarstellungen; die vollständige inhaltliche Gleichwertigkeit ist noch manuell zu prüfen | Manuelle Prüfung | Teilweise |
| DSL-Editor | CodeMirror bleibt in der automatisierten Browser-/axe-Abdeckung enthalten; seine komplexe Editor-Semantik benötigt zusätzlich eine eigene Tastatur- und Screenreader-Testmatrix | Automatisierte Browsermatrix plus manuelle Tastatur-/Screenreader-Prüfung | Teilweise |
| Kontraste | Bootstrap-Grundfarben und explizite Textfarben; vollständige Prüfung aller Zustände bleibt erforderlich | axe plus manuelle Prüfung | Teilweise |

## Automatisierte Aufgaben- und Ergonomienachweise

Die Maven-gesteuerte Rollen-/Zustandssuite führt vier konkrete Aufgaben aus, anstatt dieselbe visuelle Prüfung nur mit unterschiedlichen Rollenbezeichnungen zu versehen:

1. Ein USER gibt eine Anforderung ein und analysiert sie.
2. Ein schmales/mobiles Profil öffnet das fertige Ergebnis und findet die Folgeaktion vor dem Referenzbaum.
3. Ein ARCHITECT prüft einen echten offenen Relationsvorschlag, entscheidet ihn, erstellt über die Oberfläche eine Variante und kehrt in den Ursprungskontext zurück.
4. Ein ADMIN öffnet das Health Dashboard, erkennt die begrenzten Komponentenstatus und aktualisiert sie.

Jedes Profil schreibt ein `taskMeasurements`-Objekt in seine `report.json` mit:

- Aufgabenerfolg und fehlgeschlagenem Schritt;
- Zeit bis die Primäraktion sichtbar, aktiv und fokussierbar ist;
- Zeit bis zum Aufgabenabschluss;
- Pixeln und Viewport-Anteil vor der Aufgabenoberfläche;
- Seitenwechseln und Navigationsfehlern;
- Sichtbarkeit von Primär- und Folgeaktion im Viewport;
- standardmäßig eingeklapptem Betriebszustand und weiteren Werkzeugen.

Der erste vollständige Lauf bildet die repository-eigene Baseline. Zahlenbudgets werden aus geprüften Nachweisen abgeleitet, mit Begründung eingecheckt und getrennt von axe-Befunden behandelt.

## Automatischer Accessibility-Gate

Accessibility ist Teil der Maven-gesteuerten Browser-Suite und kein eigener GitHub-Workflow mehr:

```bash
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=accessibility
```

Das Profil `ci` führt dieselben authentifizierten axe-Szenarien zusammen mit der Primärworkflow- und Rollen-/Zustandsmatrix aus. Maven installiert die fest gepinnten Node-, Playwright- und axe-Abhängigkeiten, startet die echte Spring-Boot-Anwendung und schreibt Berichte unter `target/ui-verification/accessibility/`.

Fest gepinnte Pakete:

- `@playwright/test` 1.61.1
- `@axe-core/playwright` 4.12.1

Automatische Prüfungen belegen keine vollständige Konformität und ersetzen weder Tastatur-, Screenreader-, Zoom- und Kognitionstests noch die fachliche Prüfung von Diagrammalternativen.

## Verbindliche manuelle Release-Prüfungen

- [ ] Primären Workflow vollständig ohne Maus bedienen.
- [ ] Fokusreihenfolge und Fokusrückgabe für jeden Dialog prüfen.
- [ ] Die vier Aufgabenstufen, die kontextabhängige Folgeaktion und beide Expertenkürzel auf Deutsch und Englisch prüfen.
- [ ] Bestätigen, dass gesunde Betriebsdetails eingeklappt bleiben und blockierende Fehler sofort auffindbar sind.
- [ ] 200 % und 400 % Browser-Zoom ohne Verlust wesentlicher Inhalte testen.
- [ ] 320 CSS-Pixel sowie ein Touch-Gerät testen.
- [ ] Windows High Contrast / Forced Colors testen.
- [ ] `prefers-reduced-motion` testen.
- [ ] Taxonomiebaum und Versionsdialoge mit NVDA oder JAWS testen.
- [ ] Primären Workflow mit VoiceOver unter macOS/iOS testen.
- [ ] Für jede Architektur-/Graphinformation eine Tabelle oder strukturierte Textalternative bestätigen.
- [ ] Sicherstellen, dass Validierungsfehler Feld, Ursache und Korrekturmöglichkeit benennen und Eingaben erhalten bleiben.

## Bekannte Einschränkungen

1. Komplexe D3-Diagramme benötigen noch eine vollständige Gleichwertigkeitsprüfung ihrer Tabellen-/Detailalternativen.
2. CodeMirror benötigt eine eigene Screenreader- und Tastatur-Testmatrix.
3. Eine formale BIK-BITV-Prüfung fehlt.
4. 400-%-Zoom und alle unterstützten mobilen Geräte sind nicht unabhängig zertifiziert.
5. Expertenbereiche wie selektiver Transfer, rohe DSL, Git-Historie und Konfliktauflösung besitzen weiterhin hohe kognitive Last.
6. Drittanbieter-Browserressourcen müssen lokal bereitgestellt sein, bevor eine Installation als vollständig netzisoliert bezeichnet werden kann.

## Softwareergonomische Regeln

Neue oder geänderte Workflows müssen:

- Erkennen statt Erinnern unterstützen: suchbare Auswahl statt roher IDs oder Commit-Hashes.
- pro Aufgabenbereich eine klare Primäraktion zeigen.
- Diagnose- und Systemmetriken aus der Standardarbeitsfläche heraushalten.
- verborgene Betriebsdetails automatisch öffnen, wenn sie einen blockierenden Fehler enthalten.
- wesentliche Aktionen nicht ausschließlich hinter Hover verstecken.
- Farbe nie als einzigen Informationsträger verwenden.
- keine nativen `alert()`-/`prompt()`-Dialoge verwenden.
- Eingaben nach Validierungs- oder Netzwerkfehlern erhalten.
- asynchrone Ergebnisse und Fehler über Live-Regionen ankündigen.
- zu jedem Graphen und Diagramm eine nichtgrafische Darstellung bereitstellen.

## Vorlage für die Konformitätserklärung

Bis zum Abschluss einer formalen Prüfung sollte eine einsetzende Stelle sinngemäß formulieren:

> Der Taxonomy Architecture Analyzer ist teilweise konform mit BITV 2.0 / WCAG 2.1 Level AA. Automatisierte axe-Prüfungen decken die zentralen authentifizierten Anwendungsbereiche ab. Verbleibende Einschränkungen betreffen komplexe Visualisierungen, den DSL-Editor, umfassende Screenreader-Prüfungen und die formale BIK-BITV-Zertifizierung.

Die veröffentlichte Erklärung muss Kontaktweg, Erstellungsdatum, Prüfmethode, bekannte Barrieren und das Schlichtungsverfahren enthalten.

## Verwandte Dokumente

- [Benutzerhandbuch](USER_GUIDE.md)
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md)
- [Sicherheit](SECURITY.md)
- [Datenschutz](DATA_PROTECTION.md)
- [Digitale Souveränität](DIGITAL_SOVEREIGNTY.md)

## Automatisierte Browser-Abdeckungsmatrix

Die gepflegte Browser-, Viewport-, CodeMirror-, Tastatur-, Reduced-Motion- und axe-Abdeckung ist in [`docs/dev/BROWSER_QA.md`](../dev/BROWSER_QA.md) beschrieben. Neue moderate axe-Befunde werden gegen eine geprüfte Baseline blockiert; CodeMirror ist nicht von der Prüfung ausgeschlossen.
