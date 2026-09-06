package com.example.data.core

/**
 * SkillMatrix representation for Wasti OS Lead Radar System.
 * Holds the comprehensive portfolio of creative, digital, and corporate services
 * offered by Syed Nabeel Wasti.
 */
data class SkillMatrix(
    val ownerName: String = "Syed Nabeel Wasti",
    val ownerTitle: String = "Creative, Digital & Technical Solutions Specialist",
    val description: String = "Extensive suite of creative, digital, and technical services bridging high-end design, advanced AI integration, and practical corporate solutions.",
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
    val detailedCatalog: Map<String, List<String>> = mapOf(
        "Design & Multimedia" to listOf(
            "Graphic Design & Branding: Logos, corporate identities, social media kits, restaurant/shop branding, menus, signboards, payment stands",
            "Advanced Visuals: 2D/3D design, motion graphics, video & photo editing, cinematic portraits, architectural rendering, 3D modeling",
            "Screen Printing & Production Design: Technical artwork setup, pre-press coordination for apparel and merchandise"
        ),
        "Tech, AI & Web Development" to listOf(
            "Web & App Solutions: Custom website development, business software, employee/corporate portals, automated attendance systems",
            "AI Integration: AI business automation, AI voice/text assistants, AI detection/reporting",
            "Digital Presence: Full-scale Social Media Management, SEO, strategic business consulting"
        ),
        "Writing, Admin & Specialized Support" to listOf(
            "Content Creation: Professional copywriting, e-book formatting, poetry, musical lyrics across all languages",
            "Corporate Outreach: B2B cold email campaigns, procurement coordination, international client outreach",
            "Protection & Support: DMCA content protection, stolen content removal, academic/instructional design support, secure file management"
        )
    )
) {
    fun formatSkillSummary(): String {
        return services.joinToString(", ")
    }
}
