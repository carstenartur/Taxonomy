#!/usr/bin/env python3
"""Measure semantic CSS tokens directly instead of requiring rendered architecture nodes."""

from pathlib import Path

path = Path(".github/scripts/ui-role-state-flow.mjs")
text = path.read_text(encoding="utf-8")
start_marker = "  const architectureContrast = await page.evaluate(() => {\n"
end_marker = "  await runAxe('analysis-success');\n"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("Could not locate the architecture contrast evidence block")

replacement = r'''  const architectureContrast = await page.evaluate(() => {
    const surfaceTokens = [
      '--taxonomy-layer-cap-surface',
      '--taxonomy-layer-proc-surface',
      '--taxonomy-layer-svc-surface',
      '--taxonomy-layer-app-surface',
      '--taxonomy-layer-info-surface',
      '--taxonomy-layer-comm-surface',
      '--taxonomy-layer-system-surface',
      '--taxonomy-layer-component-surface',
      '--taxonomy-layer-default-surface'
    ];
    function parseColor(value) {
      const normalized = value.trim();
      const hex = normalized.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
      if (hex) {
        const digits = hex[1].length === 3
          ? [...hex[1]].map(character => character + character).join('')
          : hex[1];
        return [
          Number.parseInt(digits.slice(0, 2), 16),
          Number.parseInt(digits.slice(2, 4), 16),
          Number.parseInt(digits.slice(4, 6), 16)
        ];
      }
      const rgb = normalized.match(/rgba?\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)/i);
      return rgb ? [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])] : null;
    }
    function channel(value) {
      const normalized = value / 255;
      return normalized <= 0.04045
        ? normalized / 12.92
        : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
    function luminance(value) {
      const color = parseColor(value);
      if (!color) return null;
      return 0.2126 * channel(color[0])
        + 0.7152 * channel(color[1])
        + 0.0722 * channel(color[2]);
    }
    function ratio(foreground, background) {
      const left = luminance(foreground);
      const right = luminance(background);
      if (left === null || right === null) return null;
      return (Math.max(left, right) + 0.05) / (Math.min(left, right) + 0.05);
    }
    const root = getComputedStyle(document.documentElement);
    const foreground = root.getPropertyValue('--taxonomy-layer-on-surface').trim();
    const samples = surfaceTokens.map(token => {
      const background = root.getPropertyValue(token).trim();
      return { token, foreground, background, ratio: ratio(foreground, background) };
    });
    const validRatios = samples.map(sample => sample.ratio).filter(Number.isFinite);
    return {
      sampleCount: samples.length,
      missingTokens: samples
        .filter(sample => !sample.foreground || !sample.background || !Number.isFinite(sample.ratio))
        .map(sample => sample.token),
      minimumRatio: validRatios.length ? Math.min(...validRatios) : null,
      samples
    };
  });
  assert(architectureContrast.sampleCount === 9
    && architectureContrast.missingTokens.length === 0
    && architectureContrast.minimumRatio >= 4.5,
  `Architecture layer contrast failed: ${JSON.stringify(architectureContrast)}`);
  passed('contrast-safe architecture layer tokens');
'''

path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
print("Changed live contrast evidence to verify all nine semantic CSS layer tokens.")
