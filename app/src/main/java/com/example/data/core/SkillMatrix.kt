package com.example.data.core

/**
 * SkillMatrix representation for Wasti OS Lead Radar System.
 * Holds the comprehensive list of core offered services.
 */
data class SkillMatrix(
    val services: List<String> = listOf(
        "Graphic Design",
        "Video Editing",
        "AutoCAD",
        "CorelDRAW",
        "Canva",
        "DMCA Takedowns",
        "AI Automation"
    ),
    val ownerTitle: String = "Wasti Super-Agent Lead Specialist",
    val description: String = "Expert multi-lane specialist in Graphic Design, Video Editing, AutoCAD architectural drafting, CorelDRAW vector graphics, Canva visual publishing, DMCA Takedown enforcement, and AI Automation workflows."
) {
    fun formatSkillSummary(): String {
        return services.joinToString(", ")
    }
}
