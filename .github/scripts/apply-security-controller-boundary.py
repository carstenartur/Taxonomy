#!/usr/bin/env python3
"""Remove obsolete controller-to-repository architecture exceptions."""

from pathlib import Path


ARCH = Path("taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java")
DOC = Path("docs/dev/08-archunit-exceptions.md")


def main() -> None:
    text = ARCH.read_text(encoding="utf-8")
    old_entries = '''    private static final List<AllowlistEntry> CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST = List.of(
            new AllowlistEntry(
                    "com.taxonomy.security.controller.ChangePasswordController",
                    "Current password-change flow still validates and updates AppUser directly.",
                    "Remove once password change is handled via a dedicated security service/facade."),
            new AllowlistEntry(
                    "com.taxonomy.security.controller.UserManagementController",
                    "Current admin user-management endpoints still orchestrate user/role persistence directly.",
                    "Remove once user/role CRUD orchestration is moved to a dedicated security service/facade.")
    );
'''
    new_entries = '''    private static final List<AllowlistEntry> CONTROLLER_REPOSITORY_ACCESS_ALLOWLIST = List.of();
'''
    if old_entries in text:
        text = text.replace(old_entries, new_entries, 1)
    elif new_entries not in text:
        raise SystemExit("Cannot locate controller repository allowlist")
    ARCH.write_text(text, encoding="utf-8")

    text = DOC.read_text(encoding="utf-8")
    old_section = '''## Controller → repository allowlist

These are temporary exceptions to the rule
`controllersShouldNotAccessRepositories`.

1. `com.taxonomy.security.controller.ChangePasswordController`
   - Why: password-change flow still validates and updates `AppUser` directly.
   - Remove when: password change is handled via a dedicated security service/facade.
2. `com.taxonomy.security.controller.UserManagementController`
   - Why: admin user-management endpoints still orchestrate user/role persistence directly.
   - Remove when: user/role CRUD orchestration is moved to a dedicated security service/facade.

'''
    new_section = '''## Controller → repository rule

There are no remaining controller exceptions. Password changes and user/role
administration are delegated to `PasswordChangeService` and
`UserManagementService`; all controller packages are therefore required to stay
repository-free.

'''
    if old_section in text:
        text = text.replace(old_section, new_section, 1)
    elif new_section not in text:
        raise SystemExit("Cannot locate controller exception documentation")
    DOC.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
