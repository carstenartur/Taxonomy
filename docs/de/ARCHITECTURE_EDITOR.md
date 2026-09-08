# Architektureditor

Den **Architektureditor** über Projekte oder die Snapshot-Workbench öffnen. Die Kontextzeile zeigt Repository, Workspace, Branch, Commit und Benutzer. Historische Commits und zentrale Lesekontexte sind schreibgeschützt. **Privaten Workspace anlegen** erstellt bei passender Repository-Mitgliedschaft eine Arbeitskopie. Zum Bearbeiten ist außerdem die Rolle ARCHITECT oder ADMIN nötig.

1. Die Elementliste durchsuchen oder ein Objekt im Graph auswählen. Eigenschaften, Relationen, Belege und DSL-Leseansicht folgen derselben Auswahl. Die Seitenadresse enthält den exakten Kontext und das ausgewählte Element.
2. **Neues Element** wählen oder bestehende Eigenschaften bearbeiten. Der Server vergibt neue Identitäten; eine Umbenennung ändert diese nicht. Taxonomiereferenzen und importierte Herkunftsangaben bleiben schreibgeschützt.
3. Eine Begründung eintragen und **Änderung vorschauen** wählen. Die betroffenen Blöcke und den Commit prüfen, dann **Annehmen und committen**. Eingabe und Vorschau speichern nichts. **Entwurf verwerfen** stellt die angenommenen Eigenschaften wieder her.
4. Zum Verbinden den Relationstyp und die Identität des Zielelements angeben. Die Auswahl einer Graphkante füllt das Relationsformular. Bei einem vorhandenen Tripel ändert das Formular den Prüfstatus. Der Server lehnt unpassende Richtung/Typen, Duplikate und Selbstbezüge ab. Das Feld für das übergeordnete Element verschiebt oder löst eine Zugehörigkeit; Kreise und mehrere Eltern sind verboten.
5. **Löschung vorschauen** zeigt blockierende Abhängigkeiten. Diese zuerst ausdrücklich entfernen oder neu zuordnen. Es gibt keine verdeckte Kaskadenlöschung.
6. **Rückgängig vorschauen** bzw. **Wiederholen vorschauen** fordert einen neuen protokollierten Gegenbefehl an. Spätere abhängige Änderungen können ihn blockieren. Die Historie übersteht das Neuladen. Text rückgängig machen betrifft nur die Formulareingabe; Git-Revert einer Baseline bleibt ein separater Versionsvorgang.

Hat sich der Branch seit der Vorschau geändert, **Aktualisieren, vergleichen und erneut vorschauen** wählen. Den neuen Vorher-/Nachher-Zustand prüfen und ausdrücklich annehmen. Bei einem Netzwerkfehler bleibt die Befehlsidentität für einen sicheren erneuten Versuch erhalten. Ein Git-Commit kann erfolgreich sein, obwohl die sekundäre Relationsprojektion neu aufgebaut werden muss: Der angenommene SHA bleibt maßgeblich; **Projektion neu aufbauen** repariert sie ohne weitere Modelländerung.

Graph und Elementliste verwenden dasselbe Servermodell. Die Liste zeigt 50 durchsuchbare Einträge pro Seite; der Graph stellt höchstens 200 Knoten und 400 Kanten gleichzeitig dar. Suche und Fokus erschließen auch Objekte außerhalb des sichtbaren Ausschnitts. Im fokussierten Graph verschieben Pfeiltasten den Ausschnitt, +/− ändern den Zoom. Formulare bieten eine vollständige Tastaturalternative zur Graphauswahl.

SVG und Vektor-PDF verwenden das vollständige deterministische Serverlayout des angezeigten Commits, unabhängig von lokalem Zoom und Ausschnitt. Der Editor verwendet derzeit abgeleitetes Layout und eine synchronisierte DSL-Leseansicht. Bearbeitbare DSL-Übersetzung, gespeicherte Layouts, gemeinsames Undo, weitergehende Belegentscheidungen und erweiterte Historie bleiben in [#814](https://github.com/carstenartur/Taxonomy/issues/814) verfolgt. Bestehende Analysesnapshots bleiben unveränderlich.

Technische Entscheidung: [ADR 0005](../adr/0005-private-architecture-editor.md). [English guide](../en/ARCHITECTURE_EDITOR.md).
