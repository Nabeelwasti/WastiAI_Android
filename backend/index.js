// Wasti AI OS Backend Orchestrator Service
require('dotenv').config();
const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const { Octokit } = require('@octokit/rest');
const orchestrator = require('./orchestrator');
const brevo = require('./brevo');
const stripeHelper = require('./stripe_helper');
const firebaseHelper = require('./firebase_helper');

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
  allowedHeaders: ['Content-Type', 'Authorization', 'x-approval-token', 'stripe-signature']
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

// JSON Body Parser for standard endpoints
app.use(bodyParser.json({ limit: '2mb' }));

const PORT = process.env.PORT || 8080;
const GITHUB_PAT = process.env.BACKEND_GITHUB_PAT || process.env.BACKEND_GITHUB_CLASSIC || process.env.GITHUB_PAT || null;
const octokit = GITHUB_PAT ? new Octokit({ auth: GITHUB_PAT }) : null;

// Auth Middleware for sensitive endpoints
function requireAuth(req, res, next) {
  const authHeader = req.headers['authorization'];
  const tokenHeader = req.headers['x-wasti-auth-token'] || req.headers['x-api-key'];
  const expectedSecret = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.BACKEND_API_SECRET;

  if (expectedSecret) {
    let providedToken = tokenHeader;
    if (!providedToken && authHeader && authHeader.startsWith('Bearer ')) {
      providedToken = authHeader.substring(7).trim();
    }
    if (!providedToken || providedToken !== expectedSecret) {
      return res.status(401).json({ error: 'Unauthorized: Valid Wasti authentication token required' });
    }
  }
  next();
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

app.post('/llm', requireAuth, async (req, res) => {
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

app.post('/dev/patch', requireAuth, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GitHub integration not configured on server' });
    const { owner, repo, base = 'main', title = 'Wasti Dev Patch', body = 'Automated patch', changes = [] } = req.body;
    if (!owner || !repo) return res.status(400).json({ error: 'owner and repo required' });
    if (!Array.isArray(changes) || changes.length === 0) {
      return res.status(400).json({ error: 'At least one file change required to create patch' });
    }

    // Validate repo ownership against optional allowlist
    const allowedRepos = process.env.ALLOWED_GITHUB_REPOS ? process.env.ALLOWED_GITHUB_REPOS.split(',').map(r => r.trim().toLowerCase()) : null;
    if (allowedRepos && !allowedRepos.includes(`${owner}/${repo}`.toLowerCase()) && !allowedRepos.includes(repo.toLowerCase())) {
      return res.status(403).json({ error: 'Repository not in authorized allowlist for automated patches' });
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

app.post('/email/send', async (req, res) => {
  try {
    const { to, subject, html, from } = req.body;
    if (!to || !subject || !html) return res.status(400).json({ error: 'to, subject, html required' });
    
    // Require approval token header for safety
    const approval = req.headers['x-approval-token'];
    if (!approval || !process.env.OUTREACH_APPROVAL_TOKEN || approval !== process.env.OUTREACH_APPROVAL_TOKEN) {
      return res.status(403).json({ error: 'Email sending requires a verified approval token' });
    }
    const out = await brevo.sendEmail({ toEmail: to, toName: to, subject, htmlContent: html, fromEmail: from?.email, fromName: from?.name });
    return res.json({ status: 'sent', detail: out });
  } catch (err) {
    console.error('email/send failed', err.message);
    res.status(500).json({ error: 'email/send failed', detail: err.message || String(err) });
  }
});

// Stripe webhook raw body handling with strict cryptographic signature verification
const rawBodySaver = bodyParser.raw({ type: 'application/json' });
app.post('/stripe/webhook', rawBodySaver, async (req, res) => {
  try {
    const sig = req.headers['stripe-signature'];
    let event;
    try {
      event = stripeHelper.constructEvent(req.body, sig);
    } catch (err) {
      console.error('stripe webhook verification rejected:', err.message);
      return res.status(400).send(`Webhook Error: ${err.message}`);
    }
    console.log('Verified Stripe event received:', event.type);
    return res.json({ received: true, eventType: event.type });
  } catch (err) {
    console.error('stripe webhook handler error:', err.message);
    res.status(500).send('Internal server error');
  }
});

app.post('/wakeword', async (req, res) => {
  try {
    const payload = req.body;
    if (!payload || typeof payload !== 'object') {
      return res.status(400).json({ error: 'Invalid wakeword payload' });
    }
    if (process.env.FIREBASE_SA_BASE64 || process.env.FIREBASE_SA_PATH) {
      if (payload.token) {
        const out = await firebaseHelper.sendPush(payload.token, { wakeword: JSON.stringify(payload.event || {}) });
        return res.json({ status: 'pushed', detail: out });
      }
      return res.json({ status: 'accepted', detail: 'Firebase configured, awaiting device registration token.' });
    }
    return res.status(200).json({ status: 'accepted', detail: 'Local node received event. Durable push queue unconfigured.' });
  } catch (err) {
    console.error('wakeword dispatch failed', err.message);
    res.status(500).json({ error: 'wakeword failed', detail: err.message || String(err) });
  }
});

app.listen(PORT, () => {
  console.log(`Wasti AI OS Backend listening securely on port ${PORT}`);
});

