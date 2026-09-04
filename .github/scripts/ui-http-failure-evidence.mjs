/** Bounded diagnostic evidence; never changes which HTTP/console errors fail a test. */
export function observeHttpFailures(page) {
  const evidence = { requests: [], suppressed: 0 };
  function record(request, status) {
    if (evidence.requests.length >= 100) { evidence.suppressed++; return; }
    let path = '[invalid-url]';
    try {
      const url = new URL(request.url());
      if (['http:', 'https:'].includes(url.protocol)) {
        // Drop origin/userinfo, query and fragment. Strip path session parameters too.
        path = url.pathname.replace(/;[^/]*/g, ';[redacted]').slice(0, 512);
      }
    } catch { /* Do not reflect malformed URLs into the report. */ }
    const method = request.method();
    evidence.requests.push({ path,
      method: /^[A-Z]{1,16}$/.test(method) ? method : 'UNKNOWN', status });
  }
  page.on('response', response => {
    if (response.status() >= 400) record(response.request(), response.status());
  });
  page.on('requestfailed', request => record(request, null));
  return evidence;
}
