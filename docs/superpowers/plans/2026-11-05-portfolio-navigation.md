# Share navigation across portfolio views

The requirement import, matrices and reports pages use their own headers.
Replace each header with the application's existing navigation and contextual
help, while retaining page controls and language changes. The return action
must reopen the current project, including a mounted base path and workspace
scope where present. Show the selected project in the shared context.

Extend the existing real-browser shared-navigation and routing checks for all
three pages in English and German. Retain the import, matrix, report, permission
and recovery assertions already owned by the existing test suites.
