package com.example.model

enum class ResignStep(val stepNumber: Int, val label: String) {
    IDLE(0, "Ready"),
    INSPECTING_IMAGE(1, "Inspecting Image Header & Superblock"),
    UNPACKING_IMAGE(2, "Unpacking Image to sdcard/RESIGN/UNPACK"),
    SCANNING_SIGNATURES(3, "Scanning APK Signatures (V1, V2, V3, V4)"),
    FACTUAL_AI_ANALYSIS(4, "Factual AI Cross-Analysis (PrivApp & Keyset)"),
    AWAITING_KEYSET(5, "Awaiting Keyset Configuration"),
    GENERATING_KEYSET(6, "Generating Keyset to sdcard/RESIGN/Data/Key"),
    RESIGNING_APKS(7, "Re-signing APKs with Stock Mapping Logic"),
    UPDATING_PERMISSIONS(8, "Updating Permissions & PrivApp Mappings"),
    REGENERATING_METADATA(9, "Regenerating FSV-Meta, Oat & Inode Tables"),
    REPACKING_IMAGE(10, "Repacking Output Image to sdcard/RESIGN/SIGNED"),
    FINISHED_SUCCESS(11, "GSI Re-signing Completed Successfully"),
    FAILED(12, "Process Failed")
}

data class FactualAiReport(
    val summary: String,
    val clusterRoles: Map<String, String> = emptyMap(),
    val permissionsAuditedCount: Int = 0,
    val permissionFindings: List<String> = emptyList(),
    val securityConsistency: String = "High",
    val warnings: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
)

data class EngineProgress(
    val step: ResignStep = ResignStep.IDLE,
    val progressPercent: Float = 0f,
    val currentItemDetail: String = "",
    val itemsProcessed: Int = 0,
    val totalItems: Int = 0,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val signedImagePath: String? = null
)
