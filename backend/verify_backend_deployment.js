#!/usr/bin/env node

/**
 * Wasti AI OS - Backend Deployment & Runtime Verification Engine (P0-05)
 * 
 * Verifies live reachability, HTTP health contract, subsystem readiness,
 * and fail-closed authentication boundary of the companion backend service.
 * Emits cryptographic evidence ledger (backend_deployment_evidence.json)
 * with zero fabrication.
 */

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const defaultTargetUrl = (process.argv[2] && process.argv[2].startsWith('http')) ? process.argv[2] : (process.env.WASTI_BACKEND_URL || 'http://127.0.0.1:8080');
const defaultOutputFile = process.argv[3] || 'backend_deployment_evidence.json';
const authToken = process.env.WASTI_BACKEND_AUTH_SECRET || process.env.WASTI_SERVER_SECRET || null;

if (require.main === module) {
  console.log('========================================================');
  console.log('  WASTI AI OS: BACKEND DEPLOYMENT VERIFICATION (P0-05)  ');
  console.log(`  Target Base URL: ${defaultTargetUrl}`);
  console.log('========================================================');
}

function parseUrl(u) {
  try {
    return new URL(u);
  } catch (err) {
    console.error(`ERROR: Invalid target URL: '${u}' (${err.message})`);
    process.exit(1);
  }
}

function sendHttpRequest(method, urlStr, headers = {}, body = null, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(urlStr);
    const isHttps = parsed.protocol === 'https:';
    const client = isHttps ? https : http;

    const options = {
      protocol: parsed.protocol,
      hostname: parsed.hostname,
      port: parsed.port || (isHttps ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method: method,
      headers: {
        'User-Agent': 'WastiAI-DeploymentVerifier/1.0',
        ...headers
      },
      timeout: timeoutMs
    };

    const startTime = Date.now();
    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        const latencyMs = Date.now() - startTime;
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: data,
          latencyMs
        });
      });
    });

    req.on('timeout', () => {
      req.destroy();
      reject(new Error(`Request timed out after ${timeoutMs}ms`));
    });

    req.on('error', (err) => {
      reject(err);
    });

    if (body) {
      req.write(typeof body === 'string' ? body : JSON.stringify(body));
    }
    req.end();
  });
}

