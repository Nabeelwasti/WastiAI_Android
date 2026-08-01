// Orchestrator: attempts to fulfill an LLM request using available providers in order,
// and falls back to local or web-based approaches when possible.

require('dotenv').config();
const axios = require('axios');

const OPENAI_KEY = process.env.BACKEND_OPENAI_KEY || process.env.OPENAI_API_KEY || null;
const GEMINI_KEY = process.env.BACKEND_GEMINI_KEY || null;
const GROQ_KEY = process.env.BACKEND_GROQ_VOICE || null;

async function callOpenAI(payload) {
  if (!OPENAI_KEY) throw new Error('OpenAI key not configured');
  const res = await axios.post('https://api.openai.com/v1/chat/completions', payload, {
    headers: { Authorization: `Bearer ${OPENAI_KEY}` }
  });
  return res.data;
}

async function callGemini(payload) {
  // Placeholder for Gemini API. Implement when key and endpoint specifics are known.
  if (!GEMINI_KEY) throw new Error('Gemini key not configured');
  throw new Error('Gemini support not implemented in this orchestrator yet');
}

async function callLocalLLM(payload) {
  // If you run a local LLM server (e.g., llama.cpp webui, local-replicate, or other), point to it here.
  if (!process.env.LOCAL_LLM_URL) throw new Error('Local LLM URL not configured');
  const res = await axios.post(process.env.LOCAL_LLM_URL, payload, { timeout: 600000 });
  return res.data;
}

module.exports = {
  callProviders: async function (payload, priority = ['openai','gemini','local']) {
    // priority is an array of provider keys to try in order
    const errors = [];
    for (const p of priority) {
      try {
        if (p === 'openai') {
          const out = await callOpenAI(payload);
          return { provider: 'openai', result: out };
        }
        if (p === 'gemini') {
          const out = await callGemini(payload);
          return { provider: 'gemini', result: out };
        }
        if (p === 'local') {
          const out = await callLocalLLM(payload);
          return { provider: 'local', result: out };
        }
      } catch (err) {
        errors.push({ provider: p, error: err.message || String(err) });
        continue;
      }
    }
    return { provider: null, error: 'All providers failed', errors };
  }
};
