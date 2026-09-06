// Stripe webhook helper (uses stripe package). Strictly verifies webhook cryptographic signature.

try {
  require('dotenv').config();
} catch (e) {
  // dotenv optional in production environments where process.env is injected
}

let Stripe = null;
try {
  Stripe = require('stripe');
} catch (e) {
  // stripe package not installed
}

const fs = require('fs');
const path = require('path');

const stripeKey = process.env.STRIPE_SECRET_KEY || process.env.BACKEND_STRIPE_SECRET || '';
const stripe = (Stripe && stripeKey) ? new Stripe(stripeKey, { apiVersion: '2022-11-15' }) : null;

// Durable idempotency & replay attack mitigation
const STRIPE_EVENT_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const MAX_EVENTS_IN_MEMORY = 10000;
const STRIPE_STORE_PATH = process.env.STRIPE_EVENTS_CACHE_PATH || path.join(__dirname, '.stripe_events_store.json');

const eventCache = new Map();

function loadDurableEvents() {
  try {
    if (fs.existsSync(STRIPE_STORE_PATH)) {
      const raw = fs.readFileSync(STRIPE_STORE_PATH, 'utf-8');
      const data = JSON.parse(raw);
      const now = Date.now();
      if (Array.isArray(data)) {
        for (const item of data) {
          if (item && item.id && (now - item.ts) < STRIPE_EVENT_TTL_MS) {
            eventCache.set(item.id, item.ts);
          }
        }
      }
    }
  } catch (e) {
    console.warn('Could not load durable stripe events store:', e.message);
  }
}

function flushDurableEvents() {
  try {
    const now = Date.now();
    const pruned = [];
    for (const [id, ts] of eventCache.entries()) {
      if (now - ts < STRIPE_EVENT_TTL_MS) {
        pruned.push({ id, ts });
      } else {
        eventCache.delete(id);
      }
    }
    fs.writeFileSync(STRIPE_STORE_PATH, JSON.stringify(pruned), 'utf-8');
  } catch (e) {
    console.warn('Could not persist durable stripe events store:', e.message);
  }
}

// Initialize cache from durable store on startup
loadDurableEvents();

function isEventProcessed(eventId) {
  if (!eventId || typeof eventId !== 'string') return false;
  const ts = eventCache.get(eventId);
  if (!ts) return false;
  if (Date.now() - ts > STRIPE_EVENT_TTL_MS) {
    eventCache.delete(eventId);
    return false;
  }
  return true;
}

function recordProcessedEvent(eventId, metadata = {}) {
  if (!eventId || typeof eventId !== 'string') return;
  const now = Date.now();
  eventCache.set(eventId, now);
  if (eventCache.size > MAX_EVENTS_IN_MEMORY) {
    const oldestKey = eventCache.keys().next().value;
    eventCache.delete(oldestKey);
  }
  flushDurableEvents();
}

function validateEventTimestamp(event, maxAgeSec = 300) {
  if (!event || typeof event.created !== 'number') return { valid: true };
  const nowSec = Math.floor(Date.now() / 1000);
  const ageSec = Math.abs(nowSec - event.created);
  if (ageSec > maxAgeSec) {
    return {
      valid: false,
      ageSec,
      error: `Webhook event timestamp is outside tolerance window (${ageSec}s > ${maxAgeSec}s)`
    };
  }
  return { valid: true, ageSec };
}

function clearEventsForTesting() {
  eventCache.clear();
  try {
    if (fs.existsSync(STRIPE_STORE_PATH)) fs.unlinkSync(STRIPE_STORE_PATH);
  } catch (_) {}
}

module.exports = {
  constructEvent: function(rawBody, sigHeader) {
    const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;
    if (!webhookSecret) {
      throw new Error('STRIPE_WEBHOOK_SECRET is not configured on server. Insecure unverified webhook parsing is prohibited.');
    }
    if (!sigHeader) {
      throw new Error('Missing stripe-signature header. Webhook signature verification required.');
    }
    if (!Stripe) {
      throw new Error('Stripe package is not installed on server.');
    }
    if (!stripe) {
      throw new Error('Stripe client is not initialized with a valid secret key.');
    }
    return stripe.webhooks.constructEvent(rawBody, sigHeader, webhookSecret);
  },
  isEventProcessed,
  recordProcessedEvent,
  validateEventTimestamp,
  clearEventsForTesting,
  STRIPE_EVENT_TTL_MS
};

