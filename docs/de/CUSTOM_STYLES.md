# Kundenspezifische Oberflächengestaltung

Ein Betreiber kann eine eigene UTF-8-CSS-Datei bereitstellen. Taxonomy lädt sie
nach seinen mitgelieferten Styles, sodass sie die Oberfläche gezielt anpassen
kann. Ohne Konfiguration wird keine zusätzliche Datei angefordert.

Setzen Sie `TAXONOMY_UI_CUSTOM_STYLESHEET` auf den absoluten Dateipfad, zum
Beispiel `/etc/taxonomy/theme/customer.css`. Alternativ kann die Spring-Einstellung
`taxonomy.ui.custom-stylesheet` verwendet werden. Die Datei darf bis zu 512 KiB
groß sein. Änderungen sind beim nächsten Neuladen der Seite sichtbar; ein
Browser-Cache hält die Datei nicht fest.

Beispiel für einen Ausgangspunkt:

```css
:root {
  --taxonomy-navigation-background: #17406f;
  --taxonomy-navigation-foreground: #fff;
  --taxonomy-surface-background: #eef3f8;
  --taxonomy-surface-foreground: #17243a;
  --taxonomy-panel-background: #fff;
  --taxonomy-panel-border: #c8d1dd;
  --taxonomy-canvas-background: #f8fafc;
  --taxonomy-layer-cap-surface: #17406f;
  --taxonomy-layer-proc-surface: #146c43;
  --taxonomy-relation-supports: #27834a;
}
```

Diese gemeinsamen Farben gelten für die gemeinsame Navigation und die Flächen,
Panels und Diagrammhintergründe der Arbeitsarchitektur und des gespeicherten
Analysezustands. Andere Oberflächen und einzelne Bedienelemente können weiterhin
zusätzliche CSS-Regeln benötigen. Vorder- und Hintergrundfarben müssen zusammen
auf ausreichenden Kontrast geprüft werden.

Die interaktiven Graphen von Analyse, gespeichertem Analysezustand und
Arbeitsarchitektur verwenden dieselben Farben für Taxonomieebenen und Beziehungen.
Neben `cap` und `proc` stehen die
Ebenen-Tokens `svc`, `app`, `info`, `comm`, `system`, `component` und `default`
mit demselben Namensmuster zur Verfügung. Für Beziehungen kann beispielsweise
`--taxonomy-relation-supports` gesetzt werden. Für die automatische Wahl von
heller oder dunkler Diagrammschrift eignen sich hexadezimale Farbwerte. Prüfen
Sie auch den Kontrast anderer Ansichten; deren Textfarben können separat über
CSS angepasst werden.
Die alternative Ebenenansicht der Analyse nutzt dieselben Tokens für die
Elementhintergründe; ihre Schrift und Beziehungsbeschriftungen können eigene
CSS-Anpassungen benötigen.

In Rancher kann die Datei als ConfigMap in ein Verzeichnis des Taxonomy-Containers
eingehängt und der Pfad über die Umgebungsvariable gesetzt werden. Bei mehreren
Pods muss dieselbe Konfiguration in jedem Pod verfügbar sein. Ein Verzeichnis-Mount
erlaubt Kubernetes, aktualisierte ConfigMap-Dateien zu ersetzen; bei einem
`subPath`-Mount ist dafür ein Neustart des Pods erforderlich.

Ist die konfigurierte Datei nicht lesbar, zu groß oder kein gültiges UTF-8, meldet
der Stylesheet-Aufruf einen Fehler und Taxonomy protokolliert die Ursache. Die
mitgelieferten Styles bleiben verfügbar. Exporte verwenden bei einem solchen
Lesefehler ihre mitgelieferte Gestaltung.

## Exportgestaltung

Serverexporte lesen dieselbe begrenzte Datei einmal pro Dokument und übernehmen
ein unveränderliches Farbschema. Dateiänderungen gelten für den nächsten Export.
Die Gestaltung verändert weder ausgewählte Elemente, Relevanzwerte,
Originalbegründungen, Herkunftsnachweise noch die Autorität eines Snapshots.

