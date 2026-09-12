try {
  require('dotenv').config();
} catch (e) {
  // dotenv optional when process.env is injected by deployment runtime
}
const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const { Octokit } = require('@octokit/rest');
const orchestrator = require('./orchestrator');
const brevo = require('./brevo');
const stripeHelper = require('./stripe_helper');
const firebaseHelper = require('./firebase_helper');
const wakewordQueue = require('./wakeword_queue');
const { isValidAdminCredential } = require('./auth_helper');

const app = express();

// Production CORS Security Policy
const allowedOrigins = (process.env.ALLOWED_ORIGINS || 'http://localhost:3000,http://localhost:8080')
  .split(',')
  .map(o => o.trim())
  .filter(Boolean);

app.use(cors({
  origin: function(origin, callback) {
    if (!origin || allowedOrigins.includes('*') || allowedOrigins.includes(origin)) {
      return callback(null, true);
    }
    return callback(new Error('Blocked by CORS policy: Origin not allowed'));
  },
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type', 'Authorization', 'x-wasti-auth-token', 'x-api-key', 'x-wasti-admin-token', 'x-approval-token', 'stripe-signature']
}));

// Basic Rate Limiting / Request Throttling In-Memory Counter
const requestCounts = new Map();
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 120;

app.use((req, res, next) => {
  const ip = req.ip || req.connection.remoteAddress || 'unknown';
  const now = Date.now();
  const clientRecord = requestCounts.get(ip) || { count: 0, resetAt: now + RATE_LIMIT_WINDOW_MS };

  if (now > clientRecord.resetAt) {
    clientRecord.count = 1;
    clientRecord.resetAt = now + RATE_LIMIT_WINDOW_MS;
  } else {
    clientRecord.count++;
  }
  requestCounts.set(ip, clientRecord);

  if (clientRecord.count > MAX_REQUESTS_PER_WINDOW) {
    return res.status(429).json({ error: 'Too Many Requests', retryAfterMs: clientRecord.resetAt - now });
  }
  next();
});

// Stripe webhook raw body handling with strict cryptographic signature verification & durable idempotency
// NOTE: Must be mounted BEFORE global bodyParser.json() to preserve raw Buffer for signature verification
app.post('/stripe/webhook', bodyParser.raw({ type: 'application/json' }), async (req, res) => {
  try {
    const sig = req.headers['stripe-signature'];
    let event;
    try {
      event = stripeHelper.constructEvent(req.body, sig);
    } catch (err) {
      console.error('stripe webhook verification rejected:', err.message);
      return res.status(400).send(`Webhook Error: ${err.message}`);
    }

    if (!event || !event.id) {
      return res.status(400).json({ error: 'Malformed webhook event: missing event id' });
    }

    // Enforce timestamp freshness to prevent delayed replay attacks
    const timeValidation = stripeHelper.validateEventTimestamp(event);
    if (!timeValidation.valid) {
      console.warn(`Stripe event ${event.id} failed timestamp freshness check:`, timeValidation.error);
      return res.status(400).json({ error: 'Replay rejected: ' + timeValidation.error });
    }

    // Check durable idempotency
    if (stripeHelper.isEventProcessed(event.id)) {
      console.log(`Duplicate Stripe webhook event detected: ${event.id} (${event.type})`);
      return res.json({ received: true, eventType: event.type, duplicate: true });
    }

    // Mark event processed in durable store
    stripeHelper.recordProcessedEvent(event.id, { type: event.type, receivedAt: Date.now() });

    console.log('Verified Stripe event processed:', event.type, event.id);
    return res.json({ received: true, eventType: event.type, duplicate: false });
  } catch (err) {
    console.error('stripe webhook handler error:', err.message);
    res.status(500).send('Internal server error');
  }
});


// JSON Body Parser for standard endpoints
app.use(bodyParser.json({ limit: '2mb' }));

const PORT = process.env.PORT || 8080;
const GITHUB_PAT = process.env.BACKEND_GITHUB_PAT || process.env.BACKEND_GITHUB_CLASSIC || process.env.GITHUB_PAT || null;
const octokit = GITHUB_PAT ? new Octokit({ auth: GITHUB_PAT }) : null;

