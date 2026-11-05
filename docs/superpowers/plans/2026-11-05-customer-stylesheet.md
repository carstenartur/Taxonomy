# Operator-supplied interface stylesheet

Allow a deployment to provide one UTF-8 stylesheet file after the bundled
styles on application pages, including login and user management. Leave the
default installation unchanged when no file is configured. Use a bounded,
uncached same-origin CSS response and document the Rancher directory mount.

Verify default behavior, updates, missing and invalid files with the existing
JUnit tooling. Extend the existing browser acceptance to apply a custom style
on the analysis and projects pages under a mounted context path. Server-rendered
exports remain outside this step and are documented as such.
