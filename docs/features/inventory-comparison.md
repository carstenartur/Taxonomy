# Inventory and planning evidence comparison

Open a saved requirement analysis on its requirement page or in the Projects
snapshot history. **Compare inventory and planning** works with selected scored
nodes from the exact recorded catalogue tree. Architecture elements and generated
relationships are optional. The comparison does not rerun or rescale analysis.

Select the recorded nodes, using organization, optional environment and a time
question: exact date, start window, or continuous period. End dates are exclusive.
Curated sourced products work without vendor credentials. Add an explicit
availability, capability, support, organization or environment assertion to an
existing product, with citation, planned/declared/observed kind and date boundaries.
An assertion accepts `YYYY` or `YYYY-MM-DD` and retains that precision. Missing
boundaries remain unknown; product ACTIVE and verification dates do not establish
organizational availability. End of support concerns product support only.

Each candidate has separate criterion findings: SUPPORTED, CONTRADICTED, UNKNOWN,
CONFLICT, or UNEXAMINED. A partial start-window overlap remains unknown. A documented
start/end that prevents continuous coverage contradicts that period. Relevant
positive and negative evidence remains a conflict. Hard contradictions/conflicts
prevent selection regardless of any taxonomy coverage percentage.

Review requires a reason. An optional existing project solution and an explicit
reviewed coverage percentage apply the candidate and requirement link through the
existing portfolio services, with the exact saved snapshot and comparison evidence
reference. This does not mark implementation, satisfaction or procurement. Source
objects retain their identities. An external object must first be explicitly mapped
to an existing sourced product, with rationale and a frozen target-product version.
Names never establish this identity. Start a new comparison to use the mapping or
new assertions. Mapped aliases share admissible evidence without losing source rows.

Retained comparisons, their reviews and JSON downloads are local reads. They do not
refresh a vendor source. Current grants are checked again; changed demand, catalogue,
assertions, profile, plugin artifact or locally known source observations require a
new comparison before applying a review. Identical review IDs/payloads replay once;
a changed reused ID conflicts. Retained hard exclusions are outside browser-editable
candidate fields.

## Directed relationship contracts

Add an explicit relationship requirement with its own ID, an exact source candidate,
an exact target candidate, kind and optional qualifier. Both endpoints must occur in
this retained comparison. The supported kinds are `INTERFACE`, `DEPENDENCY`,
`ALTERNATIVE` and `HARD_EXCLUSION`. Direction and qualifier are part of the contract:
evidence for A → B does not establish B → A, and evidence for one interface qualifier
does not establish another. Names or similar catalogue entries do not establish
identity.

The relationship finding retains the exact supporting or contradicting assertion
IDs, their declared/planned/observed status and temporal limitations. Missing edges,
missing date boundaries and expired positive claims remain unknown; they do not
prove that a dependency is absent. A required contradictory relationship blocks
selection. An informational relationship stays visible without becoming a required
condition. For `HARD_EXCLUSION`, positive evidence records that the pair is excluded;
explicit admissible negative evidence records that this exclusion does not apply.
No relationship requirement means that no relationship satisfaction is inferred.

## Compare existing solution variants

Choose existing project solutions and record the action under consideration, such
as reuse, change, procurement, an organizational measure or replacement. This creates
a retained assessment of the existing domain objects; it does not create substitute
solutions, catalogue products or a separate planning workspace. Each assessment
freezes the project-solution and solution IDs, their semantic fingerprint and the
existing solution view, including coverage, product candidates and requirement links.

Selected candidate IDs supply **additional evidence**. They do not change the
solution's product membership or remove unselected product candidates. Confirmed
solution coverage remains part of the assessment even when an additional product has
positive coverage. In particular, confirmed zero coverage cannot disappear by adding
a positive candidate. Retained exclusions on the solution and on all its existing
product candidates remain binding. Semantic coverage alone never establishes dated
organizational availability; unanswered availability questions remain open.