// Scopes definition for fine-grained principle of least privilege
const SCOPES = {
  DEV: 'dev',
  EMAIL: 'email',
  COMPUTE: 'compute',
  LLM: 'llm',
  WAKEWORD: 'wakeword',
  ADMIN: 'admin'
};

function getAuthorizedScopes(providedToken) {
  if (!providedToken) return [];
  const masterSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;
  if (masterSecret && providedToken === masterSecret) {
    return [SCOPES.ADMIN, SCOPES.DEV, SCOPES.EMAIL, SCOPES.COMPUTE, SCOPES.LLM, SCOPES.WAKEWORD];
  }

  const scopes = [];
  if (process.env.WASTI_DEV_TOKEN && providedToken === process.env.WASTI_DEV_TOKEN) scopes.push(SCOPES.DEV);
  if (process.env.WASTI_EMAIL_TOKEN && providedToken === process.env.WASTI_EMAIL_TOKEN) scopes.push(SCOPES.EMAIL);
  if (process.env.WASTI_COMPUTE_TOKEN && providedToken === process.env.WASTI_COMPUTE_TOKEN) scopes.push(SCOPES.COMPUTE);
  if (process.env.WASTI_LLM_TOKEN && providedToken === process.env.WASTI_LLM_TOKEN) scopes.push(SCOPES.LLM);
  if (process.env.WASTI_WAKEWORD_TOKEN && providedToken === process.env.WASTI_WAKEWORD_TOKEN) scopes.push(SCOPES.WAKEWORD);

  if (process.env.WASTI_SCOPED_TOKENS) {
    try {
      const parsed = JSON.parse(process.env.WASTI_SCOPED_TOKENS);
      if (parsed[providedToken]) {
        const tokenScopes = Array.isArray(parsed[providedToken]) ? parsed[providedToken] : [parsed[providedToken]];
        scopes.push(...tokenScopes);
      }
    } catch (_) {}
  }

  return scopes;
}

function requireScope(requiredScope) {
  return function (req, res, next) {
    const authHeader = req.headers['authorization'];
    const tokenHeader = req.headers['x-wasti-auth-token'] || req.headers['x-api-key'];
    const masterSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;

    const hasAnySecret = Boolean(
      masterSecret ||
      process.env.WASTI_COMPUTE_TOKEN ||
      process.env.WASTI_DEV_TOKEN ||
      process.env.WASTI_EMAIL_TOKEN ||
      process.env.WASTI_LLM_TOKEN ||
      process.env.WASTI_WAKEWORD_TOKEN ||
      process.env.WASTI_SCOPED_TOKENS
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
}

function requireAuth(req, res, next) {
  return requireScope(null)(req, res, next);
}

// Health check endpoint with subsystem status
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: Date.now(),
    githubConfigured: Boolean(octokit),
    brevoConfigured: Boolean(process.env.BREVO_API_KEY),
    stripeConfigured: Boolean(process.env.STRIPE_SECRET_KEY || process.env.BACKEND_STRIPE_SECRET),
    firebaseConfigured: Boolean(process.env.FIREBASE_SA_BASE64 || process.env.FIREBASE_SA_PATH),
    authEnforced: Boolean(process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET)
  });
});

app.post('/llm', requireScope(SCOPES.LLM), async (req, res) => {
  try {
    const { provider = 'openai', payload = {}, priority } = req.body;
    if (!payload || (typeof payload !== 'object' && typeof payload !== 'string')) {
      return res.status(400).json({ error: 'Payload must be a valid object or string' });
    }
    const order = priority || (provider === 'openai' ? ['openai', 'gemini', 'local'] : [provider, 'local']);
    const result = await orchestrator.callProviders(payload, order);
    if (result.error) return res.status(500).json(result);
    return res.json(result);
  } catch (err) {
    console.error('/llm error', err.message);
    res.status(500).json({ error: 'LLM orchestration failed', detail: err.message || String(err) });
  }
});

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

