# Customer interface styling

An operator can supply a UTF-8 CSS file. Taxonomy loads it after its bundled
styles so it can customize the interface. Without a configured file, no extra
stylesheet request is made.

Set `TAXONOMY_UI_CUSTOM_STYLESHEET` to the absolute file path, for example
`/etc/taxonomy/theme/customer.css`. The Spring property
`taxonomy.ui.custom-stylesheet` is an alternative. The file may be up to
512 KiB. Changes are visible on the next page reload; the response is not cached.

Example starting point:

```css
:root {
  --taxonomy-navigation-background: #17406f;
  --taxonomy-navigation-foreground: #fff;
  --taxonomy-surface-background: #eef3f8;
  --taxonomy-surface-foreground: #17243a;
  --taxonomy-panel-background: #fff;
  --taxonomy-panel-border: #c8d1dd;
  --taxonomy-canvas-background: #f8fafc;
  --taxonomy-layer-cap-surface: #17406f;
  --taxonomy-layer-proc-surface: #146c43;
  --taxonomy-relation-supports: #27834a;
}
```

These shared colors apply to the common navigation and to the surfaces, panels
and diagram backgrounds of the working architecture and saved analysis state.
Other areas and individual controls may still need additional CSS rules. Check
foreground and background colors together for sufficient contrast.

The interactive graphs for analysis, saved analysis snapshots, and the working
architecture share taxonomy layer and relationship colors.
In addition to `cap` and `proc`, the layer tokens `svc`, `app`, `info`, `comm`,
`system`, `component`, and `default` follow the same naming pattern. Relations
can be customized, for example, with `--taxonomy-relation-supports`. Hexadecimal
colors allow the diagrams to choose light or dark text automatically. Check
contrast in other views as well; their text colors may need separate CSS rules.
The alternative analysis layer view uses the same layer background tokens, but
its text and relationship labels can need separate CSS adjustments.

In Rancher, mount a ConfigMap as a directory in the Taxonomy container and set
the environment variable to the mounted file. Every pod needs the same
configuration. Kubernetes can update files in a directory mount; a `subPath`
mount needs a pod restart to receive ConfigMap changes.

An unreadable, oversized or invalid UTF-8 file makes the stylesheet request
fail and records the cause in the application log. Bundled styles remain
available. Exports fall back to their bundled appearance if that read fails.

## Export appearance

Server exports read the same bounded file once per document and capture an
immutable color theme. Updating the file affects the next export. Styles never
change selected elements, scores, original explanations, provenance or snapshot
authority.

| Output | Supported customer appearance |
| --- | --- |
| Standalone architecture SVG and vector architecture PDF, including working views and saved snapshots | `--taxonomy-layer-<layer>-surface` fills, `--taxonomy-relation-<relation>` strokes, `--taxonomy-canvas-background` and `--taxonomy-canvas-foreground`; navigation background and panel border color the node outlines. PDF inventory pages use the same canvas theme. |
| Native Visio VSDX and its handoff bundle | Taxonomy layer fills, readable node text, navigation/panel outlines and typed relationship strokes. Transparent containers, native white pages, connector captions and presentation-only evidence retain their native styles. Proposal dashes remain visible. |
| Architecture and decision HTML reports | Surface background/foreground, panel background/border and navigation background/foreground color the report page, headings, tables and panels. The generated HTML is self-contained. |
| Word/DOTX reports and their embedded architecture figures | The supported document template controls branding. Customer UI CSS is not applied. |
| Decision tree/chapter figures embedded in reports | Their score, disposition and leading-path colors retain their semantic report palette. |
| ArchiMate, Sparx, Structurizr, Mermaid and data exports | Their native exchange or source representation applies; UI CSS is not exported. |

The portable subset consists of literal opaque `#rgb` or `#rrggbb` values in
top-level, unqualified `:root { ... }` blocks. Repeated declarations follow source
order; `!important` takes precedence. Comments are allowed. Layer names are
`cap`, `proc`, `svc`, `app`, `info`, `comm`, `system`, `component` and `default`.
Relationship names are `supports`, `realizes`, `realized-by`, `uses`, `requires`,
`depends-on`, `fulfills`, `consumes`, `assigned-to`, `produces`,
`communicates-with`, `contains` and `related-to`.
An entire `:root` block containing nested rules is outside this subset and is ignored.

Other declarations are ignored for exports: arbitrary selectors, theme-qualified
or grouped selectors, nested rules, `@media`, `@import`, `var()`, named/RGB/alpha
colors, fonts, images and layout changes are browser CSS features. They continue
to work in application pages according to the browser's CSS rules. A supported
token with no portable value keeps its bundled export value.

For shared SVG/PDF diagrams and styled Visio nodes, the color resolver adjusts
text when needed for at least 4.5:1 contrast against its surface. Custom connector
and node-outline colors use at least 3:1 against the canvas or node fill respectively.
HTML report surfaces also resolve their foreground against the relevant page or panel.
This can make the exported foreground differ from a low-contrast customer value.
Check the final files in the intended reader before deploying
a theme; automated XML/PDF rendering checks do not certify Microsoft Visio or
Office compatibility.