| Ausgabe | Unterstützte Kundengestaltung |
| --- | --- |
| Eigenständige Architektur-SVGs und vektorbasierte Architektur-PDFs einschließlich Arbeitsansichten und gespeicherter Snapshots | Flächen über `--taxonomy-layer-<Ebene>-surface`, Beziehungen über `--taxonomy-relation-<Beziehung>`, Hintergrund und Schrift über `--taxonomy-canvas-background` und `--taxonomy-canvas-foreground`; Navigationshintergrund und Panelrahmen färben Elementumrandungen. PDF-Evidenzseiten verwenden dasselbe Canvas-Farbschema. |
| Native Visio-VSDX-Dateien und Übergabepakete | Taxonomieflächen, lesbare Elementschrift, Navigations-/Panelumrandungen und typisierte Beziehungslinien. Transparente Container, native weiße Seiten, Beziehungsbeschriftungen und reine Evidenzdarstellungen behalten ihre nativen Styles. Vorschläge bleiben gestrichelt. |
| Architektur- und Entscheidungsberichte als HTML | Hintergrund/Schrift der Oberfläche, Panelhintergrund/-rahmen sowie Navigationshintergrund/-schrift gestalten Berichtsseite, Überschriften, Tabellen und Panels. Das erzeugte HTML ist eigenständig. |
| Word-/DOTX-Berichte und eingebettete Architekturbilder | Das unterstützte Dokumenttemplate steuert die Gestaltung. Die CSS-Datei der Oberfläche wird nicht angewendet. |
| Eingebettete Entscheidungsbaum- und Kapitelbilder | Relevanz-, Entscheidungs- und führende Pfadfarben behalten ihre fachliche Berichtspalette. |
| ArchiMate, Sparx, Structurizr, Mermaid und Datenexporte | Es gilt die native Austausch- oder Quelldarstellung; Oberflächen-CSS wird nicht exportiert. |

Der portable Teil unterstützt deckende, wörtliche Werte im Format `#rgb` oder
`#rrggbb` in nicht qualifizierten `:root { ... }`-Blöcken auf oberster Ebene.
Wiederholte Deklarationen folgen der Quellreihenfolge; `!important` hat Vorrang.
Kommentare sind erlaubt. Ebenennamen sind `cap`, `proc`, `svc`, `app`, `info`,
`comm`, `system`, `component` und `default`. Beziehungsnamen sind `supports`,
`realizes`, `realized-by`, `uses`, `requires`, `depends-on`, `fulfills`, `consumes`,
`assigned-to`, `produces`, `communicates-with`, `contains` und `related-to`.
Ein kompletter `:root`-Block mit verschachtelten Regeln liegt außerhalb dieses
Teilumfangs und wird ignoriert.

Andere Deklarationen werden für Exporte ignoriert: beliebige, gruppierte oder
designabhängige Selektoren, verschachtelte Regeln, `@media`, `@import`, `var()`,
benannte/RGB/Alpha-Farben, Schriften, Bilder und Layoutänderungen bleiben
Browser-CSS. Auf Anwendungsseiten gelten dafür weiterhin die CSS-Regeln des
Browsers. Ein unterstütztes Token ohne portablen Wert behält seinen
mitgelieferten Exportwert.

Für die gemeinsamen SVG-/PDF-Diagramme und gestaltete Visio-Elemente passt die
Farbauflösung Schrift bei Bedarf auf mindestens 4,5:1 Kontrast zur Fläche an.
Kundenspezifische Beziehungs- und Elementumrandungen verwenden mindestens 3:1
zum Canvas beziehungsweise zur Elementfläche. HTML-Berichte bestimmen ihre
Schriftfarbe ebenfalls passend zur jeweiligen Seite oder Panelfläche.
Ein schlecht lesbarer Kundenwert kann dadurch im Export abweichen.
Prüfen Sie fertige Dateien im vorgesehenen
Anzeigeprogramm, bevor Sie ein Farbschema bereitstellen; automatisierte
XML-/PDF-Prüfungen zertifizieren keine Microsoft-Visio- oder Office-Kompatibilität.
