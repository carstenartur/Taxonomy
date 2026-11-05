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
available. Server-generated PDF, Word and diagram exports do not yet consume
this CSS file.
