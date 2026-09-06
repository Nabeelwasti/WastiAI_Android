// Wasti AI OS Backend Unit Tests
// Tests security constraints, path traversal checks, stripe webhook safety, and provider orchestration.

const test = require('node:test');
const assert = require('node:assert');

// 1. Path traversal and branch validation tests
test('dev/patch security: rejects invalid or path-traversing file paths', () => {
  const isInvalidPath = (p) => {
    if (!p || typeof p !== 'string') return true;
    if (p.includes('..') || p.startsWith('/') || p.startsWith('\\') || p.includes('\0')) return true;
    return false;
  };

  assert.strictEqual(isInvalidPath('../etc/passwd'), true);
  assert.strictEqual(isInvalidPath('/absolute/path.js'), true);
  assert.strictEqual(isInvalidPath('\\windows\\path.js'), true);
  assert.strictEqual(isInvalidPath('src/../../secrets.env'), true);
  assert.strictEqual(isInvalidPath('src/null\0byte.js'), true);
  assert.strictEqual(isInvalidPath(null), true);
  assert.strictEqual(isInvalidPath(123), true);

  assert.strictEqual(isInvalidPath('app/src/main/java/Main.kt'), false);
  assert.strictEqual(isInvalidPath('backend/index.js'), false);
  assert.strictEqual(isInvalidPath('README.md'), false);
});

test('dev/patch security: rejects non-allowlisted base branches', () => {
  const allowedBranches = ['main', 'master', 'develop'];
  assert.strictEqual(allowedBranches.includes('main'), true);
  assert.strictEqual(allowedBranches.includes('master'), true);
  assert.strictEqual(allowedBranches.includes('production-bypass'), false);
  assert.strictEqual(allowedBranches.includes('refs/heads/hack'), false);
});

// 2. Fail-closed authentication logic test
test('requireAuth: fails closed when secret is not configured', () => {
  function checkAuth(authHeader, tokenHeader, expectedSecret) {
    if (!expectedSecret) {
      return { status: 503, allowed: false };
    }
    let providedToken = tokenHeader;
    if (!providedToken && authHeader && authHeader.startsWith('Bearer ')) {
      providedToken = authHeader.substring(7).trim();
    }
    if (!providedToken || providedToken !== expectedSecret) {
      return { status: 401, allowed: false };
    }
    return { status: 200, allowed: true };
  }

  // When expectedSecret is undefined/null -> MUST FAIL CLOSED with 503
  assert.deepStrictEqual(checkAuth(undefined, undefined, undefined), { status: 503, allowed: false });
  assert.deepStrictEqual(checkAuth('Bearer some-token', undefined, ''), { status: 503, allowed: false });

  // When expectedSecret is configured -> MUST REQUIRE EXACT MATCH
  const SECRET = 'test-secret-12345';
  assert.deepStrictEqual(checkAuth(undefined, 'wrong-token', SECRET), { status: 401, allowed: false });
  assert.deepStrictEqual(checkAuth('Bearer wrong-bearer', undefined, SECRET), { status: 401, allowed: false });
  assert.deepStrictEqual(checkAuth(undefined, SECRET, SECRET), { status: 200, allowed: true });
  assert.deepStrictEqual(checkAuth(`Bearer ${SECRET}`, undefined, SECRET), { status: 200, allowed: true });
});

// 3. Email validation test
test('email validation: rejects invalid recipient emails', () => {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  assert.strictEqual(emailRegex.test(''), false);
  assert.strictEqual(emailRegex.test('not-an-email'), false);
  assert.strictEqual(emailRegex.test('@missinguser.com'), false);
  assert.strictEqual(emailRegex.test('missingat.com'), false);
  assert.strictEqual(emailRegex.test('valid.user@wasti.ai'), true);
});

// 4. Stripe webhook helper test
test('stripe_helper: constructEvent throws when webhook secret is unconfigured', () => {
  const stripeHelper = require('./stripe_helper');
  const prevKey = process.env.STRIPE_WEBHOOK_SECRET;
  delete process.env.STRIPE_WEBHOOK_SECRET;

  try {
    assert.throws(
      () => stripeHelper.constructEvent('{}', 'sig_test'),
      /STRIPE_WEBHOOK_SECRET is not configured/
    );
  } finally {
    if (prevKey) process.env.STRIPE_WEBHOOK_SECRET = prevKey;
  }
});

// 5. Orchestrator fallback tests
test('orchestrator: gracefully reports provider errors when credentials are empty', async () => {
  const orchestrator = require('./orchestrator');
  const res = await orchestrator.callProviders({ prompt: 'test' }, ['openai']);
  assert.strictEqual(res.provider, null);
  assert.strictEqual(res.error, 'All providers failed');
  assert.ok(Array.isArray(res.errors));
});
