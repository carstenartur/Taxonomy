# Accessibility Concept (BITV 2.0 / WCAG 2.1)

This document describes the accessibility concept for the Taxonomy Architecture Analyzer in accordance with the **Barrierefreie-Informationstechnik-Verordnung (BITV 2.0)** and the **Web Content Accessibility Guidelines (WCAG 2.1 Level AA)**.

---

## Table of Contents

1. [Scope](#scope)
2. [Conformance Target](#conformance-target)
3. [Inventory of UI Components](#inventory-of-ui-components)
4. [Identified Areas of Action](#identified-areas-of-action)
5. [Action Plan](#action-plan)
6. [Testing Procedures](#testing-procedures)
7. [Accessibility Statement](#accessibility-statement)

---

## Scope

| Aspect | Detail |
|---|---|
| **Application** | Taxonomy Architecture Analyzer — Web application (Single-Page) |
| **Legal Basis** | BITV 2.0 (§ 1–3), based on BGG § 12a–12d |
| **Technical Standard** | WCAG 2.1 Level AA (EN 301 549 V3.2.1) |
| **Applicability** | All publicly accessible web pages and application interfaces when deployed in federal authorities |
| **Deadlines** | Existing web applications: BITV 2.0 fully applicable |

---

## Conformance Target

The conformance target is **WCAG 2.1 Level AA**, which corresponds to the BITV 2.0 standard. The four core principles:

| Principle | Description | Relevance for Taxonomy |
|---|---|---|
| **Perceivable** | Information must be presentable in different forms | High — color-coded taxonomy trees, diagrams |
| **Operable** | Navigation and operation must be possible via keyboard | High — complex tree navigation, modal dialogs |
| **Understandable** | Content and operation must be comprehensible | Medium — technical terminology, AI results |
| **Robust** | Content must be interpretable by assistive technologies | High — dynamic Bootstrap components |

---

## Inventory of UI Components

### Technology Stack

| Component | Technology | Accessibility Relevance |
|---|---|---|
| **Framework** | Bootstrap 5 | Basic ARIA support available |
| **Template Engine** | Thymeleaf (server-side rendering) | HTML structure controllable |
| **JavaScript** | Vanilla JS (~29 modules) | Dynamic content requires ARIA live regions |
| **Diagrams** | Mermaid.js (SVG rendering) | SVG requires text alternatives |
| **Icons** | Bootstrap Icons | Icon-only elements require sr-only labels |
| **Tree View** | Custom JavaScript (Taxonomy Tree) | Complex component; treeview ARIA required |

### UI Areas and Assessment

| UI Area | Description | Status |
|---|---|---|
| **Navigation** | Top navbar with dropdown menus | ⚠️ Verify keyboard navigation |
| **Analysis Panel** | Textarea + buttons for AI analysis | ⚠️ Verify label association |
| **Taxonomy Tree** | Scored tree with color coding | ❌ Color coding alone is inaccessible |
| **Architecture View** | Mermaid diagrams (SVG) | ❌ No text alternative |
| **Diff View** | Color-coded code diffs | ⚠️ Additional markers required |
| **Graph Exploration** | Visual graphs | ❌ Not accessible |
| **Admin Panel** | 🔒 Emoji as interactive element | ❌ Not accessible |
| **Modal Dialogs** | Bootstrap Modals | ⚠️ Verify focus management |
| **Toasts/Notifications** | Bootstrap Toasts | ⚠️ Verify ARIA live regions |

---

## Identified Areas of Action

### Priority High 🔴

| # | Area of Action | Affected WCAG Criteria | Description |
|---|---|---|---|
| **A1** | Scored Taxonomy Tree — Color Coding | 1.4.1 (Use of Color), 1.1.1 (Non-text Content) | Color coding of scores (red/yellow/green) is the only source of information; screen readers receive no score information |
| **A2** | Keyboard Navigation | 2.1.1 (Keyboard), 2.4.3 (Focus Order), 2.4.7 (Focus Visible) | Ensure tab order, skip links, and focus indicators for all interactive elements |
| **A3** | Form Labels | 1.3.1 (Info and Relationships), 3.3.2 (Labels or Instructions) | Provide all form fields (analysis textarea, search fields, login) with associated `<label for="">` elements |
| **A4** | Admin Panel Lock Button | 2.5.3 (Label in Name), 1.1.1 (Non-text Content) | Replace 🔒 emoji as interactive element with an accessible button with a text label |
| **A5** | Architecture View (Mermaid) | 1.1.1 (Non-text Content) | SVG diagrams without text alternative; provide alt texts or tabular alternative |

### Priority Medium 🟡

| # | Area of Action | Affected WCAG Criteria | Description |
|---|---|---|---|
| **A6** | Diff View | 1.4.1 (Use of Color) | Supplement color-coded diffs (green/red) with additional symbols (+/−/~) and screen reader labels |
| **A7** | Graph Exploration | 1.1.1 (Non-text Content) | Supplement visual graphs with a tabular alternative view with keyboard navigation |
| **A8** | Color Contrasts | 1.4.3 (Contrast Minimum) | Perform contrast audit of all colors with axe/Lighthouse; ensure minimum contrast ratio of 4.5:1 |
| **A9** | ARIA Live Regions | 4.1.3 (Status Messages) | Mark dynamic status messages (analysis running, export completed) as ARIA live regions |
| **A10** | Modal Dialogs | 2.4.3 (Focus Order) | Ensure focus trapping in modals; restore focus on close |

---

## Action Plan

### Phase 1: Audit and Quick Wins (Weeks 1–2)

| # | Action | Effort | WCAG Criteria |
|---|---|---|---|
| M1 | Perform axe/Lighthouse audit of the main page | 2 days | All |
| M2 | Implement skip links (`<a href="#main-content">Skip to content</a>`) | 0.5 days | 2.4.1 |
| M3 | `<label for="">` association for all form fields | 1 day | 1.3.1, 3.3.2 |
| M4 | Replace 🔒 button with accessible button with text | 0.5 days | 2.5.3, 1.1.1 |
| M5 | Ensure `lang="de"` or `lang="en"` on `<html>` element | 0.5 days | 3.1.1 |

### Phase 2: Core Components (Weeks 3–6)

| # | Action | Effort | WCAG Criteria |
|---|---|---|---|
| M6 | Taxonomy tree: ARIA `treeview` role, score as text (`aria-label`) | 3 days | 1.4.1, 1.1.1, 4.1.2 |
| M7 | Taxonomy tree: Keyboard navigation (arrow keys, Enter, Space) | 2 days | 2.1.1, 2.4.3 |
| M8 | Architecture View: Tabular alternative view | 2 days | 1.1.1 |
| M9 | Diff View: Add +/−/~ symbols and `aria-label` | 1 day | 1.4.1 |
| M10 | ARIA live regions for dynamic status messages | 1 day | 4.1.3 |

### Phase 3: Refinement (Weeks 7–10)

| # | Action | Effort | WCAG Criteria |
|---|---|---|---|
| M11 | Contrast audit and color corrections | 2 days | 1.4.3 |
| M12 | Graph Exploration: Tabular alternative | 3 days | 1.1.1 |
| M13 | Improve focus management in modals | 1 day | 2.4.3 |
| M14 | Screen reader tests (NVDA, VoiceOver) | 3 days | All |
| M15 | Prepare BIK BITV conformance test | 2 days | All |

---

## Testing Procedures

### Automated Tests

| Tool | Area of Use | Frequency |
|---|---|---|
| **axe-core** | HTML structure, ARIA, contrasts, labels | On every build (CI integration recommended) |
| **Lighthouse Accessibility Audit** | Full-page assessment | Monthly / on release |
| **Pa11y** | Automated page testing | Optional, supplementary |

### Manual Tests

| Test | Description | Frequency |
|---|---|---|
| **Keyboard Navigation** | Are all functions reachable without a mouse? Is tab order logical? | On every UI release |
| **Screen Reader Test** | NVDA (Windows) / VoiceOver (macOS) / Orca (Linux) | Quarterly |
| **Zoom Test** | 200% zoom: No content clipped? | On every UI release |
| **Contrast Check** | Colour Contrast Analyser for critical colors | On color changes |

### BIK BITV Test

For deployment in federal authorities, a complete **BIK BITV test** is recommended:

| Aspect | Detail |
|---|---|
| **Testing Procedure** | BIK BITV test (92 test steps, based on EN 301 549) |
| **Conducted By** | Certified BIK testing centers |
| **Recommended Timing** | After completion of action plan phases 1–3 |
| **Result** | BITV conformance report with test protocol |

---

## Accessibility Statement

In accordance with **§ 12b BGG** (Behindertengleichstellungsgesetz — German Act on Equal Opportunities of Persons with Disabilities), an accessibility statement must be published. Template:

---

> ### Accessibility Statement
>
> **[Name of the authority]** is committed to making the Taxonomy Architecture Analyzer accessible in compliance with § 12a BGG and BITV 2.0.
>
> **Conformance status:** This application is **partially conformant** with BITV 2.0.
>
> **Non-accessible content:**
> - Color-coded taxonomy trees and architecture diagrams do not yet have complete text alternatives
> - Graph exploration features are primarily visual and do not yet offer a tabular alternative
> - Some interactive elements are not yet fully operable via keyboard
>
> **Actions:** The identified barriers will be addressed incrementally in accordance with the documented [Action Plan](#action-plan).
>
> **Feedback and contact:** If you encounter barriers when using this application, please contact **[email of the responsible office]**.
>
> **Arbitration procedure:** In case of an unsatisfactory response, you may contact the arbitration body pursuant to § 16 BGG: [Schlichtungsstelle nach dem Behindertengleichstellungsgesetz](https://www.schlichtungsstelle-bgg.de/).

---

## Related Documentation

- [User Guide](USER_GUIDE.md) — User manual
- [Security](SECURITY.md) — Security architecture
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Deployment checklist for government environments
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digital sovereignty
