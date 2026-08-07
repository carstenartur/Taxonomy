#!/usr/bin/env python3
"""Apply the accessible modal and collision-safe overlay contract for issue #622."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ONBOARDING = ROOT / "taxonomy-app/src/main/resources/static/js/shared/taxonomy-onboarding.js"
ERGONOMICS = ROOT / "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css"
ROLE_FLOW = ROOT / ".github/scripts/ui-role-state-flow.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def patch_onboarding(source: str) -> str:
    source = replace_once(
        source,
        """    var STORAGE_KEY = 'taxonomy_onboarded';
    var reviewAcknowledged = false;
""",
        """    var STORAGE_KEY = 'taxonomy_onboarded';
    var reviewAcknowledged = false;
    var overlayLaneObserver = null;
    var overlayLaneRefreshFrame = null;
""",
        "overlay state",
    )

    start = source.index("    function initWelcomeOverlay() {")
    end = source.index("    function createTaskProgress() {")
    replacement = r'''    function focusableDialogElements(dialog) {
        return Array.from(dialog.querySelectorAll(
            'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), ' +
            'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )).filter(function (element) {
            return element.getClientRects().length > 0 &&
                getComputedStyle(element).visibility !== 'hidden';
        });
    }

    function trapDialogFocus(dialog, event) {
        if (event.key !== 'Tab') return;
        var focusable = focusableDialogElements(dialog);
        if (!focusable.length) {
            event.preventDefault();
            dialog.focus();
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    function initWelcomeOverlay() {
        if (localStorage.getItem(STORAGE_KEY) || document.getElementById('onboardingOverlay')) {
            return;
        }

        var returnFocus = document.activeElement instanceof HTMLElement
            ? document.activeElement : null;
        var dialog = document.createElement('dialog');
        dialog.className = 'onboarding-overlay';
        dialog.id = 'onboardingOverlay';
        dialog.setAttribute('role', 'dialog');
        dialog.setAttribute('aria-modal', 'true');
        dialog.setAttribute('aria-labelledby', 'onboardingTitle');
        dialog.setAttribute('aria-describedby', 'onboardingIntro');
        dialog.innerHTML =
            '<div class="onboarding-card">' +
            '  <h2 id="onboardingTitle">' + t('onboarding.title') + '</h2>' +
            '  <p id="onboardingIntro">' + t('onboarding.intro') + '</p>' +
            '  <div class="steps">' +
            '    <div class="step-item"><span class="step-number" aria-hidden="true">1</span><span>' + t('onboarding.step1') + '</span></div>' +
            '    <div class="step-item"><span class="step-number" aria-hidden="true">2</span><span>' + t('onboarding.step2') + '</span></div>' +
            '    <div class="step-item"><span class="step-number" aria-hidden="true">3</span><span>' + t('onboarding.step3') + '</span></div>' +
            '  </div>' +
            '  <div class="onboarding-actions">' +
            '    <button id="onboardingDismiss" type="button" class="btn btn-primary">' + t('onboarding.dismiss') + '</button>' +
            '  </div>' +
            '</div>';

        document.body.appendChild(dialog);
        var dismissBtn = document.getElementById('onboardingDismiss');
        dismissBtn.addEventListener('click', dismiss);
        dialog.addEventListener('cancel', function (event) {
            event.preventDefault();
            dismiss();
        });
        dialog.addEventListener('click', function (event) {
            if (event.target === dialog) dismiss();
        });
        dialog.addEventListener('keydown', function (event) {
            trapDialogFocus(dialog, event);
        });
        dialog.addEventListener('close', function () {
            dialog.remove();
            if (returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') {
                requestAnimationFrame(function () {
                    returnFocus.focus({ preventScroll: true });
                });
            }
        }, { once: true });

        // Native modal dialogs make the rest of the document inert, constrain
        // focus to the dialog and expose the correct platform accessibility tree.
        dialog.showModal();
        requestAnimationFrame(function () { dismissBtn.focus(); });
    }

    function dismiss() {
        localStorage.setItem(STORAGE_KEY, '1');
        var dialog = document.getElementById('onboardingOverlay');
        if (!dialog) return;
        if (dialog.open) dialog.close('dismissed');
        else dialog.remove();
    }

    function reset() {
        localStorage.removeItem(STORAGE_KEY);
    }

    function isVisibleElement(element) {
        if (!element || element.hidden || !element.isConnected) return false;
        var style = getComputedStyle(element);
        return style.display !== 'none' && style.visibility !== 'hidden' &&
            element.getClientRects().length > 0;
    }

    function rectanglesOverlap(left, right, margin) {
        var spacing = margin || 0;
        return left.left < right.right + spacing &&
            left.right > right.left - spacing &&
            left.top < right.bottom + spacing &&
            left.bottom > right.top - spacing;
    }

    function isElementUnobscured(element) {
        if (!isVisibleElement(element)) return false;
        var rect = element.getBoundingClientRect();
        var x = Math.min(window.innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
        var y = Math.min(window.innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
        var topElement = document.elementFromPoint(x, y);
        return Boolean(topElement && (topElement === element || element.contains(topElement)));
    }

    function ensureOverlayLane() {
        var lane = document.getElementById('taxonomyOverlayLane');
        if (lane) return lane;
        lane = document.createElement('div');
        lane.id = 'taxonomyOverlayLane';
        lane.className = 'taxonomy-overlay-lane';
        lane.dataset.position = 'bottom-end';
        document.body.appendChild(lane);
        return lane;
    }

    function protectedOverlayTargets(lane) {
        var targets = [
            document.activeElement,
            document.getElementById('analyzeBtn'),
            document.getElementById('taskNextAction'),
            document.querySelector('.help-back-to-top:not([hidden])')
        ];
        return Array.from(new Set(targets)).filter(function (element) {
            return element instanceof HTMLElement && !lane.contains(element) &&
                isVisibleElement(element);
        });
    }

    function refreshOverlayLane() {
        if (overlayLaneRefreshFrame !== null) {
            cancelAnimationFrame(overlayLaneRefreshFrame);
        }
        overlayLaneRefreshFrame = requestAnimationFrame(function () {
            overlayLaneRefreshFrame = null;
            var lane = document.getElementById('taxonomyOverlayLane');
            if (!lane) return;
            var visibleChildren = Array.from(lane.children).filter(isVisibleElement);
            if (!visibleChildren.length) {
                lane.dataset.position = 'bottom-end';
                return;
            }
            var targets = protectedOverlayTargets(lane);
            var positions = ['bottom-end', 'bottom-start', 'top-end', 'top-start'];
            var selected = positions[positions.length - 1];
            for (var index = 0; index < positions.length; index++) {
                lane.dataset.position = positions[index];
                var laneRect = lane.getBoundingClientRect();
                var collides = targets.some(function (target) {
                    return rectanglesOverlap(laneRect, target.getBoundingClientRect(), 8);
                });
                if (!collides) {
                    selected = positions[index];
                    break;
                }
            }
            lane.dataset.position = selected;
        });
    }

    function routeOverlayNode(node, lane) {
        if (!(node instanceof Element)) return;
        var candidates = [];
        if (node.matches('.undo-toast')) candidates.push(node);
        node.querySelectorAll('.undo-toast').forEach(function (toast) {
            candidates.push(toast);
        });
        candidates.forEach(function (toast) {
            if (toast.parentElement === lane) return;
            if (!toast.hasAttribute('role')) toast.setAttribute('role', 'status');
            toast.setAttribute('aria-live', 'polite');
            lane.appendChild(toast);
        });
    }

    function installOverlayLane() {
        var lane = ensureOverlayLane();
        document.querySelectorAll('.undo-toast').forEach(function (toast) {
            routeOverlayNode(toast, lane);
        });
        if (overlayLaneObserver) return;
        overlayLaneObserver = new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                mutation.addedNodes.forEach(function (node) {
                    routeOverlayNode(node, lane);
                });
            });
            refreshOverlayLane();
        });
        overlayLaneObserver.observe(document.body, { childList: true, subtree: true });
        window.addEventListener('resize', refreshOverlayLane);
        document.addEventListener('focusin', refreshOverlayLane);
        document.addEventListener('shown.bs.tab', refreshOverlayLane);
        refreshOverlayLane();
    }

'''
    source = source[:start] + replacement + source[end:]

    source = replace_once(
        source,
        """    function init() {
        initWelcomeOverlay();
        initTaskHierarchy();
    }
