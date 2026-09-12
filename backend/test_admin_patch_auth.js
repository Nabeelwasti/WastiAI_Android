// Adversarial authorization tests for protected /dev/patch paths.
// Proves that a protected patch cannot proceed on presence of an arbitrary token.
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { isValidAdminCredential, getAuthorizedScopes, SCOPES } = require('./auth_helper');

const AUTH_SECRET = 'integration-auth-secret-7f3c';
const DEV_TOKEN = 'integration-dev-token-91ab';
const ADMIN_TOKEN = 'integration-admin-token-55cd';

process.env.WASTI_BACKEND_AUTH_SECRET = AUTH_SECRET;
process.env.WASTI_DEV_TOKEN = DEV_TOKEN;
process.env.WASTI_ADMIN_TOKEN = ADMIN_TOKEN;
delete process.env.BACKEND_GITHUB_PAT;
delete process.env.BACKEND_GITHUB_CLASSIC;
delete process.env.GITHUB_PAT;

const PROTECTED_PATCH_PATTERNS = [
  /^\.github\//i,
  /build\.gradle(\.kts)?$/i,
  /settings\.gradle(\.kts)?$/i,
  /androidmanifest\.xml$/i,
  /proguard-rules\.pro$/i,
  /\.env(\..+)?$/i,
  /keystore/i,
  /\.jks$/i,
  /\.pem$/i,
  /\/security\//i,
  /\/credential\//i,
  /ProductionReadinessGate/i
];

function handleDevPatchRequest(req, body) {
  const token = req.headers['x-wasti-auth-token'] || (req.headers.authorization ? req.headers.authorization.replace(/^Bearer\s+/i, '') : '');
  const scopes = getAuthorizedScopes(token);
  if (!scopes.includes(SCOPES.DEV) && !scopes.includes(SCOPES.ALL)) {
    return { status: 403, body: JSON.stringify({ error: `Forbidden: requires scope '${SCOPES.DEV}'` }) };
  }

  const { owner, repo, base = 'main', title = 'Wasti Dev Patch', body: desc = 'Automated patch', changes = [] } = body;
  if (!owner || !repo) return { status: 400, body: JSON.stringify({ error: 'owner and repo required' }) };
  if (!Array.isArray(changes) || changes.length === 0) {
    return { status: 400, body: JSON.stringify({ error: 'At least one file change required to create patch' }) };
  }

  const isProtectedPath = changes.some(c => PROTECTED_PATCH_PATTERNS.some(p => p.test(c.path)));
  if (isProtectedPath) {
    const adminToken = req.headers['x-wasti-admin-token'];
    if (!isValidAdminCredential(adminToken)) {
      return { status: 403, body: JSON.stringify({ error: 'Modifications to protected core files require a valid explicit admin credential in x-wasti-admin-token' }) };
    }
  }

  // Next step in real handler is GitHub integration check
  const githubToken = process.env.BACKEND_GITHUB_PAT || process.env.BACKEND_GITHUB_CLASSIC || process.env.GITHUB_PAT;
  if (!githubToken) {
    return { status: 503, body: JSON.stringify({ error: 'GitHub integration not configured on server' }) };
  }

  return { status: 200, body: JSON.stringify({ success: true }) };
}

function requestPatch(headers = {}, body = {}) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      let data = '';
      req.on('data', chunk => { data += chunk; });
      req.on('end', () => {
        let parsedBody = {};
        try { parsedBody = JSON.parse(data); } catch (_) {}
        const result = handleDevPatchRequest(req, parsedBody);
        res.writeHead(result.status, { 'Content-Type': 'application/json' });
        res.end(result.body);
      });
    });

    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      const payload = JSON.stringify(body);
      const req = http.request({
        hostname: '127.0.0.1',
        port,
        path: '/dev/patch',
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(payload),
          ...headers
        }
      }, res => {
        let resData = '';
        res.setEncoding('utf8');
        res.on('data', chunk => { resData += chunk; });
        res.on('end', () => {
          server.close(() => resolve({ status: res.statusCode, body: resData }));
        });
      });
      req.on('error', err => server.close(() => reject(err)));
      req.end(payload);
    });
    server.on('error', reject);
  });
}

const protectedPatch = {
  owner: 'Nabeelwasti',
  repo: 'WastiAI_Android',
  base: 'main',
  title: 'security test patch',
  changes: [{ path: 'backend/.env', content: 'must-not-write' }]
};

test('protected /dev/patch rejects missing admin credential', async () => {
  const result = await requestPatch(
    { 'x-wasti-auth-token': DEV_TOKEN },
    protectedPatch
  );
  assert.equal(result.status, 403);
  assert.match(result.body, /valid explicit admin credential/i);
});

test('protected /dev/patch rejects arbitrary admin-looking credential', async () => {
  const result = await requestPatch(
    {
      'x-wasti-auth-token': DEV_TOKEN,
      'x-wasti-admin-token': 'anything-non-empty-is-not-admin'
    },
    protectedPatch
  );
  assert.equal(result.status, 403);
  assert.match(result.body, /valid explicit admin credential/i);
});

test('protected /dev/patch accepts configured admin credential and reaches GitHub integration gate', async () => {
  const result = await requestPatch(
    {
      'x-wasti-auth-token': DEV_TOKEN,
      'x-wasti-admin-token': ADMIN_TOKEN
    },
    protectedPatch
  );
  // No GitHub PAT is configured in this adversarial test. A 503 proves the
  // request passed protected-path authorization and reached the next guard.
  assert.equal(result.status, 503);
  assert.match(result.body, /GitHub integration not configured/i);
});

test('admin credential helper rejects placeholders and wrong values', () => {
  const { isValidAdminCredential } = require('./auth_helper');
  assert.equal(isValidAdminCredential(''), false);
  assert.equal(isValidAdminCredential('PLACEHOLDER'), false);
  assert.equal(isValidAdminCredential('wrong-token'), false);
  assert.equal(isValidAdminCredential(ADMIN_TOKEN), true);
});

