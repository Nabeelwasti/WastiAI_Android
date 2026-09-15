package com.example.data.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Supported Industry Presets for Wasti AI OS Universal Business Suite.
 * Enables the app to serve any business type: agencies, tech companies, real estate,
 * e-commerce, consulting, contractors, legal, health, and custom enterprises.
 */
enum class BusinessIndustry(val displayName: String, val defaultLanes: List<Pair<String, String>>) {
    CREATIVE_AGENCY(
        "Creative & Digital Agency",
        listOf(
            "All Services" to "Creative, Digital & Technical Services",
            "Branding & Design" to "Graphic Design & Branding",
            "Visuals & 3D" to "Advanced Visuals & Motion Graphics",
            "Web & Apps" to "Web & App Solutions",
            "AI Automation" to "AI Integration & Automation",
            "SEO & Growth" to "Digital Presence & SEO",
            "Copywriting" to "Professional Copywriting & Content Creation",
            "B2B Outreach" to "Corporate Outreach & B2B Campaigns",
            "Screen Printing" to "Screen Printing & Production Design"
        )
    ),
    SOFTWARE_TECH(
        "Software & Web Development",
        listOf(
            "All Tech" to "Software Development & Cloud Engineering",
            "Full Stack" to "Full Stack Web & Mobile App Development",
            "Backend & API" to "Backend Architecture & Microservices",
            "DevOps & Cloud" to "DevOps, CI/CD & Cloud Infrastructure",
            "AI & ML" to "AI Engineering, LLM & Machine Learning",
            "QA & Security" to "Automated Testing, QA & Security Audits"
        )
    ),
    REAL_ESTATE(
        "Real Estate & Property",
        listOf(
            "All Property" to "Real Estate Brokerage & Property Solutions",
            "Residential" to "Residential Property Sales & Acquisition",
            "Commercial" to "Commercial Leasing & Office Spaces",
            "Property Mgmt" to "Full-Service Property Management & Rentals",
            "Valuation" to "Property Valuation, Appraisal & Advisory",
            "Investment" to "Real Estate Investment Portfolio Consultation"
        )
    ),
    ECOMMERCE_RETAIL(
        "E-Commerce & Retail",
        listOf(
            "All E-Commerce" to "E-Commerce Growth & Retail Operations",
            "Store Setup" to "Shopify, Amazon & WooCommerce Setup",
            "Product Sourcing" to "Product Sourcing, Supply Chain & Logistics",
            "Performance Ads" to "Meta, Google Ads & Performance Marketing",
            "Inventory Mgmt" to "Inventory Forecasting & Fulfillment Automation",
            "Customer Support" to "Omnichannel Customer Experience & Retention"
        )
    ),
    CONSULTING_PROFESSIONAL(
        "Consulting & Advisory",
        listOf(
            "All Consulting" to "Management Consulting & Strategic Advisory",
            "Strategic Planning" to "Business Strategy & Growth Roadmap",
            "Financial Advisory" to "Financial Planning, Budgeting & Auditing",
            "Process Opt" to "Business Process Optimization & Workflow Automation",
            "Risk & Governance" to "Corporate Governance, Risk & Compliance",
            "M&A Advisory" to "Mergers, Acquisitions & Due Diligence"
        )
    ),
    LOCAL_SERVICES(
        "Local Services & Contracting",
        listOf(
            "All Services" to "Contracting, Maintenance & Field Services",
            "General Repair" to "General Maintenance & Repair Services",
            "HVAC & Electric" to "Electrical, HVAC & Smart Climate Systems",
            "Plumbing" to "Plumbing Installation & Emergency Repair",
            "Renovation" to "Interior Renovation & Space Refurbishment",
            "Estimates" to "On-Site Job Estimation & Preventive Maintenance"
        )
    ),
    LEGAL_COMPLIANCE(
        "Legal & Corporate Services",
        listOf(
            "All Legal" to "Corporate Legal, IP & Regulatory Advisory",
            "Contract Review" to "Contract Drafting, Negotiation & Review",
            "IP & Trademark" to "Intellectual Property, Copyright & Patents",
            "Compliance" to "Regulatory Compliance, Data Privacy & GDPR",
            "Dispute Resolution" to "Commercial Mediation & Dispute Resolution",
            "Entity Formation" to "Corporate Formation & International Structuring"
        )
    ),
    HEALTH_WELLNESS(
        "Health & Wellness",
        listOf(
            "All Healthcare" to "Healthcare Practice & Wellness Solutions",
            "Clinical Care" to "Clinical Consultation & Patient Care",
            "Telehealth" to "Digital Health & Telehealth Consultation",
            "Wellness Coaching" to "Nutrition, Fitness & Corporate Wellness",
            "Practice Mgmt" to "Medical Practice Automation & Billing Support"
        )
    ),
    CUSTOM(
        "Universal / Custom Business",
        listOf(
            "Core Offerings" to "Bespoke Professional Solutions",
            "Client Services" to "Client Project Execution & Management",
            "Strategic Support" to "Turnkey Business Support & Consulting"
        )
    )
}

