# Wasti Backend Orchestrator Service

This is the hardened Node.js/Express companion service for the Wasti AI OS platform, handling multi-provider LLM orchestration, automated developer patches, transactional email delivery, FCM wakeword forwarding, and Stripe webhook processing.

## Security Architecture
- **Zero Hardcoded Secrets**: Secrets are supplied strictly via environment variables.
- **Fail-Closed Authentication**: All sensitive routes (`/llm`, `/dev/patch`, `/email/send`, `/wakeword`) require `x-wasti-auth-token` or `Authorization: Bearer <token>` matching `WASTI_BACKEND_AUTH_SECRET`. If the secret is unset on the server, requests are blocked with HTTP 503.
- **Outreach Approval Guard**: `/email/send` additionally enforces `x-approval-token` matching `OUTREACH_APPROVAL_TOKEN`.
- **Stripe Webhook Cryptographic Verification**: `/stripe/webhook` processes raw JSON buffers with `stripe.webhooks.constructEvent` before JSON parsing.
- **Dev Patch Safeguards**: `/dev/patch` validates repository allowlists, branch allowlists, and rejects directory traversal or absolute paths.

## Environment Variables
See [.env.example](file:///data/data/com.termux/files/home/WastiAI_Android/backend/.env.example) for a full template:
- `PORT`: Server port (default `8080`).
- `ALLOWED_ORIGINS`: Comma-separated list of permitted CORS origins.
- `WASTI_BACKEND_AUTH_SECRET`: Secret token for `requireAuth` middleware.
- `OUTREACH_APPROVAL_TOKEN`: Token for human/governed approval of email delivery.
- `BREVO_API_KEY`: API key for Brevo transactional email.
- `STRIPE_SECRET_KEY`: Stripe API secret key.
- `STRIPE_WEBHOOK_SECRET`: Webhook signing secret from Stripe Dashboard.
- `BACKEND_GITHUB_PAT`: GitHub Personal Access Token for PR generation.
- `ALLOWED_GITHUB_REPOS`: Comma-separated repo allowlist (e.g. `Nabeelwasti/WastiAI_Android`).
- `ALLOWED_PATCH_BRANCHES`: Comma-separated target branch allowlist (default: `main,master,develop`).
- `OPENAI_API_KEY`, `BACKEND_GEMINI_KEY`, `BACKEND_GROQ_VOICE`, `LOCAL_LLM_URL`: Provider credentials.
- `FIREBASE_SA_BASE64` or `FIREBASE_SA_PATH`: Firebase Service Account for FCM push.

## Endpoints
- `GET /health`: Health and configuration status check (unauthenticated).
- `POST /stripe/webhook`: Stripe webhook handler with raw payload signature verification.
- `POST /llm`: Multi-provider LLM orchestration proxy (requires auth).
- `POST /dev/patch`: Automated patch and pull request creator with path traversal protection (requires auth).
- `POST /email/send`: Secure Brevo transactional email sender (requires auth + approval token).
- `POST /wakeword`: FCM push notification wakeword dispatcher (requires auth).

## Running the Backend
```bash
cd backend
npm install
cp .env.example .env # configure values
npm start
```
