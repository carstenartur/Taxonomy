# Abgrenzung der Multi-Repository-Vorschau

Taxonomy enthält eine integrierte technische Grundlage für einen Repository-Katalog, persönliche Arbeitskopien, zentrale Forks und Repository-Mitgliedschaften. Diese Grundlage wird für die weitere Entwicklung des Multi-Repository-Programms benötigt, ist in Taxonomy 1.4.0 jedoch **keine unterstützte Produktionsfunktion**.

## Standardverhalten

Die öffentliche Repository-Katalog-API unter `/api/repositories/**` ist deaktiviert, solange ein Betreiber sie nicht ausdrücklich einschaltet:

```text
TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED=true
```

Die entsprechende Spring-Eigenschaft lautet:

```properties
taxonomy.features.multi-repository-api.enabled=true
```

Wenn die Eigenschaft fehlt oder `false` ist:

- wird der `ArchitectureRepositoryController` nicht erzeugt;
- werden Repository-Katalog, Anlage zentraler Repositories, Arbeitskopien, Forks und Mitgliedschaftsendpunkte nicht registriert;
- erscheinen diese Endpunkte nicht in der erzeugten OpenAPI-Dokumentation;
- arbeiten der interne Primär-Repository-Katalog und die bisher unterstützte Single-Repository-/Workspace-Infrastruktur weiter.

## Unterstützte Grenze für 1.4.0

Taxonomy 1.4.0 unterstützt das etablierte Primär-Repository- und persönliche Workspace-Verhalten. Der integrierte Multi-Repository-Code bleibt als Entwicklungsgrundlage erhalten, die öffentliche API bleibt jedoch opt-in, bis das Abschlussprogramm aus #609 und die Blockergruppen #741–#749 erledigt sind.

Für eine produktive Freigabe fehlen insbesondere noch vollständige Nachweise für:

- readiness-geprüfte produktive Reads;
- dauerhafte Wiederholung und Projektionswiederherstellung;
- Mandantentrennung für Anforderungen, Portfolio, Entscheidungen und Audit;
- Isolation von Caches, Suche, Embeddings und Index-Lebenszyklus;
- exakte Branch-, Commit- und Stale-State-Identität;
- Repository-Auswahl und Kontextnavigation in der Oberfläche;
- ancestry-erhaltende Clone-, Fork- und inkrementelle Transferoperationen;
- konsistente Organisationssichtbarkeit und Mitgliedschaften;
- ein vollständiges repositoryübergreifendes Ende-zu-Ende-Abnahmepaket.

## Nur für Evaluation

Eine ausdrückliche Aktivierung ist nur für isolierte Entwicklungs- und Evaluationsumgebungen ohne vertrauliche oder organisationsübergreifende Produktionsdaten vorgesehen. Der Feature-Schalter ersetzt weder Mandantenisolation noch Autorisierung, Recovery oder Readiness-Prüfungen. Betreiber müssen alle betroffenen Endpunkte als experimentell behandeln und dürfen die aktivierte Topologie nicht als unterstützte 1.4.0-Produktionskonfiguration darstellen.

Die Release Notes müssen diese Grenze wahrheitsgemäß beschreiben: Die technische Grundlage ist integriert, während die öffentliche Multi-Repository-Produktfunktion standardmäßig deaktiviert und noch nicht abgeschlossen ist.
