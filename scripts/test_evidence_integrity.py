#!/usr/bin/env python3
"""
Unit test suite for scripts/verify_evidence_integrity.py
Tests fresh valid evidence, stale evidence, commit mismatch, artifact hash mismatch,
missing metadata, malformed evidence, tampered hash, historical evidence, and truth states.
"""

import os
import sys
import json
import time
import hashlib
import tempfile
import unittest

from verify_evidence_integrity import (
    validate_evidence_dict,
    compute_evidence_payload_hash,
    DEFAULT_MAX_AGE_SECONDS
)

class TestEvidenceFreshnessAndIntegrity(unittest.TestCase):

    def test_fresh_valid_evidence_passes(self):
        now_ms = int(time.time() * 1000)
        commit = "a9e91d2abc1234567890"
        artifact_hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "verificationType": "RUNTIME_VERIFIED",
            "overallVerificationStatus": "RUNTIME_VERIFIED",
            "packageId": "com.aistudio.wastios.k9v2pz",
            "commitSha": commit,
            "workflowRunId": "run_1001",
            "apkSha256": artifact_hash,
            "timestamp": now_ms - 2000, # 2 seconds ago
            "checks": {
                "manifestIdentity": "VERIFIED",
                "signingIntegrity": "VERIFIED"
            }
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit=commit,
            expected_sha256=artifact_hash,
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "VALID_CURRENT")
        self.assertTrue(res["isValidCurrent"])

    def test_stale_evidence_rejected(self):
        now_ms = int(time.time() * 1000)
        commit = "commit_123"
        artifact_hash = "hash_123"

        # Evidence is 25 hours old (default TTL is 24 hours)
        stale_timestamp = now_ms - (25 * 3600 * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": commit,
            "apkSha256": artifact_hash,
            "timestamp": stale_timestamp
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit=commit,
            expected_sha256=artifact_hash,
            allow_historical=False,
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "STALE")
        self.assertFalse(res["isValidCurrent"])

    def test_future_timestamp_rejected(self):
        now_ms = int(time.time() * 1000)
        commit = "commit_123"
        artifact_hash = "hash_123"

        # Evidence is 10 minutes in future (tolerance is 5 min)
        future_timestamp = now_ms + (600 * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": commit,
            "apkSha256": artifact_hash,
            "timestamp": future_timestamp
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit=commit,
            expected_sha256=artifact_hash,
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "INVALID_TIMESTAMP")
        self.assertFalse(res["isValidCurrent"])

    def test_commit_mismatch_rejected(self):
        now_ms = int(time.time() * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": "11111111111111111111",
            "timestamp": now_ms - 1000
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit="22222222222222222222",
            allow_historical=False,
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "COMMIT_MISMATCH")
        self.assertFalse(res["isValidCurrent"])

    def test_artifact_hash_mismatch_rejected(self):
        now_ms = int(time.time() * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": "commit_123",
            "apkSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "timestamp": now_ms - 1000
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit="commit_123",
            expected_sha256="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "ARTIFACT_HASH_MISMATCH")
        self.assertFalse(res["isValidCurrent"])

    def test_missing_or_malformed_metadata_rejected(self):
        now_ms = int(time.time() * 1000)

        # Missing timestamp
        bad_evidence = {
            "schemaVersion": "2.0",
            "overallVerificationStatus": "VERIFIED"
        }
        res1 = validate_evidence_dict(bad_evidence, current_time_ms=now_ms)
        self.assertEqual(res1["status"], "MALFORMED")

        # Unsupported schema version
        bad_schema = {
            "schemaVersion": "99.0",
            "overallVerificationStatus": "VERIFIED",
            "timestamp": now_ms
        }
        res2 = validate_evidence_dict(bad_schema, current_time_ms=now_ms)
        self.assertEqual(res2["status"], "MALFORMED")

    def test_tampered_payload_hash_rejected(self):
        now_ms = int(time.time() * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": "commit_123",
            "timestamp": now_ms - 1000,
            "evidenceHash": "fake_forged_evidence_hash_0000000000000000000000000000000000000000"
        }

        res = validate_evidence_dict(
            evidence,
            expected_commit="commit_123",
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "HASH_TAMPERED")
        self.assertFalse(res["isValidCurrent"])

    def test_historical_evidence_mode(self):
        now_ms = int(time.time() * 1000)
        old_commit = "old_commit_001"
        current_commit = "current_commit_002"

        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "RELEASE_RUNTIME",
            "overallVerificationStatus": "VERIFIED",
            "commitSha": old_commit,
            "timestamp": now_ms - 1000
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        # In historical mode, commit mismatch is classified as HISTORICAL and preserved
        res = validate_evidence_dict(
            evidence,
            expected_commit=current_commit,
            allow_historical=True,
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "HISTORICAL")
        self.assertFalse(res["isValidCurrent"])

    def test_truth_state_unavailable_never_valid_current(self):
        now_ms = int(time.time() * 1000)
        evidence = {
            "schemaVersion": "2.0",
            "verificationScope": "DEVICE_RUNTIME",
            "overallVerificationStatus": "UNAVAILABLE",
            "commitSha": "commit_123",
            "timestamp": now_ms - 1000
        }
        evidence["evidenceHash"] = compute_evidence_payload_hash(evidence)

        res = validate_evidence_dict(
            evidence,
            expected_commit="commit_123",
            current_time_ms=now_ms
        )
        self.assertEqual(res["status"], "UNAVAILABLE")
        self.assertFalse(res["isValidCurrent"])

if __name__ == "__main__":
    unittest.main()