Prerequisites and risks require a citation and an explicit status: `OPEN`,
`SATISFIED` or `BLOCKING`. Blocking notes prevent selection; open notes remain visible
as unresolved questions. An assessment accepts up to 20 prerequisites, 20 risks and
20 cost assumptions. Cost assumptions retain their currency, basis and citation
separately. The amount is an exact **decimal string**, with at most 18 significant
digits and four decimal places. For example, `"1234567890123456.78"` remains exactly
that value through input, storage and JSON export. The comparison does not combine
incompatible cost bases or invent a price, total or ranking.

Apply a reasoned `EVALUATED`, `SELECTED` or `REJECTED` decision to the existing project
solution, with an explicit reviewed coverage percentage and the exact saved
requirement snapshot link. Selection requires acknowledgement of unresolved
questions and is prohibited while a hard exclusion remains. Evaluation and rejection
can retain those exclusions. Selection does not mean implementation, satisfaction
or completed procurement. Reload the saved comparison to see the same frozen
assessment and its retained review.

## Immutable history and current authority

Comparison evidence and review reasons are immutable historical records. Reopening
them does not refresh an external source or replace a retained finding. A new review
application still requires current source authorization, the approved profile and
plugin artifact, the latest locally retained successful observation, and current
identity-mapping authority. Every external candidate retains the complete mapping
state fingerprint, including a previously empty mapping set. A replacement mapping
between comparison and review requires a new comparison; an old positive finding
cannot silently authorize a decision under a different mapping.

Full review rationales and all blocking-note reasons remain in the immutable
records. If the provenance prefix plus rationale would exceed the existing mutable
portfolio field limit, that field stores the exact comparison/snapshot/review
reference and an explicit pointer to the full retained rationale. Long exclusion
lists likewise retain their full reasons in the comparison and use an explicit
comparison/fingerprint pointer in the bounded portfolio exclusion field. The pointer
keeps the exclusion active; it is not a loss of the underlying reasons.

Ordinary solution/product edits cannot clear retained exclusion authority to select
or implement a blocked item. Git materialization preserves an existing exclusion
together with the comparison ID and fingerprint that established it. Retaining
reasons from comparison A while replacing their provenance with unrelated comparison
B is not permitted.

## Optional operator-approved sources

The server binds `taxonomy.inventory.profiles.<profile-id>` to an exact repository,
organization, approved principals and a nonempty selected-ID read selector. Each
profile declares a revision, extension ID, administrative instance ID, trusted HTTPS
origin, type projections, budgets and adapter-specific nonsecret source options.
`credential-environment` and optional `principal-environment` are environment variable
references; their values are never persisted or exported. No configured authorized
profile means no outbound call. Select at most three sources, with combined budgets
at most 100 requests, 10,000 objects, 16 MiB and 120 seconds; individual adapters may
be stricter. Candidate results are bounded to 100.

Optional `assertions` projection rules explicitly name the neutral criterion,
positive boolean field, start/end fields, organization/environment fields,
optional node field and assertion kind. Unmapped raw fields stay evidence and do
not become positive suitability findings. Unknown types, missing/null/unsupported
fields and non-atomic limitations stay visible. Errors publish a failed outcome
and preserve prior successful evidence; no failed refresh becomes an empty success.

Optional `relationships` rules map an exact source relation meaning to an approved
relationship kind, qualifier and assertion kind. Only edges with both exact endpoints
in the returned selected objects become directed assertions. Unknown meanings and
missing endpoints do not create claims. Raw observation time does not manufacture a
validity interval; missing dates remain unknown for a dated demand.

Source reads use the installed extension's lease and retain its exact artifact
identity. Operator profiles/grants and credential environment references are not
backup data. The interop backup owner captures typed assertions, observations,
comparisons, mappings and reviews; portfolio captures immutable selection evidence
references. Restored evidence never activates a source grant or a remote read.
The application's existing closed restore-owner matrix remains authoritative.

