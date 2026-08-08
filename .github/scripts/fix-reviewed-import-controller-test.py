#!/usr/bin/env python3
"""Fix assertion ordering and keep workspace stubs local to successful calls."""

from pathlib import Path

path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/portfolio/PortfolioReviewedImportControllerTest.java"
)
text = path.read_text(encoding="utf-8")
text = text.replace("import org.junit.jupiter.api.BeforeEach;\n", "")
setup = '''    @BeforeEach
    void configureWorkspace() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

'''
if text.count(setup) != 1:
    raise SystemExit(f"Expected one global workspace setup, found {text.count(setup)}")
text = text.replace(setup, "", 1)
text = text.replace(
    '''catchThrowableOfType(
                    PortfolioException.class,
                    () -> ''',
    '''catchThrowableOfType(
                    () -> ''')
text = text.replace(
    '''catchThrowableOfType(
                PortfolioException.class,
                () -> ''',
    '''catchThrowableOfType(
                () -> ''')
for old, new in (
    (
        '''controller.importReviewed(41L, request));''',
        '''controller.importReviewed(41L, request), PortfolioException.class);''',
    ),
    (
        '''itemLimited.importReviewed(41L, tooMany));''',
        '''itemLimited.importReviewed(41L, tooMany), PortfolioException.class);''',
    ),
    (
        '''characterLimited.importReviewed(41L, tooLong));''',
        '''characterLimited.importReviewed(41L, tooLong), PortfolioException.class);''',
    ),
):
    if old not in text:
        raise SystemExit(f"Expected assertion call not found: {old.strip()}")
    text = text.replace(old, new, 1)

for signature in (
    "    void nullItemsTextAndSourceAreCountedSafely() {\n",
    "    void importWithoutRequestedAnalysisReturnsCreatedAndPreservesContext() {\n",
    "    void requestedAnalysisIsNotEnqueuedWhenNoRequirementWasAffected() {\n",
    "    void requestedAnalysisReturnsAcceptedLocationAndDeduplicatedRequest() {\n",
):
    if text.count(signature) != 1:
        raise SystemExit(f"Expected one success test signature: {signature.strip()}")
    text = text.replace(signature, signature + "        stubWorkspace();\n", 1)

helper_marker = '''    private PortfolioReviewedImportController controller(int maximumItems,
'''
helper = '''    private void stubWorkspace() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

'''
if text.count(helper_marker) != 1:
    raise SystemExit("Expected one controller helper marker")
text = text.replace(helper_marker, helper + helper_marker, 1)
path.write_text(text, encoding="utf-8")
print("Corrected reviewed import controller tests before execution.")
