# UI-Lückenanalyse

Status der GUI-Dialoge und deren Implementierung.

## Implementierte Dialoge

| Dialog | Status | Phase | Dateien |
|---|---|---|---|
| Merge-Vorschau-Modal | ✅ Implementiert | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Cherry-Pick-Vorschau-Modal | ✅ Implementiert | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Merge-Konfliktlösungs-Modal | ✅ Implementiert | 1.1 | `index.html`, `taxonomy-merge-resolution.js` |
| Cherry-Pick-Konfliktdialog | ✅ Implementiert | 1.2 | `index.html`, `taxonomy-merge-resolution.js` |
| Sync-Divergenz-Auflösungs-Modal | ✅ Implementiert | 1.3 | `index.html`, `taxonomy-merge-resolution.js` |
| Branch-Löschung (mit Bestätigung) | ✅ Implementiert | 1.4 | `taxonomy-variants.js` |
| Operationsergebnis-Toasts | ✅ Implementiert | 1.5 | `taxonomy-operation-result.js` |
| Varianten-Erstellungs-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-bar.js` |
| Kontext-Vergleichs-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-compare.js` |
| Kontext-Transfer-Modal | ✅ Implementiert | Vorbestehend | `index.html`, `taxonomy-context-transfer.js` |

## Backend-Endpunkte

| Endpunkt | Status | Beschreibung |
|---|---|---|
| `GET /api/dsl/merge/preview` | ✅ Vorbestehend | Merge-Ergebnis in der Vorschau anzeigen |
| `GET /api/dsl/cherry-pick/preview` | ✅ Vorbestehend | Cherry-Pick-Ergebnis in der Vorschau anzeigen |
| `GET /api/dsl/merge/conflicts` | ✅ Implementiert | Details zu Merge-Konflikten |
| `POST /api/dsl/merge/resolve` | ✅ Implementiert | Aufgelösten Merge-Inhalt committen |
| `GET /api/dsl/cherry-pick/conflicts` | ✅ Implementiert | Details zu Cherry-Pick-Konflikten |
| `POST /api/dsl/cherry-pick/resolve` | ✅ Implementiert | Aufgelösten Cherry-Pick-Inhalt committen |
| `DELETE /api/dsl/branch` | ✅ Implementiert | Branch löschen (mit Schutz) |
| `POST /api/workspace/resolve-diverged` | ✅ Implementiert | Divergierten Sync-Status auflösen |

## JavaScript-Module

| Modul | Zeilen | Zweck |
|---|---|---|
| `taxonomy-merge-resolution.js` | Neu | Konfliktlösungs-UI, Auflösung divergierter Zustände |
| `taxonomy-operation-result.js` | Neu | Toast-Benachrichtigungssystem für Git-Operationen |
| `taxonomy-action-guards.js` | Aktualisiert | Bootstrap-Modale ersetzen `alert()`/`confirm()` |
| `taxonomy-variants.js` | Aktualisiert | Lösch-Button für nicht geschützte Branches hinzugefügt |
| `taxonomy-workspace-sync.js` | Aktualisiert | Auflösen-Button für DIVERGED-Status, Toast-Benachrichtigungen |
| `taxonomy-dsl-editor.js` | Aktualisiert | Toast-Benachrichtigungen für Merge-/Cherry-Pick-Ergebnisse |
