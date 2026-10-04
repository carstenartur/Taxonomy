/** Measure rendered layout rather than an unstyled navigation document. */
export async function pageFitsViewport(page) {
  // A successful click/DOM assertion does not imply that navigation CSS is ready.
  // Keep the load timeout and the geometry assertion blocking; do not retry overflow.
  await page.waitForLoadState('load');
  return page.evaluate(async () => {
    await document.fonts.ready;
    return document.documentElement.scrollWidth <= window.innerWidth + 2;
  });
}
