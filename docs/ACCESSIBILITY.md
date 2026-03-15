# Barrierefreiheitskonzept (BITV 2.0 / WCAG 2.1)

Dieses Dokument beschreibt das Barrierefreiheitskonzept für den Taxonomy Architecture Analyzer gemäß der **Barrierefreie-Informationstechnik-Verordnung (BITV 2.0)** und den **Web Content Accessibility Guidelines (WCAG 2.1 Level AA)**.

---

## Inhaltsverzeichnis

1. [Geltungsbereich](#geltungsbereich)
2. [Konformitätsziel](#konformitätsziel)
3. [Bestandsaufnahme der UI-Komponenten](#bestandsaufnahme-der-ui-komponenten)
4. [Identifizierte Handlungsfelder](#identifizierte-handlungsfelder)
5. [Maßnahmenplan](#maßnahmenplan)
6. [Prüfverfahren](#prüfverfahren)
7. [Erklärung zur Barrierefreiheit](#erklärung-zur-barrierefreiheit)

---

## Geltungsbereich

| Aspekt | Detail |
|---|---|
| **Anwendung** | Taxonomy Architecture Analyzer — Web-Anwendung (Single-Page) |
| **Rechtsgrundlage** | BITV 2.0 (§ 1–3), basierend auf BGG § 12a–12d |
| **Technischer Standard** | WCAG 2.1 Level AA (EN 301 549 V3.2.1) |
| **Geltung** | Alle öffentlich zugänglichen Webseiten und Anwendungsoberflächen bei Einsatz in Bundesbehörden |
| **Fristen** | Bestehende Webanwendungen: BITV 2.0 vollständig anwendbar |

---

## Konformitätsziel

Das Konformitätsziel ist **WCAG 2.1 Level AA**, das dem BITV-2.0-Standard entspricht. Die vier Grundprinzipien:

| Prinzip | Beschreibung | Relevanz für Taxonomy |
|---|---|---|
| **Wahrnehmbar** | Informationen müssen in verschiedenen Formen darstellbar sein | Hoch — farbcodierte Taxonomie-Bäume, Diagramme |
| **Bedienbar** | Navigation und Bedienung müssen per Tastatur möglich sein | Hoch — komplexe Baum-Navigation, modale Dialoge |
| **Verständlich** | Inhalte und Bedienung müssen verständlich sein | Mittel — Fachterminologie, KI-Ergebnisse |
| **Robust** | Inhalte müssen von assistiven Technologien interpretierbar sein | Hoch — dynamische Bootstrap-Komponenten |

---

## Bestandsaufnahme der UI-Komponenten

### Technologie-Stack

| Komponente | Technologie | Barrierefreiheits-Relevanz |
|---|---|---|
| **Framework** | Bootstrap 5 | Grundlegende ARIA-Unterstützung vorhanden |
| **Template Engine** | Thymeleaf (serverseitiges Rendering) | HTML-Struktur kontrollierbar |
| **JavaScript** | Vanilla JS (~29 Module) | Dynamische Inhalte erfordern ARIA-Live-Regionen |
| **Diagramme** | Mermaid.js (SVG-Rendering) | SVG erfordert Textalternativen |
| **Icons** | Bootstrap Icons | Icon-only-Elemente erfordern sr-only-Labels |
| **Baumansicht** | Custom JavaScript (Taxonomie-Baum) | Komplexe Komponente; Treeview-ARIA erforderlich |

### UI-Bereiche und Bewertung

| UI-Bereich | Beschreibung | Status |
|---|---|---|
| **Navigation** | Top-Navbar mit Dropdown-Menüs | ⚠️ Tastaturnavigation prüfen |
| **Analysepanel** | Textarea + Buttons für KI-Analyse | ⚠️ Label-Zuordnung prüfen |
| **Taxonomie-Baum** | Scored Tree mit Farbcodierung | ❌ Farbcodierung allein unzugänglich |
| **Architecture View** | Mermaid-Diagramme (SVG) | ❌ Keine Textalternative |
| **Diff-Ansicht** | Farbcodierte Code-Diffs | ⚠️ Zusätzliche Markierungen erforderlich |
| **Graph-Exploration** | Visuelle Graphen | ❌ Nicht barrierefrei |
| **Admin Panel** | 🔒 Emoji als Interaktionselement | ❌ Nicht barrierefrei |
| **Modale Dialoge** | Bootstrap Modals | ⚠️ Fokus-Management prüfen |
| **Toasts/Benachrichtigungen** | Bootstrap Toasts | ⚠️ ARIA-Live-Regionen prüfen |

---

## Identifizierte Handlungsfelder

### Priorität Hoch 🔴

| # | Handlungsfeld | Betroffene WCAG-Kriterien | Beschreibung |
|---|---|---|---|
| **A1** | Scored Taxonomy Tree — Farbcodierung | 1.4.1 (Use of Color), 1.1.1 (Non-text Content) | Farbcodierung der Scores (rot/gelb/grün) ist die einzige Informationsquelle; Screenreader erhalten keine Score-Information |
| **A2** | Tastaturnavigation | 2.1.1 (Keyboard), 2.4.3 (Focus Order), 2.4.7 (Focus Visible) | Tab-Order, Skip-Links und Focus-Indikatoren für alle interaktiven Elemente sicherstellen |
| **A3** | Formular-Labels | 1.3.1 (Info and Relationships), 3.3.2 (Labels or Instructions) | Alle Formularfelder (Analyse-Textarea, Suchfelder, Login) mit zugeordneten `<label for="">`-Elementen versehen |
| **A4** | Admin Panel Lock-Button | 2.5.3 (Label in Name), 1.1.1 (Non-text Content) | 🔒-Emoji als Interaktionselement durch accessible Button mit Textlabel ersetzen |
| **A5** | Architecture View (Mermaid) | 1.1.1 (Non-text Content) | SVG-Diagramme ohne Textalternative; Alt-Texte oder tabellarische Alternative bereitstellen |

### Priorität Mittel 🟡

| # | Handlungsfeld | Betroffene WCAG-Kriterien | Beschreibung |
|---|---|---|---|
| **A6** | Diff-Ansicht | 1.4.1 (Use of Color) | Farbcodierte Diffs (grün/rot) durch zusätzliche Symbole (+/−/~) und Screenreader-Labels ergänzen |
| **A7** | Graph-Exploration | 1.1.1 (Non-text Content) | Visuelle Graphen durch tabellarische Alternativansicht mit Keyboard-Navigation ergänzen |
| **A8** | Farbkontraste | 1.4.3 (Contrast Minimum) | Kontrast-Audit aller Farben mit axe/Lighthouse durchführen; Mindestkontrastverhältnis 4.5:1 sicherstellen |
| **A9** | ARIA-Live-Regionen | 4.1.3 (Status Messages) | Dynamische Statusmeldungen (Analyse läuft, Export abgeschlossen) als ARIA-Live-Regionen markieren |
| **A10** | Modale Dialoge | 2.4.3 (Focus Order) | Fokus-Trapping in Modals sicherstellen; Fokus bei Schließen zurücksetzen |

---

## Maßnahmenplan

### Phase 1: Audit und Quick Wins (Wochen 1–2)

| # | Maßnahme | Aufwand | WCAG-Kriterien |
|---|---|---|---|
| M1 | axe/Lighthouse-Audit der Hauptseite durchführen | 2 Tage | Alle |
| M2 | Skip-Links implementieren (`<a href="#main-content">Zum Inhalt springen</a>`) | 0,5 Tage | 2.4.1 |
| M3 | `<label for="">`-Zuordnung für alle Formularfelder | 1 Tag | 1.3.1, 3.3.2 |
| M4 | 🔒-Button durch accessible Button mit Text ersetzen | 0,5 Tage | 2.5.3, 1.1.1 |
| M5 | `lang="de"` oder `lang="en"` auf `<html>`-Element sicherstellen | 0,5 Tage | 3.1.1 |

### Phase 2: Kernkomponenten (Wochen 3–6)

| # | Maßnahme | Aufwand | WCAG-Kriterien |
|---|---|---|---|
| M6 | Taxonomie-Baum: ARIA-`treeview`-Rolle, Score als Text (`aria-label`) | 3 Tage | 1.4.1, 1.1.1, 4.1.2 |
| M7 | Taxonomie-Baum: Tastaturnavigation (Pfeiltasten, Enter, Space) | 2 Tage | 2.1.1, 2.4.3 |
| M8 | Architecture View: Tabellarische Alternativansicht | 2 Tage | 1.1.1 |
| M9 | Diff-Ansicht: +/−/~-Symbole und `aria-label` ergänzen | 1 Tag | 1.4.1 |
| M10 | ARIA-Live-Regionen für dynamische Statusmeldungen | 1 Tag | 4.1.3 |

### Phase 3: Verfeinerung (Wochen 7–10)

| # | Maßnahme | Aufwand | WCAG-Kriterien |
|---|---|---|---|
| M11 | Kontrast-Audit und Farbkorrekturen | 2 Tage | 1.4.3 |
| M12 | Graph-Exploration: Tabellarische Alternative | 3 Tage | 1.1.1 |
| M13 | Fokus-Management in Modals verbessern | 1 Tag | 2.4.3 |
| M14 | Screenreader-Tests (NVDA, VoiceOver) | 3 Tage | Alle |
| M15 | BIK-BITV-Konformitätstest vorbereiten | 2 Tage | Alle |

---

## Prüfverfahren

### Automatisierte Tests

| Tool | Einsatzbereich | Frequenz |
|---|---|---|
| **axe-core** | HTML-Struktur, ARIA, Kontraste, Labels | Bei jedem Build (CI-Integration empfohlen) |
| **Lighthouse Accessibility Audit** | Gesamtseiten-Bewertung | Monatlich / bei Release |
| **Pa11y** | Automatisierte Seitenprüfung | Optional, ergänzend |

### Manuelle Tests

| Test | Beschreibung | Frequenz |
|---|---|---|
| **Tastaturnavigation** | Alle Funktionen ohne Maus erreichbar? Tab-Order logisch? | Bei jedem UI-Release |
| **Screenreader-Test** | NVDA (Windows) / VoiceOver (macOS) / Orca (Linux) | Quartalsweise |
| **Zoom-Test** | 200% Zoom: Keine Inhalte abgeschnitten? | Bei jedem UI-Release |
| **Kontrast-Prüfung** | Colour Contrast Analyser für kritische Farben | Bei Farbänderungen |

### BIK-BITV-Test

Für den Einsatz in Bundesbehörden wird ein vollständiger **BIK-BITV-Test** empfohlen:

| Aspekt | Detail |
|---|---|
| **Prüfverfahren** | BIK-BITV-Test (92 Prüfschritte, basierend auf EN 301 549) |
| **Durchführung** | Durch zertifizierte BIK-Prüfstellen |
| **Empfohlener Zeitpunkt** | Nach Abschluss der Maßnahmen Phase 1–3 |
| **Ergebnis** | BITV-Konformitätsbericht mit Prüfprotokoll |

---

## Erklärung zur Barrierefreiheit

Gemäß **§ 12b BGG** (Behindertengleichstellungsgesetz) muss eine Erklärung zur Barrierefreiheit veröffentlicht werden. Vorlage:

---

> ### Erklärung zur Barrierefreiheit
>
> **[Name der Behörde]** ist bemüht, den Taxonomy Architecture Analyzer im Einklang mit § 12a BGG und der BITV 2.0 barrierefrei zugänglich zu machen.
>
> **Stand der Vereinbarkeit:** Diese Anwendung ist **teilweise vereinbar** mit der BITV 2.0.
>
> **Nicht barrierefreie Inhalte:**
> - Farbcodierte Taxonomie-Bäume und Architekturdiagramme verfügen noch nicht über vollständige Textalternativen
> - Graph-Exploration-Funktionen sind primär visuell und bieten noch keine tabellarische Alternative
> - Einzelne interaktive Elemente sind noch nicht vollständig per Tastatur bedienbar
>
> **Maßnahmen:** Die identifizierten Barrieren werden gemäß dem dokumentierten [Maßnahmenplan](#maßnahmenplan) schrittweise behoben.
>
> **Feedback und Kontakt:** Wenn Sie Barrieren bei der Nutzung dieser Anwendung feststellen, kontaktieren Sie bitte **[E-Mail der zuständigen Stelle]**.
>
> **Schlichtungsverfahren:** Bei unbefriedigender Reaktion können Sie sich an die Schlichtungsstelle nach § 16 BGG wenden: [Schlichtungsstelle nach dem Behindertengleichstellungsgesetz](https://www.schlichtungsstelle-bgg.de/).

---

## Verwandte Dokumentation

- [User Guide](USER_GUIDE.md) — Benutzerhandbuch
- [Security](SECURITY.md) — Sicherheitsarchitektur
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behördenumgebungen
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität
