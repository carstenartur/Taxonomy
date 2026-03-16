/**
 * taxonomy-i18n.js — Internationalization Module
 *
 * Provides a lightweight client-side i18n system with English (default) and
 * German translations for workspace/version management UI labels.
 * Language preference is persisted in localStorage.
 *
 * Usage:
 *   var t = TaxonomyI18n.t;
 *   t('context.mode.editable')  // → "Working version" (en) or "Arbeitsversion" (de)
 *
 * @module TaxonomyI18n
 */
window.TaxonomyI18n = (function () {
    'use strict';

    var STORAGE_KEY = 'taxonomy_language';
    var DEFAULT_LANG = 'en';
    var currentLang = DEFAULT_LANG;

    // ── Translation Dictionaries ────────────────────────────────────

    var translations = {
        en: {
            // ── Context Bar (AP 1) ──────────────────────────────────
            'context.mode.editable': '\uD83D\uDFE2 Working version',
            'context.mode.readonly': '\uD83D\uDFE1 View only',
            'context.mode.temporary': '\u26AA Draft',
            'context.branch.main': 'Main version',
            'context.time.just_now': 'Saved just now',
            'context.time.minutes_ago': 'Saved {0} min. ago',
            'context.time.hours_ago': 'Saved {0} hr. ago',
            'context.time.days_ago': 'Saved {0} day(s) ago',
            'context.search': 'Search: ',
            'context.dirty': '\uD83D\uDD34 Unsaved changes',
            'context.btn.back': '\u21A9 Back',
            'context.btn.back.title': 'Return to previous context',
            'context.btn.origin': '\uD83C\uDFE0 To origin',
            'context.btn.origin.title': 'Return to origin',
            'context.btn.transfer': '\uD83D\uDCE4 Copy back',
            'context.btn.transfer.title': 'Copy elements back to your working version',
            'context.btn.variant': '\uD83C\uDF3F New variant',
            'context.btn.variant.title': 'Create new variant',
            'context.btn.compare': '\uD83D\uDD0D Compare',
            'context.btn.compare.title': 'Compare versions',
            'context.variant.error_empty': 'Please enter a variant name.',
            'context.variant.error': 'Error: ',
            'context.compare.loading': 'Compare feature is loading\u2026',

            // ── Sync in Context Bar (AP 6) ───────────────────────────
            'sync.bar.changes_to_share': '\uD83D\uDD04 {0} change(s) to share',
            'sync.bar.changes_to_share.title': 'Publish now',
            'sync.bar.updates_available': '\u2B07 Updates available',
            'sync.bar.updates_available.title': 'Update now',
            'sync.bar.diverged': '\u26A0 Versions diverged',

            // ── Variants Browser (AP 2) ──────────────────────────────
            'variants.loading': 'Loading variants\u2026',
            'variants.unavailable': 'No variant information available.',
            'variants.load_error': 'Failed to load variants: ',
            'variants.empty.icon': '\uD83C\uDF3F',
            'variants.empty.title': 'No variants available',
            'variants.empty.description': 'Variants enable parallel work on different architecture drafts without affecting the main version.',
            'variants.empty.create': '\uD83C\uDF3F Create first variant',
            'variants.badge.active': '\u2713 Active',
            'variants.badge.main': '\uD83C\uDFE0 Main',
            'variants.meta.main': 'Main version \u2014 Reference for all variants',
            'variants.meta.variant': 'Variant \u2014 derived from main version',
            'variants.btn.open': '\u27A1 Open',
            'variants.btn.open.title': 'Switch to this variant',
            'variants.btn.compare': '\uD83D\uDD0D Compare',
            'variants.btn.compare.title': 'Compare with current version',
            'variants.btn.integrate': '\uD83D\uDD00 Integrate',
            'variants.btn.integrate.title': 'Integrate changes from this variant into the current version',
            'variants.btn.delete': '\uD83D\uDDD1 Delete',
            'variants.btn.delete.title': 'Delete this variant',
            'variants.delete.title': 'Delete variant',
            'variants.delete.confirm': 'Really delete variant <strong>{0}</strong>?',
            'variants.delete.warning': '\u26A0 This cannot be undone.',
            'variants.delete.btn': 'Delete',
            'variants.delete.error': 'Delete failed',
            'variants.delete.not_found': 'Variant \u201E{0}\u201C not found.',
            'variants.delete.success.title': 'Variant deleted',
            'variants.delete.success.msg': 'Variant \u201E{0}\u201C has been deleted.',
            'variants.merge.title': 'Integrate',
            'variants.merge.confirm': 'Integrate changes from <strong>{0}</strong> into <strong>{1}</strong>?',
            'variants.merge.preview': '<strong>Preview:</strong> {0} change(s) will be applied',
            'variants.merge.preview_detail': '+{0} elements, \u2212{1} removed, ~{2} changed',
            'variants.merge.btn': 'Integrate',
            'variants.merge.error': 'Integration failed',
            'variants.merge.success.title': 'Successfully integrated',
            'variants.merge.success.msg': 'Changes from \u201E{0}\u201C integrated into \u201E{1}\u201C.',
            'common.cancel': 'Cancel',

            // ── Compare View (AP 3) ──────────────────────────────────
            'compare.loading': 'Comparing\u2026',
            'compare.error': 'Compare failed.',
            'compare.summary.title': 'Summary of differences:',
            'compare.stat.elements_added': '\uD83D\uDFE2 +{0} element(s) added',
            'compare.stat.elements_removed': '\uD83D\uDD34 \u2212{0} element(s) removed',
            'compare.stat.elements_changed': '\uD83D\uDFE1 ~{0} element(s) changed',
            'compare.stat.relations_added': '+{0} relation(s)',
            'compare.stat.relations_removed': '\u2212{0} relation(s)',
            'compare.stat.relations_changed': '~{0} relation(s)',
            'compare.stat.no_diff': 'No differences found',
            'compare.col.added': '\uD83D\uDFE2 Added ({0})',
            'compare.col.changed': '\uD83D\uDFE1 Changed ({0})',
            'compare.col.removed': '\uD83D\uDD34 Removed ({0})',
            'compare.col.empty.added': 'No added elements',
            'compare.col.empty.changed': 'No changed elements',
            'compare.col.empty.removed': 'No removed elements',
            'compare.raw_diff': '\u25B8 <strong>Show DSL diff</strong> (expert mode)',
            'compare.no_diff': 'No differences found.',

            // ── Versions / Timeline (AP 4) ───────────────────────────
            'versions.loading': 'Loading version history\u2026',
            'versions.empty': 'No versions found on this branch.',
            'versions.load_error': 'Failed to load history: ',
            'versions.author.unknown': 'unknown',
            'versions.message.none': 'No message',
            'versions.restore_marker': '\uD83D\uDD04 Restored',
            'versions.revert_marker': '\uD83D\uDD04 Reverted',
            'versions.btn.view': '\uD83D\uDC41 View',
            'versions.btn.view.title': 'View DSL at this version',
            'versions.btn.compare': '\uD83D\uDD0D Compare',
            'versions.btn.compare.title': 'Compare with current version',
            'versions.btn.restore': '\u21A9 Restore',
            'versions.btn.restore.title': 'Restore to this version',
            'versions.btn.revert': '\u274C Revert',
            'versions.btn.revert.title': 'Revert this change',
            'versions.btn.variant': '\uD83C\uDF3F Variant',
            'versions.btn.variant.title': 'Create variant from this version',
            'versions.compare.title': 'Compare: {0} \u2194 Current',
            'versions.compare.same': 'This is the current version \u2014 nothing to compare.',
            'versions.compare.no_diff': 'No differences found.',
            'versions.compare.added': '+{0} added',
            'versions.compare.removed': '\u2212{0} removed',
            'versions.compare.changed': '~{0} changed',
            'versions.compare.h.added': 'Added ({0})',
            'versions.compare.h.removed': 'Removed ({0})',
            'versions.compare.h.changed': 'Changed ({0})',
            'versions.restore.title': 'Restore to version',
            'versions.restore.confirm': 'Restore to version <code>{0}</code>?',
            'versions.restore.explain': 'A new version will be created containing the content of the selected version. The existing version history will be preserved.',
            'versions.restore.preview': '<strong>Preview of changes:</strong>',
            'versions.restore.preview_no_diff': 'No differences from current state.',
            'versions.restore.btn': 'Restore',
            'versions.restore.error': 'Restore failed',
            'versions.restore.success.title': 'Version restored',
            'versions.restore.success.msg': 'Version {0} has been restored.',
            'versions.revert.title': 'Revert change',
            'versions.revert.confirm': 'Revert changes from version <code>{0}</code>?',
            'versions.revert.explain': 'A new version will be created that undoes the changes of this commit. Only the changes of this single commit will be reverted.',
            'versions.revert.btn': 'Revert',
            'versions.revert.error': 'Revert failed',
            'versions.revert.success.title': 'Change reverted',
            'versions.revert.success.msg': 'Version {0} has been reverted.',
            'versions.undo.title': 'Undo last change',
            'versions.undo.confirm': 'Undo the last change on <strong>{0}</strong>?',
            'versions.undo.explain': 'The last entry will be removed from the version history.',
            'versions.undo.btn': 'Undo',
            'versions.undo.error': 'Undo failed',
            'versions.undo.info': 'Last: \u201E{0}\u201C',
            'versions.save.error_empty': 'Please enter a title.',
            'versions.save.error_no_dsl': 'No DSL content on this branch',
            'versions.save.error': 'Save failed: ',
            'versions.save.success': 'Version saved! ({0})',
            'versions.today': 'Today, ',
            'common.close': 'Close',
            'common.error': 'Error',

            // ── Workspace Sync (AP 6) ────────────────────────────────
            'sync.status.synced': 'synced',
            'sync.status.behind': 'updates available',
            'sync.status.ahead': '{0} unpublished',
            'sync.status.diverged': 'diverged',
            'sync.panel.up_to_date': 'Up to date',
            'sync.panel.behind': 'Updates from team available',
            'sync.panel.ahead': '{0} unpublished change(s)',
            'sync.panel.diverged': 'Diverged \u2014 both sides have changes',
            'sync.panel.resolve': 'Resolve\u2026',
            'sync.panel.resolve.title': 'Open resolution dialog',
            'sync.panel.last_synced': 'Last synced',
            'sync.panel.last_published': 'Last published',
            'sync.panel.synced_commit': 'Synced commit',
            'sync.panel.unpublished': 'Unpublished',
            'sync.panel.unpublished_count': '{0} change(s)',
            'sync.dirty.title': 'Workspace has unsaved changes',
            'sync.clean.title': 'Current workspace / user',
            'sync.local.title': '\uD83D\uDCC4 Local Changes',
            'sync.local.refresh': '\uD83D\uDD04 Refresh',
            'sync.local.no_changes': 'No unpublished changes. Your workspace is in sync with the shared repository.',
            'sync.local.changes': '{0} unpublished change(s) on <strong>{1}</strong>.',
            'sync.local.publish': '\uD83D\uDCE4 Publish to team',
            'sync.local.sync': '\uD83D\uDCE5 Update from team',
            'sync.sync.error': 'Sync failed',
            'sync.sync.success.title': 'Sync complete',
            'sync.sync.success.msg': 'Updated from team repository.',
            'sync.publish.error': 'Publish failed',
            'sync.publish.success.title': 'Publish complete',
            'sync.publish.success.msg': 'Changes have been shared with the team repository.'
        },

        de: {
            // ── Context Bar (AP 1) ──────────────────────────────────
            'context.mode.editable': '\uD83D\uDFE2 Arbeitsversion',
            'context.mode.readonly': '\uD83D\uDFE1 Nur ansehen',
            'context.mode.temporary': '\u26AA Entwurf',
            'context.branch.main': 'Hauptversion',
            'context.time.just_now': 'Gerade eben gespeichert',
            'context.time.minutes_ago': 'Gespeichert vor {0} Min.',
            'context.time.hours_ago': 'Gespeichert vor {0} Std.',
            'context.time.days_ago': 'Gespeichert vor {0} Tag(en)',
            'context.search': 'Suche: ',
            'context.dirty': '\uD83D\uDD34 Ungespeicherte \u00C4nderungen',
            'context.btn.back': '\u21A9 Zur\u00FCck',
            'context.btn.back.title': 'Zur\u00FCck zum vorherigen Kontext',
            'context.btn.origin': '\uD83C\uDFE0 Zum Ursprung',
            'context.btn.origin.title': 'Zur\u00FCck zum Ursprung',
            'context.btn.transfer': '\uD83D\uDCE4 Zur\u00FCckkopieren',
            'context.btn.transfer.title': 'Elemente zur\u00FCck in die Arbeitsversion kopieren',
            'context.btn.variant': '\uD83C\uDF3F Neue Variante',
            'context.btn.variant.title': 'Neue Variante erstellen',
            'context.btn.compare': '\uD83D\uDD0D Vergleichen',
            'context.btn.compare.title': 'Versionen vergleichen',
            'context.variant.error_empty': 'Bitte geben Sie einen Variantennamen ein.',
            'context.variant.error': 'Fehler: ',
            'context.compare.loading': 'Vergleichsfunktion wird geladen\u2026',

            // ── Sync in Context Bar (AP 6) ───────────────────────────
            'sync.bar.changes_to_share': '\uD83D\uDD04 {0} \u00C4nderung(en) zum Teilen',
            'sync.bar.changes_to_share.title': 'Jetzt ver\u00F6ffentlichen',
            'sync.bar.updates_available': '\u2B07 Aktualisierungen verf\u00FCgbar',
            'sync.bar.updates_available.title': 'Jetzt aktualisieren',
            'sync.bar.diverged': '\u26A0 Versionen weichen ab',

            // ── Variants Browser (AP 2) ──────────────────────────────
            'variants.loading': 'Varianten werden geladen\u2026',
            'variants.unavailable': 'Keine Varianten-Informationen verf\u00FCgbar.',
            'variants.load_error': 'Varianten konnten nicht geladen werden: ',
            'variants.empty.icon': '\uD83C\uDF3F',
            'variants.empty.title': 'Keine Varianten vorhanden',
            'variants.empty.description': 'Varianten erm\u00F6glichen paralleles Arbeiten an verschiedenen Architektur-Entw\u00FCrfen, ohne die Hauptversion zu beeinflussen.',
            'variants.empty.create': '\uD83C\uDF3F Erste Variante erstellen',
            'variants.badge.active': '\u2713 Aktiv',
            'variants.badge.main': '\uD83C\uDFE0 Haupt',
            'variants.meta.main': 'Hauptversion \u2014 Referenz f\u00FCr alle Varianten',
            'variants.meta.variant': 'Variante \u2014 abgeleitet von Hauptversion',
            'variants.btn.open': '\u27A1 \u00D6ffnen',
            'variants.btn.open.title': 'Zu dieser Variante wechseln',
            'variants.btn.compare': '\uD83D\uDD0D Vergleichen',
            'variants.btn.compare.title': 'Mit aktueller Version vergleichen',
            'variants.btn.integrate': '\uD83D\uDD00 Integrieren',
            'variants.btn.integrate.title': '\u00C4nderungen dieser Variante in die aktuelle Version \u00FCbernehmen',
            'variants.btn.delete': '\uD83D\uDDD1 L\u00F6schen',
            'variants.btn.delete.title': 'Diese Variante l\u00F6schen',
            'variants.delete.title': 'Variante l\u00F6schen',
            'variants.delete.confirm': 'Variante <strong>{0}</strong> wirklich l\u00F6schen?',
            'variants.delete.warning': '\u26A0 Dies kann nicht r\u00FCckg\u00E4ngig gemacht werden.',
            'variants.delete.btn': 'L\u00F6schen',
            'variants.delete.error': 'L\u00F6schen fehlgeschlagen',
            'variants.delete.not_found': 'Variante \u201E{0}\u201C nicht gefunden.',
            'variants.delete.success.title': 'Variante gel\u00F6scht',
            'variants.delete.success.msg': 'Variante \u201E{0}\u201C wurde gel\u00F6scht.',
            'variants.merge.title': 'Integrieren',
            'variants.merge.confirm': '\u00C4nderungen aus <strong>{0}</strong> in <strong>{1}</strong> \u00FCbernehmen?',
            'variants.merge.preview': '<strong>Vorschau:</strong> {0} \u00C4nderung(en) werden \u00FCbernommen',
            'variants.merge.preview_detail': '+{0} Elemente, \u2212{1} entfernt, ~{2} ge\u00E4ndert',
            'variants.merge.btn': 'Integrieren',
            'variants.merge.error': 'Integration fehlgeschlagen',
            'variants.merge.success.title': 'Erfolgreich integriert',
            'variants.merge.success.msg': '\u00C4nderungen aus \u201E{0}\u201C in \u201E{1}\u201C \u00FCbernommen.',
            'common.cancel': 'Abbrechen',

            // ── Compare View (AP 3) ──────────────────────────────────
            'compare.loading': 'Vergleich wird durchgef\u00FChrt\u2026',
            'compare.error': 'Vergleich fehlgeschlagen.',
            'compare.summary.title': 'Zusammenfassung der Unterschiede:',
            'compare.stat.elements_added': '\uD83D\uDFE2 +{0} Element(e) hinzugef\u00FCgt',
            'compare.stat.elements_removed': '\uD83D\uDD34 \u2212{0} Element(e) entfernt',
            'compare.stat.elements_changed': '\uD83D\uDFE1 ~{0} Element(e) ge\u00E4ndert',
            'compare.stat.relations_added': '+{0} Relation(en)',
            'compare.stat.relations_removed': '\u2212{0} Relation(en)',
            'compare.stat.relations_changed': '~{0} Relation(en)',
            'compare.stat.no_diff': 'Keine Unterschiede gefunden',
            'compare.col.added': '\uD83D\uDFE2 Hinzugef\u00FCgt ({0})',
            'compare.col.changed': '\uD83D\uDFE1 Ge\u00E4ndert ({0})',
            'compare.col.removed': '\uD83D\uDD34 Entfernt ({0})',
            'compare.col.empty.added': 'Keine hinzugef\u00FCgten Elemente',
            'compare.col.empty.changed': 'Keine ge\u00E4nderten Elemente',
            'compare.col.empty.removed': 'Keine entfernten Elemente',
            'compare.raw_diff': '\u25B8 <strong>DSL-Diff anzeigen</strong> (Expertenmodus)',
            'compare.no_diff': 'Keine Unterschiede gefunden.',

            // ── Versions / Timeline (AP 4) ───────────────────────────
            'versions.loading': 'Versionsverlauf wird geladen\u2026',
            'versions.empty': 'Keine Versionen auf diesem Zweig gefunden.',
            'versions.load_error': 'Verlauf konnte nicht geladen werden: ',
            'versions.author.unknown': 'unbekannt',
            'versions.message.none': 'Keine Nachricht',
            'versions.restore_marker': '\uD83D\uDD04 Wiederherstellung',
            'versions.revert_marker': '\uD83D\uDD04 R\u00FCckg\u00E4ngig',
            'versions.btn.view': '\uD83D\uDC41 Ansehen',
            'versions.btn.view.title': 'DSL dieser Version anzeigen',
            'versions.btn.compare': '\uD83D\uDD0D Vergleichen',
            'versions.btn.compare.title': 'Mit aktuellem Stand vergleichen',
            'versions.btn.restore': '\u21A9 Zur\u00FCcksetzen',
            'versions.btn.restore.title': 'Auf diese Version zur\u00FCcksetzen',
            'versions.btn.revert': '\u274C R\u00FCckg\u00E4ngig',
            'versions.btn.revert.title': 'Diese \u00C4nderung r\u00FCckg\u00E4ngig machen',
            'versions.btn.variant': '\uD83C\uDF3F Variante',
            'versions.btn.variant.title': 'Variante aus dieser Version erstellen',
            'versions.compare.title': 'Vergleich: {0} \u2194 Aktuell',
            'versions.compare.same': 'Dies ist die aktuelle Version \u2014 nichts zu vergleichen.',
            'versions.compare.no_diff': 'Keine Unterschiede gefunden.',
            'versions.compare.added': '+{0} hinzugef\u00FCgt',
            'versions.compare.removed': '\u2212{0} entfernt',
            'versions.compare.changed': '~{0} ge\u00E4ndert',
            'versions.compare.h.added': 'Hinzugef\u00FCgt ({0})',
            'versions.compare.h.removed': 'Entfernt ({0})',
            'versions.compare.h.changed': 'Ge\u00E4ndert ({0})',
            'versions.restore.title': 'Auf Version zur\u00FCcksetzen',
            'versions.restore.confirm': 'Auf Version <code>{0}</code> zur\u00FCcksetzen?',
            'versions.restore.explain': 'Es wird eine neue Version erstellt, die den Inhalt der ausgew\u00E4hlten Version enth\u00E4lt. Die bisherige Versionsgeschichte bleibt erhalten.',
            'versions.restore.preview': '<strong>Vorschau der \u00C4nderungen:</strong>',
            'versions.restore.preview_no_diff': 'Keine Unterschiede zum aktuellen Stand.',
            'versions.restore.btn': 'Zur\u00FCcksetzen',
            'versions.restore.error': 'Wiederherstellung fehlgeschlagen',
            'versions.restore.success.title': 'Version wiederhergestellt',
            'versions.restore.success.msg': 'Version {0} wurde wiederhergestellt.',
            'versions.revert.title': '\u00C4nderung r\u00FCckg\u00E4ngig machen',
            'versions.revert.confirm': '\u00C4nderung von Version <code>{0}</code> r\u00FCckg\u00E4ngig machen?',
            'versions.revert.explain': 'Es wird eine neue Version erstellt, die die \u00C4nderungen dieses Commits r\u00FCckg\u00E4ngig macht. Nur die \u00C4nderungen dieses einen Commits werden zur\u00FCckgenommen.',
            'versions.revert.btn': 'R\u00FCckg\u00E4ngig machen',
            'versions.revert.error': 'R\u00FCckg\u00E4ngig fehlgeschlagen',
            'versions.revert.success.title': '\u00C4nderung r\u00FCckg\u00E4ngig gemacht',
            'versions.revert.success.msg': 'Version {0} wurde r\u00FCckg\u00E4ngig gemacht.',
            'versions.undo.title': 'Letzte \u00C4nderung r\u00FCckg\u00E4ngig machen',
            'versions.undo.confirm': 'Die letzte \u00C4nderung auf <strong>{0}</strong> r\u00FCckg\u00E4ngig machen?',
            'versions.undo.explain': 'Der letzte Eintrag wird aus der Versionsgeschichte entfernt.',
            'versions.undo.btn': 'R\u00FCckg\u00E4ngig machen',
            'versions.undo.error': 'R\u00FCckg\u00E4ngig fehlgeschlagen',
            'versions.undo.info': 'Letzte: \u201E{0}\u201C',
            'versions.save.error_empty': 'Bitte geben Sie einen Titel ein.',
            'versions.save.error_no_dsl': 'Kein DSL-Inhalt auf diesem Zweig',
            'versions.save.error': 'Speichern fehlgeschlagen: ',
            'versions.save.success': 'Version gespeichert! ({0})',
            'versions.today': 'Heute, ',
            'common.close': 'Schlie\u00DFen',
            'common.error': 'Fehler',

            // ── Workspace Sync (AP 6) ────────────────────────────────
            'sync.status.synced': 'synchron',
            'sync.status.behind': 'Aktualisierungen verf\u00FCgbar',
            'sync.status.ahead': '{0} unver\u00F6ffentlicht',
            'sync.status.diverged': 'abweichend',
            'sync.panel.up_to_date': 'Auf dem neuesten Stand',
            'sync.panel.behind': 'Aktualisierungen vom Team verf\u00FCgbar',
            'sync.panel.ahead': '{0} unver\u00F6ffentlichte \u00C4nderung(en)',
            'sync.panel.diverged': 'Abweichend \u2014 beide Seiten haben \u00C4nderungen',
            'sync.panel.resolve': 'Aufl\u00F6sen\u2026',
            'sync.panel.resolve.title': 'Dialog zur Aufl\u00F6sung \u00F6ffnen',
            'sync.panel.last_synced': 'Zuletzt synchronisiert',
            'sync.panel.last_published': 'Zuletzt ver\u00F6ffentlicht',
            'sync.panel.synced_commit': 'Synchronisierter Commit',
            'sync.panel.unpublished': 'Unver\u00F6ffentlicht',
            'sync.panel.unpublished_count': '{0} \u00C4nderung(en)',
            'sync.dirty.title': 'Arbeitsbereich hat ungespeicherte \u00C4nderungen',
            'sync.clean.title': 'Aktueller Arbeitsbereich / Benutzer',
            'sync.local.title': '\uD83D\uDCC4 Lokale \u00C4nderungen',
            'sync.local.refresh': '\uD83D\uDD04 Aktualisieren',
            'sync.local.no_changes': 'Keine unver\u00F6ffentlichten \u00C4nderungen. Ihr Arbeitsbereich ist mit dem Team-Repository synchron.',
            'sync.local.changes': '{0} unver\u00F6ffentlichte \u00C4nderung(en) auf <strong>{1}</strong>.',
            'sync.local.publish': '\uD83D\uDCE4 F\u00FCr Team ver\u00F6ffentlichen',
            'sync.local.sync': '\uD83D\uDCE5 Vom Team aktualisieren',
            'sync.sync.error': 'Synchronisierung fehlgeschlagen',
            'sync.sync.success.title': 'Synchronisierung abgeschlossen',
            'sync.sync.success.msg': 'Vom Team-Repository aktualisiert.',
            'sync.publish.error': 'Ver\u00F6ffentlichung fehlgeschlagen',
            'sync.publish.success.title': 'Ver\u00F6ffentlichung abgeschlossen',
            'sync.publish.success.msg': '\u00C4nderungen wurden mit dem Team-Repository geteilt.'
        }
    };

    // ── Initialization ──────────────────────────────────────────────

    function init() {
        var stored = null;
        try {
            stored = localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            // localStorage may be unavailable
        }
        if (stored && translations[stored]) {
            currentLang = stored;
        } else {
            // Detect browser language
            var browserLang = (navigator.language || navigator.userLanguage || '').substring(0, 2).toLowerCase();
            if (translations[browserLang]) {
                currentLang = browserLang;
            } else {
                currentLang = DEFAULT_LANG;
            }
        }
    }

    // ── Translation Function ────────────────────────────────────────

    /**
     * Get a translated string by key.
     * Supports parameter substitution: {0}, {1}, {2}, etc.
     *
     * @param {string} key — translation key
     * @param {...*} args — substitution values
     * @returns {string} the translated string, or the key if not found
     */
    function t(key) {
        var dict = translations[currentLang] || translations[DEFAULT_LANG];
        var str = dict[key];
        if (str === undefined) {
            // Fallback to default language
            str = translations[DEFAULT_LANG][key];
        }
        if (str === undefined) {
            return key; // Return the key as fallback
        }
        // Parameter substitution
        var args = Array.prototype.slice.call(arguments, 1);
        if (args.length > 0) {
            for (var i = 0; i < args.length; i++) {
                str = str.replace('{' + i + '}', args[i]);
            }
        }
        return str;
    }

    /**
     * Get the current language code.
     * @returns {string} 'en' or 'de'
     */
    function getLang() {
        return currentLang;
    }

    /**
     * Set the current language and persist the choice.
     * @param {string} lang — 'en' or 'de'
     */
    function setLang(lang) {
        if (!translations[lang]) return;
        currentLang = lang;
        try {
            localStorage.setItem(STORAGE_KEY, lang);
        } catch (e) {
            // localStorage may be unavailable
        }
        // Trigger a re-render event for listening modules
        document.dispatchEvent(new CustomEvent('taxonomy-lang-changed', { detail: { lang: lang } }));
    }

    /**
     * Get the list of supported languages.
     * @returns {Array<{code: string, label: string}>}
     */
    function getSupportedLanguages() {
        return [
            { code: 'en', label: 'English' },
            { code: 'de', label: 'Deutsch' }
        ];
    }

    /**
     * Format a branch name for user display.
     * 'draft' → main version label, otherwise use the branch name.
     * @param {string} branch — branch name
     * @returns {string} display name
     */
    function formatBranch(branch) {
        if (!branch || branch === 'draft') return t('context.branch.main');
        return branch;
    }

    // Run init immediately
    init();

    return {
        t: t,
        getLang: getLang,
        setLang: setLang,
        getSupportedLanguages: getSupportedLanguages,
        formatBranch: formatBranch
    };
}());