app.post('/dev/patch', requireScope(SCOPES.DEV), async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GitHub integration not configured on server' });
    const { owner, repo, base = 'main', title = 'Wasti Dev Patch', body = 'Automated patch', changes = [] } = req.body;
    if (!owner || !repo) return res.status(400).json({ error: 'owner and repo required' });
    if (!Array.isArray(changes) || changes.length === 0) {
      return res.status(400).json({ error: 'At least one file change required to create patch' });
    }

    const allowedRepos = process.env.ALLOWED_GITHUB_REPOS
      ? process.env.ALLOWED_GITHUB_REPOS.split(',').map(r => r.trim().toLowerCase())
      : ['nabeelwasti/wastiai_android'];
    if (!allowedRepos.includes(`${owner}/${repo}`.toLowerCase()) && !allowedRepos.includes(repo.toLowerCase())) {
      return res.status(403).json({ error: 'Repository not in authorized allowlist for automated patches' });
    }

    const allowedBranches = (process.env.ALLOWED_PATCH_BRANCHES || 'main,master,develop')
      .split(',')
      .map(b => b.trim())
      .filter(Boolean);
    if (!allowedBranches.includes(base)) {
      return res.status(400).json({ error: `Base branch '${base}' is not allowed for automated patches` });
    }

    for (const c of changes) {
      if (!c.path || typeof c.path !== 'string') {
        return res.status(400).json({ error: 'Invalid change: path must be a non-empty string' });
      }
      if (c.path.includes('..') || c.path.startsWith('/') || c.path.startsWith('\\') || c.path.includes('\0')) {
        return res.status(400).json({ error: `Security violation: Path traversal or absolute path detected: ${c.path}` });
      }
      if (typeof c.content !== 'string') {
        return res.status(400).json({ error: `Invalid content for file ${c.path}` });
      }
    }

    // [P0-40] Protected path enforcement: require an explicitly configured admin credential.
    const touchesProtectedPath = changes.some(c =>
      PROTECTED_PATCH_PATTERNS.some(pattern => pattern.test(c.path))
    );
    const adminToken = req.headers['x-wasti-admin-token'] || req.body.adminAuthToken;
    if (touchesProtectedPath && !isValidAdminCredential(adminToken)) {
      return res.status(403).json({
        error: 'Security violation: Protected security/signing paths require a valid explicit admin credential',
        protected: true
      });
    }

    if (changes.length > 20) {
      return res.status(400).json({ error: 'Patch exceeds maximum allowed file count (20)' });
    }
    const totalPayloadBytes = changes.reduce((sum, c) => sum + (c.content ? c.content.length : 0), 0);
    if (totalPayloadBytes > 200000) {
      return res.status(400).json({ error: 'Patch exceeds maximum allowed payload size (200KB)' });
    }

    const baseRef = `heads/${base}`;
    const mainRef = await octokit.git.getRef({ owner, repo, ref: baseRef });
    const baseSha = mainRef.data.object.sha;
    const branchName = `wasti/patch-${Date.now()}`;
    await octokit.git.createRef({ owner, repo, ref: `refs/heads/${branchName}`, sha: baseSha });

    const blobs = [];
    for (const c of changes) {
      const blob = await octokit.git.createBlob({ owner, repo, content: c.content, encoding: 'utf-8' });
      blobs.push({ path: c.path, mode: '100644', type: 'blob', sha: blob.data.sha });
    }
    const baseCommit = await octokit.git.getCommit({ owner, repo, commit_sha: baseSha });
    const newTree = await octokit.git.createTree({ owner, repo, tree: blobs, base_tree: baseCommit.data.tree.sha });
    const newCommit = await octokit.git.createCommit({ owner, repo, message: title, tree: newTree.data.sha, parents: [baseSha] });
    await octokit.git.updateRef({ owner, repo, ref: `refs/heads/${branchName}`, sha: newCommit.data.sha });

    const pr = await octokit.pulls.create({ owner, repo, title, head: branchName, base, body });
    return res.json({ prUrl: pr.data.html_url, branch: branchName });
  } catch (err) {
    console.error('dev/patch failed', err?.response?.data || err.message || err);
    res.status(500).json({ error: 'dev/patch failed', detail: err?.response?.data?.message || err.message });
  }
});

