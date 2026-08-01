# Wasti Backend Scaffold

This is a minimal Node/Express scaffold used by the Wasti AI app for secure LLM calls and developer patch generation.

Important notes:
- Do NOT store production secrets in this repository.
- Use environment variables for credentials (example in .env.example).

Environment variables (example):
- OPENAI_API_KEY=your_openai_api_key_here
- BACKEND_GITHUB_PAT=your_personal_access_token_for_github
- PORT=8080

Endpoints:
- GET /health -> simple health check
- POST /llm -> proxy an LLM request via configured provider (OpenAI implemented)
- POST /dev/patch -> create a branch + PR in the target repo. The request body should include owner, repo, and optional changes array.

Usage:
- cd backend
- npm install
- cp .env.example .env (edit)
- npm start
