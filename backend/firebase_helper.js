// Firebase admin helper for FCM push notifications (wakeword forwarding)

require('dotenv').config();
const fs = require('fs');
const path = require('path');

let admin = null;
try {
  admin = require('firebase-admin');
} catch (_) {
  // firebase-admin not installed in all environments — handle gracefully
  console.warn('firebase-admin not available in runtime');
}

let initialized = false;

function init() {
  if (!admin) return false;
  if (initialized) return true;

  // 1. Direct JSON string in environment
  if (process.env.FIREBASE_SA_JSON) {
    try {
      const sa = JSON.parse(process.env.FIREBASE_SA_JSON);
      admin.initializeApp({ credential: admin.credential.cert(sa) });
      initialized = true;
      return true;
    } catch (e) {
      console.warn('Failed to parse FIREBASE_SA_JSON:', e.message);
    }
  }

  // 2. Base64-encoded service account JSON
  const saB64 = process.env.FIREBASE_SA_BASE64 || null;
  if (saB64) {
    try {
      const saJson = Buffer.from(saB64, 'base64').toString('utf-8');
      const sa = JSON.parse(saJson);
      admin.initializeApp({ credential: admin.credential.cert(sa) });
      initialized = true;
      return true;
    } catch (e) {
      console.warn('Failed to parse FIREBASE_SA_BASE64:', e.message);
    }
  }

  // 3. Standard literal service account configuration file
  if (fs.existsSync('./firebase-service-account.json')) {
    try {
      const sa = JSON.parse(fs.readFileSync('./firebase-service-account.json', 'utf-8'));
      admin.initializeApp({ credential: admin.credential.cert(sa) });
      initialized = true;
      return true;
    } catch (e) {
      console.warn('Failed to load ./firebase-service-account.json:', e.message);
    }
  }

  // 4. Standard GCP Application Default Credentials
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    try {
      admin.initializeApp({ credential: admin.credential.applicationDefault() });
      initialized = true;
      return true;
    } catch (e) {
      console.warn('Failed to initialize with applicationDefault credentials:', e.message);
    }
  }

  return false;
}

async function sendPush(token, payload) {
  if (!init()) throw new Error('Firebase admin not initialized');
  return admin.messaging().sendToDevice(token, { data: payload });
}

module.exports = { init, sendPush };
