// Scopes definition for fine-grained principle of least privilege
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
    upper === '' ||
    upper === 'PLACEHOLDER' ||
    upper === 'DUMMY' ||
    upper === 'NULL' ||
    upper === 'UNDEFINED' ||
    upper === 'MY_TOKEN' ||
    upper === 'YOUR_TOKEN' ||
    upper.includes('PLACEHOLDER') ||
    upper.startsWith('MY_') ||
    upper.startsWith('YOUR_') ||
    upper.startsWith('TODO') ||
    upper.startsWith('CHANGEME') ||
    upper.startsWith('DUMMY_') ||
    upper.startsWith('FAKE_')
  );
}

function getAuthorizedScopes(providedToken) {
  if (!providedToken || typeof providedToken !== 'string') return [];
  const token = providedToken.trim();
  if (isPlaceholderToken(token)) return [];

  const masterSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;

  // Master secret grants full administrative access across all scopes
  if (masterSecret && !isPlaceholderToken(masterSecret) && token === masterSecret) {
    return [SCOPES.ADMIN, SCOPES.DEV, SCOPES.EMAIL, SCOPES.COMPUTE, SCOPES.LLM, SCOPES.WAKEWORD];
  }

  const authorizedScopes = [];

  // Capability-specific secret checks
  if (process.env.WASTI_COMPUTE_TOKEN && !isPlaceholderToken(process.env.WASTI_COMPUTE_TOKEN) && token === process.env.WASTI_COMPUTE_TOKEN) {
    authorizedScopes.push(SCOPES.COMPUTE);
  }
  if (process.env.WASTI_DEV_TOKEN && !isPlaceholderToken(process.env.WASTI_DEV_TOKEN) && token === process.env.WASTI_DEV_TOKEN) {
    authorizedScopes.push(SCOPES.DEV);
  }
  if (process.env.WASTI_EMAIL_TOKEN && !isPlaceholderToken(process.env.WASTI_EMAIL_TOKEN) && token === process.env.WASTI_EMAIL_TOKEN) {
    authorizedScopes.push(SCOPES.EMAIL);
  }
  if (process.env.WASTI_LLM_TOKEN && !isPlaceholderToken(process.env.WASTI_LLM_TOKEN) && token === process.env.WASTI_LLM_TOKEN) {
    authorizedScopes.push(SCOPES.LLM);
  }
  if (process.env.WASTI_WAKEWORD_TOKEN && !isPlaceholderToken(process.env.WASTI_WAKEWORD_TOKEN) && token === process.env.WASTI_WAKEWORD_TOKEN) {
    authorizedScopes.push(SCOPES.WAKEWORD);
  }

  // Support fine-grained token map format in WASTI_SCOPED_TOKENS:
  // e.g., JSON {"token123": ["compute", "llm"]} or semicolon-separated "compute,llm:token123;dev:token456"
  if (process.env.WASTI_SCOPED_TOKENS && !isPlaceholderToken(process.env.WASTI_SCOPED_TOKENS)) {
    try {
      const parsed = JSON.parse(process.env.WASTI_SCOPED_TOKENS);
      if (parsed[token] && Array.isArray(parsed[token])) {
        authorizedScopes.push(...parsed[token]);
      }
    } catch {
      const entries = process.env.WASTI_SCOPED_TOKENS.split(';');
      for (const entry of entries) {
        const [scopeList, secret] = entry.split(':');
        if (secret && !isPlaceholderToken(secret) && secret.trim() === token && scopeList) {
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

      if (!hasAnySecret) {
        console.error('CRITICAL: Backend authentication secret not configured. Failing closed.');
        return res.status(503).json({ error: 'Backend authentication secret not configured on server. Access blocked.' });
      }

      let providedToken = tokenHeader;
      if (!providedToken && authHeader && authHeader.startsWith('Bearer ')) {
        providedToken = authHeader.substring(7).trim();
      }

      if (!providedToken) {
        return res.status(401).json({ error: 'Unauthorized: Valid Wasti authentication token required' });
      }

      const authorizedScopes = getAuthorizedScopes(providedToken);
      if (authorizedScopes.length === 0) {
        return res.status(401).json({ error: 'Unauthorized: Invalid Wasti authentication token' });
      }

      if (requiredScope && !authorizedScopes.includes(SCOPES.ADMIN) && !authorizedScopes.includes(requiredScope)) {
        return res.status(403).json({
          error: `Forbidden: Token lacks required scope '${requiredScope}'. Authorized scopes: ${authorizedScopes.join(', ')}`
        });
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
  getAuthorizedScopes,
  createRequireScopeMiddleware,
  requireScope,
  requireAuth
};