/**
 * Universal Business Profile representation.
 * Allows any user, freelancer, or enterprise to adapt the entire Wasti AI OS business
 * suite (Lead Radar, CRM, Invoices, Proposals, Outreach) to their exact brand.
 */
data class BusinessProfile(
    val businessName: String = "ThriveBridge Growth Solutions",
    val ownerName: String = "Syed Nabeel Wasti",
    val ownerTitle: String = "Founder & Solutions Specialist",
    val ownerPhone: String = "03067370864",
    val ownerPhoneInternational: String = "+923067370864",
    val ownerEmail: String = "wastinabeel99@gmail.com",
    val industry: BusinessIndustry = BusinessIndustry.CREATIVE_AGENCY,
    val services: List<String> = listOf(
        "Graphic Design & Branding",
        "Advanced Visuals & Motion Graphics",
        "Screen Printing & Production Design",
        "Web & App Solutions",
        "AI Integration & Automation",
        "Digital Presence & SEO",
        "Professional Copywriting & Content Creation",
        "Corporate Outreach & B2B Campaigns",
        "DMCA Content Protection",
        "Instructional Design & File Management"
    ),
    val tagline: String = "Extensive suite of creative, digital, and technical services bridging high-end design, advanced AI integration, and practical corporate solutions.",
    val currency: String = "USD",
    val taxId: String = "",
    val website: String = "https://wasti.ai",
    val paymentMethods: List<String> = listOf(
        "Bank Transfer / IBAN / ACH",
        "Stripe / Credit Card",
        "PayPal / Payoneer",
        "Crypto (USDT / USDC)"
    ),
    val paymentTerms: String = "Payment due within 10 days of issuance."
) {
    fun toSkillMatrix(): SkillMatrix {
        return SkillMatrix(
            ownerName = ownerName,
            agencyName = businessName,
            ownerTitle = ownerTitle,
            ownerPhone = ownerPhone,
            ownerPhoneInternational = ownerPhoneInternational,
            ownerEmail = ownerEmail,
            description = tagline,
            services = services
        )
    }
}

/**
 * Persistent Manager for the Universal Business Suite with Intelligent Owner & Client Detection.
 * 
 * Invariants:
 * 1. If Owner (Syed Nabeel Wasti) logs in, the app automatically recognizes him and restores his
 *    complete Founder profile, ThriveBridge details, contact info, and specialized skills.
 * 2. If a new user or client installs and logs in, the app detects they are not the founder,
 *    loads their saved profile if returning, or prompts them to configure their own company
 *    details, currency, industry, and service catalog.
 */
object BusinessProfileManager {
    private const val PREF_NAME = "wasti_business_profile_prefs"
    private const val PREFIX_USER = "wasti_user_profile_"

    // Canonical Founder & Owner Profile — Preserved Permanently
    val OWNER_CANONICAL_PROFILE = BusinessProfile(
        businessName = "ThriveBridge Growth Solutions",
        ownerName = "Syed Nabeel Wasti",
        ownerTitle = "Founder & Solutions Specialist",
        ownerPhone = "03067370864",
        ownerPhoneInternational = "+923067370864",
        ownerEmail = "wastinabeel99@gmail.com",
        industry = BusinessIndustry.CREATIVE_AGENCY,
        services = listOf(
            "Graphic Design & Branding",
            "Advanced Visuals & Motion Graphics",
            "Screen Printing & Production Design",
            "Web & App Solutions",
            "AI Integration & Automation",
            "Digital Presence & SEO",
            "Professional Copywriting & Content Creation",
            "Corporate Outreach & B2B Campaigns",
            "DMCA Content Protection",
            "Instructional Design & File Management"
        ),
        tagline = "Extensive suite of creative, digital, and technical services bridging high-end design, advanced AI integration, and practical corporate solutions.",
        currency = "USD",
        taxId = "",
        website = "https://wasti.ai",
        paymentMethods = listOf(
            "Bank Transfer / IBAN / ACH",
            "Stripe / Credit Card",
            "PayPal / Payoneer",
            "Crypto (USDT / USDC)"
        ),
        paymentTerms = "Payment due within 10 days of issuance."
    )

