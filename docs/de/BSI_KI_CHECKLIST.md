# BSI-Kriterienkatalog-Checkliste für KI-Modelle

Diese Checkliste bildet die BSI-Kriterien für den Einsatz von KI-Modellen in der Bundesverwaltung auf die Taxonomy Architecture Analyzer-Implementierung ab. Sie dient als prüffähiges Dokument für BSI-Audits und interne Sicherheitsbewertungen.

---

## Inhaltsverzeichnis

1. [Modellwahl und Herkunft](#modellwahl-und-herkunft)
2. [Integrationskontrolle](#integrationskontrolle)
3. [Logging und Nachvollziehbarkeit](#logging-und-nachvollziehbarkeit)
4. [Datensouveränität](#datensouveränität)
5. [Robustheit und Ausfallsicherheit](#robustheit-und-ausfallsicherheit)
6. [Transparenz und Erklärbarkeit](#transparenz-und-erklärbarkeit)
7. [Menschliche Aufsicht](#menschliche-aufsicht)
8. [Prompt-Injection-Schutz](#prompt-injection-schutz)
9. [SBOM und Lieferkette](#sbom-und-lieferkette)
10. [Schutz vor Bias](#schutz-vor-bias)
11. [Regelmäßige Überprüfung](#regelmäßige-überprüfung)
12. [Zusammenfassung](#zusammenfassung)

---

## Modellwahl und Herkunft

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Dokumentation der eingesetzten KI-Modelle | 7 Provider dokumentiert in [AI_PROVIDERS.md](AI_PROVIDERS.md) | ✅ Erfüllt |
| Nachvollziehbare Modellherkunft | Provider-Übersicht mit Modellnamen, API-Endpunkten, Datenstandorten | ✅ Erfüllt |
| Souveräner Betrieb möglich | `LOCAL_ONNX`-Modus für vollständig lokale Inferenz ohne externe API-Aufrufe | ✅ Erfüllt |
| EU-basierter Provider verfügbar | Mistral AI (Frankreich) als EU-Alternative konfigurierbar | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Supported LLM Providers](AI_TRANSPARENCY.md#supported-llm-providers)

---

## Integrationskontrolle

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Prompt-Templates administrierbar | Admin-kontrollierte Prompt-Templates über Admin-Panel | ✅ Erfüllt |
| Eingabegrößen begrenzt | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` (Standard: 5000 Zeichen) | ✅ Erfüllt |
| Kein autonomes Anwenden von KI-Ergebnissen | Alle KI-Ausgaben erfordern menschliche Überprüfung (Accept/Reject-Workflow) | ✅ Erfüllt |
| Provider-Auswahl kontrollierbar | `LLM_PROVIDER`-Umgebungsvariable; Per-Request-Override möglich | ✅ Erfüllt |
| Rate-Limiting konfigurierbar | `TAXONOMY_LLM_RPM` zur Provider-Quota-Anpassung | ✅ Erfüllt |

**Nachweis:** Siehe [Configuration Reference](CONFIGURATION_REFERENCE.md) und [Security](SECURITY.md)

---

## Logging und Nachvollziehbarkeit

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Protokollierung der KI-Nutzung | LLM Communication Log im Admin-Panel (Prompts, Antworten, Zeitstempel, Token-Counts) | ✅ Erfüllt |
| Audit-Logging für Sicherheitsereignisse | `TAXONOMY_AUDIT_LOGGING=true` loggt Login, User-Management, Systemereignisse | ✅ Erfüllt |
| Nachvollziehbarkeit der Analyseergebnisse | Explanation Traces: Score + Justification für jeden Taxonomie-Knoten | ✅ Erfüllt |
| Versionierung von Architekturentscheidungen | JGit-basierte Versionskontrolle mit Commit-Autor-Attribution | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Diagnostics and Monitoring](AI_TRANSPARENCY.md#diagnostics-and-monitoring) und [Security — Audit Logging](SECURITY.md#audit-logging)

---

## Datensouveränität

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Air-Gapped-Betrieb möglich | `LLM_PROVIDER=LOCAL_ONNX` + vorgeladene Embedding-Modelle | ✅ Erfüllt |
| Keine Datenübertragung in Drittstaaten erforderlich | LOCAL_ONNX (lokal) oder Mistral (EU/Frankreich) konfigurierbar | ✅ Erfüllt |
| Datenresidenz kontrollierbar | On-Premises-Deployment; alle Daten in lokaler Datenbank und JGit-Repository | ✅ Erfüllt |
| Kein Training mit Kundendaten | Kein eigenes Modelltraining; Prompts enthalten keine personenbezogenen Daten | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Configuration for Government Use](AI_TRANSPARENCY.md#configuration-for-government-use) und [Data Protection](DATA_PROTECTION.md)

---

## Robustheit und Ausfallsicherheit

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Anwendung funktioniert ohne KI | Browse-, Such- und Export-Funktionalität ohne LLM verfügbar | ✅ Erfüllt |
| Graceful Degradation | AI-Status-Indikator (🟢 Connected / 🟡 Degraded / 🔴 Unavailable); Fehler werden abgefangen | ✅ Erfüllt |
| Rate-Limiting | Provider-spezifisches Rate-Limiting konfigurierbar | ✅ Erfüllt |
| Fehlerbehandlung | LLM-Fehler werden protokolliert und als Benutzer-Feedback dargestellt, nicht als Systemabsturz | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Diagnostics and Monitoring](AI_TRANSPARENCY.md#diagnostics-and-monitoring)

---

## Transparenz und Erklärbarkeit

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Scoring nachvollziehbar | Score (0–100) + Justification-Text für jeden Knoten | ✅ Erfüllt |
| Modellverhalten dokumentiert | [AI_TRANSPARENCY.md](AI_TRANSPARENCY.md) beschreibt Datenflüsse, Limitationen, Risiken | ✅ Erfüllt |
| Limitationen transparent | Halluzination, Bias, Inkonsistenz als Risiken dokumentiert mit Mitigationsmaßnahmen | ✅ Erfüllt |
| Ergebnisse vergleichbar | Multi-Provider-Vergleich möglich; verschiedene Modelle für dieselbe Anforderung auswertbar | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Scoring and Explainability](AI_TRANSPARENCY.md#scoring-and-explainability)

---

## Menschliche Aufsicht

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Human-in-the-Loop-Prinzip | Kein autonomes Handeln; alle KI-Ausgaben erfordern explizite Benutzeraktion | ✅ Erfüllt |
| Accept/Reject-Workflow | Architektur-Vorschläge werden als Proposals dargestellt; Architekt akzeptiert oder verwirft | ✅ Erfüllt |
| Kein Auto-Apply | KI-Scores werden zur Überprüfung angezeigt, nicht automatisch angewendet | ✅ Erfüllt |
| Versionskontrolle | DSL-Änderungen erfordern expliziten Commit durch berechtigten Benutzer | ✅ Erfüllt |

**Nachweis:** Siehe [AI Transparency — Human Oversight](AI_TRANSPARENCY.md#human-oversight)

---

## Prompt-Injection-Schutz

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Eingabegrößenbegrenzung | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` begrenzt Eingabetextlänge | ✅ Erfüllt |
| Admin-kontrollierte Templates | Prompt-Templates nur durch ADMIN-Rolle editierbar | ✅ Erfüllt |
| Keine direkte Benutzer-Prompt-Manipulation | Benutzertext wird in strukturiertes Prompt-Template eingebettet | ✅ Erfüllt |
| Kein Code-Execution durch KI | KI-Ausgaben werden als Text interpretiert, nicht als ausführbarer Code | ✅ Erfüllt |

**Nachweis:** Siehe [Security](SECURITY.md) und [AI Transparency — Limitations and Risks](AI_TRANSPARENCY.md#limitations-and-risks)

---

## SBOM und Lieferkette

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Software Bill of Materials vorhanden | CycloneDX SBOM automatisch generiert (`mvn package`) | ✅ Erfüllt |
| Abhängigkeiten dokumentiert | `target/taxonomy-sbom.json` und `target/taxonomy-sbom.xml` mit Paketnamen, Versionen, Lizenzen, Hashes | ✅ Erfüllt |
| Drittanbieter-Transparenz | [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md) dokumentiert alle Drittanbieter-Lizenzen | ✅ Erfüllt |
| Open-Source-Lizenz | MIT-Lizenz — vollständig offener Quellcode | ✅ Erfüllt |

**Nachweis:** Siehe [Security — SBOM](SECURITY.md#sbom-software-bill-of-materials)

---

## Schutz vor Bias

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Multi-Provider-Vergleich möglich | 7 LLM-Provider ermöglichen Kreuzvalidierung von Ergebnissen | ✅ Erfüllt |
| Lokales Modell als Baseline | `LOCAL_ONNX` als Referenz-Baseline ohne Cloud-Abhängigkeit | ✅ Erfüllt |
| Bias-Dokumentation | Bias als Risiko in [AI Transparency](AI_TRANSPARENCY.md) dokumentiert | ⚠️ Empfehlung |
| Systematisches Bias-Monitoring | Noch kein automatisiertes Bias-Monitoring implementiert | ⚠️ Empfehlung |

**Empfehlung:** Regelmäßige Vergleichsanalysen zwischen verschiedenen Providern durchführen und Abweichungen dokumentieren. Ein Bias-Monitoring-Prozess sollte in die Betriebshandbücher aufgenommen werden.

---

## Regelmäßige Überprüfung

| BSI-Kriterium | Taxonomy-Umsetzung | Status |
|---|---|---|
| Bewertungsrhythmus definiert | ❌ Noch nicht formal definiert | ⚠️ Ergänzen |
| KI-Modell-Updates verfolgen | Provider-Modellversionen in Konfiguration dokumentiert | ✅ Erfüllt |
| Sicherheitsupdates | SBOM-basierte Schwachstellenprüfung; Dependabot/Renovate empfohlen | ✅ Erfüllt |

**Empfehlung:** Folgenden Bewertungsrhythmus einführen:

| Intervall | Aktivität | Verantwortlich |
|---|---|---|
| **Monatlich** | SBOM-Prüfung auf bekannte Schwachstellen | IT-Sicherheitsbeauftragter |
| **Quartalsweise** | Provider-Vergleich und Bias-Stichprobe | Fachverantwortlicher KI |
| **Halbjährlich** | Überprüfung der BSI-Checkliste gegen aktuelle BSI-Richtlinien | IT-Sicherheitsbeauftragter |
| **Jährlich** | Vollständiges KI-Audit inkl. Risikoklassifizierung | Datenschutzbeauftragter + IT-Sicherheit |

---

## Zusammenfassung

| Kriterienbereich | Erfüllt | Teilweise | Offen |
|---|:---:|:---:|:---:|
| Modellwahl und Herkunft | ✅ | | |
| Integrationskontrolle | ✅ | | |
| Logging und Nachvollziehbarkeit | ✅ | | |
| Datensouveränität | ✅ | | |
| Robustheit und Ausfallsicherheit | ✅ | | |
| Transparenz und Erklärbarkeit | ✅ | | |
| Menschliche Aufsicht | ✅ | | |
| Prompt-Injection-Schutz | ✅ | | |
| SBOM und Lieferkette | ✅ | | |
| Schutz vor Bias | | ⚠️ | |
| Regelmäßige Überprüfung | | ⚠️ | |

**Gesamtbewertung:** 9 von 11 Kriterien vollständig erfüllt; 2 Kriterien mit konkreten Empfehlungen zur Ergänzung.

---

## Verwandte Dokumentation

- [AI Transparency](AI_TRANSPARENCY.md) — KI-Transparenz und Datenflüsse
- [AI Providers](AI_PROVIDERS.md) — LLM-Provider-Konfiguration
- [Security](SECURITY.md) — Sicherheitsarchitektur
- [Data Protection](DATA_PROTECTION.md) — Datenschutz und DSGVO
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behördenumgebungen
- [AI Literacy Concept](AI_LITERACY_CONCEPT.md) — Schulungskonzept gemäß EU AI Act Art. 4
