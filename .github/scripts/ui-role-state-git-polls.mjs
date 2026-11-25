// Serialized by Playwright into each role-state document before app startup.
export function installRoleStateGitPollBarrier() {
  if (window.__taxonomyRoleStateGitPolls) return;
  const pending = new Set();
  let paused;

  function wrapClient(client) {
    const request = client.request;
    client.request = function (url, init, policy) {
      const path = new URL(url, window.location.href).pathname;
      const method = (init?.method || 'GET').toUpperCase();
      // Git status has a distinct transport policy. Its server-contact event
      // can start a second passive observation of the draft revision.
      const gitPoll = path.endsWith('/api/git/state') && policy?.acceptedStatuses?.length === 0;
      const draftStatus = /\/api\/analysis-drafts\/[^/]+\/status$/.test(path);
      if (method !== 'GET' || !policy?.signal || (!gitPoll && !draftStatus)) {
        return request.call(this, url, init, policy);
      }

      const dispatch = async () => {
        const completion = Promise.withResolvers();
        pending.add(completion.promise);
        function complete() {
          pending.delete(completion.promise);
          completion.resolve();
        }
        try {
          const response = await request.call(this, url, init, policy);
          if (response.status === 204) {
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
      const ownsContext = client.captureContext();
      return paused.promise.then(() => {
        if (policy.signal.aborted || !ownsContext()) {
          throw new client.ApiError('Deferred poll context was superseded', 0, url, null, { code: 'ABORTED' });
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
