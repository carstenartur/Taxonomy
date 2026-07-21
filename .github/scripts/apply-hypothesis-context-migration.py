#!/usr/bin/env python3
"""One-time mechanical migration of hypothesis callers and architecture evidence."""

from pathlib import Path


ROOT = Path.cwd()
CONTROLLER = ROOT / "taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DslApiController.java"
ARCH_TEST = ROOT / "taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java"
EXCEPTIONS = ROOT / "docs/dev/08-archunit-exceptions.md"
WORKSPACE_DOC = ROOT / "docs/dev/05-workspace-git-context.md"


def replace_once(text: str, old: str, new: str, description: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"Cannot locate {description}")


def patch_controller() -> None:
    text = CONTROLLER.read_text(encoding="utf-8")
    old_list = '''    public ResponseEntity<List<RelationHypothesis>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status) {
        List<RelationHypothesis> result;
        if (status != null) {
            result = hypothesisService.findByStatus(status);
        } else {
            result = hypothesisService.findAll();
        }
        return ResponseEntity.ok(result);
    }
'''
    new_list = '''    public ResponseEntity<List<RelationHypothesis>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status) {
        WorkspaceContext context = currentWorkspaceContext();
        List<RelationHypothesis> result = status != null
                ? hypothesisService.findByStatus(status, context)
                : hypothesisService.findAll(context);
        return ResponseEntity.ok(result);
    }
'''
    text = replace_once(text, old_list, new_list, "hypothesis listing endpoint")

    replacements = {
        "hypothesisService.accept(id)": "hypothesisService.accept(id, currentWorkspaceContext())",
        "hypothesisService.reject(id)": "hypothesisService.reject(id, currentWorkspaceContext())",
        "hypothesisService.applyForSession(id)": "hypothesisService.applyForSession(id, currentWorkspaceContext())",
        "hypothesisService.findEvidence(id)": "hypothesisService.findEvidence(id, currentWorkspaceContext())",
    }
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new)
        elif new not in text:
            raise SystemExit(f"Cannot locate controller call {old}")

    helper_anchor = "    private WorkspaceContext resolveWorkspaceContext(String username) {\n"
    helper = '''    private WorkspaceContext currentWorkspaceContext() {
        return resolveWorkspaceContext(workspaceResolver.resolveCurrentUsername());
    }

'''
    if helper not in text:
        if helper_anchor not in text:
            raise SystemExit("Cannot locate workspace helper anchor")
        text = text.replace(helper_anchor, helper + helper_anchor, 1)

    CONTROLLER.write_text(text, encoding="utf-8")


def patch_architecture_test() -> None:
    text = ARCH_TEST.read_text(encoding="utf-8")
    entry = '''            new AllowlistEntry(
                    "com.taxonomy.versioning.service.HypothesisService",
                    "Hypothesis persistence/listing still resolves active workspace internally.",
                    "Remove once hypothesis methods receive WorkspaceContext from request boundary."),
'''
    text = text.replace(entry, "")
    ARCH_TEST.write_text(text, encoding="utf-8")


def patch_docs() -> None:
    text = EXCEPTIONS.read_text(encoding="utf-8")
    text = text.replace(
        '''6. `com.taxonomy.versioning.service.HypothesisService`
   - Why: hypothesis persistence/listing still resolves active workspace internally.
   - Remove when: hypothesis methods receive `WorkspaceContext` from request boundary.
7. `com.taxonomy.versioning.service.SelectiveTransferService`
''',
        '''6. `com.taxonomy.versioning.service.SelectiveTransferService`
''')
    EXCEPTIONS.write_text(text, encoding="utf-8")

    text = WORKSPACE_DOC.read_text(encoding="utf-8")
    completed = "- `HypothesisService` now receives explicit `WorkspaceContext` for persistence, listing, evidence access, acceptance, rejection, and session application; cross-workspace IDs are treated as not found.\n"
    anchor = "- `DslApiController` now resolves `username` + `WorkspaceContext` once before workspace-aware history/current response state is loaded through `DslOperationsFacade`.\n"
    if completed not in text:
        text = replace_once(text, anchor, anchor + completed, "workspace refactored examples anchor")
    text = text.replace(
        "- `com.taxonomy.versioning.service.HypothesisService` — hypothesis persistence/listing still derives the active workspace internally.\n",
        "")
    WORKSPACE_DOC.write_text(text, encoding="utf-8")


def main() -> None:
    patch_controller()
    patch_architecture_test()
    patch_docs()


if __name__ == "__main__":
    main()
