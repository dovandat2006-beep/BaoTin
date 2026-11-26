// Runs the fixture's real script against synthetic transport, not a provider/browser.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {webcrypto} = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, 'oauth-check.html'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace('__OAUTH_CHECK_CONFIG__', JSON.stringify({api:'https://fixture.example:9443',
    origin:'https://fixture.example:9444', nonce:'synthetic-run-nonce'}));

function fixture(role = 'USER') {
  const elements = new Map();
  const storage = new Map();
  const reports = []; const requests = []; const history = [];
  let exchanges = 0;
  const response = (status, body = {}) => ({status, json:async () => body});
  const sandbox = {
    crypto:webcrypto, TextEncoder, Uint8Array, URL, URLSearchParams, Date,
    btoa:s => Buffer.from(s, 'binary').toString('base64'),
    document:{getElementById(id) {if (!elements.has(id)) elements.set(id, {value:'', hidden:true}); return elements.get(id);}},
    sessionStorage:{setItem:(k,v) => storage.set(k,v), getItem:k => storage.get(k), removeItem:k => storage.delete(k)},
    location:{pathname:'/', search:''}, history:{replaceState:(_s,_t,p) => history.push(p)},
    window:{location:{assign(url) {sandbox.destination = url;}}},
    fetch:async (uri, options) => {
      const body = options.body && JSON.parse(options.body); requests.push({uri, options, body});
      if (uri === '/result') {reports.push(body); return response(204);}
      const route = new URL(uri).pathname;
      if (route.endsWith('/oauth2/requests')) return response(200, {authorizationUrl:'https://accounts.google.com/o/oauth2/v2/auth?state=synthetic'});
      if (route === '/api/v1/auth/oauth2/tokens') return response(401);
      if (route === '/api/v1/auth/web/oauth2/tokens') {
        exchanges++;
        if (exchanges !== 2) return response(401);
        return response(200, {accessToken:'synthetic-access-one', csrfToken:'synthetic-csrf-one'});
      }
      if (route.endsWith('/admin/users')) return response(403);
      if (route.endsWith('/web/refresh')) return options.headers['X-CSRF-TOKEN']
        ? response(200, {accessToken:'synthetic-access-two', csrfToken:'synthetic-csrf-two'}) : response(403);
      if (route.endsWith('/web/logout')) return response(204);
      if (route.endsWith('/me')) return options.headers.Authorization.endsWith('synthetic-access-two')
        ? response(401) : response(200, {role});
      throw new Error('Unexpected fixture route');
    }
  };
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  return {sandbox, storage, reports, requests, history, elements};
}

(async () => {
  const f = fixture();
  await vm.runInContext("start('GOOGLE')", f.sandbox);
  assert.equal(new URL(f.sandbox.destination).hostname, 'accounts.google.com');
  assert.ok(f.storage.size === 1);
  const pending = JSON.parse([...f.storage.values()][0]);
  assert.equal(pending.verifier.length, 43);
  assert.equal(f.requests[0].body.transport, 'WEB');
  f.sandbox.location.search = '?code=' + 'A'.repeat(43);
  await vm.runInContext('callback()', f.sandbox);
  assert.equal(f.history[0], '/auth/callback');
  assert.equal(f.reports.length, 1); assert.equal(f.reports[0].passed, true);
  assert.equal(f.reports[0].wrongPkce, 401); assert.equal(f.reports[0].wrongTransport, 401);
  assert.equal(f.reports[0].replay, 401); assert.equal(f.reports[0].admin, 403);
  assert.equal(f.reports[0].missingCsrf, 403); assert.equal(f.reports[0].revoked, 401);
  assert.equal(f.storage.size, 0);
  assert.ok(!JSON.stringify(f.reports).includes('synthetic-access'));
  const denied = fixture();
  await vm.runInContext("start('GOOGLE')", denied.sandbox);
  denied.sandbox.location.search = '?error=oauth_denied';
  await vm.runInContext('callback()', denied.sandbox);
  assert.equal(denied.reports[0].phase, 'denied'); assert.equal(denied.reports[0].passed, false);
  assert.equal(denied.storage.size, 0);
  const bad = fixture('ADMIN');
  await vm.runInContext("start('GOOGLE')", bad.sandbox);
  bad.sandbox.location.search = '?code=' + 'A'.repeat(43);
  await vm.runInContext('callback().catch(failure)', bad.sandbox);
  assert.equal(bad.reports[0].passed, false); assert.equal(bad.storage.size, 0);
  assert.ok(bad.requests.some(r => r.uri.endsWith('/web/logout')));
  console.log('OAUTH_BROWSER_TESTS PASS cases=3 web_pkce=verified session_logout=verified denied_consent=no_session wrong_role=failed browser_secrets=not_persisted reports=status_only');
})().catch(() => {console.error('OAUTH_BROWSER_TESTS FAILED (synthetic diagnostics withheld)'); process.exitCode = 1;});
