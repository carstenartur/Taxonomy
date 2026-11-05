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
mitgelieferten Styles bleiben verfügbar. Serverseitig erzeugte PDF-, Word- und
Diagrammexporte übernehmen diese CSS-Datei noch nicht.