async function verifyDeployment(targetUrl = defaultTargetUrl, outputFile = defaultOutputFile) {
  const normalizedBase = targetUrl.replace(/\/+$/, '');
  const healthUrl = `${normalizedBase}/health`;

  console.log(`--- Step 1: Health Probe (${healthUrl}) ---`);
  let healthResult;
  try {
    healthResult = await sendHttpRequest('GET', healthUrl, { 'Accept': 'application/json' });
  } catch (err) {
    console.warn(`WARNING: Direct connection to ${healthUrl} failed: ${err.message}`);
    console.log('NOTICE: Backend instance is not currently reachable at the target URL.');
    
    // Generate unverified / unreachable evidence record truthfully
    const unreachableEvidence = {
      verificationType: "BACKEND_UNREACHABLE",
      targetUrl: normalizedBase,
      timestamp: Date.now(),
      isReachable: false,
      error: err.message,
      checks: {
        healthProbe: "FAILED",
        authBoundary: "UNTESTED",
        subsystems: "UNAVAILABLE"
      }
    };
    fs.writeFileSync(outputFile, JSON.stringify(unreachableEvidence, null, 2), 'utf-8');
    console.log(`Unreachable deployment record written to ${outputFile}`);
    return { success: false, evidence: unreachableEvidence };
  }

  if (healthResult.statusCode !== 200) {
    console.error(`ERROR: /health responded with HTTP ${healthResult.statusCode}: ${healthResult.body}`);
    process.exit(1);
  }

  let healthJson;
  try {
    healthJson = JSON.parse(healthResult.body);
  } catch (err) {
    console.error(`ERROR: /health returned invalid non-JSON body: ${healthResult.body}`);
    process.exit(1);
  }

  if (healthJson.status !== 'ok') {
    console.error(`ERROR: /health payload status is not 'ok':`, healthJson);
    process.exit(1);
  }

  console.log(`SUCCESS: Health probe passed in ${healthResult.latencyMs}ms.`);
  console.log(`  Subsystem Status:`, {
    github: healthJson.githubConfigured,
    brevo: healthJson.brevoConfigured,
    stripe: healthJson.stripeConfigured,
    firebase: healthJson.firebaseConfigured,
    authEnforced: healthJson.authEnforced
  });

  // Step 2: Fail-Closed Security Boundary Test
  console.log('--- Step 2: Fail-Closed Authentication Boundary Probe ---');
  const testOffloadUrl = `${normalizedBase}/compute/offload`;
  let unauthResult;
  try {
    unauthResult = await sendHttpRequest('POST', testOffloadUrl, {
      'Content-Type': 'application/json'
    }, JSON.stringify({ taskType: 'SYSTEM_DIAGNOSTICS' }));
  } catch (err) {
    console.error(`ERROR: Failed to probe unauthenticated route: ${err.message}`);
    process.exit(1);
  }

  const isFailClosed = (unauthResult.statusCode === 401 || unauthResult.statusCode === 503);
  if (!isFailClosed) {
    console.error(`SECURITY VIOLATION: Unauthenticated request to /compute/offload was NOT blocked! Status: ${unauthResult.statusCode}`);
    process.exit(1);
  }
  console.log(`SUCCESS: Unauthenticated access blocked correctly with HTTP ${unauthResult.statusCode} (Fail-Closed).`);

  // Step 3: Authenticated Probe (if token provided)
  let authProbeStatus = "SKIPPED_NO_SECRET";
  if (authToken) {
    console.log('--- Step 3: Authenticated Authorized Route Probe ---');
    try {
      const authResult = await sendHttpRequest('POST', testOffloadUrl, {
        'Content-Type': 'application/json',
        'x-wasti-auth-token': authToken
      }, JSON.stringify({ taskType: 'SYSTEM_DIAGNOSTICS' }));

      if (authResult.statusCode === 200) {
        authProbeStatus = "VERIFIED_200";
        console.log('SUCCESS: Authenticated compute probe succeeded (HTTP 200).');
      } else {
        authProbeStatus = `REJECTED_${authResult.statusCode}`;
        console.log(`NOTICE: Authenticated probe returned HTTP ${authResult.statusCode}`);
      }
    } catch (err) {
      authProbeStatus = `ERROR_${err.message}`;
    }
  }

  // Step 4: Cryptographic Evidence Ledger Generation
  console.log('--- Step 4: Generating Cryptographic Deployment Proof ---');
  const evidencePayload = {
    verificationType: "BACKEND_DEPLOYMENT_VERIFIED",
    targetUrl: normalizedBase,
    timestamp: Date.now(),
    isReachable: true,
    latencyMs: healthResult.latencyMs,
    httpCode: healthResult.statusCode,
    subsystems: {
      githubConfigured: Boolean(healthJson.githubConfigured),
      brevoConfigured: Boolean(healthJson.brevoConfigured),
      stripeConfigured: Boolean(healthJson.stripeConfigured),
      firebaseConfigured: Boolean(healthJson.firebaseConfigured),
      authEnforced: Boolean(healthJson.authEnforced)
    },
    securityBoundary: {
      unauthenticatedStatus: unauthResult.statusCode,
      failClosedEnforced: true,
      authenticatedProbe: authProbeStatus
    }
  };

  const hash = crypto.createHash('sha256')
    .update(JSON.stringify(evidencePayload))
    .digest('hex');

  evidencePayload.evidenceHash = hash;

  fs.writeFileSync(outputFile, JSON.stringify(evidencePayload, null, 2), 'utf-8');
  console.log(`SUCCESS: Deployment evidence successfully recorded to ${outputFile}`);
  console.log(`Evidence Hash (SHA-256): ${hash}`);
  console.log('========================================================');
  console.log('  BACKEND DEPLOYMENT VERIFICATION COMPLETE: VERIFIED    ');
  console.log('========================================================');
  return { success: true, evidence: evidencePayload };
}

if (require.main === module) {
  verifyDeployment().then((res) => {
    if (!res.success) {
      // If run as CLI script and target is unreachable, exit 1 unless --allow-offline specified
      if (!process.argv.includes('--allow-offline')) {
        process.exit(1);
      }
    }
  }).catch((err) => {
    console.error('Fatal deployment verification error:', err);
    process.exit(1);
  });
}

module.exports = {
  verifyDeployment,
  sendHttpRequest
};
