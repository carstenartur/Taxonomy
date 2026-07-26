# Security Policy

## Supported versions

Security fixes are developed for the current `main` branch and, where practical, for the latest published release. Older releases may no longer receive fixes. Verify the exact version and deployment configuration before reporting a problem.

## Reporting a vulnerability

Please do **not** disclose suspected vulnerabilities in a public issue, pull request, discussion, log excerpt, or screenshot.

Use GitHub's private vulnerability-reporting or Security Advisory workflow for this repository. Include:

- the affected version or commit;
- the relevant deployment profile and configuration, with secrets removed;
- reproducible steps or a minimal proof of concept;
- the security impact and affected data or privileges;
- any mitigation or patch suggestion you have already tested.

When private vulnerability reporting is unavailable, contact the repository owner privately through the GitHub profile and request a private reporting channel. Do not send credentials, API keys, access tokens, personal data, or production database contents in an initial message.

The maintainer will assess the report, coordinate remediation, and agree on disclosure timing. No fixed response-time SLA is currently promised.

## Credential policy

The application has no reusable built-in administrator password.

- On a new local database, an unset `TAXONOMY_ADMIN_PASSWORD` causes a high-entropy one-time bootstrap password to be generated and printed once to the startup log.
- Bootstrap accounts must replace their initial password.
- The `production` profile refuses missing, known-placeholder, or shorter-than-16-character administrator passwords before an account can be created.
- External Git access tokens are read from deployment configuration and are not persisted in repository entities or returned by status APIs.

Never commit credentials to this repository. Use environment variables or the deployment platform's secret store.

## Operational security

A successful build or demonstration is not by itself evidence that a deployment is secure. Operators must configure HTTPS, access control, secret storage, database and index protection, backups, logging, and vulnerability monitoring for their environment.

See the detailed documentation:

- [Security architecture and configuration](docs/en/SECURITY.md)
- [Production deployment checklist](docs/en/DEPLOYMENT_CHECKLIST.md)
- [Configuration reference](docs/en/CONFIGURATION_REFERENCE.md)
- [Data protection](docs/en/DATA_PROTECTION.md)
