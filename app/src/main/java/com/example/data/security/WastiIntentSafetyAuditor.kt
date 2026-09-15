package com.example.data.security

import android.util.Log
import com.example.data.auth.WastiIdentityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * Real User-Intent Safety Detection & Owner Admin Escalation Architecture.
 * 
 * Rules:
 * 1. Analyzes genuine intent risk: prevents destructive wiping, unauthorized exfiltration,
 *    unauthorized remote tunnels, or silent privilege escalation.
 * 2. Precision without False Positives: Standard coding, debugging, test runs, workspace file operations,
 *    git actions, and polyglot execution are recognized as SAFE development tasks.
 * 3. Escalation: High-risk intents generate evidence-backed alerts on the Owner Admin Dashboard
 *    with explicit [Accept / Reject / Scope Constraints] controls.
 */
enum class IntentRiskSeverity {
    SAFE,
    ELEVATED_NOTICE,
    HIGH_RISK_GATED,
    CRITICAL_BLOCKED
}

data class IntentSafetyEvaluation(
    val severity: IntentRiskSeverity,
    val summary: String,
    val riskFactors: List<String>,
    val requiresOwnerAuthorization: Boolean,
    val suggestedScopeConstraint: String? = null
)

enum class IntentSafetyDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY
}

data class IntentSafetyDecisionResult(
    val decision: IntentSafetyDecision,
    val evaluation: IntentSafetyEvaluation,
    val evidenceHash: String,
    val reason: String
)

data class OwnerAdminSafetyAlert(
    val id: String = UUID.randomUUID().toString(),
    val requestingUser: String,
    val intentText: String,
    val evaluation: IntentSafetyEvaluation,
    val evidenceHash: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    var isResolved: Boolean = false,
    var resolutionStatus: String = "PENDING" // "ACCEPTED", "REJECTED", "SCOPED"
)

object WastiIntentSafetyAuditor {
    private const val TAG = "IntentSafetyAuditor"

    private val _pendingAlerts = MutableStateFlow<List<OwnerAdminSafetyAlert>>(emptyList())
    val pendingAlerts: StateFlow<List<OwnerAdminSafetyAlert>> = _pendingAlerts.asStateFlow()

    // Explicit high-risk destructive patterns
    private val DESTRUCTIVE_WIPE_PATTERNS = listOf(
        Regex("""rm\s+-[rfRF]{1,4}\s+(/|/system|/data|/sdcard|\*|~|/\*)"""),
        Regex("""dd\s+if=.*\s+of=/dev/(block|disk|zero)"""),
        Regex("""mkfs(\.\w+)?\s+/dev/""")
    )

    private val EXFILTRATION_PATTERNS = listOf(
        Regex("""(?i)(cat|tar|zip|base64|curl|nc)\s+.*(keystore|\.env|secrets|id_rsa|private_key).*\s+(>|\||http)"""),
        Regex("""(?i)export\s+.*(API_KEY|PASSWORD|TOKEN).*\s*>\s*/sdcard""")
    )

    private val UNAUTHORIZED_REMOTE_TUNNEL_PATTERNS = listOf(
        Regex("""nc\s+-[eE]\s+/bin/(sh|bash)"""),
        Regex("""bash\s+-i\s+>&?\s+/dev/tcp/"""),
        Regex("""python.*-c.*socket.*pty\.spawn""")
    )

    /**
     * Evaluates a user prompt or command line for genuine safety risks.
     */
    fun evaluateIntent(promptOrCommand: String): IntentSafetyEvaluation {
        val trimmed = promptOrCommand.trim()
        val riskFactors = mutableListOf<String>()

        // Check destructive wiping
        for (pattern in DESTRUCTIVE_WIPE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                riskFactors.add("Potential destructive wipe of root or device system filesystem")
                return IntentSafetyEvaluation(
                    severity = IntentRiskSeverity.CRITICAL_BLOCKED,
                    summary = "Destructive filesystem operation blocked",
                    riskFactors = riskFactors,
                    requiresOwnerAuthorization = true,
                    suggestedScopeConstraint = "Restrict operations strictly to /home/wasti workspace directory"
                )
            }
        }