No real vendor acceptance is claimed by deterministic fixtures. A live installation
still requires its approved configuration and multi-page, permission-denied and
failed-refresh acceptance with its actual vendor data.

### Review input fence and retained evidence limits

Review locks the existing repository owner first, then the inventory-comparison
scope anchor, requested comparison, project, solution definitions and products.
Multiple solution/product locks use sorted IDs. Comparison creation, mapping and
observation retention take the same repository fence in their transaction. It also
covers a source writer that starts before the first comparison exists: a later
review cannot apply stale source authority while that writer commits a new observation.
The owner fence performs an actual row write without changing the repository metadata
version, so detached metadata retained by Git remains valid. The scope anchor is shared
across projects in the same exact
repository/scope/branch, so a mapping replacement in another project cannot commit
between evidence validation and decision application. Mapping and observation writers
take the same scope anchor before writing. Assertion writers use the existing product
fence; they never acquire a repository or scope fence after a product lock.

The scope anchor is an existing comparison row, not a second guard store or an
invented evidence record. Every row has a distinct non-null technical guard key;
one real row is lazily promoted to the scope key before any portfolio lock. Only the
technical guard and row version change. The business comparison JSON and fingerprint
remain immutable. The version increment takes an actual write fence under MVCC.

Solution-definition/coverage writers observe the solution fence. Project solution,
requirement-link and product-membership writers observe the project fence; product
and assertion writers observe the product fence. Git materialization takes the
repository owner fence, then sorted projects, solutions and products at its outer entry, before importing project
blocks or restoring current versions. It does not acquire the scope anchor afterward.
An earlier committed input change invalidates the frozen fingerprint. Concurrent
changes either follow the reviewed transaction or fail with a conflict; they never
rewrite the historical decision.

At most 1,000 immutable assertions per scoped product and 100 mappings per external
candidate can be evaluated. The journal reads one extra row and fails closed on
overflow, so a truncated evidence subset cannot authorize comparison or review.
Older evaluator versions require a new comparison for a new decision; an identical
retained review still replays without reapplication. Pre-Hibernate migrations backfill
technical keys/version fields for existing databases, including the shipped
Flyway-disabled compatibility profiles. These coordination fields do not grant
restored evidence active authority.

## Reviewed implementation dependencies

The inventory comparison uses the existing module boundaries. Its application
adapter implements the neutral `InventoryEvidencePort`; portfolio does not acquire
a dependency on the interop implementation. The adapter delegates persistence and
source reads to their existing interop owners. Backup selection uses the same
canonical tenant identity and scope helpers as the other feature backup owners.

The architecture dependency baseline records these reviewed class pairs:

| Source | Cross-context dependencies | Purpose |
|---|---|---|
| `InventoryEvidenceAdapter` | `IntegrationJson`, `IntegrationProblem`, `InventoryJournal`, `InventorySourceHost` | Compose neutral evidence operations, deterministic fingerprints and typed failures with their interop owners. |
| `IntegrationBackupContributor` | `BackupRowScope`, `RepositoryTenantIdentity` | Restrict inventory rows to the authorized backup selection and validate repository/branch ownership. |
| `InventoryJournal` | `RepositoryMutationFence` | Coordinate observation retention and review through the existing repository owner, including before the first comparison exists. |
| `InventoryComparisonController` | `WorkspaceResolver` | Resolve the authenticated request identity and pass its workspace context into the portfolio service. |
| `InventoryComparisonService` | `WorkspaceContext` | Preserve the explicit workspace identity through comparison, review and application. |
| `PortfolioDecisionGitContributor` | `RepositoryMutationFence`, `WorkspaceContext` | Acquire the same repository owner before portfolio writes and preserve explicit workspace identity during Git materialization. |

`ArchitectureContextDependencyRatchetTest` checks these cross-context relationships
and the package dependency inventory. Structural changes must be reviewed against
that gate; this documentation does not substitute for a fresh result.


## Deutsch: Bestandsbelege, Beziehungen und Lösungsvarianten

