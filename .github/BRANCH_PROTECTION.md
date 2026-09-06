# Wasti AI OS - Main Branch Governance & Protection Policy

## 1. Governance Overview
This document specifies the authoritative branch protection requirements, required continuous integration (CI) status checks, code review guidelines, and least-privilege release permissions for the `main` branch of `Nabeelwasti/WastiAI_Android`.

Governing Law: **NO FAKE SUCCESS, NO FAKE VERIFICATION, NO FAKE CAPABILITY, NO FAKE READINESS**.

---

## 2. Branch Protection Requirements for `main`

| Policy Item | Setting | Rationale |
| :--- | :--- | :--- |
| **Target Branch** | `main` | Production baseline branch. |
| **Require Pull Request** | Enabled (`1` approving review) | Ensures pair-programming/multi-party verification. |
| **Dismiss Stale Approvals** | Enabled | New commits invalidate prior reviews. |
| **Require Code Owner Review** | Enabled | Architecture & security owners must sign off. |
| **Require Status Checks** | Enabled (`strict: true`) | Branches must be up-to-date with `main` before merging. |
| **Required Status Checks** | `Validate, Test & Build APK` | Executes backend tests, Robolectric unit tests, lint, and secret boundary scan. |
| **Require Linear History** | Enabled | Preserves verifiable Git bisectability and commit provenance. |
| **Require Conversation Resolution** | Enabled | All review comments must be explicitly resolved before merge. |
| **Enforce on Administrators** | Enabled | Policy applies equally to repo owners and administrators. |
| **Allow Force Pushes** | **Disabled** (`false`) | Prevents rewriting public history and destroying audit trails. |
| **Allow Deletions** | **Disabled** (`false`) | Protects `main` from accidental or malicious deletion. |

---

## 3. Least-Privilege CI/CD Permissions Matrix

In `.github/workflows/build-apk.yml`:
1. **Workflow Level Default**:
   ```yaml
   permissions:
     contents: read
   ```
2. **`validate` Job**:
   ```yaml
   permissions:
     contents: read
   ```
   *Strictly read-only repository access during testing, linting, and APK assembly.*
3. **`publish` Job**:
   ```yaml
   permissions:
     contents: write
   ```
   *Write access is granted exclusively to the release step for publishing signed release assets (`app-release.apk`, `app-release.aab`).*

---

## 4. Application via GitHub CLI & API

To apply the canonical branch protection ruleset defined in `.github/branch_protection.json` via GitHub CLI:

```bash
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  /repos/Nabeelwasti/WastiAI_Android/branches/main/protection \
  --input .github/branch_protection.json
```

> [!NOTE]
> Setting branch protection rules on GitHub requires repository administration privileges. The configuration files `.github/branch_protection.json` and `.github/BRANCH_PROTECTION.md` define the exact authoritative ruleset in version control. Applying these settings to the remote repository requires execution with an authenticated token holding `admin:repo` scope.
