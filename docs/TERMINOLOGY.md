# Neutral product and scenario terminology

Taxonomy addresses federal, state and local public authorities and enterprises.
Scenario labels describe an example or a verification method, not a restricted audience.
Use “scenario acceptance”, “architecture acceptance” or “flood-information example”;
in German use “Szenario-Abnahme”, “Architekturabnahme” or “Hochwasser-Beispiel”.

## Current naming

The former “civilian” / “zivil” prefix was a test label, not a product requirement.
Maintained guides, examples, test classes, the Maven profile, the workflow, artifact
paths and the document-check command now use neutral names. The Maven profile is
`scenario-acceptance`; the tooling command is `check-scenario-documents`.
The existing workflow job ID `scenario`, test selection, positive JUnit evidence
requirements, budgets, timeouts, permissions and provider boundary are unchanged.
The example connector name and credential-variable name in the Sparx documentation
are illustrative configuration, not built-in reserved names.

## Reference data and history

Do not rename official catalogue entries, their codes or imported user requirements.
The literal `Civilian Roles` binding for catalogue code `BR-1228` is the upstream
reference label, not a target-user claim. The original workbook and catalogue overlays
are unchanged. Authored example prose is distinct from those reference labels.

Existing Git commits, archived CI logs, checksummed evidence JSON, original screenshots
and recorded implementation reports retain their original names and identities.
Historical links to renamed files are pinned to their actual pre-rename source.
Old screenshots are shown only in the explicitly historical appendix, not as current
UI captures. Their retention does not restrict the product audience.

The maintained authored flood fixture uses the neutral `flood-information-v1` identity
and `FLOOD-001` requirement key. New runs calculate their own fixture/prompt hashes.
An old result is not relabelled as a fresh execution of this fixture. The bounded
relation-quality helper evaluates this current fixture; replaying older snapshots
requires the helper and fixture from their recorded source revision.

## Verification boundary

`ScenarioAcceptanceNamingTest` checks maintained naming and the existing acceptance
selection/document checks. It is not a filter on user inputs or reference catalogues.
The before/after naming checks do not by themselves prove a full application pass;
the normal Maven, browser, database and CI requirements still apply.
