// A callback vocabulary for the JUnit owner. It has no autonomous test runner.
const state = { cases: [], before: [], after: [], beforeEach: [], afterEach: [] };
function declaration(name, options, body) {
  if (typeof options === 'function') { body = options; options = {}; }
  if (typeof name !== 'string' || typeof body !== 'function') throw Error('Unsupported node:test declaration');
  const unknown = Object.keys(options || {}).filter(key => !['skip','todo','timeout','concurrency'].includes(key));
  if (unknown.length || options?.concurrency) throw Error(`Unsupported node:test options: ${JSON.stringify(options)}`);
  state.cases.push({ name, options: options || {}, body });
}
for (const hook of ['before','after','beforeEach','afterEach']) declaration[hook] = callback => state[hook].push(callback);
for (const unsupported of ['only','skip','todo']) declaration[unsupported] = () => { throw Error(`Unsupported node:test.${unsupported}; use explicit declaration options`); };
declaration.test = declaration;
declaration.state = state;
module.exports = declaration;
