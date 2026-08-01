// Stripe webhook helper (uses stripe package). Verifies webhook signature if STRIPE_SECRET_KEY is present.

require('dotenv').config();
const Stripe = require('stripe');
const stripe = new Stripe(process.env.STRIPE_SECRET_KEY || process.env.BACKEND_STRIPE_SECRET || '', { apiVersion: '2022-11-15' });

module.exports = {
  constructEvent: function(rawBody, sigHeader) {
    const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET || null;
    if (webhookSecret) {
      try {
        return stripe.webhooks.constructEvent(rawBody, sigHeader, webhookSecret);
      } catch (err) {
        throw err;
      }
    }
    // If no webhook secret configured, parse JSON body directly (less secure)
    try { return JSON.parse(rawBody); } catch (e) { throw e; }
  }
};