Öffnen Sie eine gespeicherte Anforderungsanalyse auf der Anforderungsseite oder in
der Snapshot-Historie des Projekts. Der Bestandsvergleich verwendet die ausdrücklich
ausgewählten, im konkreten Snapshot gespeicherten Katalogknoten. Er führt keine neue
Analyse aus und verändert keine ursprünglichen Bewertungen. Organisation, optionale
Umgebung und Zeitfrage gehören zum Vergleich. Das Ende eines Zeitraums ist exklusiv.
Fehlende Angaben bleiben `UNKNOWN`; Produktstatus und Prüftermine beweisen keine
organisatorische Verfügbarkeit.

### Gerichtete Beziehungen

Eine Beziehungsanforderung benennt ihre eigene ID, den genauen Ausgangs- und
Zielkandidaten, den Typ und gegebenenfalls einen Qualifikator. Beide Kandidaten müssen
im gespeicherten Vergleich vorkommen. Unterstützt werden Schnittstelle (`INTERFACE`),
Abhängigkeit (`DEPENDENCY`), Alternative (`ALTERNATIVE`) und harter Ausschluss
(`HARD_EXCLUSION`). Richtung und Qualifikator müssen zur Evidenz passen. Ein Nachweis
für A → B erfüllt keine Anforderung für B → A oder einen anderen Schnittstellenvertrag.

Die Ergebnisse behalten Beleg-IDs, Quellenart, zeitliche Grenzen und offene Fragen.
Eine fehlende Kante oder ein abgelaufener positiver Beleg beweist keine negative
Beziehung. Fehlende Datumsgrenzen bleiben offen. Eine widersprochene erforderliche
Beziehung verhindert die Auswahl; eine nur informative Beziehung wird weiterhin
angezeigt. Bei einem harten Ausschluss bestätigt positive Evidenz das Verbot des
Paars. Ohne expliziten Vertrag entsteht keine angenommene Beziehungserfüllung.

Quellprofile dürfen genaue Beziehungsbedeutungen ausdrücklich auf Typ, Qualifikator
und Belegart abbilden. Unbekannte Bedeutungen oder fehlende Endpunkte erzeugen keine
Behauptung. Der Zeitpunkt des Abrufs wird nicht zu einem erfundenen Gültigkeitszeitraum.

### Vorhandene Lösungen vergleichen und bewusst entscheiden

Wählen Sie vorhandene Projektlösungen und die betrachtete Maßnahme, etwa
Wiederverwendung, Änderung, Beschaffung, eine organisatorische Maßnahme oder Ersatz.
Der Vergleich friert die tatsächlichen Projektlösungs-/Lösungs-IDs, den Fingerprint
und die vorhandene Sicht mit Abdeckung, Produktkandidaten und Anforderungslinks ein.

Zusätzlich ausgewählte Kandidaten sind **ergänzende Evidenz**. Sie verändern keine
Produktmitgliedschaft der Lösung und entfernen keine abgewählten Produktkandidaten.
Vorhandene bestätigte Lösungsabdeckung bleibt erhalten. Insbesondere verschwindet
eine bestätigte Nullabdeckung nicht durch einen zusätzlich positiv bewerteten
Kandidaten. Gespeicherte harte Ausschlüsse der Lösung und sämtlicher vorhandener
Produktkandidaten bleiben verbindlich. Fachliche Abdeckung allein bestätigt keine
zeitlich belegte Verfügbarkeit.

Voraussetzungen und Risiken benötigen eine Quellenangabe und den bewussten Status
`OPEN`, `SATISFIED` oder `BLOCKING`. Blockierende Notizen verhindern die Auswahl;
offene Punkte bleiben als Fragen sichtbar. Je Variante sind bis zu 20 Voraussetzungen,
20 Risiken und 20 Kostenannahmen möglich. Jede Kostenannahme behält Währung,
Bezugsgrundlage und Quelle. Der Betrag wird als exakter Dezimalstring mit höchstens
18 signifikanten Ziffern und vier Nachkommastellen übertragen und gespeichert, etwa
`"1234567890123456.78"`. Es entstehen keine erfundenen Preise oder Summen aus
unterschiedlichen Kostenbasen.