app.post('/email/send', requireScope(SCOPES.EMAIL), async (req, res) => {
  try {
    const { to, subject, html, from } = req.body;
    if (!to || !subject || !html) return res.status(400).json({ error: 'to, subject, html required' });
    
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(to)) {
      return res.status(400).json({ error: 'Invalid recipient email address format' });
    }

    const allowedEmailDomains = process.env.ALLOWED_EMAIL_DOMAINS
      ? process.env.ALLOWED_EMAIL_DOMAINS.split(',').map(d => d.trim().toLowerCase()).filter(Boolean)
      : null;
    if (allowedEmailDomains && allowedEmailDomains.length > 0) {
      const domain = to.split('@')[1]?.toLowerCase();
      if (!domain || !allowedEmailDomains.includes(domain)) {
        return res.status(403).json({ error: `Recipient domain '${domain}' is not in authorized email domain allowlist` });
      }
    }

    const approval = req.headers['x-approval-token'];
    if (!process.env.OUTREACH_APPROVAL_TOKEN) {
      return res.status(503).json({ error: 'Outreach approval token not configured on server' });
    }
    if (!approval || approval !== process.env.OUTREACH_APPROVAL_TOKEN) {
      return res.status(403).json({ error: 'Email sending requires a verified approval token' });
    }
    const out = await brevo.sendEmail({ toEmail: to, toName: to, subject, htmlContent: html, fromEmail: from?.email, fromName: from?.name });
    return res.json({ status: 'sent', detail: out });
  } catch (err) {
    console.error('email/send failed', err.message);
    res.status(500).json({ error: 'email/send failed', detail: err.message || String(err) });
  }
});

app.post('/wakeword', requireScope(SCOPES.WAKEWORD), async (req, res) => {
  try {
    const payload = req.body;
    if (!payload || typeof payload !== 'object' || !payload.event || typeof payload.event !== 'object') {
      return res.status(400).json({ error: 'Invalid wakeword payload: must contain an event object' });
    }
    if (process.env.FIREBASE_SA_BASE64 || process.env.FIREBASE_SA_PATH) {
      if (payload.token) {
        const out = await firebaseHelper.sendPush(payload.token, { wakeword: JSON.stringify(payload.event) });
        return res.json({ status: 'pushed', detail: out });
      }
      const queuedItem = wakewordQueue.enqueueEvent(payload.event, { awaitingToken: true });
      return res.status(202).json({
        status: 'QUEUED_AWAITING_DEVICE_TOKEN',
        eventId: queuedItem.eventId,
        queueDepth: wakewordQueue.getQueueStatus().queueDepth,
        detail: 'Firebase configured; event buffered pending device registration token.'
      });
    }

    const queuedItem = wakewordQueue.enqueueEvent(payload.event);
    return res.status(202).json({
      status: 'QUEUED_IN_MEMORY',
      eventId: queuedItem.eventId,
      durable: false,
      queueDepth: wakewordQueue.getQueueStatus().queueDepth,
      detail: 'Event buffered in process memory. Durable cloud push queue unconfigured.'
    });
  } catch (err) {
    console.error('wakeword dispatch failed', err.message);
    res.status(500).json({ error: 'wakeword failed', detail: err.message || String(err) });
  }
});

app.get('/wakeword/queue', requireScope(SCOPES.WAKEWORD), (req, res) => {
  try {
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : 20;
    const status = wakewordQueue.getQueueStatus({ limit, includeEvents: true });
    return res.json({
      status: 'ok',
      cloudPushConfigured: Boolean(process.env.FIREBASE_SA_BASE64 || process.env.FIREBASE_SA_PATH),
      ...status
    });
  } catch (err) {
    res.status(500).json({ error: 'Failed retrieving wakeword queue status', detail: err.message });
  }
});

