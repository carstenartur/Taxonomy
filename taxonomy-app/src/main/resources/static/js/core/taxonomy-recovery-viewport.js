/* Inline recovery feedback and an explicitly opened, visual-viewport-aware detail dialog. */
(function () {
    'use strict';
    function bounds(viewport, measuredHeight, focused) {
        var margin = 8;
        var width = Math.max(1, viewport.width - margin * 2);
        var height = Math.max(1, Math.min(measuredHeight, viewport.height - margin * 2));
        var left = viewport.offsetLeft + margin;
        var top = viewport.offsetTop + viewport.height - margin - height;
        if (focused && focused.bottom > top && focused.top < top + height
                && focused.right > left && focused.left < left + width) {
            top = viewport.offsetTop + margin;
        }
        return { left: left, top: top, width: width, height: height };
    }
    function mount(actions) {
        var doc = document;
        var dismissalStorage = 'taxonomy.recovery.dismissed.v1';
        var dismissedScope = null;
        var hasMessage = false, active = false;
        try { dismissedScope = sessionStorage.getItem(dismissalStorage); } catch (ignored) { /* Storage is optional. */ }
        function scopeKey() { return actions.scopeKey ? String(actions.scopeKey() || '') : 'current'; }
        function isDismissed() { return dismissedScope === scopeKey(); }
        function visibility() {
            bar.hidden = !hasMessage || !active || isDismissed() || dialog.open;
            reopen.hidden = !hasMessage;
        }
        function hideFeedback() {
            dismissedScope = scopeKey();
            try { sessionStorage.setItem(dismissalStorage, dismissedScope); } catch (ignored) { /* Retain in memory. */ }
            visibility(); schedule();
            var menu = reopen.closest('details');
            if (menu && !menu.open) menu.querySelector('summary')?.focus();
            else if (!reopen.hidden) reopen.focus();
        }
        function element(tag, text, className) {
            var e = doc.createElement(tag);
            if (text) e.textContent = text;
            if (className) e.className = className;
            return e;
        }
        function button(text, handler) {
            var b = element('button', text, 'btn btn-sm btn-outline-primary');
            b.type = 'button'; b.addEventListener('click', handler); return b;
        }
        var bar = element('aside', '', 'analysis-recovery-bar');
        bar.id = 'analysisRecoveryBar'; bar.hidden = true;
        bar.setAttribute('aria-label', actions.text('Bearbeitungsfortschritt', 'Processing progress'));
        var status = element('div'); status.id = 'analysisRecoveryStatus'; status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite'); status.setAttribute('aria-atomic', 'true');
        var detailButton = button(actions.text('Details / Fortsetzen', 'Details / Continue'), actions.open);
        detailButton.id = 'analysisRecoveryDetails';
        var cancel = button(actions.text('Lauf abbrechen', 'Cancel run'), actions.cancel);
        cancel.id = 'analysisRecoveryCancel';
        var hide = button(actions.text('Ausblenden', 'Hide'), hideFeedback);
        hide.id = 'analysisRecoveryDismiss';
        var buttons = element('div', '', 'analysis-recovery-buttons'); buttons.append(detailButton, cancel, hide);
        bar.append(status, buttons);
        var host = doc.getElementById('analysisRecoveryHost');
        if (!host) {
            host = element('div'); host.id = 'analysisRecoveryHost';
            var pane = doc.getElementById('tab-analyze') || doc.body;
            pane.prepend(host);
        }
        host.append(bar);
        var reopen = button(actions.text('Analyse-Status', 'Analysis status'), actions.open);
        reopen.id = 'analysisRecoveryReopen'; reopen.hidden = true;
        reopen.setAttribute('aria-haspopup', 'dialog');
        reopen.setAttribute('aria-controls', 'analysisRecoveryDialog');
        (doc.getElementById('analysisStatusMenuSlot') || host).append(reopen);

        var dialog = element('dialog', '', 'analysis-recovery-dialog'); dialog.id = 'analysisRecoveryDialog';
        dialog.setAttribute('aria-labelledby', 'analysisRecoveryTitle');
        var title = element('h2'); title.id = 'analysisRecoveryTitle';
        var summary = element('p'); summary.id = 'analysisRecoverySummary'; summary.setAttribute('role', 'status');
        var content = element('div', '', 'analysis-recovery-body'); content.id = 'analysisRecoveryBody';
        var footer = element('div', '', 'analysis-recovery-actions');
        var dismiss = button(actions.text('Schließen', 'Close'), function () { dialog.close(); });
        dismiss.id = 'analysisRecoveryClose';
        var header = element('div', '', 'analysis-recovery-heading'); header.append(title, dismiss);
        dialog.append(header, summary, content, footer); doc.body.append(dialog);
        var scheduled = false;
        function position() {
            scheduled = false;
            var vv = window.visualViewport;
            var viewport = { width: vv ? vv.width : window.innerWidth,
                height: vv ? vv.height : window.innerHeight,
                offsetLeft: vv ? vv.offsetLeft : 0, offsetTop: vv ? vv.offsetTop : 0 };
            var compact = viewport.height <= 300;
            dialog.dataset.compact = String(compact);
            bar.dataset.narrow = String(viewport.width <= 480);
            bar.dataset.compact = String(compact);
            var fullSummary = summary.dataset.fullMessage || '';
            var shownSummary = compact ? fullSummary.split(' · ')[0] : fullSummary;
            if (summary.textContent !== shownSummary) summary.textContent = shownSummary;
            summary.title = fullSummary;
            footer.querySelectorAll('button[data-short-label]').forEach(function (button) {
                var label = compact ? button.dataset.shortLabel : button.dataset.fullLabel;
                if (button.textContent !== label) button.textContent = label;
            });
            dialog.style.width = Math.max(1, Math.min(760, viewport.width - 16)) + 'px';
            dialog.style.maxHeight = Math.max(1, viewport.height - 16) + 'px';
            dialog.style.left = viewport.offsetLeft + 8 + 'px';
            dialog.style.top = viewport.offsetTop + 8 + 'px';
        }
        function schedule() {
            if (scheduled) return;
            scheduled = true; window.requestAnimationFrame(position);
        }
        window.addEventListener('resize', schedule);
        window.addEventListener('scroll', schedule, { passive: true });
        doc.addEventListener('focusin', schedule);
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', schedule);
            window.visualViewport.addEventListener('scroll', schedule);
        }
        if (window.ResizeObserver) new ResizeObserver(schedule).observe(bar);
        dialog.addEventListener('close', function () { visibility(); schedule(); });
        return {
            update: function (message, busy, canCancel) {
                if (status.textContent !== message) status.textContent = message;
                status.title = message;
                hasMessage = Boolean(message); active = Boolean(busy || canCancel);
                visibility();
                cancel.hidden = !canCancel; cancel.disabled = false;
                bar.setAttribute('aria-busy', busy ? 'true' : 'false');
                if (dialog.open) summary.dataset.fullMessage = message;
                schedule();
            },
            open: function (heading, message, build, options) {
                title.textContent = heading; summary.textContent = message; summary.dataset.fullMessage = message;
                content.replaceChildren(); footer.replaceChildren();
                build(content, element);
                options.forEach(function (option) {
                    var b = button(option.label, option.handler); b.id = option.id;
                    if (option.shortLabel) {
                        b.dataset.shortLabel = option.shortLabel; b.dataset.fullLabel = option.label;
                        b.setAttribute('aria-label', option.label);
                    }
                    b.disabled = Boolean(option.disabled); footer.append(b);
                });
                if (!dialog.open) dialog.showModal();
                bar.hidden = true; position();
            },
            close: function () { if (dialog.open) dialog.close(); },
            error: function (message) {
                var alert = element('p', message, 'analysis-recovery-error'); alert.setAttribute('role', 'alert');
                content.prepend(alert); schedule();
            },
            position: schedule,
            isOpen: function () { return dialog.open; },
            isDismissed: isDismissed
        };
    }
    window.TaxonomyRecoveryViewport = { bounds: bounds, mount: mount };
}());
