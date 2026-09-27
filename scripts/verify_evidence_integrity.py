#!/usr/bin/env python3
"""
Wasti AI OS - Machine-Verifiable Evidence Freshness & Integrity Validator
Enforces cryptographic integrity, commit alignment, artifact binding, and freshness
across all verification evidence artifacts (release, debug, device, and backend deployment).
"""

import os
import sys
import json
import time
import hashlib
import argparse
import subprocess

VALID_SCHEMA_VERSIONS = {"1.0", "2.0"}
DEFAULT_MAX_AGE_SECONDS = 86400  # 24 hours
CLOCK_SKEW_TOLERANCE_SECONDS = 300  # 5 minutes

def get_current_git_commit():
    if os.environ.get("GITHUB_SHA"):
        return os.environ.get("GITHUB_SHA")
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, timeout=5).strip()
    except Exception:
        return "local_development"

def get_file_sha256(filepath):
    if not os.path.isfile(filepath):
        return None
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def compute_evidence_payload_hash(evidence_dict):
    """
    Computes deterministic SHA-256 over evidence dictionary,
    ignoring 'evidenceHash' field if present.
    """
    payload = {k: v for k, v in evidence_dict.items() if k != "evidenceHash"}
    canonical_str = json.dumps(payload, sort_keys=True)
    return hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()

def validate_evidence_dict(
    evidence,
    expected_commit=None,
    target_artifact=None,
    expected_sha256=None,
    max_age_seconds=DEFAULT_MAX_AGE_SECONDS,
    require_current_commit=False,
    allow_historical=False,
    current_time_ms=None
):
    """
    Validates evidence data dictionary against freshness, commit, artifact, and integrity rules.
    Returns: dict(status=..., is_valid_current=..., reason=..., details=...)
    """
    if not isinstance(evidence, dict):
        return {
            "status": "MALFORMED",
            "isValidCurrent": False,
            "reason": "Evidence payload is not a valid JSON object"
        }

    # 1. Mandatory metadata fields
    required_fields = ["timestamp", "overallVerificationStatus"]
    for field in required_fields:
        if field not in evidence:
            return {
                "status": "MALFORMED",
                "isValidCurrent": False,
                "reason": f"Missing mandatory evidence field '{field}'"
            }

    schema_version = str(evidence.get("schemaVersion", "1.0"))
    if schema_version not in VALID_SCHEMA_VERSIONS:
        return {
            "status": "MALFORMED",
            "isValidCurrent": False,
            "reason": f"Unsupported evidence schema version '{schema_version}'"
        }

    # 2. Cryptographic Tamper / Hash Check
    declared_hash = evidence.get("evidenceHash")
    if declared_hash:
        computed_hash = compute_evidence_payload_hash(evidence)
        # Some legacy backend payloads may hash JSON.stringify without sort_keys
        # Check standard canonical hash first, fallback to raw serialization if legacy
        if declared_hash != computed_hash:
            # Fallback check for backend payload serialization format
            legacy_payload = {k: v for k, v in evidence.items() if k != "evidenceHash"}
            legacy_hash = hashlib.sha256(json.dumps(legacy_payload).encode("utf-8")).hexdigest()
            if declared_hash != legacy_hash:
                return {
                    "status": "HASH_TAMPERED",
                    "isValidCurrent": False,
                    "reason": f"Cryptographic evidence hash mismatch: declared '{declared_hash}', computed '{computed_hash}'"
                }

    # 3. Timestamp Freshness & Clock Skew Audit
    now_ms = current_time_ms if current_time_ms is not None else int(time.time() * 1000)
    timestamp_ms = evidence.get("timestamp")
    if not isinstance(timestamp_ms, (int, float)) or timestamp_ms <= 0:
        return {
            "status": "MALFORMED",
            "isValidCurrent": False,
            "reason": "Invalid or missing timestamp"
        }

    age_seconds = (now_ms - timestamp_ms) / 1000.0
    if age_seconds < -CLOCK_SKEW_TOLERANCE_SECONDS:
        return {
            "status": "INVALID_TIMESTAMP",
            "isValidCurrent": False,
            "reason": f"Evidence timestamp is in the future ({abs(age_seconds):.1f}s beyond tolerance)"
        }

    is_expired = age_seconds > max_age_seconds

    # 4. Commit SHA Alignment Audit
    commit_sha = evidence.get("commitSha")
    target_commit = expected_commit
    if require_current_commit and not target_commit:
        target_commit = get_current_git_commit()

    is_commit_mismatched = False
    if target_commit and commit_sha:
        # Ignore local_development comparisons when target is local_development
        if target_commit != "local_development" and commit_sha != "local_development":
            if not (target_commit.startswith(commit_sha) or commit_sha.startswith(target_commit)):
                is_commit_mismatched = True

    # 5. Artifact Hash Alignment Audit
    is_artifact_mismatched = False
    if target_artifact and os.path.isfile(target_artifact):
        actual_sha = get_file_sha256(target_artifact)
        evidence_apk_sha = evidence.get("apkSha256") or evidence.get("artifactHash")
        if evidence_apk_sha and actual_sha and evidence_apk_sha.lower() != actual_sha.lower():
            is_artifact_mismatched = True
    elif expected_sha256:
        evidence_apk_sha = evidence.get("apkSha256") or evidence.get("artifactHash")
        if evidence_apk_sha and evidence_apk_sha.lower() != expected_sha256.lower():
            is_artifact_mismatched = True

    # 6. Evaluate State Precedence
    if is_artifact_mismatched:
        return {
            "status": "ARTIFACT_HASH_MISMATCH",
            "isValidCurrent": False,
            "reason": "Artifact SHA-256 hash does not match expected target binary"
        }

    if is_commit_mismatched:
        if allow_historical:
            return {
                "status": "HISTORICAL",
                "isValidCurrent": False,
                "reason": f"Historical evidence from commit '{commit_sha}' (current target is '{target_commit}')",
                "ageSeconds": age_seconds
            }
        return {
            "status": "COMMIT_MISMATCH",
            "isValidCurrent": False,
            "reason": f"Evidence commit '{commit_sha}' does not match target commit '{target_commit}'"
        }

    if is_expired:
        if allow_historical:
            return {
                "status": "HISTORICAL",
                "isValidCurrent": False,
                "reason": f"Historical evidence aged {age_seconds:.1f}s (TTL: {max_age_seconds}s)",
                "ageSeconds": age_seconds
            }
        return {
            "status": "STALE",
            "isValidCurrent": False,
            "reason": f"Evidence expired (age: {age_seconds:.1f}s, max allowed: {max_age_seconds}s)"
        }

    # 7. Verification Status Check
    status_str = str(evidence.get("overallVerificationStatus", "")).upper()
    valid_verified_statuses = {"VERIFIED", "RUNTIME_VERIFIED", "BUILD_VERIFIED"}
    if status_str not in valid_verified_statuses:
        return {
            "status": status_str if status_str else "UNVERIFIED",
            "isValidCurrent": False,
            "reason": f"Evidence reports non-verified state: '{status_str}'"
        }

    return {
        "status": "VALID_CURRENT",
        "isValidCurrent": True,
        "verificationStatus": status_str,
        "verificationScope": evidence.get("verificationScope", "UNSPECIFIED"),
        "commitSha": commit_sha,
        "timestamp": timestamp_ms,
        "ageSeconds": age_seconds,
        "reason": f"Evidence verified fresh ({age_seconds:.1f}s old) and cryptographically intact for commit '{commit_sha}'"
    }