app.post('/wakeword/dequeue', requireScope(SCOPES.WAKEWORD), (req, res) => {
  try {
    const limit = req.body?.limit || req.query?.limit || 10;
    const dequeued = wakewordQueue.dequeueEvents(limit);
    return res.json({
      status: 'ok',
      count: dequeued.length,
      events: dequeued,
      remainingQueueDepth: wakewordQueue.getQueueStatus().queueDepth
    });
  } catch (err) {
    res.status(500).json({ error: 'Failed dequeuing wakeword events', detail: err.message });
  }
});

app.post('/wakeword/ack', requireScope(SCOPES.WAKEWORD), (req, res) => {
  try {
    const { eventIds = [] } = req.body || {};
    if (!Array.isArray(eventIds)) {
      return res.status(400).json({ error: 'eventIds must be an array of event IDs' });
    }
    const acknowledged = wakewordQueue.acknowledgeEvents(eventIds);
    return res.json({
      status: 'ok',
      acknowledgedCount: acknowledged,
      remainingQueueDepth: wakewordQueue.getQueueStatus().queueDepth
    });
  } catch (err) {
    res.status(500).json({ error: 'Failed acknowledging wakeword events', detail: err.message });
  }
});

app.post('/compute/offload', requireScope(SCOPES.COMPUTE), async (req, res) => {
  try {
    const { taskType, payload = {} } = req.body;
    const allowedTaskTypes = [
      'CODE_COMPILATION_AND_ANALYSIS',
      'BATCH_EMBEDDINGS',
      'MULTI_MODEL_CONSENSUS',
      'HEAVY_FILE_TRANSFORM',
      'SYSTEM_DIAGNOSTICS'
    ];

    if (!taskType || !allowedTaskTypes.includes(taskType)) {
      return res.status(400).json({
        error: 'Invalid or missing taskType. Allowed: ' + allowedTaskTypes.join(', ')
      });
    }

    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return res.status(400).json({ error: 'Payload must be a valid JSON object' });
    }

    const startTime = Date.now();
    let resultData = null;

    switch (taskType) {
      case 'MULTI_MODEL_CONSENSUS': {
        const prompt = payload.prompt;
        if (!prompt || typeof prompt !== 'string') {
          return res.status(400).json({ error: 'payload.prompt string required for MULTI_MODEL_CONSENSUS' });
        }
        const configuredProviders = orchestrator.getConfiguredProviders();
        if (configuredProviders.length < 2) {
          return res.status(503).json({
            success: false,
            taskType,
            error: 'CAPABILITY_UNAVAILABLE',
            reason: 'Genuine cloud multi-model consensus requires at least two configured LLM provider credentials (e.g. OpenAI and Gemini). Currently configured: ' + (configuredProviders.join(', ') || 'none')
          });
        }
        const promises = configuredProviders.map(p =>
          orchestrator.callProviders({ prompt }, [p])
        );
        const settled = await Promise.allSettled(promises);
        const outputs = [];
        for (let i = 0; i < configuredProviders.length; i++) {
          const s = settled[i];
          outputs.push({
            provider: configuredProviders[i],
            status: s.status,
            output: s.status === 'fulfilled' ? s.value : { error: s.reason?.message || String(s.reason) }
          });
        }
        resultData = {
          participatingProviders: configuredProviders,
          streamResults: outputs,
          status: 'CONSENSUS_EVALUATED'
        };
        break;
      }
      case 'BATCH_EMBEDDINGS': {
        const texts = Array.isArray(payload.texts) ? payload.texts : [];
        if (texts.length === 0) {
          return res.status(400).json({ error: 'payload.texts must be a non-empty array of strings' });
        }
        const hasOpenAI = Boolean(process.env.BACKEND_OPENAI_KEY || process.env.OPENAI_API_KEY);
        if (!hasOpenAI) {
          return res.status(503).json({
            success: false,
            taskType,
            error: 'CAPABILITY_UNAVAILABLE',
            reason: 'No cloud neural embedding provider credentials configured on backend server.'
          });
        }
        try {
          const embData = await orchestrator.callEmbeddings(texts);
          resultData = {
            processedCount: texts.length,
            model: embData.model || 'text-embedding-3-small',
            data: embData.data,
            usage: embData.usage,
            status: 'EMBEDDINGS_GENERATED'
          };
        } catch (embErr) {
          return res.status(502).json({
            success: false,
            taskType,
            error: 'EMBEDDING_PROVIDER_ERROR',
            detail: embErr.message
          });
        }
        break;
      }
      case 'CODE_COMPILATION_AND_ANALYSIS': {
        const code = payload.code;
        const language = (payload.language || 'javascript').toLowerCase();
        if (typeof code !== 'string') {
          return res.status(400).json({ error: 'payload.code must be a string' });
        }
        if (language === 'javascript' || language === 'js') {
          const vm = require('vm');
          try {
            new vm.Script(code);
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: true,
              diagnostics: [],
              status: 'SYNTAX_VERIFIED'
            };
          } catch (syntaxErr) {
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: false,
              diagnostics: [{ line: syntaxErr.lineNumber || 1, message: syntaxErr.message }],
              status: 'SYNTAX_ERROR'
            };
          }
        } else if (language === 'json') {
          try {
            JSON.parse(code);
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: true,
              diagnostics: [],
              status: 'SYNTAX_VERIFIED'
            };
          } catch (jsonErr) {
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: false,
              diagnostics: [{ line: 1, message: jsonErr.message }],
              status: 'SYNTAX_ERROR'
            };
          }
        } else if (language === 'python' || language === 'py') {
          const { spawnSync } = require('child_process');
          const pyCheck = spawnSync('python3', ['-c', 'import ast, sys; ast.parse(sys.stdin.read())'], {
            input: code,
            encoding: 'utf-8',
            timeout: 5000
          });
          if (pyCheck.status === 0) {
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: true,
              diagnostics: [],
              status: 'SYNTAX_VERIFIED'
            };
          } else {
            const errOutput = pyCheck.stderr || pyCheck.stdout || 'Python syntax error';
            resultData = {
              language,
              codeLengthBytes: Buffer.byteLength(code, 'utf-8'),
              syntaxValid: false,
              diagnostics: [{ line: 1, message: errOutput.trim() }],
              status: 'SYNTAX_ERROR'
            };
          }
        } else {
          return res.status(501).json({
            success: false,
            taskType,
            error: 'NOT_IMPLEMENTED',
            reason: `Cloud compilation sandbox for language '${language}' is not implemented in Node.js runtime.`
          });
        }
        break;
      }
      case 'HEAVY_FILE_TRANSFORM': {
        const content = payload.content;
        if (typeof content !== 'string') {
          return res.status(400).json({ error: 'payload.content must be a string' });
        }
        const crypto = require('crypto');
        const hash = crypto.createHash('sha256').update(content).digest('hex');
        resultData = {
          transformedSizeBytes: Buffer.byteLength(content, 'utf-8'),
          sha256: hash,
          status: 'TRANSFORMED'
        };
        break;
      }
      case 'SYSTEM_DIAGNOSTICS': {
        const mem = process.memoryUsage();
        resultData = {
          uptimeSec: Math.floor(process.uptime()),
          nodeVersion: process.version,
          memoryRssBytes: mem.rss,
          memoryHeapUsedBytes: mem.heapUsed,
          cpuUsage: process.cpuUsage(),
          status: 'DIAGNOSTICS_CAPTURED'
        };
        break;
      }
      default: {
        return res.status(400).json({ error: 'Unsupported taskType' });
      }
    }

    const durationMs = Date.now() - startTime;
    return res.json({
      success: true,
      taskType,
      result: resultData,
      durationMs,
      timestamp: Date.now()
    });
  } catch (err) {
    console.error('compute/offload failed', err.message);
    res.status(500).json({ error: 'compute/offload failed', detail: err.message || String(err) });
  }
});


if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`Wasti AI OS Backend listening securely on port ${PORT}`);
  });
}

module.exports = {
  app,
  requireAuth,
  requireScope,
  SCOPES,
  getAuthorizedScopes,
  wakewordQueue,
  stripeHelper
};
