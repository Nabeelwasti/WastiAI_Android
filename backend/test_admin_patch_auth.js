// Adversarial authorization tests for protected /dev/patch paths.
// Proves that a protected patch cannot proceed on presence of an arbitrary token.

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');

const AUTH_SECRET = 'integration-auth-secret-7f3c';
const DEV_TOKEN = 'integration-dev-token-91ab';
const ADMIN_TOKEN = 'integration-admin-token-55cd';

process.env.WASTI_BACKEND_AUTH_SECRET = AUTH_SECRET;
process.env.WASTI_DEV_TOKEN = DEV_TOKEN;
process.env.WASTI_ADMIN_TOKEN = ADMIN_TOKEN;
delete process.env.BACKEND_GITHUB_PAT;
delete process.env.BACKEND_GITHUB_CLASSIC;
delete process.env.GITHUB_PAT;

const { app } = require('./index');

function requestPatch(headers = {}, body = {}) {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, '127.0.0.1', () => {
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
        let data = '';
        res.setEncoding('utf8');
        res.on('data', chunk => { data += chunk; });
        res.on('end', () => {
          server.close(() => resolve({ status: res.statusCode, body: data }));
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