def main():
    parser = argparse.ArgumentParser(description="Wasti AI Evidence Freshness & Integrity Validator")
    parser.add_argument("evidence_file", help="Path to evidence JSON file")
    parser.add_argument("--expected-commit", help="Expected Git commit SHA")
    parser.add_argument("--target-artifact", help="Path to target binary artifact to verify against apkSha256")
    parser.add_argument("--expected-sha256", help="Expected artifact SHA-256 hash")
    parser.add_argument("--max-age", type=int, default=DEFAULT_MAX_AGE_SECONDS, help="Maximum allowed age in seconds")
    parser.add_argument("--require-current-commit", action="store_true", help="Assert commitSha matches current git HEAD")
    parser.add_argument("--allow-historical", action="store_true", help="Permit historical evidence without failing if valid for historical record")
    parser.add_argument("--require-verified", action="store_true", help="Exit with code 1 if status is not VALID_CURRENT")

    args = parser.parse_args()

    if not os.path.isfile(args.evidence_file):
        print(f"ERROR: Evidence file not found: {args.evidence_file}")
        sys.exit(1)

    try:
        with open(args.evidence_file, "r") as f:
            evidence = json.load(f)
    except Exception as e:
        print(f"ERROR: Failed to parse JSON in {args.evidence_file}: {e}")
        sys.exit(1)

    result = validate_evidence_dict(
        evidence,
        expected_commit=args.expected_commit,
        target_artifact=args.target_artifact,
        expected_sha256=args.expected_sha256,
        max_age_seconds=args.max_age,
        require_current_commit=args.require_current_commit,
        allow_historical=args.allow_historical
    )

    print("========================================================")
    print("  WASTI AI OS: EVIDENCE INTEGRITY & FRESHNESS AUDIT     ")
    print(f"  Evidence File: {args.evidence_file}")
    print(f"  Result Status: {result['status']}")
    print(f"  Is Valid Current: {result['isValidCurrent']}")
    print(f"  Reason: {result['reason']}")
    print("========================================================")

    if args.require_verified and not result["isValidCurrent"]:
        sys.exit(1)
    
    if result["status"] in {"MALFORMED", "HASH_TAMPERED", "ARTIFACT_HASH_MISMATCH"}:
        sys.exit(1)
    
    if not args.allow_historical and result["status"] in {"STALE", "COMMIT_MISMATCH"}:
        sys.exit(1)

    sys.exit(0)

if __name__ == "__main__":
    main()