""",
        """    function init() {
        installOverlayLane();
        initWelcomeOverlay();
        initTaskHierarchy();
    }
""",
        "initialize overlay lane",
    )
    source = replace_once(
        source,
        """        reset: reset,
        syncTaskProgress: syncTaskProgress
""",
        """        reset: reset,
        syncTaskProgress: syncTaskProgress,
        refreshOverlayLane: refreshOverlayLane,
        isElementUnobscured: isElementUnobscured
""",
        "export overlay diagnostics",
    )
    return source


def patch_css(source: str) -> str:
    marker = "/* Accessible modal onboarding and collision-safe transient overlays. */"
    if marker in source:
        return source
    addition = r'''

/* Accessible modal onboarding and collision-safe transient overlays. */
dialog.onboarding-overlay {
    position: fixed;
    inset: 0;
    width: 100vw;
    max-width: none;
    height: 100dvh;
    max-height: none;
    margin: 0;
    padding: 0;
    border: 0;
    overflow: hidden;
    color: var(--bs-body-color, #212529);
    background: transparent;
    animation: none;
}
dialog.onboarding-overlay:not([open]) {
    display: none;
}
dialog.onboarding-overlay[open] {
    display: grid;
    place-items: center;
}
dialog.onboarding-overlay::backdrop {
    background: rgba(0, 0, 0, 0.62);
}
dialog.onboarding-overlay .onboarding-card {
    width: min(32.5rem, calc(100vw - 2rem));
    max-height: calc(100dvh - 2rem - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    margin: max(1rem, env(safe-area-inset-top)) max(1rem, env(safe-area-inset-right))
        max(1rem, env(safe-area-inset-bottom)) max(1rem, env(safe-area-inset-left));
    overflow: auto;
    overscroll-behavior: contain;
    text-align: left;
}
.onboarding-actions {
    display: flex;
    justify-content: flex-end;
}
#onboardingDismiss {
    min-width: 44px;
    min-height: 44px;
}

.taxonomy-overlay-lane {
    position: fixed;
    z-index: 9999;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 0.75rem;
    width: min(26rem, calc(100vw - 2rem - env(safe-area-inset-left) - env(safe-area-inset-right)));
    max-height: calc(100dvh - 2rem - env(safe-area-inset-top) - env(safe-area-inset-bottom));
    pointer-events: none;
    inset-block-end: max(1rem, env(safe-area-inset-bottom));
    inset-inline-end: max(1rem, env(safe-area-inset-right));
}
.taxonomy-overlay-lane[data-position="bottom-start"] {
    align-items: flex-start;
    inset-inline-start: max(1rem, env(safe-area-inset-left));
    inset-inline-end: auto;
}
.taxonomy-overlay-lane[data-position="top-end"] {
    inset-block-start: max(1rem, env(safe-area-inset-top));
    inset-block-end: auto;
}
.taxonomy-overlay-lane[data-position="top-start"] {
    align-items: flex-start;
    inset-block-start: max(1rem, env(safe-area-inset-top));
    inset-block-end: auto;
    inset-inline-start: max(1rem, env(safe-area-inset-left));
    inset-inline-end: auto;
}
.taxonomy-overlay-lane > .undo-toast {
    position: static !important;
    inset: auto !important;
    max-width: 100%;
    margin: 0;
    pointer-events: auto;
}
.undo-toast .undo-btn,
.help-back-to-top {
    min-width: 44px !important;
    min-height: 44px !important;
}
.help-back-to-top {
    width: 44px !important;
    height: 44px !important;
}

@media (max-height: 32rem), (max-width: 30rem) {
    dialog.onboarding-overlay .onboarding-card {
        width: calc(100vw - 1rem);
        max-height: calc(100dvh - 1rem - env(safe-area-inset-top) - env(safe-area-inset-bottom));
        margin: 0.5rem;
        padding: 1rem;
    }
    .taxonomy-overlay-lane {
        width: calc(100vw - 1rem - env(safe-area-inset-left) - env(safe-area-inset-right));
        inset-block-end: max(0.5rem, env(safe-area-inset-bottom));
        inset-inline-end: max(0.5rem, env(safe-area-inset-right));
    }
}
'''
    return source.rstrip() + addition + "\n"


def patch_role_flow(source: str) -> str:
    old = """  assert(await page.evaluate(() => document.activeElement?.id === 'businessText'),
    'Dialog did not restore focus to its invoker');
  passed('dialog focus entry and restoration');

  const operationalWasOpen = await page.locator('#operationalContextDetails').evaluate(el => el.open);