    private val _activeProfile = MutableStateFlow(OWNER_CANONICAL_PROFILE)
    val activeProfile: StateFlow<BusinessProfile> = _activeProfile.asStateFlow()

    private val _isNewUserSetupRequired = MutableStateFlow(false)
    val isNewUserSetupRequired: StateFlow<Boolean> = _isNewUserSetupRequired.asStateFlow()

    private var isInitialized = false

    fun isFounderEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        val clean = email.trim().lowercase()
        return clean == "wastinabeel99@gmail.com" ||
               clean == "wasti.ai.os@gmail.com" ||
               clean.startsWith("wastinabeel")
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val loaded = loadActiveProfile(context)
        _activeProfile.value = loaded
        WastiRootController.updateActiveSkillMatrix(loaded.toSkillMatrix())
    }

    fun getActiveProfile(context: Context? = null): BusinessProfile {
        if (context != null && !isInitialized) {
            init(context)
        }
        return _activeProfile.value
    }

    /**
     * Called when a user authenticates or switches accounts.
     * Automatically differentiates Owner from other users/clients.
     */
    fun onUserSwitched(
        context: Context,
        userId: String,
        email: String?,
        displayName: String?,
        isVerifiedOwner: Boolean
    ) {
        val isOwner = isVerifiedOwner || isFounderEmail(email) ||
                      (displayName != null && displayName.contains("Syed Nabeel Wasti", ignoreCase = true))

        if (isOwner) {
            // Owner logged in: restore founder profile
            val savedOwner = loadProfileForUser(context, "owner_primary") ?: OWNER_CANONICAL_PROFILE
            _activeProfile.value = savedOwner
            _isNewUserSetupRequired.value = false
            saveActiveProfileToPrefs(context, savedOwner, "owner_primary")
            WastiRootController.updateActiveSkillMatrix(savedOwner.toSkillMatrix())
            android.util.Log.i("BusinessProfileManager", "Recognized FOUNDER & OWNER: Syed Nabeel Wasti. Full Founder profile active.")
        } else {
            // New user or client logged in
            val existingProfile = loadProfileForUser(context, userId)
            if (existingProfile != null) {
                // Returning user: restore their previous account info
                _activeProfile.value = existingProfile
                _isNewUserSetupRequired.value = false
                saveActiveProfileToPrefs(context, existingProfile, userId)
                WastiRootController.updateActiveSkillMatrix(existingProfile.toSkillMatrix())
                android.util.Log.i("BusinessProfileManager", "Returning user profile loaded for userId=$userId, business=${existingProfile.businessName}")
            } else {
                // Brand new user: initialize their profile and request business setup
                val initialUser = BusinessProfile(
                    businessName = if (!displayName.isNullOrBlank()) "$displayName's Business" else "My Enterprise",
                    ownerName = displayName?.takeIf { it.isNotBlank() } ?: (email?.substringBefore("@") ?: "Business Owner"),
                    ownerTitle = "Executive Director",
                    ownerPhone = "",
                    ownerPhoneInternational = "",
                    ownerEmail = email ?: "",
                    industry = BusinessIndustry.CUSTOM,
                    services = listOf(
                        "Client Consulting & Project Delivery",
                        "Operations & Strategy",
                        "Custom Professional Services"
                    ),
                    tagline = "Professional client solutions tailored for scalable business growth.",
                    currency = "USD",
                    taxId = "",
                    website = "",
                    paymentMethods = listOf("Bank Transfer / IBAN / ACH", "Stripe / Credit Card", "Online Payments"),
                    paymentTerms = "Payment due within 10 days of issuance."
                )
                _activeProfile.value = initialUser
                _isNewUserSetupRequired.value = true
                saveProfileForUser(context, initialUser, userId)
                saveActiveProfileToPrefs(context, initialUser, userId)
                WastiRootController.updateActiveSkillMatrix(initialUser.toSkillMatrix())
                android.util.Log.i("BusinessProfileManager", "New user setup initialized for userId=$userId. Prompting business profile customization.")
            }
        }
    }

    fun dismissNewUserSetup() {
        _isNewUserSetupRequired.value = false
    }

    fun saveProfile(context: Context, profile: BusinessProfile, userId: String? = null) {
        val effectiveUserId = userId ?: getActiveUserId(context)
        saveProfileForUser(context, profile, effectiveUserId)
        saveActiveProfileToPrefs(context, profile, effectiveUserId)
        _activeProfile.value = profile
        _isNewUserSetupRequired.value = false
        WastiRootController.updateActiveSkillMatrix(profile.toSkillMatrix())
    }

    private fun getActiveUserId(context: Context): String {
        val identityPrefs = context.getSharedPreferences("wasti_identity_prefs", Context.MODE_PRIVATE)
        val email = identityPrefs.getString("identity_email", null)
        if (isFounderEmail(email)) return "owner_primary"
        return identityPrefs.getString("identity_user_id", "owner_primary") ?: "owner_primary"
    }

    private fun saveActiveProfileToPrefs(context: Context, profile: BusinessProfile, activeUserId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val servicesJson = JSONArray(profile.services).toString()
        val methodsJson = JSONArray(profile.paymentMethods).toString()

        prefs.edit()
            .putString("active_user_id", activeUserId)
            .putString("business_name", profile.businessName)
            .putString("owner_name", profile.ownerName)
            .putString("owner_title", profile.ownerTitle)
            .putString("owner_phone", profile.ownerPhone)
            .putString("owner_phone_intl", profile.ownerPhoneInternational)
            .putString("owner_email", profile.ownerEmail)
            .putString("industry", profile.industry.name)
            .putString("services_json", servicesJson)
            .putString("tagline", profile.tagline)
            .putString("currency", profile.currency)
            .putString("tax_id", profile.taxId)
            .putString("website", profile.website)
            .putString("payment_methods_json", methodsJson)
            .putString("payment_terms", profile.paymentTerms)
            .apply()
    }

    fun saveProfileForUser(context: Context, profile: BusinessProfile, userId: String) {
        val prefs = context.getSharedPreferences(PREFIX_USER + userId, Context.MODE_PRIVATE)
        val servicesJson = JSONArray(profile.services).toString()
        val methodsJson = JSONArray(profile.paymentMethods).toString()

        prefs.edit()
            .putString("business_name", profile.businessName)
            .putString("owner_name", profile.ownerName)
            .putString("owner_title", profile.ownerTitle)
            .putString("owner_phone", profile.ownerPhone)
            .putString("owner_phone_intl", profile.ownerPhoneInternational)
            .putString("owner_email", profile.ownerEmail)
            .putString("industry", profile.industry.name)
            .putString("services_json", servicesJson)
            .putString("tagline", profile.tagline)
            .putString("currency", profile.currency)
            .putString("tax_id", profile.taxId)
            .putString("website", profile.website)
            .putString("payment_methods_json", methodsJson)
            .putString("payment_terms", profile.paymentTerms)
            .apply()
    }

    fun loadProfileForUser(context: Context, userId: String): BusinessProfile? {
        val prefs = context.getSharedPreferences(PREFIX_USER + userId, Context.MODE_PRIVATE)
        if (!prefs.contains("business_name")) {
            return if (userId == "owner_primary") OWNER_CANONICAL_PROFILE else null
        }
        return deserializeFromPrefs(prefs)
    }

    fun loadActiveProfile(context: Context): BusinessProfile {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("business_name")) {
            return OWNER_CANONICAL_PROFILE
        }
        return deserializeFromPrefs(prefs)
    }

    private fun deserializeFromPrefs(prefs: android.content.SharedPreferences): BusinessProfile {
        val industryStr = prefs.getString("industry", BusinessIndustry.CREATIVE_AGENCY.name) ?: BusinessIndustry.CREATIVE_AGENCY.name
        val industry = try {
            BusinessIndustry.valueOf(industryStr)
        } catch (_: Exception) {
            BusinessIndustry.CREATIVE_AGENCY
        }

        val servicesList = mutableListOf<String>()
        val servicesRaw = prefs.getString("services_json", null)
        if (!servicesRaw.isNullOrBlank()) {
            try {
                val array = JSONArray(servicesRaw)
                for (i in 0 until array.length()) {
                    servicesList.add(array.getString(i))
                }
            } catch (_: Exception) {}
        }
        if (servicesList.isEmpty()) {
            servicesList.addAll(industry.defaultLanes.map { it.second })
        }

        val methodsList = mutableListOf<String>()
        val methodsRaw = prefs.getString("payment_methods_json", null)
        if (!methodsRaw.isNullOrBlank()) {
            try {
                val array = JSONArray(methodsRaw)
                for (i in 0 until array.length()) {
                    methodsList.add(array.getString(i))
                }
            } catch (_: Exception) {}
        }
        if (methodsList.isEmpty()) {
            methodsList.addAll(listOf("Bank Transfer / IBAN / ACH", "Stripe / Credit Card", "PayPal / Payoneer", "Crypto (USDT / USDC)"))
        }

        return BusinessProfile(
            businessName = prefs.getString("business_name", OWNER_CANONICAL_PROFILE.businessName) ?: OWNER_CANONICAL_PROFILE.businessName,
            ownerName = prefs.getString("owner_name", OWNER_CANONICAL_PROFILE.ownerName) ?: OWNER_CANONICAL_PROFILE.ownerName,
            ownerTitle = prefs.getString("owner_title", OWNER_CANONICAL_PROFILE.ownerTitle) ?: OWNER_CANONICAL_PROFILE.ownerTitle,
            ownerPhone = prefs.getString("owner_phone", OWNER_CANONICAL_PROFILE.ownerPhone) ?: OWNER_CANONICAL_PROFILE.ownerPhone,
            ownerPhoneInternational = prefs.getString("owner_phone_intl", OWNER_CANONICAL_PROFILE.ownerPhoneInternational) ?: OWNER_CANONICAL_PROFILE.ownerPhoneInternational,
            ownerEmail = prefs.getString("owner_email", OWNER_CANONICAL_PROFILE.ownerEmail) ?: OWNER_CANONICAL_PROFILE.ownerEmail,
            industry = industry,
            services = servicesList,
            tagline = prefs.getString("tagline", OWNER_CANONICAL_PROFILE.tagline) ?: OWNER_CANONICAL_PROFILE.tagline,
            currency = prefs.getString("currency", "USD") ?: "USD",
            taxId = prefs.getString("tax_id", "") ?: "",
            website = prefs.getString("website", "https://wasti.ai") ?: "https://wasti.ai",
            paymentMethods = methodsList,
            paymentTerms = prefs.getString("payment_terms", "Payment due within 10 days of issuance.") ?: "Payment due within 10 days of issuance."
        )
    }

    fun applyIndustryPreset(context: Context, industry: BusinessIndustry): BusinessProfile {
        val current = getActiveProfile(context)
        val defaultServices = industry.defaultLanes.map { it.second }
        val updated = current.copy(
            industry = industry,
            services = defaultServices,
            tagline = when (industry) {
                BusinessIndustry.CREATIVE_AGENCY -> "End-to-end turnkey solutions bridging high-end design, advanced media, and digital scaling."
                BusinessIndustry.SOFTWARE_TECH -> "High-performance software engineering, cloud architecture, and mission-critical systems."
                BusinessIndustry.REAL_ESTATE -> "Premium residential & commercial real estate brokerage, advisory, and property management."
                BusinessIndustry.ECOMMERCE_RETAIL -> "Full-funnel e-commerce store acceleration, brand building, and automated retail operations."
                BusinessIndustry.CONSULTING_PROFESSIONAL -> "Elite management consulting, financial advisory, and business transformation."
                BusinessIndustry.LOCAL_SERVICES -> "Licensed contracting, high-grade maintenance, and rapid-dispatch field solutions."
                BusinessIndustry.LEGAL_COMPLIANCE -> "Corporate legal advisory, intellectual property protection, and regulatory governance."
                BusinessIndustry.HEALTH_WELLNESS -> "Comprehensive clinical wellness, preventative health, and modern healthcare practice."
                BusinessIndustry.CUSTOM -> current.tagline
            }
        )
        saveProfile(context, updated)
        return updated
    }
}
