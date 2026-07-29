# Nachweismatrix zur Barrierefreiheit (BITV 2.0 / WCAG 2.1)

**Letzte codebasierte Prüfung:** 29. Juli 2026  
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
| Responsive Aufgabenreihenfolge | Bei schmalen beziehungsweise gezoomten Ansichten wird die primäre Analyseaufgabe im tatsächlichen DOM vor den Referenzbaum verschoben; beim Desktop-Layout wird die ursprüngliche Reihenfolge wiederhergestellt | `taxonomy-utils.js` sowie Rollen-/Zustandstests für Geometrie, DOM-, Lese- und Fokusreihenfolge | Umgesetzt |
| Zoom und Reflow | Navigation bleibt eine einzeilige horizontal erreichbare Leiste; Panels und Aktionen brechen ohne wesentlichen horizontalen Inhaltsverlust um | Responsives Stylesheet und Maven-gesteuerte Rollen-/Zustandsmatrix; manuelle Geräteprüfung bleibt erforderlich | Teilweise |
| Reduzierte Bewegung | Animationen und Übergänge werden bei `prefers-reduced-motion` minimiert | CSS | Umgesetzt |
| Graphen und Diagramme | Mehrere Ansichten besitzen Tabellen oder Detaildarstellungen; die vollständige inhaltliche Gleichwertigkeit ist noch manuell zu prüfen | Manuelle Prüfung | Teilweise |
| DSL-Editor | CodeMirror stellt eine eigene Accessibility-Struktur bereit; separate Prüfung nötig | Manuelle Tastatur-/Screenreader-Prüfung | Teilweise |
| Kontraste | Bootstrap-Grundfarben und explizite Textfarben; vollständige Prüfung aller Zustände bleibt erforderlich | axe plus manuelle Prüfung | Teilweise |

## Automatischer Accessibility-Gate

Accessibility ist Teil der Maven-gesteuerten Browser-Suite und kein eigener
GitHub-Workflow mehr:

```bash
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=accessibility
```

Das Profil `ci` führt dieselben authentifizierten axe-Szenarien zusammen mit
der Primärworkflow- und Rollen-/Zustandsmatrix aus. Maven installiert die fest
gepinnten Node-, Playwright- und axe-Abhängigkeiten, startet die echte
Spring-Boot-Anwendung und schreibt Berichte unter
`target/ui-verification/accessibility/`.

Fest gepinnte Pakete:

- `@playwright/test` 1.61.1
- `@axe-core/playwright` 4.12.1

Automatische Prüfungen belegen keine vollständige Konformität und ersetzen
weder Tastatur-, Screenreader-, Zoom- und Kognitionstests noch die fachliche
Prüfung von Diagrammalternativen.

## Verbindliche manuelle Release-Prüfungen

- [ ] Primären Workflow vollständig ohne Maus bedienen.
- [ ] Fokusreihenfolge und Fokusrückgabe für jeden Dialog prüfen.
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
