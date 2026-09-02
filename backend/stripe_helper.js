// Stripe webhook helper (uses stripe package). Strictly verifies webhook cryptographic signature.

require('dotenv').config();
const Stripe = require('stripe');
const stripeKey = process.env.STRIPE_SECRET_KEY || process.env.BACKEND_STRIPE_SECRET || '';
const stripe = stripeKey ? new Stripe(stripeKey, { apiVersion: '2022-11-15' }) : null;

module.exports = {
  constructEvent: function(rawBody, sigHeader) {
    const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;
    if (!webhookSecret) {
      throw new Error('STRIPE_WEBHOOK_SECRET is not configured on server. Insecure unverified webhook parsing is prohibited.');
    }
    if (!sigHeader) {
      throw new Error('Missing stripe-signature header. Webhook signature verification required.');
    }
    if (!stripe) {
      throw new Error('Stripe client is not initialized with a valid secret key.');
    }
    return stripe.webhooks.constructEvent(rawBody, sigHeader, webhookSecret);
  }
};