Eine begründete Entscheidung setzt die vorhandene Projektlösung auf `EVALUATED`,
`SELECTED` oder `REJECTED` und verknüpft die ausdrücklich überprüfte Abdeckung mit dem
genauen Anforderungs-Snapshot. Eine Auswahl verlangt die bewusste Bestätigung offener
Fragen und ist bei harten Ausschlüssen gesperrt. Bewertung und Ablehnung können die
Ausschlüsse dokumentieren. Auswahl bedeutet keine Umsetzung oder abgeschlossene
Beschaffung. Nach erneutem Öffnen stehen derselbe Vergleich und dieselbe gespeicherte
Entscheidung zur Verfügung.

### Historische Evidenz und aktuelle Entscheidungsbefugnis

Vergleich und Entscheidungsbegründung bleiben unveränderliche Historie. Ihr erneutes
Öffnen löst keinen Quellenabruf aus. Vor einer neuen Anwendung werden jedoch aktuelle
Quellenberechtigung, Profilrevision, Plugin-Artefakt, der letzte lokal gespeicherte
erfolgreiche Abruf und die Identitätszuordnungen erneut geprüft. Auch eine zuvor
leere Zuordnungsmenge wird eingefroren. Eine ersetzende Zuordnung zwischen Vergleich
und Entscheidung verlangt einen neuen Vergleich; alte positive Ergebnisse dürfen
keine unbemerkte neue Zuordnungsbefugnis erhalten. Namen allein begründen keine
Produktidentität.

Die vollständige Begründung und sämtliche Ausschlussgründe bleiben in den
unveränderlichen Datensätzen. Würden Referenz und Text die vorhandenen veränderlichen
Portfoliofelder überschreiten, steht dort ein ausdrücklicher Verweis mit den genauen
Vergleichs-/Snapshot-/Review-IDs beziehungsweise dem Fingerprint. Der vollständige
Text bleibt abrufbar und der Ausschluss weiterhin wirksam. Gewöhnliche Änderungen
oder Git-Materialisierung dürfen ihn nicht entfernen, um einen gesperrten Eintrag
auszuwählen oder als umgesetzt zu markieren. Bei Git bleibt ein erhaltener Ausschluss
mit genau der Vergleichs-ID und dem Fingerprint seiner ursprünglichen Begründung
verbunden.

Technisch beginnt die Entscheidungsanwendung beim vorhandenen Repository-Datensatz,
danach folgt der Vergleichsanker für Repository, Bereich und Branch. Die gemeinsame
Repository-Sperre umfasst auch Vergleichserzeugung, Zuordnung und Abrufspeicherung.
Sie schützt bereits den Zeitraum vor dem ersten Vergleich: Ein begonnener Abrufschreiber
kann keine neuere Beobachtung zwischen Quellenprüfung und Entscheidungsanwendung
einschieben. Die Sperre verändert die fachliche Repository-Metadatenversion nicht.
Danach folgen Vergleich, Projekt, Lösungen und
Produkte; mehrere gleichartige Aggregate werden nach ID geordnet gesperrt.
Zuordnungs- und Abrufschreiber verwenden denselben Anker. Git sperrt bereits am
äußeren Einstieg den Repository-Datensatz, Projekte, Lösungen und Produkte in dieser Reihenfolge und nimmt
danach keinen Vergleichsanker hinzu. Nur technische Sperr-/Versionsfelder ändern
sich; fachliches Vergleichs-JSON und Fingerprints bleiben unverändert. Die vorhandenen
Schema- und Backup-Verantwortlichen behalten diese Daten. Wiederhergestellte Evidenz
erfordert erneut Berechtigung und Prüfung; sie aktiviert keine Quellenzugänge.
