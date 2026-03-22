## Nachgeschobene Korrekturen zum Plan — bitte berücksichtigen

### 1. Nicht überall blind `resolveCurrentContext()` verwenden

Der vorgeschlagene Fix `repositoryFactory.resolveRepository(contextResolver.resolveCurrentContext())` ist **nicht immer der beste Ansatz**.

Einige Services arbeiten **nicht** im Web-Request-Kontext, sondern in:
- Hintergrund-/Indexing-Flows
- zustandslosen Service-Aufrufen
- benutzerbezogenen Methoden wie `getState(username, branch)`

Konkret bei `CommitIndexService.indexBranch(branch)`:
- `resolveCurrentContext()` ist hier **zu implizit**
- Der Service hat zwar einen `WorkspaceContextResolver`, benutzt ihn aber nur für den Branch-Filter

**Regel:** 
- Wo möglich: **expliziten** `WorkspaceContext` oder `workspaceId` als **Parameter** in die Methoden hineintragen
- `resolveCurrentContext()` nur dort nutzen, wo der Service wirklich request-/UI-gebunden ist (z.B. Facade-Layer)
- Für `indexBranch`, `getState`, `isProjectionStale`: den Kontext **explizit** übergeben

### 2. `DslOperationsFacade` konsequent fixen

Bei `DslOperationsFacade` ist das Problem besonders deutlich:
- Der Service hält `gitRepository` als festes System-Repo
- Hat aber gleichzeitig schon einen `WorkspaceResolver` injiziert

**Regel:**
- Fix hier **konsequent** — nicht einzelne Methoden umbiegen
- Sondern Repozugriff **zentral** über einen privaten Resolver-Helfer kapseln
- Alle Git-Methoden (`commitDsl`, `getDslHistory`, `merge`, `listBranches`, etc.) sollen durch den Helfer gehen

### 3. Pflicht-Test: SHARED/System-Kontext muss weiter funktionieren

In `FactoryModeRepositoryRoutingTest` bitte **zusätzlich** einen Test ergänzen:

```java
@Test
void sharedContextStillUsesSystemRepository() {
    // 1. Factory erzeugen
    // 2. System-Repo: Commit machen
    // 3. Service mit SHARED-Kontext aufrufen
    // 4. Beweisen: System-Repo Commits sind sichtbar
    // 5. Beweisen: Workspace-Repo Commits sind NICHT sichtbar
}
```

Das ist der **Gegenbeweis** der sicherstellt, dass der Fix keine Regression im Shared-Pfad verursacht.

### 4. Doku-Widerspruch wirklich auflösen

In `WORKSPACE_DESIGN.md` und `REPOSITORY_TOPOLOGY.md`:
- Nicht nur umformulieren, sondern **klar** sagen:
  - Was der Default in Produktion ist (Factory-Mode / per-workspace repos)
  - Was Legacy/Testmodus ist (shared repo + branch isolation)
  - Welche Services jetzt repo-aware sind
  - Dass alle 6 betroffenen Services den `resolveRepository()`-Pfad nutzen

### Zusammenfassung der Korrekturen

| Bereich | Was anders als im ursprünglichen Plan |
|---|---|
| `CommitIndexService.indexBranch()` | Expliziten `WorkspaceContext`-Parameter statt implizitem `resolveCurrentContext()` |
| `RepositoryStateService.getState()` | Expliziten Kontext aus Username+Workspace ableiten, nicht aus SecurityContext |
| `DslOperationsFacade` | Zentraler privater Resolver-Helfer für ALLE Git-Methoden |
| Tests | Zusätzlicher SHARED/System-Repo Sanity-Test |
| Doku | Nicht nur anpassen, sondern Widerspruch bewusst auflösen |