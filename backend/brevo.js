// Brevo (Sendinblue) email helper
// Uses BREVO_API_KEY environment variable (you named it BREVO_API_KEY)

require('dotenv').config();
const axios = require('axios');

const BREVO_KEY = process.env.BREVO_API_KEY || process.env.BREVO_API_KEY;

async function sendEmail({toEmail, toName, subject, htmlContent, fromEmail, fromName}) {
  if (!BREVO_KEY) throw new Error('Brevo API key not configured');
  const url = 'https://api.sendinblue.com/v3/smtp/email';
  const payload = {
    sender: { email: fromEmail || 'no-reply@wasti.ai', name: fromName || 'Wasti Assistant' },
    to: [{ email: toEmail, name: toName }],
    subject: subject,
    htmlContent: htmlContent
  };
  const res = await axios.post(url, payload, { headers: { 'api-key': BREVO_KEY, 'Content-Type': 'application/json' } });
  return res.data;
}

module.exports = { sendEmail };
