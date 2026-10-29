# Contextual view help

The user's request is the specification: each application view should offer a
button opening the help that explains that view. Reuse bundled English/German
help, keep the application context path and leave unsaved work in its original tab.

1. Add regression coverage for rendered links and actual help section targets in
   both languages, plus asynchronous deep-link navigation and stale responses.
   Observe missing contextual links / section navigation failing first.
2. Add one reusable, accessible link fragment to main workspace panes and standalone
   views. Give taxonomy display modes their own section targets. Supply stable
   language-independent anchors where translated headings differ.
3. Teach the existing help reader `#help/DOCUMENT/section` links, preserve the
   existing document-only format, show the help pane on arrival and focus/scroll
   the requested heading after loading. Retain the exact URL and selected locale.
4. Run focused Maven-owned regressions, then canonical verification
   `./mvnw -B verify -Pci -DrunOnnxTests=true`. Report unavailable prerequisites
   distinctly. Review the complete diff and publish an independent PR from main.

Review focus: initial navigation order, duplicate/racing requests, invalid targets,
locale/context path, document heading existence, native new-tab link behavior,
keyboard focus, and preserving the user's original working state.
