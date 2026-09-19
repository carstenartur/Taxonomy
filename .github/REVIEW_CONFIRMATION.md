# Pull-request review

Required CI checks verify the build, tests, architecture boundaries, security
and product behavior. They do not parse or score Copilot review prose.

Reviewers assess the actual diff and investigate concrete findings. Copilot
provides advice; missing coverage counts or a particular summary format do not
block a merge. Use ordinary GitHub reviews and the maintainer merge decision.
No `/confirm-review` command or separate review-metadata audit is required.

A changed implementation must pass the relevant checks. Do not weaken product
tests, security checks or architecture rules to resolve a review-format issue.
