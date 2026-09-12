// Scopes definition for fine-grained principle of least privilege
const crypto = require('crypto');

const SCOPES = {
  DEV: 'dev',
  EMAIL: 'email',
  COMPUTE: 'compute',
  LLM: 'llm',
  WAKEWORD: 'wakeword',
  ADMIN: 'admin'
};

function isPlaceholderToken(val) {
  if (!val || typeof val !== 'string') return true;
  const upper = val.trim().toUpperCase();
  return (
    upper === '' || upper === 'PLACEHOLDER' || upper === 'DUMMY' || upper === 'NULL' ||
    upper === 'UNDEFINED' || upper === 'MY_TOKEN' || upper === 'YOUR_TOKEN' ||
    upper.includes('PLACEHOLDER') || upper.startsWith('MY_') || upper.startsWith('YOUR_') ||
    upper.startsWith('TODO') || upper.startsWith('CHANGEME') || upper.startsWith('DUMMY_') ||
    upper.startsWith('FAKE_')
  );
}

function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/**
 * Validates the secondary administrative credential used for protected patches.
 * A supplied value is never considered valid merely because it is non-empty.
 * The credential must exactly match an explicitly configured server secret.
 */
function isValidAdminCredential(providedToken) {
  if (!providedToken || typeof providedToken !== 'string' || isPlaceholderToken(providedToken)) return false;
  const configured = [
    process.env.WASTI_ADMIN_TOKEN,
    process.env.WASTI_BACKEND_ADMIN_TOKEN,
    process.env.WASTI_BACKEND_AUTH_SECRET,
    process.env.BACKEND_API_SECRET
  ].filter(value => value && !isPlaceholderToken(value));
  return configured.some(secret => constantTimeEqual(providedToken.trim(), secret.trim()));
}

function getAuthorizedScopes(providedToken) {
  if (!providedToken || typeof providedToken !== 'string') return [];
  const token = providedToken.trim();
  if (isPlaceholderToken(token)) return [];

  const masterSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;
  if (masterSecret && !isPlaceholderToken(masterSecret) && constantTimeEqual(token, masterSecret.trim())) {
    return [SCOPES.ADMIN, SCOPES.DEV, SCOPES.EMAIL, SCOPES.COMPUTE, SCOPES.LLM, SCOPES.WAKEWORD];
  }

  const authorizedScopes = [];
  if (process.env.WASTI_COMPUTE_TOKEN && !isPlaceholderToken(process.env.WASTI_COMPUTE_TOKEN) && constantTimeEqual(token, process.env.WASTI_COMPUTE_TOKEN.trim())) authorizedScopes.push(SCOPES.COMPUTE);
  if (process.env.WASTI_DEV_TOKEN && !isPlaceholderToken(process.env.WASTI_DEV_TOKEN) && constantTimeEqual(token, process.env.WASTI_DEV_TOKEN.trim())) authorizedScopes.push(SCOPES.DEV);
  if (process.env.WASTI_EMAIL_TOKEN && !isPlaceholderToken(process.env.WASTI_EMAIL_TOKEN) && constantTimeEqual(token, process.env.WASTI_EMAIL_TOKEN.trim())) authorizedScopes.push(SCOPES.EMAIL);
  if (process.env.WASTI_LLM_TOKEN && !isPlaceholderToken(process.env.WASTI_LLM_TOKEN) && constantTimeEqual(token, process.env.WASTI_LLM_TOKEN.trim())) authorizedScopes.push(SCOPES.LLM);
  if (process.env.WASTI_WAKEWORD_TOKEN && !isPlaceholderToken(process.env.WASTI_WAKEWORD_TOKEN) && constantTimeEqual(token, process.env.WASTI_WAKEWORD_TOKEN.trim())) authorizedScopes.push(SCOPES.WAKEWORD);

  if (process.env.WASTI_SCOPED_TOKENS && !isPlaceholderToken(process.env.WASTI_SCOPED_TOKENS)) {
    try {
      const parsed = JSON.parse(process.env.WASTI_SCOPED_TOKENS);
      if (parsed[token] && Array.isArray(parsed[token])) authorizedScopes.push(...parsed[token]);
    } catch {
      const entries = process.env.WASTI_SCOPED_TOKENS.split(';');
      for (const entry of entries) {
        const [scopeList, secret] = entry.split(':');
        if (secret && !isPlaceholderToken(secret) && constantTimeEqual(secret.trim(), token) && scopeList) {
          authorizedScopes.push(...scopeList.split(',').map(s => s.trim()).filter(Boolean));
        }
      }
    }
  }

  return [...new Set(authorizedScopes)];
}

function createRequireScopeMiddleware() {
  return function requireScope(requiredScope) {
    return function (req, res, next) {
      const authHeader = req.headers ? req.headers['authorization'] : null;
      const tokenHeader = req.headers ? (req.headers['x-wasti-auth-token'] || req.headers['x-api-key']) : null;
      const masterSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;
      const hasAnySecret = Boolean(
        (masterSecret && !isPlaceholderToken(masterSecret)) ||
        (process.env.WASTI_COMPUTE_TOKEN && !isPlaceholderToken(process.env.WASTI_COMPUTE_TOKEN)) ||
        (process.env.WASTI_DEV_TOKEN && !isPlaceholderToken(process.env.WASTI_DEV_TOKEN)) ||
        (process.env.WASTI_EMAIL_TOKEN && !isPlaceholderToken(process.env.WASTI_EMAIL_TOKEN)) ||
        (process.env.WASTI_LLM_TOKEN && !isPlaceholderToken(process.env.WASTI_LLM_TOKEN)) ||
        (process.env.WASTI_WAKEWORD_TOKEN && !isPlaceholderToken(process.env.WASTI_WAKEWORD_TOKEN)) ||
        (process.env.WASTI_SCOPED_TOKENS && !isPlaceholderToken(process.env.WASTI_SCOPED_TOKENS))
      );

      if (!hasAnySecret) return res.status(503).json({ error: 'Backend authentication secret not configured on server. Access blocked.' });

      let providedToken = tokenHeader;
      if (!providedToken && authHeader && authHeader.startsWith('Bearer ')) providedToken = authHeader.substring(7).trim();
      if (!providedToken) return res.status(401).json({ error: 'Unauthorized: Valid Wasti authentication token required' });

      const authorizedScopes = getAuthorizedScopes(providedToken);
      if (authorizedScopes.length === 0) return res.status(401).json({ error: 'Unauthorized: Invalid Wasti authentication token' });

      if (requiredScope && !authorizedScopes.includes(SCOPES.ADMIN) && !authorizedScopes.includes(requiredScope)) {
        return res.status(403).json({ error: `Forbidden: Token lacks required scope '${requiredScope}'. Authorized scopes: ${authorizedScopes.join(', ')}` });
      }

      req.authScopes = authorizedScopes;
      next();
    };
  };
}

const requireScope = createRequireScopeMiddleware();
const requireAuth = requireScope(null);

module.exports = {
  SCOPES,
  isPlaceholderToken,
  constantTimeEqual,
  isValidAdminCredential,
  getAuthorizedScopes,
  createRequireScopeMiddleware,
  requireScope,
  requireAuth
};
