// Serialized by Playwright into each role-state document before app startup.
export function installRoleStateGitPollBarrier() {
  if (window.__taxonomyRoleStateGitPolls) return;
  const pending = new Set();
  let paused;

  function wrapClient(client) {
    const request = client.request;
    client.request = function (url, init, policy) {
      const target = new URL(url, window.location.href);
      const path = target.pathname;
      const method = (init?.method || 'GET').toUpperCase();
      // Git status has a distinct transport policy. Its server-contact event
      // can start a second passive observation of the draft revision.
      const gitPoll = path.endsWith('/api/git/state') && policy?.acceptedStatuses?.length === 0;
      const draftStatus = /\/api\/analysis-drafts\/[^/]+\/status$/.test(path);
      // Source hydration also reads the selected repository's proposal inbox.
      const proposalList = target.origin === window.location.origin && policy?.selectedRepository === true
        && /\/api\/proposals(?:\/pending)?$/.test(path);
      if (method !== 'GET' || !policy?.signal || (!gitPoll && !draftStatus && !proposalList)) {
        return request.call(this, url, init, policy);
      }

      const ownsContext = client.captureContext();
      const dispatch = async () => {
        const completion = Promise.withResolvers();
        pending.add(completion.promise);
        function complete() {
          if (!pending.delete(completion.promise)) return;
          policy.signal.removeEventListener('abort', complete);
          completion.resolve();
        }
        policy.signal.addEventListener('abort', complete, { once: true });
        if (policy.signal.aborted) complete();
        try {
          const response = await request.call(this, url, init, policy);
          if (!response.ok || response.status === 204 || !ownsContext()) {
            complete();
            return response;
          }
          return new Proxy(response, {
            get(target, property) {
              if (property !== 'json') return Reflect.get(target, property);
              return async (...args) => {
                try { return await target.json(...args); }
                finally { complete(); }
              };
            }
          });
        } catch (error) {
          complete();
          throw error;
        }
      };

      if (!paused) return dispatch();
      return paused.promise.then(() => {
        if (policy.signal.aborted || !ownsContext()) {
          throw new client.ApiError('Deferred read context was superseded', 0, url, null, { code: 'ABORTED' });
        }
        return dispatch();
      });
    };
  }

  function resume() {
    const release = paused;
    paused = null;
    release?.resolve();
  }

  window.__taxonomyRoleStateGitPolls = {
    async pause() {
      if (paused) throw new Error('An accessibility audit already owns the poll barrier');
      paused = Promise.withResolvers();
      await Promise.all(pending);
    },
    resume
  };
  window.addEventListener('pagehide', resume);

  if (window.TaxonomyApiClient) {
    wrapClient(window.TaxonomyApiClient);
  } else {
    Object.defineProperty(window, 'TaxonomyApiClient', {
      configurable: true,
      set(client) {
        wrapClient(client);
        Object.defineProperty(window, 'TaxonomyApiClient', {
          configurable: true, enumerable: true, writable: true, value: client
        });
      }
    });
  }
}
