# Versionierte Word-Vorlagen

Taxonomy verwaltet Word-Vorlagen als entpackte OOXML-Paketbäume im logischen
JGit-/Hibernate-Repository `taxonomy-document-templates`. WebDAV und Downloads
stellen daraus vollständige `.dotx`-Dateien zusammen.

## Standardvorlage für den Bewertungsbericht

Die Anwendung liefert die erforderliche Vorlage

```text
decision-rationale-report
```

im Container beziehungsweise JAR mit. Beim ersten erfolgreichen Start wird sie
idempotent als erster Git-Commit importiert. Existiert die Vorlage bereits, wird sie
weder beim Neustart noch bei einem Softwareupdate überschrieben. Eigene Logos,
Organisationsangaben und Layoutänderungen bleiben daher erhalten.

Der DOCX-Bewertungsbericht wird tatsächlich aus der jeweils aktuellen Git-Version
dieser Vorlage erzeugt. Taxonomy verwendet insbesondere:

```text
{{taxonomy.report.title}}
{{taxonomy.report.subtitle}}
{{taxonomy.report.status}}
{{taxonomy.report.requirement}}
{{taxonomy.report.generatedAt}}
{{taxonomy.report.generatedBy}}
{{taxonomy.report.taxonomyVersion}}
{{taxonomy.report.applicationVersion}}
{{taxonomy.report.workspace}}
{{taxonomy.report.branch}}
{{taxonomy.template.id}}
{{taxonomy.template.commit}}
{{taxonomy.report.body}}
```

`{{taxonomy.report.body}}` muss genau einmal als eigener Absatz vorkommen und der
letzte nicht leere Inhaltsblock des Dokuments sein. Taxonomy entfernt ihn beim Export
und fügt dort Kurzfassung, Entscheidungskapitel, Diagramme und Anhang ein. Titelseite,
Bilder, Kopf- und Fußzeilen, Seitenformat und Word-Formatvorlagen bleiben Bestandteil
der bearbeitbaren DOTX-Vorlage.

Uploads und Wiederherstellungen der Standardvorlage werden zusätzlich zum
allgemeinen OOXML-Sicherheitscheck gegen diesen Vertrag validiert. Der
Actuator-Health-Check `decisionRationaleTemplate` meldet `DOWN`, wenn die Pflichtvorlage
fehlt oder strukturell ungültig ist.
