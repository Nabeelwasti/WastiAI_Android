// Update backend index.js to use orchestrator, brevo, stripe helper, and firebase helper

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
app.use(cors());
// For Stripe webhook raw body is required, so use raw for that route; use json for others
app.use(bodyParser.json({ limit: '2mb' }));

const PORT = process.env.PORT || 8080;

const GITHUB_PAT = process.env.BACKEND_GITHUB_PAT || process.env.BACKEND_GITHUB_CLASSIC || process.env.GITHUB_PAT || null;
const octokit = GITHUB_PAT ? new Octokit({ auth: GITHUB_PAT }) : null;

app.get('/health', (req, res) => {
  res.json({ status: 'ok', time: Date.now() });
});

app.post('/llm', async (req, res) => {
  try {
    const { provider = 'openai', payload = {}, priority } = req.body;
    // allow clients to suggest priority but default to openai->gemini->local
    const order = priority || (provider === 'openai' ? ['openai','gemini','local'] : [provider,'local']);
    const result = await orchestrator.callProviders(payload, order);
    if (result.error) return res.status(500).json(result);
    return res.json(result);
  } catch (err) {
    console.error('/llm error', err);
    res.status(500).json({ error: 'LLM orchestration failed', detail: err.message || String(err) });
  }
});

app.post('/dev/patch', async (req, res) => {
  // keep original dev/patch flow but refuse to write unless changes provided
  try {
    if (!octokit) return res.status(500).json({ error: 'GitHub PAT not configured on server' });
    const { owner, repo, base = 'main', title = 'Wasti Dev Patch', body = 'Automated patch', changes = [] } = req.body;
    if (!owner || !repo) return res.status(400).json({ error: 'owner and repo required' });

    const baseRef = `heads/${base}`;
    const mainRef = await octokit.git.getRef({ owner, repo, ref: baseRef });
    const baseSha = mainRef.data.object.sha;
    const branchName = `wasti/patch-${Date.now()}`;
    await octokit.git.createRef({ owner, repo, ref: `refs/heads/${branchName}`, sha: baseSha });

    if (Array.isArray(changes) && changes.length > 0) {
      const blobs = [];
      for (const c of changes) {
        const blob = await octokit.git.createBlob({ owner, repo, content: c.content, encoding: 'utf-8' });
        blobs.push({ path: c.path, mode: '100644', type: 'blob', sha: blob.data.sha });
      }
      const baseCommit = await octokit.git.getCommit({ owner, repo, commit_sha: baseSha });
      const newTree = await octokit.git.createTree({ owner, repo, tree: blobs, base_tree: baseCommit.data.tree.sha });
      const newCommit = await octokit.git.createCommit({ owner, repo, message: title, tree: newTree.data.sha, parents: [baseSha] });
      await octokit.git.updateRef({ owner, repo, ref: `refs/heads/${branchName}`, sha: newCommit.data.sha });
    }
    const pr = await octokit.pulls.create({ owner, repo, title, head: branchName, base, body });
    return res.json({ prUrl: pr.data.html_url, branch: branchName });
  } catch (err) {
    console.error('dev/patch failed', err?.response?.data || err.message || err);
    res.status(500).json({ error: 'dev/patch failed', detail: err?.response?.data || err.message });
  }
});

app.post('/email/send', async (req, res) => {
  try {
    const { to, subject, html, from } = req.body;
    if (!to || !subject || !html) return res.status(400).json({ error: 'to, subject, html required' });
    // Require an approval_token header for safety
    const approval = req.headers['x-approval-token'];
    if (!approval || approval !== process.env.OUTREACH_APPROVAL_TOKEN) {
      return res.status(403).json({ error: 'Email sending requires approval token' });
    }
    const out = await brevo.sendEmail({ toEmail: to, toName: to, subject, htmlContent: html, fromEmail: from?.email, fromName: from?.name });
    return res.json({ status: 'sent', detail: out });
  } catch (err) {
    console.error('email/send failed', err);
    res.status(500).json({ error: 'email/send failed', detail: err.message || String(err) });
  }
});

// Stripe webhook raw body
const rawBodySaver = bodyParser.raw({ type: 'application/json' });
app.post('/stripe/webhook', rawBodySaver, async (req, res) => {
  try {
    const sig = req.headers['stripe-signature'];
    let event;
    try {
      event = stripeHelper.constructEvent(req.body, sig);
    } catch (err) {
      console.error('stripe webhook verify failed', err.message || err);
      return res.status(400).send(`Webhook Error: ${err.message}`);
    }
    // handle event types (payment_intent.succeeded, charge.succeeded, etc.)
    console.log('stripe event', event.type || 'parsed');
    return res.json({ received: true });
  } catch (err) {
    console.error('stripe webhook handler failed', err);
    res.status(500).send('internal error');
  }
});

app.post('/wakeword', async (req, res) => {
  try {
    const payload = req.body;
    // If firebase admin available, deliver to device tokens or topics
    if (process.env.FIREBASE_SA_BASE64 || process.env.FIREBASE_SA_PATH) {
      // example payload: { token: '<fcm-token>', event: { ... } }
      if (payload.token) {
        const out = await firebaseHelper.sendPush(payload.token, { wakeword: JSON.stringify(payload.event || {}) });
        return res.json({ status: 'pushed', detail: out });
      }
    }
    // otherwise just enqueue or log
    console.log('wakeword event queued', payload);
    return res.json({ status: 'queued' });
  } catch (err) {
    console.error('wakeword failed', err);
    res.status(500).json({ error: 'wakeword failed', detail: err.message || String(err) });
  }
});

app.listen(PORT, () => {
  console.log(`Wasti backend listening on port ${PORT}`);
});
