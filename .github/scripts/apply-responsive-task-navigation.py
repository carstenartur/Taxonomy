#!/usr/bin/env python3
"""Apply the discoverable responsive navigation contract for issue #619."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UTILS = ROOT / "taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js"
ERGONOMICS = ROOT / "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css"
FIXTURES = ROOT / ".github/scripts/ui-role-fixtures.mjs"
ROLE_FLOW = ROOT / ".github/scripts/ui-role-state-flow.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_utils(source: str) -> str:
    anchor = """    // ── Responsive task reading and focus order ───────────────────────────
    function installResponsiveTaskOrder() {
"""
    responsive = r'''    // ── Discoverable responsive primary navigation ───────────────────────
    function authorizedMainNavigationLinks() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList) return [];
        return Array.from(tabList.querySelectorAll('.nav-link[data-page]')).filter(function (link) {
            var item = link.closest('.nav-item');
            return item && getComputedStyle(item).display !== 'none';
        });
    }

    function navigationLabel(link) {
        return (link.textContent || link.getAttribute('data-page') || '')
            .replace(/\s+/g, ' ').trim();
    }

    function syncResponsiveMainNavigation() {
        var select = document.getElementById('mobileMainNavigationSelect');
        if (!select) return;
        var links = authorizedMainNavigationLinks();
        var active = links.find(function (link) { return link.classList.contains('active'); });
        var selectedPage = active ? active.getAttribute('data-page') : select.value;
        var existing = Array.from(select.options).map(function (option) { return option.value; });
        var desired = links.map(function (link) { return link.getAttribute('data-page'); });
        if (existing.join('|') !== desired.join('|')) {
            select.replaceChildren();
            links.forEach(function (link) {
                var option = document.createElement('option');
                option.value = link.getAttribute('data-page');
                option.textContent = navigationLabel(link);
                select.appendChild(option);
            });
        }
        if (desired.includes(selectedPage)) select.value = selectedPage;
        select.disabled = desired.length === 0;
    }

    function focusCurrentTask() {
        if (typeof window.navigateToPage === 'function') {
            window.navigateToPage('analyze');
        } else {
            document.querySelector('#mainNavTabs [data-page="analyze"]')?.click();
        }
        requestAnimationFrame(function () {
            var nextAction = document.getElementById('taskNextAction');
            var input = document.getElementById('businessText');
            var target = nextAction && !nextAction.disabled ? nextAction : input;
            if (!target) return;
            target.scrollIntoView({ block: 'center', inline: 'nearest' });
            target.focus({ preventScroll: true });
        });
    }

    function installResponsiveMainNavigation() {
        var tabList = document.getElementById('mainNavTabs');
        if (!tabList || document.getElementById('mobileMainNavigation')) return;

        var wrapper = document.createElement('nav');
        wrapper.id = 'mobileMainNavigation';
        wrapper.className = 'mobile-main-navigation';
        wrapper.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Bereichsauswahl' : 'Section navigation');

        var label = document.createElement('label');
        label.htmlFor = 'mobileMainNavigationSelect';
        label.className = 'mobile-main-navigation-label';
        label.textContent = currentLanguage() === 'de' ? 'Bereich' : 'Section';

        var select = document.createElement('select');
        select.id = 'mobileMainNavigationSelect';
        select.className = 'form-select mobile-main-navigation-select';
        select.setAttribute('aria-label', currentLanguage() === 'de'
            ? 'Hauptbereich auswählen' : 'Choose main section');
        select.addEventListener('change', function () {
            var page = select.value;
            if (typeof window.navigateToPage === 'function') {
                window.navigateToPage(page);
            } else {
                document.querySelector('#mainNavTabs [data-page="' + CSS.escape(page) + '"]')?.click();
            }
            syncResponsiveMainNavigation();
        });

        var taskButton = document.createElement('button');
        taskButton.id = 'mobileCurrentTaskBtn';
        taskButton.type = 'button';
        taskButton.className = 'btn btn-primary mobile-current-task-button';
        taskButton.textContent = currentLanguage() === 'de' ? 'Aktuelle Aufgabe' : 'Current task';
        taskButton.addEventListener('click', focusCurrentTask);

        wrapper.append(label, select, taskButton);
        tabList.parentElement.insertBefore(wrapper, tabList);
        syncResponsiveMainNavigation();

        new MutationObserver(syncResponsiveMainNavigation).observe(tabList, {
            subtree: true,
            childList: true,
            attributes: true,
            attributeFilter: ['class', 'style', 'aria-selected']
        });
        new MutationObserver(syncResponsiveMainNavigation).observe(document.body, {
            attributes: true,
            attributeFilter: ['class']
        });
        window.addEventListener('hashchange', syncResponsiveMainNavigation);
    }

'''
    source = replace_once(source, anchor, responsive + anchor, "responsive navigation insertion")
    source = replace_once(
        source,
        """        installMainNavigationKeyboardSupport();
        installResponsiveTaskOrder();
""",
        """        installMainNavigationKeyboardSupport();
        installResponsiveMainNavigation();
        installResponsiveTaskOrder();
""",
        "responsive navigation initialization",
    )
    source = replace_once(
        source,
        """        syncTreeItemAccessibility: syncTreeItemAccessibility,
        refreshNodeCodeSuggestions: refreshNodeCodeSuggestions
""",
        """        syncTreeItemAccessibility: syncTreeItemAccessibility,
        refreshNodeCodeSuggestions: refreshNodeCodeSuggestions,
        syncResponsiveMainNavigation: syncResponsiveMainNavigation,
        focusCurrentTask: focusCurrentTask
""",
        "responsive navigation public API",
    )
    return source


def patch_css(source: str) -> str:
    marker = "/* Discoverable responsive primary navigation. */"
    if marker in source:
        return source
    addition = r'''

/* Discoverable responsive primary navigation. */
.mobile-main-navigation {
    display: none;
}

@media (max-width: 991.98px) {
    #mainNavTabs {
        display: none !important;
    }
    .mobile-main-navigation {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        align-items: end;
        gap: 0.5rem;
        padding: 0.65rem 0;
        color: #fff;
    }
    .mobile-main-navigation-label {
        grid-column: 1 / -1;
        margin: 0;
        font-size: 0.875rem;
        font-weight: 600;
    }
    .mobile-main-navigation-select,
    .mobile-current-task-button {
        min-height: 44px;
    }
    .mobile-main-navigation-select {
        min-width: 0;
    }
    .mobile-current-task-button {
        white-space: nowrap;
    }
}

@media (max-width: 30rem), (max-height: 30rem) {
    .mobile-main-navigation {
        grid-template-columns: 1fr;
    }
    .mobile-main-navigation-label {
        grid-column: 1;
    }
    .mobile-current-task-button {
        width: 100%;
    }
}
'''
    return source.rstrip() + "\n" + addition.lstrip()


def patch_fixtures(source: str) -> str:
    old = r'''export async function navigateToPage(page, pageId) {
  const control = page.locator(`#mainNavTabs [data-page="${pageId}"]`);
  await control.scrollIntoViewIfNeeded();
  await control.click();
  await page.locator(`#tab-${pageId}`).waitFor({ state: 'visible', timeout: 20_000 });
}
'''
    new = r'''export async function navigateToPage(page, pageId) {
  const responsive = page.locator('#mobileMainNavigationSelect');
  if (await responsive.isVisible().catch(() => false)) {
    const values = await responsive.locator('option').evaluateAll(options =>
      options.map(option => option.value));
    if (!values.includes(pageId)) {
      throw new Error(`Responsive navigation does not expose ${pageId}: ${values.join(', ')}`);
    }
    await responsive.selectOption(pageId);
  } else {
    const control = page.locator(`#mainNavTabs [data-page="${pageId}"]`);
    await control.click();
  }
  await page.locator(`#tab-${pageId}`).waitFor({ state: 'visible', timeout: 20_000 });
}
'''
    return replace_once(source, old, new, "Playwright responsive navigation helper")


def patch_role_flow(source: str) -> str:
    source = replace_once(
        source,
        """  await page.locator('#mainNavTabs [data-page=\"analyze\"]').click();
""",
        """  await navigateToPage(page, 'analyze');
""",
        "initial responsive navigation",
    )

    source = replace_once(
        source,
        """    const progressRect = progress?.getBoundingClientRect();
    const primaryRect = primary?.getBoundingClientRect();
    const focusable = Boolean(primary && !primary.disabled
""",
        """    const progressRect = progress?.getBoundingClientRect();
    const primaryRect = primary?.getBoundingClientRect();
    const responsiveNavigation = document.getElementById('mobileMainNavigation');
    const responsiveSelect = document.getElementById('mobileMainNavigationSelect');
    const taskJump = document.getElementById('mobileCurrentTaskBtn');
    const responsiveRect = responsiveNavigation?.getBoundingClientRect();
    const taskJumpRect = taskJump?.getBoundingClientRect();
    const authorizedPages = Array.from(
      document.querySelectorAll('#mainNavTabs .nav-link[data-page]'))
      .filter(link => {
        const item = link.closest('.nav-item');
        return item && getComputedStyle(item).display !== 'none';
      })
      .map(link => link.dataset.page);
    const responsivePages = Array.from(responsiveSelect?.options || [], option => option.value);
    const focusable = Boolean(primary && !primary.disabled
""",
        "initial responsive geometry collection",
    )

    source = replace_once(
        source,
        """      progressVisible: Boolean(progressRect && progressRect.width > 0 && progressRect.height > 0),
      progressTop: progressRect?.top ?? Number.POSITIVE_INFINITY,
      viewportHeight: window.innerHeight,
""",
        """      progressVisible: Boolean(progressRect && progressRect.width > 0 && progressRect.height > 0),
      progressTop: progressRect?.top ?? Number.POSITIVE_INFINITY,
      viewportHeight: window.innerHeight,
      viewportWidth: window.innerWidth,
      responsiveNavigationVisible: Boolean(responsiveRect
        && responsiveRect.width > 0 && responsiveRect.height > 0),
      responsiveNavigationInsideViewport: Boolean(responsiveRect
        && responsiveRect.top >= 0 && responsiveRect.bottom <= window.innerHeight),
      taskJumpVisible: Boolean(taskJumpRect && taskJumpRect.width > 0 && taskJumpRect.height > 0),
      taskJumpInsideViewport: Boolean(taskJumpRect
        && taskJumpRect.top >= 0 && taskJumpRect.bottom <= window.innerHeight),
      taskJumpFocusable: Boolean(taskJump && !taskJump.disabled && taskJump.tabIndex >= 0),
      authorizedPages,
      responsivePages,
""",
        "initial responsive geometry result",
    )

    source = replace_once(
        source,
        """  assert(taskSurface.primaryVisible && taskSurface.primaryEnabled && taskSurface.primaryFocusable,
    'Primary Analyze action is not visible, enabled and focusable');
  assert(taskSurface.operationalCollapsed && taskSurface.operationalContainsOriginalSurfaces,
""",
        """  assert(taskSurface.primaryVisible && taskSurface.primaryEnabled && taskSurface.primaryFocusable,
    'Primary Analyze action is not visible, enabled and focusable');
  if (taskSurface.viewportWidth < 992) {
    assert(taskSurface.responsiveNavigationVisible
      && taskSurface.responsiveNavigationInsideViewport,
    'Responsive section navigation is not discoverable before any automatic scroll');
    assert(taskSurface.taskJumpVisible && taskSurface.taskJumpInsideViewport
      && taskSurface.taskJumpFocusable,
    'Responsive current-task jump is not visible and keyboard reachable');
    assert(JSON.stringify(taskSurface.responsivePages)
      === JSON.stringify(taskSurface.authorizedPages),
    `Responsive destinations differ from authorized tabs: ${JSON.stringify(taskSurface)}`);
    assert(taskSurface.progressTop <= taskSurface.viewportHeight,
      `Task progress begins beyond the first viewport: ${taskSurface.progressTop} > `
        + `${taskSurface.viewportHeight}`);
    assert(taskSurface.primaryInsideViewport || taskSurface.taskJumpInsideViewport,
      'Neither the primary action nor its explicit task jump is initially visible');
  }
  assert(taskSurface.operationalCollapsed && taskSurface.operationalContainsOriginalSurfaces,
""",
        "responsive initial visibility budget",
    )

    old_hierarchy = r'''    const navigation = document.getElementById('mainNavTabs');
    const visibleLinks = Array.from(navigation?.querySelectorAll('.nav-link') || [])
      .filter(link => getComputedStyle(link).display !== 'none');
    const maxLinkHeight = visibleLinks.reduce((maximum, link) =>
      Math.max(maximum, link.getBoundingClientRect().height), 0);
    return {
      viewportWidth: window.innerWidth,
      leftTop: left?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightTop: right?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightPrecedesLeftInDom: Boolean(right && left
        && (right.compareDocumentPosition(left) & Node.DOCUMENT_POSITION_FOLLOWING)),
      leftPrecedesRightInDom: Boolean(left && right
        && (left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING)),
      taskOrder: row?.dataset.taskOrder || '',
      navigationHeight: navigation?.getBoundingClientRect().height ?? 0,
      maxLinkHeight,
      navigationScrollWidth: navigation?.scrollWidth ?? 0,
      navigationClientWidth: navigation?.clientWidth ?? 0
    };
'''
    new_hierarchy = r'''    const navigation = document.getElementById('mainNavTabs');
    const responsiveNavigation = document.getElementById('mobileMainNavigation');
    const responsiveSelect = document.getElementById('mobileMainNavigationSelect');
    const taskJump = document.getElementById('mobileCurrentTaskBtn');
    const responsiveRect = responsiveNavigation?.getBoundingClientRect();
    const taskJumpRect = taskJump?.getBoundingClientRect();
    const authorizedPages = Array.from(navigation?.querySelectorAll('.nav-link[data-page]') || [])
      .filter(link => {
        const item = link.closest('.nav-item');
        return item && getComputedStyle(item).display !== 'none';
      })
      .map(link => link.dataset.page);
    return {
      viewportWidth: window.innerWidth,
      leftTop: left?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightTop: right?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
      rightPrecedesLeftInDom: Boolean(right && left
        && (right.compareDocumentPosition(left) & Node.DOCUMENT_POSITION_FOLLOWING)),
      leftPrecedesRightInDom: Boolean(left && right
        && (left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING)),
      taskOrder: row?.dataset.taskOrder || '',
      desktopNavigationDisplayed: getComputedStyle(navigation).display !== 'none',
      responsiveNavigationVisible: Boolean(responsiveRect
        && responsiveRect.width > 0 && responsiveRect.height > 0),
      responsiveNavigationInsideViewport: Boolean(responsiveRect
        && responsiveRect.top >= 0 && responsiveRect.bottom <= window.innerHeight),
      taskJumpInsideViewport: Boolean(taskJumpRect
        && taskJumpRect.top >= 0 && taskJumpRect.bottom <= window.innerHeight),
      taskJumpSize: taskJumpRect
        ? { width: taskJumpRect.width, height: taskJumpRect.height } : null,
      responsivePages: Array.from(responsiveSelect?.options || [], option => option.value),
      authorizedPages
    };
'''
    source = replace_once(source, old_hierarchy, new_hierarchy, "responsive hierarchy evidence")

    old_assertions = r'''    assert(taskHierarchy.navigationHeight <= taskHierarchy.maxLinkHeight + 6,
      `Main navigation wrapped to multiple rows: ${taskHierarchy.navigationHeight} > `
      + `${taskHierarchy.maxLinkHeight + 6}`);
    assert(taskHierarchy.navigationScrollWidth >= taskHierarchy.navigationClientWidth,
      'Main navigation must remain horizontally reachable');
    passed('single-row scrollable main navigation');
'''
    new_assertions = r'''    assert(!taskHierarchy.desktopNavigationDisplayed
      && taskHierarchy.responsiveNavigationVisible
      && taskHierarchy.responsiveNavigationInsideViewport,
    'Narrow viewport must expose the explicit responsive navigation instead of hidden overflow tabs');
    assert(taskHierarchy.taskJumpInsideViewport
      && taskHierarchy.taskJumpSize?.width >= 44
      && taskHierarchy.taskJumpSize?.height >= 44,
    `Current-task jump is not initially reachable: ${JSON.stringify(taskHierarchy)}`);
    assert(JSON.stringify(taskHierarchy.responsivePages)
      === JSON.stringify(taskHierarchy.authorizedPages),
    `Responsive navigation lost authorized destinations: ${JSON.stringify(taskHierarchy)}`);
    passed('discoverable responsive main navigation and current-task jump');
'''
    return replace_once(source, old_assertions, new_assertions, "responsive hierarchy assertions")


def main() -> None:
    utils = UTILS.read_text(encoding="utf-8")
    if "function installResponsiveMainNavigation()" in utils:
        print("Responsive task navigation already applied.")
        return
    UTILS.write_text(patch_utils(utils), encoding="utf-8")
    ERGONOMICS.write_text(patch_css(ERGONOMICS.read_text(encoding="utf-8")), encoding="utf-8")
    FIXTURES.write_text(patch_fixtures(FIXTURES.read_text(encoding="utf-8")), encoding="utf-8")
    ROLE_FLOW.write_text(patch_role_flow(ROLE_FLOW.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied discoverable responsive navigation and pre-scroll task budgets.")


if __name__ == "__main__":
    main()
