// Firebase admin helper for FCM push notifications (wakeword forwarding)

require('dotenv').config();
let admin = null;
try {
  admin = require('firebase-admin');
} catch (e) {
  // firebase-admin not installed in all environments — handle gracefully
  console.warn('firebase-admin not available in runtime');
}

let initialized = false;

function init() {
  if (!admin) return false;
  if (initialized) return true;
  // Expect a service account JSON in FIREBASE_SA (base64) or path in FIREBASE_SA_PATH
  const saB64 = process.env.FIREBASE_SA_BASE64 || null;
  if (saB64) {
    const saJson = Buffer.from(saB64, 'base64').toString('utf-8');
    const sa = JSON.parse(saJson);
    admin.initializeApp({ credential: admin.credential.cert(sa) });
    initialized = true;
    return true;
  }
  const saPath = process.env.FIREBASE_SA_PATH || null;
  if (saPath) {
    admin.initializeApp({ credential: admin.credential.cert(require(saPath)) });
    initialized = true;
    return true;
  }
  return false;
}

async function sendPush(token, payload) {
  if (!init()) throw new Error('Firebase admin not initialized');
  return admin.messaging().sendToDevice(token, { data: payload });
}

module.exports = { init, sendPush };