"""
    new = r'''  assert(await page.evaluate(() => document.activeElement?.id === 'businessText'),
    'Dialog did not restore focus to its invoker');
  passed('dialog focus entry and restoration');

  await page.evaluate(() => {
    localStorage.removeItem('taxonomy_onboarded');
    window.TaxonomyOnboarding.init();
  });
  const onboarding = page.locator('#onboardingOverlay');
  await onboarding.waitFor({ state: 'visible', timeout: 10_000 });
  await page.waitForFunction(() => document.activeElement?.id === 'onboardingDismiss');
  const onboardingState = await onboarding.evaluate(element => ({
    tagName: element.tagName,
    open: element.open,
    role: element.getAttribute('role'),
    ariaModal: element.getAttribute('aria-modal'),
    labelledBy: element.getAttribute('aria-labelledby'),
    describedBy: element.getAttribute('aria-describedby')
  }));
  assert(onboardingState.tagName === 'DIALOG' && onboardingState.open
    && onboardingState.role === 'dialog' && onboardingState.ariaModal === 'true'
    && onboardingState.labelledBy === 'onboardingTitle'
    && onboardingState.describedBy === 'onboardingIntro',
  `Incomplete onboarding dialog semantics: ${JSON.stringify(onboardingState)}`);
  await runAxe('onboarding-open');
  await saveState('onboarding-open');
  await page.keyboard.press('Escape');
  await onboarding.waitFor({ state: 'hidden' });
  await page.waitForFunction(() => document.activeElement?.id === 'businessText');
  passed('onboarding modal semantics, Escape close and focus restoration');

  await page.evaluate(() => {
    for (const index of [1, 2]) {
      const toast = document.createElement('div');
      toast.className = 'undo-toast';
      toast.dataset.qaOverlayToast = String(index);
      const message = document.createElement('span');
      message.textContent = `QA notification ${index}`;
      const undo = document.createElement('button');
      undo.type = 'button';
      undo.className = 'undo-btn';
      undo.textContent = 'Undo';
      toast.append(message, undo);
      document.body.appendChild(toast);
    }
  });
  await page.waitForFunction(() =>
    document.querySelectorAll('#taxonomyOverlayLane > [data-qa-overlay-toast]').length === 2);
  await businessText.scrollIntoViewIfNeeded();
  await businessText.focus();
  await page.evaluate(() => window.TaxonomyOnboarding.refreshOverlayLane());
  await page.waitForFunction(() =>
    document.getElementById('taxonomyOverlayLane')?.dataset.position);
  const overlayGeometry = await page.evaluate(() => {
    const lane = document.getElementById('taxonomyOverlayLane');
    const toasts = [...lane.querySelectorAll('[data-qa-overlay-toast]')];
    const rects = toasts.map(toast => toast.getBoundingClientRect());
    const overlaps = rects.length === 2
      && rects[0].left < rects[1].right && rects[0].right > rects[1].left
      && rects[0].top < rects[1].bottom && rects[0].bottom > rects[1].top;
    const undoRect = toasts[0].querySelector('.undo-btn').getBoundingClientRect();
    return {
      position: lane.dataset.position,
      toastCount: toasts.length,
      overlaps,
      undoWidth: undoRect.width,
      undoHeight: undoRect.height,
      activeUnobscured: window.TaxonomyOnboarding.isElementUnobscured(document.activeElement)
    };
  });
  assert(overlayGeometry.toastCount === 2 && !overlayGeometry.overlaps,
    `Overlay lane collision: ${JSON.stringify(overlayGeometry)}`);
  assert(overlayGeometry.undoWidth >= 44 && overlayGeometry.undoHeight >= 44,
    `Undo touch target is too small: ${JSON.stringify(overlayGeometry)}`);
  assert(overlayGeometry.activeUnobscured,
    `Focused control is obscured by overlay lane at ${overlayGeometry.position}`);
  await runAxe('overlay-lane');
  await saveState('overlay-lane');
  await page.evaluate(() => {
    document.querySelectorAll('[data-qa-overlay-toast]').forEach(toast => toast.remove());
  });
  passed('collision-safe overlay lane and touch targets');

  const operationalWasOpen = await page.locator('#operationalContextDetails').evaluate(el => el.open);
'''
    return replace_once(source, old, new, "browser overlay evidence")


def main() -> None:
    onboarding = ONBOARDING.read_text(encoding="utf-8")
    if "function ensureOverlayLane()" in onboarding:
        print("Accessible overlay contract already applied.")
        return
    ONBOARDING.write_text(patch_onboarding(onboarding), encoding="utf-8")
    ERGONOMICS.write_text(patch_css(ERGONOMICS.read_text(encoding="utf-8")), encoding="utf-8")
    ROLE_FLOW.write_text(patch_role_flow(ROLE_FLOW.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied accessible modal and collision-safe overlay contract.")


if __name__ == "__main__":
    main()
