/** Read the actual computed label foreground over its composited ancestor background. */
export async function readButtonContrast(locator) {
  await locator.evaluate(node => Promise.all(
    node.getAnimations().map(animation => animation.finished.catch(() => undefined))
  ));
  return locator.evaluate(node => {
    function rgba(value) {
      const match = value.match(/^rgba?\(([^)]+)\)$/);
      if (!match) throw new Error(`Unsupported computed color: ${value}`);
      const parts = match[1].split(',').map(Number);
      if (![3, 4].includes(parts.length) || parts.some(part => !Number.isFinite(part))) {
        throw new Error(`Invalid computed color: ${value}`);
      }
      return [parts[0], parts[1], parts[2], parts[3] ?? 1];
    }
    const over = (foreground, background) => foreground.slice(0, 3)
      .map((value, index) => value * foreground[3] + background[index] * (1 - foreground[3]));
    const ancestors = [];
    for (let current = node; current; current = current.parentElement) ancestors.push(current);
    let background = [255, 255, 255];
    for (const current of ancestors.reverse()) {
      const style = getComputedStyle(current);
      if (style.backgroundImage !== 'none') throw new Error('Image background needs separate contrast evaluation');
      background = over(rgba(style.backgroundColor), background);
    }
    const style = getComputedStyle(node);
    const foreground = over(rgba(style.color), background);
    const luminance = color => color.map(value => value / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
      .reduce((total, value, index) => total + value * [0.2126, 0.7152, 0.0722][index], 0);
    const foregroundLuminance = luminance(foreground);
    const backgroundLuminance = luminance(background);
    return {
      text: node.textContent.trim(),
      foreground: style.color,
      background: style.backgroundColor,
      compositedForeground: foreground,
      compositedBackground: background,
      contrast: (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
        / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
    };
  });
}

/** Exercise the real import radio group without uploading or starting an operation. */
export async function verifyImportModeContrast(page) {
  const ids = ['modeExtract', 'modeAiExtract', 'modeRegMap'];
  const original = await page.locator('input[name="importMode"]:checked').getAttribute('id');
  if (!ids.includes(original)) throw new Error('No recognized import mode is selected');
  const evidence = [];
  async function record(id, state) {
    const reading = await readButtonContrast(page.locator(`label[for="${id}"]`));
    evidence.push({ id, state, ...reading });
    if (!reading.text || reading.contrast < 4.5) {
      throw new Error(`Unreadable import label ${id}/${state}: ${JSON.stringify(reading)}`);
    }
  }
  try {
    for (const id of ids) {
      await page.locator(`label[for="${id}"]`).click();
      await page.keyboard.press('Tab');
      await page.mouse.move(0, 0);
      const selected = await page.locator(`#${id}`).evaluate(node =>
        node.checked && document.activeElement !== node);
      if (!selected) throw new Error(`Import mode ${id} is not checked and unfocused`);
      for (const candidate of ids) await record(candidate, candidate === id ? 'checked-unfocused' : 'unchecked');
      for (const unchecked of ids.filter(candidate => candidate !== id)) {
        await page.locator(`label[for="${unchecked}"]`).hover();
        await record(unchecked, 'unchecked-hover');
      }
      await page.mouse.move(0, 0);
      await page.locator(`#${id}`).focus();
      await record(id, 'checked-focus');
    }
  } finally {
    await page.locator(`label[for="${original}"]`).click();
    await page.keyboard.press('Tab');
    await page.mouse.move(0, 0);
  }
  return evidence;
}
