# Workbench command and navigation structure

The user approved implementing the previously described separation of command
menus, work-area navigation, contextual tools, views and status. The preceding
visual consolidation is preserved; the remaining mixed information hierarchy is
replaced rather than decorated again.

## Structure

- One command bar: File, Edit, View, Tools, Help. Every category opens a menu.
- A separate, labelled work-area navigation selects Projects, the current
  Requirement (the existing requirement/analysis workspace), or Architecture.
  These are existing destinations; no fictitious architecture catalogue is added.
- History, DSL source, catalogue views and zoom are View commands. Graph Explorer
  and tool integrations are expert tools. Administration is separate from personal
  appearance and language settings inside Tools, subject to the existing authority.
- Contextual page actions and architecture source toolbars stay with their source;
  page-specific actions never become extra top-level application menus.
- Operational state remains visible outside command menus. Analysis recovery is
  reachable in the requirement work area rather than inside Settings.

## Implementation boundaries

Thymeleaf and existing JS/CSS remain the stack. Shared commands adapt existing
feature controls and derive enabled state from those controls and their active
source. They do not implement a second undo, save, analysis or export path.
Native command menus have one keyboard, closing and focus controller. Remove
old primary-tab semantics, scrolling-tab CSS and duplicate navigation handlers.
Keep one page-activation lifecycle, URL aliases, return/context pins, language,
role checks, draft protection and recovery. A command that cannot act in the
current context is disabled and has an explanation.

Menus and work navigation remain distinct on narrow screens. Shared appearance
settings apply from one owner; old feature-specific theme handlers are removed.
No release, backend model migration, collaboration feature or external deployment
belongs to this change. Publication remains blocked pending direct push approval.