        // Check credential exfiltration
        for (pattern in EXFILTRATION_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                riskFactors.add("Suspicious exfiltration of raw cryptographic keys or environment secrets")
                return IntentSafetyEvaluation(
                    severity = IntentRiskSeverity.HIGH_RISK_GATED,
                    summary = "Credential boundary protection triggered",
                    riskFactors = riskFactors,
                    requiresOwnerAuthorization = true,
                    suggestedScopeConstraint = "Mask credentials and prohibit direct network transmission of raw keys"
                )
            }
        }

        // Check unauthorized reverse shell backdoors
        for (pattern in UNAUTHORIZED_REMOTE_TUNNEL_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                riskFactors.add("Detected reverse shell spawning or unauthenticated remote tunnel")
                return IntentSafetyEvaluation(
                    severity = IntentRiskSeverity.CRITICAL_BLOCKED,
                    summary = "Unauthenticated remote shell backdoor blocked",
                    riskFactors = riskFactors,
                    requiresOwnerAuthorization = true,
                    suggestedScopeConstraint = "Use official Wasti authenticated WebSocket or TLS endpoint"
                )
            }
        }

        // Check root privilege escalation attempt
        if (trimmed.startsWith("su ") || trimmed.startsWith("sudo ") || trimmed.contains("setenforce 0")) {
            riskFactors.add("Device root privilege acquisition attempted")
            return IntentSafetyEvaluation(
                severity = IntentRiskSeverity.HIGH_RISK_GATED,
                summary = "Superuser escalation requires Owner verification",
                riskFactors = riskFactors,
                requiresOwnerAuthorization = true,
                suggestedScopeConstraint = "Execute in unprivileged sandbox mode"
            )
        }

        // Standard developer operations (git, gradle, python, node, tests, file edits) are verified SAFE
        return IntentSafetyEvaluation(
            severity = IntentRiskSeverity.SAFE,
            summary = "Intent conforms to verified developer safety baseline",
            riskFactors = emptyList(),
            requiresOwnerAuthorization = false
        )
    }

    /**
     * Dispatches an alert to the Owner Admin Dashboard if an operation requires owner authorization.
     */
    fun escalateToOwnerDashboard(
        intentText: String,
        evaluation: IntentSafetyEvaluation
    ): OwnerAdminSafetyAlert {
        val user = WastiIdentityManager.currentProfile.value?.displayName ?: "Unknown"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest("$user:$intentText:${System.currentTimeMillis()}".toByteArray())
            .fold("") { s, b -> s + "%02x".format(b) }

        val alert = OwnerAdminSafetyAlert(
            requestingUser = user,
            intentText = intentText,
            evaluation = evaluation,
            evidenceHash = hash
        )

        _pendingAlerts.value = _pendingAlerts.value + alert
        Log.w(TAG, "Escalated safety alert to Owner Admin: ${alert.evaluation.summary} (ID: ${alert.id})")
        return alert
    }

    /**
     * Resolves an alert with Accept, Reject, or Scope Constraints (Owner Only).
     */
    fun resolveAlert(alertId: String, resolution: String, scopeConstraint: String? = null): Boolean {
        val profile = WastiIdentityManager.currentProfile.value
        if (profile?.canResolveAdminSecurityAlerts != true) {
            Log.w(TAG, "Alert resolution denied: caller is not verified Wasti Owner")
            return false
        }

        val list = _pendingAlerts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == alertId }
        if (idx == -1) return false

        val item = list[idx]
        item.isResolved = true
        item.resolutionStatus = resolution
        list[idx] = item
        _pendingAlerts.value = list
        Log.i(TAG, "Owner resolved safety alert $alertId -> $resolution (Scope: $scopeConstraint)")
        return true
    }

    /**
     * Mandatory pre-execution boundary with explicit ALLOW, REQUIRE_APPROVAL, DENY decisions.
     * Evaluates action risk, environment bounds, caller authority, and generates auditable evidence.
     */
    fun authorizeAction(
        actionName: String,
        targetResource: String = "",
        parameters: Map<String, Any?> = emptyMap(),
        callerUserId: String = "unknown",
        callerIsVerifiedOwner: Boolean = false
    ): IntentSafetyDecisionResult {
        val composite = "$actionName $targetResource ${parameters.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trim()
        val evaluation = evaluateIntent(composite)

        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest("$callerUserId:$composite:${System.currentTimeMillis()}".toByteArray())
            .fold("") { s, b -> s + "%02x".format(b) }

        return when (evaluation.severity) {
            IntentRiskSeverity.CRITICAL_BLOCKED -> {
                escalateToOwnerDashboard(composite, evaluation)
                IntentSafetyDecisionResult(
                    decision = IntentSafetyDecision.DENY,
                    evaluation = evaluation,
                    evidenceHash = hash,
                    reason = "CRITICAL_BLOCKED: ${evaluation.summary}"
                )
            }
            IntentRiskSeverity.HIGH_RISK_GATED -> {
                if (callerIsVerifiedOwner) {
                    IntentSafetyDecisionResult(
                        decision = IntentSafetyDecision.ALLOW,
                        evaluation = evaluation,
                        evidenceHash = hash,
                        reason = "AUTHORIZED_BY_VERIFIED_OWNER"
                    )
                } else {
                    escalateToOwnerDashboard(composite, evaluation)
                    IntentSafetyDecisionResult(
                        decision = IntentSafetyDecision.REQUIRE_APPROVAL,
                        evaluation = evaluation,
                        evidenceHash = hash,
                        reason = "HIGH_RISK_GATED: Requires owner approval"
                    )
                }
            }
            IntentRiskSeverity.ELEVATED_NOTICE -> {
                IntentSafetyDecisionResult(
                    decision = IntentSafetyDecision.ALLOW,
                    evaluation = evaluation,
                    evidenceHash = hash,
                    reason = "ELEVATED_NOTICE_PERMITTED"
                )
            }
            IntentRiskSeverity.SAFE -> {
                IntentSafetyDecisionResult(
                    decision = IntentSafetyDecision.ALLOW,
                    evaluation = evaluation,
                    evidenceHash = hash,
                    reason = "SAFE_OPERATION_PERMITTED"
                )
            }
        }
    }
}
