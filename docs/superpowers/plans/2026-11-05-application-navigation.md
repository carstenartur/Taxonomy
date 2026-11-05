# Consolidate the desktop application navigation

The primary destinations and the existing file, project and support actions
must share one desktop header row. The same controls remain available on small
screens, where they may wrap while keeping keyboard and touch navigation usable.

1. Place the existing per-view action fragment inside the shared main row in
   `templates/fragments/application-chrome.html`; retain all action IDs,
   translations, permissions and context-help links.
2. Update `static/css/application-chrome.css` to let the analysis actions size
   themselves naturally and preserve the existing compact navigation on mobile.
3. Extend the existing native browser acceptance in
   `.github/scripts/analysis-navigation.browser.test.mjs`: at 1440 and 1920 px,
   the brand, primary destination, Project, Settings, Help and About are aligned
   in German and English. Existing narrow-screen and action tests stay in place.

Review focus: do not hide actions, change their targets, cause horizontal page
overflow, or add a second source of navigation. Verify the existing navigation
and source-context contracts and the browser suite before merging.
