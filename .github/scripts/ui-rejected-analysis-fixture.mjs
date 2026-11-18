/** Correlates the deliberately rejected durable START with its missing receipt observations. */
export function createRejectedAnalysisFixture() {
  let expected;
  let rejectedStarts = 0;
  let missingReceipts = 0;
  return {
    async rejectStart(route) {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const body = request.postDataJSON();
      if (expected || request.method() !== 'POST' || !path.endsWith('/api/analyze')
          || body?.resumable !== true || body.continuationAction !== 'START'
          || typeof body.continuationId !== 'string'
          || !/^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(body.continuationId)) {
        throw new Error('The failure fixture requires one resumable START with a continuation UUID');
      }
      // Capture the actual target before fulfilling: its admission observation
      // can start as soon as the injected response reaches the application.
      expected = {
        startPath: path,
        receiptPath: path.replace(/\/analyze$/, '/analysis-continuations/' + body.continuationId),
        requestId: 'qa-rejected-analysis-' + body.continuationId
      };
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        headers: { 'x-request-id': expected.requestId },
        body: JSON.stringify({ error: 'QA_PROVIDER_UNAVAILABLE', message: 'Deterministic QA failure' })
      });
    },
    matches(failure) {
      if (!expected) return false;
      if (failure.method === 'POST' && failure.path === expected.startPath
          && failure.status === 503 && failure.requestId === expected.requestId) {
        rejectedStarts += 1;
        return rejectedStarts === 1;
      }
      if (failure.method === 'GET' && failure.path === expected.receiptPath && failure.status === 404) {
        // A status read may already be in flight when START is rejected. Recovery
        // then observes the same identity again after that decision; those reads
        // do not submit another START or create another continuation.
        missingReceipts += 1;
        return true;
      }
      return false;
    },
    assertComplete() {
      if (!expected || rejectedStarts !== 1 || missingReceipts < 1) {
        throw new Error('Expected exactly one injected START rejection and at least one same-ID missing receipt: '
          + JSON.stringify({ expected, rejectedStarts, missingReceipts }));
      }
    }
  };
}
