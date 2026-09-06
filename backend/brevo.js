// Brevo (Sendinblue) email helper
// Uses BREVO_API_KEY environment variable (you named it BREVO_API_KEY)

try {
  require('dotenv').config();
} catch (e) {
  // dotenv optional in production environments where process.env is injected
}

let axios = null;
try {
  axios = require('axios');
} catch (e) {
  // axios not installed
}

const BREVO_KEY = process.env.BREVO_API_KEY || null;

async function sendEmail({toEmail, toName, subject, htmlContent, fromEmail, fromName}) {
  if (!axios) throw new Error('Axios client is not installed on server');
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
