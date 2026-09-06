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

const stripeKey = process.env.STRIPE_SECRET_KEY || process.env.BACKEND_STRIPE_SECRET || '';
const stripe = (Stripe && stripeKey) ? new Stripe(stripeKey, { apiVersion: '2022-11-15' }) : null;

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
  }
};

