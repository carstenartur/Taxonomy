# Die angezeigte Architektur für Sparx EA exportieren

Unter **Exportieren** steht neben Visio **Sparx EA / XMI (Kopie, experimentell)**.
Die Funktion verwendet die vorhandene Architektur und startet keine neue Analyse.
Nach dem Bestätigen wird ein ZIP mit drei Dateien bereitgestellt:

- `architecture.xmi`: XMI 2.1 für eine neue Kopie in einem EA-Testmodell.
- `manifest.json`: ursprüngliche IDs, Abbildungen, Verluste und Prüfsumme der XMI-Datei.
- `README.txt`: Hinweise zur sicheren Einfuhr und zur Abgrenzung vom Synchronisieren.

Vor dem Import das EA-Modell sichern und den Verlustbericht prüfen. Diagrammimport
deaktiviert lassen. Layout, Positionen und Farben werden nicht als EA-Diagramm übertragen.
Einige Beziehungsarten werden als beschriftete Assoziationen abgebildet; das Original
steht im Bericht und in den Tags. Diese Projektion ist keine vollständige semantische
Gleichwertigkeit. Unbekannte Arten werden nicht stillschweigend weggelassen.

**Dies ist keine Aktualisierung eines vorhandenen EA-Modells.** Jeder direkte Export
bekommt einen eigenen Namensraum für die Kopie. Für wiederholten, bidirektionalen
Austausch die vorhandenen **Werkzeugintegrationen** mit geprüfter Verbindung verwenden.
Ein Download ändert weder die aktive Architektur noch einen Synchronisationsstand.
Die Kennung einer vorhandenen gespeicherten Version wird nicht vorgetäuscht.

Die Kompatibilität mit einer konkreten Enterprise-Architect-Version ist noch nicht
produktseitig abgenommen. Dasselbe gilt für die Microsoft-Visio-Desktop-Abnahme.
Bei einem Visio-Fehler sind Build-Commit, genaue Meldung und gegebenenfalls die
heruntergeladene Datei für die Diagnose entscheidend. Eine HTML-Anmeldeseite oder
unvollständige ZIP-Antwort wird jetzt bereits vor dem Download zurückgewiesen.

Auch fehlende lokale ZIP-Einträge und leere erforderliche Dateien werden vor dem
Download zurückgewiesen. Diese Transportprüfungen ersetzen weder die serverseitige
Schemaprüfung noch den Öffnungstest im jeweiligen Desktopprodukt.
